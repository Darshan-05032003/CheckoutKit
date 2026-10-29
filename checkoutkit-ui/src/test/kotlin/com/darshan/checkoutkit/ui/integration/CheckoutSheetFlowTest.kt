package com.darshan.checkoutkit.ui.integration

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PendingPolicy
import com.darshan.checkoutkit.core.WalletProvider
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.FakeGateway
import com.darshan.checkoutkit.core.gateway.FakeGatewayConfig
import com.darshan.checkoutkit.core.gateway.FakeOutcome
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.ui.CheckoutSheet
import com.darshan.checkoutkit.ui.CheckoutSheetOptions
import com.darshan.checkoutkit.ui.CheckoutSheetTestTags
import com.darshan.checkoutkit.ui.rememberCheckoutSheet
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The real sheet bound to the real session and state machine, with FakeGateway outcomes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CheckoutSheetFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private val results: MutableList<CheckoutResult> = Collections.synchronizedList(mutableListOf())
    private lateinit var gateway: FakeGateway
    private val request = CheckoutRequest("order-ui-1", 49_900, description = "Coffee beans")

    private fun open(config: FakeGatewayConfig = FakeGatewayConfig(latency = Duration.ZERO), options: CheckoutSheetOptions = CheckoutSheetOptions(merchantName = "Test Store")) {
        gateway = FakeGateway(config, random = Random(1))
        val kit = CheckoutKit(
            gateway,
            CheckoutConfig(
                retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = 10.milliseconds, attemptTimeout = 2.seconds),
                pendingPolicy = PendingPolicy(initialInterval = 50.milliseconds, maxInterval = 100.milliseconds, maxPolls = 20),
            ),
        )
        rule.setContent {
            MaterialTheme {
                val sheet = rememberCheckoutSheet(kit, options) { results += it }
                Button(onClick = { sheet.launch(request) }) { Text("Open checkout") }
                CheckoutSheet(sheet)
            }
        }
        rule.onNodeWithText("Open checkout").performClick()
        waitForTag(CheckoutSheetTestTags.SHEET)
    }

    private fun waitForTag(tag: String) {
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithTagSafe(tag) }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagSafe(tag: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun payWithUpi(vpa: String = "alice@okbank") {
        rule.onNodeWithTag(CheckoutSheetTestTags.VPA_FIELD).performTextInput(vpa)
        rule.onNodeWithTag(CheckoutSheetTestTags.PAY_BUTTON).performScrollTo().performClick()
    }

    private fun closeAndAwaitResult(): CheckoutResult {
        rule.onNodeWithTag(CheckoutSheetTestTags.CLOSE_BUTTON).performClick()
        rule.waitUntil(5_000) { results.isNotEmpty() }
        return results.single()
    }

    @Test
    fun upiSuccess_showsTheAmount_thenSuccess_andDeliversTheResult() {
        open()
        rule.onNodeWithText("Pay ₹499.00").assertExists()
        rule.onNodeWithContentDescription("Amount to pay ₹499.00").assertExists()
        rule.onNodeWithText("Test Store").assertExists()
        payWithUpi()
        waitForTag(CheckoutSheetTestTags.SUCCESS)
        rule.onNodeWithText("Payment successful").assertExists()
        val result = closeAndAwaitResult()
        assertThat(result).isInstanceOf(CheckoutResult.Success::class.java)
        assertThat(gateway.capturedPayments).hasSize(1)
    }

    @Test
    fun invalidVpa_showsAnInlineError_andDoesNotPay() {
        open()
        payWithUpi("alice")
        rule.onNodeWithText("Enter a UPI ID like name@bank").assertExists()
        assertThat(gateway.chargeCalls).isEqualTo(0)
    }

    @Test
    fun declined_showsTheMappedMessage_andChangeMethodReturnsToTheForm() {
        open(FakeGatewayConfig(outcome = FakeOutcome.DECLINED, latency = Duration.ZERO))
        payWithUpi()
        waitForTag(CheckoutSheetTestTags.FAILED)
        rule.onNodeWithText("Payment declined").assertExists()
        rule.onNodeWithTag(CheckoutSheetTestTags.PRIMARY_ACTION).performClick()
        waitForTag(CheckoutSheetTestTags.PAY_BUTTON)
    }

    @Test
    fun insufficientFunds_isReported() {
        open()
        payWithUpi("broke@fakebank")
        waitForTag(CheckoutSheetTestTags.FAILED)
        rule.onNodeWithText("Insufficient balance").assertExists()
        assertThat(closeAndAwaitResult()).isEqualTo(CheckoutResult.Failed(CheckoutError.InsufficientFunds))
    }

    @Test
    fun pendingThenSuccess_showsTheWaitingScreenFirst() {
        open(FakeGatewayConfig(outcome = FakeOutcome.PENDING_THEN_SUCCESS, latency = 100.milliseconds, pendingPolls = 5))
        payWithUpi()
        waitForTag(CheckoutSheetTestTags.PENDING)
        rule.onNodeWithText("Approve the payment in your UPI app", substring = true).assertExists()
        waitForTag(CheckoutSheetTestTags.SUCCESS)
    }

    @Test
    fun cardFlow_masksTheCvv_andPays() {
        open()
        rule.onNodeWithTag(CheckoutSheetTestTags.tab("CARD")).performClick()
        rule.onNodeWithTag(CheckoutSheetTestTags.CARD_NUMBER_FIELD).performTextInput("4242424242424242")
        rule.onNodeWithTag(CheckoutSheetTestTags.EXPIRY_FIELD).performTextInput("1239")
        rule.onNodeWithTag(CheckoutSheetTestTags.CVV_FIELD).performTextInput("123")
        rule.onNodeWithTag(CheckoutSheetTestTags.CVV_FIELD).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        rule.onNodeWithText("4242 4242 4242 4242").assertExists()
        rule.onNodeWithTag(CheckoutSheetTestTags.PAY_BUTTON).performScrollTo().performClick()
        waitForTag(CheckoutSheetTestTags.SUCCESS)
        assertThat(gateway.capturedPayments.single().methodType).isEqualTo("CARD")
    }

    @Test
    fun cardWithBadLuhn_showsAnError() {
        open()
        rule.onNodeWithTag(CheckoutSheetTestTags.tab("CARD")).performClick()
        rule.onNodeWithTag(CheckoutSheetTestTags.CARD_NUMBER_FIELD).performTextInput("4242424242424241")
        rule.onNodeWithTag(CheckoutSheetTestTags.EXPIRY_FIELD).performTextInput("1239")
        rule.onNodeWithTag(CheckoutSheetTestTags.CVV_FIELD).performTextInput("123")
        rule.onNodeWithTag(CheckoutSheetTestTags.PAY_BUTTON).performScrollTo().performClick()
        rule.onNodeWithText("This card number is not valid").assertExists()
        assertThat(gateway.chargeCalls).isEqualTo(0)
    }

    @Test
    fun wallet_pays() {
        open()
        rule.onNodeWithTag(CheckoutSheetTestTags.tab("WALLET")).performClick()
        rule.onNodeWithTag(CheckoutSheetTestTags.wallet(WalletProvider.PHONEPE)).performScrollTo().performClick()
        rule.onNodeWithTag(CheckoutSheetTestTags.PAY_BUTTON).performScrollTo().performClick()
        waitForTag(CheckoutSheetTestTags.SUCCESS)
    }

    @Test
    fun closeBeforePaying_cancels() {
        open()
        assertThat(closeAndAwaitResult()).isEqualTo(CheckoutResult.Cancelled)
        assertThat(gateway.chargeCalls).isEqualTo(0)
    }

    @Test
    fun whileProcessing_thePayButtonIsGone_andCloseIsHidden() {
        open(FakeGatewayConfig(latency = 1_500.milliseconds))
        payWithUpi()
        waitForTag(CheckoutSheetTestTags.PROCESSING)
        rule.onNodeWithText("Don't close this screen", substring = true).assertExists()
        assertThat(rule.onAllNodes(androidx.compose.ui.test.hasTestTag(CheckoutSheetTestTags.CLOSE_BUTTON)).fetchSemanticsNodes()).isEmpty()
        waitForTag(CheckoutSheetTestTags.SUCCESS)
    }

    @Test
    fun onlyConfiguredMethodsAreShown() {
        open(options = CheckoutSheetOptions(methods = listOf(com.darshan.checkoutkit.ui.PaymentMethodOption.UPI)))
        assertThat(rule.onAllNodes(androidx.compose.ui.test.hasTestTag(CheckoutSheetTestTags.tab("CARD"))).fetchSemanticsNodes()).isEmpty()
    }
}
