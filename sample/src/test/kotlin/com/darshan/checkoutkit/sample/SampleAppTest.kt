package com.darshan.checkoutkit.sample

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darshan.checkoutkit.ui.CheckoutSheetTestTags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

/** The whole sample, end to end, on Robolectric: outcome picker → sheet → result card → refund. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SampleAppTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var viewModel: SampleViewModel

    private fun start() {
        viewModel = SampleViewModel(SampleDependencies.create(latency = 20.milliseconds, dispatcher = Dispatchers.Default, fast = true))
        rule.setContent { SampleApp(viewModel) }
    }

    private fun waitForTag(tag: String) = rule.waitUntil(8_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun pay(outcome: SampleOutcome, vpa: String = "alice@okbank") {
        rule.onNodeWithTag(SampleTags.outcome(outcome)).performScrollTo().performClick()
        rule.onNodeWithTag(SampleTags.PAY).performScrollTo().performClick()
        waitForTag(CheckoutSheetTestTags.VPA_FIELD)
        rule.onNodeWithTag(CheckoutSheetTestTags.VPA_FIELD).performTextInput(vpa)
        rule.onNodeWithTag(CheckoutSheetTestTags.PAY_BUTTON).performScrollTo().performClick()
    }

    /**
     * Waits for a view-model condition. Unlike semantics-based waits, the predicate alone does not
     * pump Robolectric's paused main looper, so do it explicitly while waiting.
     */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met; state=${viewModel.uiState.value}" }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.waitForIdle()
            Thread.sleep(10)
        }
    }

    private fun awaitResult() = awaitCondition { viewModel.uiState.value.lastResult != null }

    private fun closeSheet() {
        rule.onNodeWithTag(CheckoutSheetTestTags.CLOSE_BUTTON).performClick()
        awaitResult()
    }

    @Test
    fun emptyState_isShownBeforeTheFirstPayment() {
        start()
        rule.onNodeWithText("No payment yet", substring = true).assertExists()
    }

    @Test
    fun success_thenRefund() {
        start()
        pay(SampleOutcome.REFUND)
        waitForTag(CheckoutSheetTestTags.SUCCESS)
        closeSheet()
        rule.onNodeWithText("Success ·", substring = true).assertExists()
        rule.onNodeWithTag(SampleTags.REFUND).performScrollTo().performClick()
        awaitCondition { viewModel.uiState.value.refund is RefundUiState.Done }
        rule.onNodeWithText("Refunded", substring = true).assertExists()
    }

    @Test
    fun declined_isShownInTheSheetAndAsTheResult() {
        start()
        pay(SampleOutcome.DECLINED)
        waitForTag(CheckoutSheetTestTags.FAILED)
        closeSheet()
        rule.onNodeWithText("DECLINED", substring = true).assertExists()
    }

    @Test
    fun pendingThenSuccess() {
        start()
        pay(SampleOutcome.PENDING_THEN_SUCCESS)
        waitForTag(CheckoutSheetTestTags.SUCCESS)
        closeSheet()
        assertThat(viewModel.uiState.value.lastResult).isInstanceOf(com.darshan.checkoutkit.core.CheckoutResult.Success::class.java)
    }

    @Test
    fun cancelByClosingTheSheetBeforePaying() {
        start()
        rule.onNodeWithTag(SampleTags.PAY).performScrollTo().performClick()
        waitForTag(CheckoutSheetTestTags.VPA_FIELD)
        closeSheet()
        rule.onNodeWithText("Cancelled ·", substring = true).assertExists()
    }

    @Test
    fun payerCancelsInTheirUpiApp() {
        start()
        pay(SampleOutcome.CANCEL)
        awaitResult()
        assertThat(viewModel.uiState.value.lastResult).isEqualTo(com.darshan.checkoutkit.core.CheckoutResult.Cancelled)
    }
}
