package com.darshan.checkoutkit.ui.integration

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.error.RecoveryAction
import com.darshan.checkoutkit.core.error.toUserMessage
import com.darshan.checkoutkit.core.validation.FieldError
import com.darshan.checkoutkit.ui.CheckoutSheetOptions
import com.darshan.checkoutkit.ui.CheckoutSheetTestTags
import com.darshan.checkoutkit.ui.PaymentMethodOption
import com.darshan.checkoutkit.ui.internal.CheckoutAction
import com.darshan.checkoutkit.ui.internal.CheckoutSheetContent
import com.darshan.checkoutkit.ui.internal.CheckoutUiState
import com.darshan.checkoutkit.ui.internal.ErrorStrings
import com.darshan.checkoutkit.ui.internal.SheetPhase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Each sheet phase rendered on its own, plus the error → string resource mapping. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CheckoutSheetContentTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<CheckoutAction>()
    private val base = CheckoutUiState(
        visible = true,
        request = CheckoutRequest("order-c", 1_23_456_78L),
        tabs = listOf(PaymentMethodOption.UPI, PaymentMethodOption.CARD, PaymentMethodOption.WALLET),
    )

    private fun show(state: CheckoutUiState) {
        rule.setContent { MaterialTheme { CheckoutSheetContent(state, CheckoutSheetOptions(merchantName = "Shop"), actions::add) } }
    }

    @Test
    fun form_showsTabsAndTheFormattedAmount() {
        show(base)
        rule.onNodeWithText("Pay ₹1,23,456.78").assertExists()
        rule.onNodeWithTag(CheckoutSheetTestTags.tab("UPI")).assertExists()
        rule.onNodeWithTag(CheckoutSheetTestTags.tab("CARD")).performClick()
        assertThat(actions).contains(CheckoutAction.SelectTab(PaymentMethodOption.CARD))
    }

    @Test
    fun fieldErrors_areShownAsText() {
        show(base.copy(tab = PaymentMethodOption.CARD, cardNumberError = FieldError.INVALID_CHECKSUM, expiryError = FieldError.EXPIRED, cvvError = FieldError.WRONG_LENGTH))
        rule.onNodeWithText("This card number is not valid").assertExists()
        rule.onNodeWithText("This card has expired").assertExists()
        rule.onNodeWithText("Enter the 3 or 4 digit CVV").assertExists()
    }

    @Test
    fun processing_showsRetryAttempt() {
        show(base.copy(phase = SheetPhase.Processing(attempt = 2)))
        rule.onNodeWithTag(CheckoutSheetTestTags.PROCESSING).assertExists()
        rule.onNodeWithText("Retrying (attempt 2)", substring = true).assertExists()
    }

    @Test
    fun success_showsTheTransactionId() {
        show(base.copy(phase = SheetPhase.Success("fake_txn_abc")))
        rule.onNodeWithText("fake_txn_abc", substring = true).assertExists()
        rule.onNodeWithTag(CheckoutSheetTestTags.CLOSE_BUTTON).performClick()
        assertThat(actions).contains(CheckoutAction.Close)
    }

    @Test
    fun unresolved_offersCheckStatus() {
        show(base.copy(phase = SheetPhase.Unresolved(CheckoutError.Timeout)))
        rule.onNodeWithText("Don't pay again", substring = true).assertExists()
        rule.onNodeWithTag(CheckoutSheetTestTags.CHECK_STATUS).performClick()
        assertThat(actions).contains(CheckoutAction.CheckStatus)
    }

    @Test
    fun failedRetryable_primaryActionIsRetry() {
        show(base.copy(phase = SheetPhase.Failed(CheckoutError.Network)))
        rule.onNodeWithText("Try again").performClick()
        assertThat(actions).contains(CheckoutAction.Retry)
    }

    @Test
    fun everyErrorHasAStringResource_andTheRightButton() {
        val errors = listOf(
            CheckoutError.Network, CheckoutError.Timeout, CheckoutError.Declined, CheckoutError.InsufficientFunds,
            CheckoutError.InvalidVpa, CheckoutError.InvalidCard, CheckoutError.CardExpired, CheckoutError.LimitExceeded,
            CheckoutError.AuthenticationFailed, CheckoutError.RateLimited(), CheckoutError.GatewayError("X"),
            CheckoutError.InvalidRequest("x"), CheckoutError.ResponseMismatch, CheckoutError.Unknown(),
        )
        val titles = errors.map { context.getString(ErrorStrings.titleOf(it)) }
        assertThat(titles.all { it.isNotBlank() }).isTrue()
        errors.forEach { e ->
            assertThat(context.getString(ErrorStrings.messageOf(e))).isNotEmpty()
            val action = e.toUserMessage().action
            val button = context.getString(ErrorStrings.actionLabelOf(action))
            assertThat(button).isNotEmpty()
        }
        assertThat(context.getString(ErrorStrings.actionLabelOf(RecoveryAction.RETRY))).isEqualTo("Try again")
        FieldError.entries.forEach { assertThat(context.getString(ErrorStrings.fieldErrorOf(it, com.darshan.checkoutkit.ui.internal.InputField.VPA))).isNotEmpty() }
    }
}
