package com.darshan.checkoutkit.core.state

import com.darshan.checkoutkit.core.error.CheckoutError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PaymentStateMachineTest {

    private val created = PaymentState.Created
    private val processing = PaymentState.Processing(attempt = 1)
    private val pending = PaymentState.Pending(transactionId = "txn_1")
    private val success = PaymentState.Success(transactionId = "txn_1")
    private val failed = PaymentState.Failed(error = CheckoutError.Network)
    private val refunded = PaymentState.Refunded(transactionId = "txn_1", refundId = "rf_1")
    private val cancelled = PaymentState.Cancelled

    private val allStates = listOf(created, processing, pending, success, failed, refunded, cancelled)

    private val submit = PaymentEvent.Submit
    private val retry = PaymentEvent.Retry(attempt = 2)
    private val awaitConfirmation = PaymentEvent.AwaitConfirmation(transactionId = "txn_1")
    private val succeed = PaymentEvent.Succeed(transactionId = "txn_1")
    private val fail = PaymentEvent.Fail(error = CheckoutError.Timeout)
    private val cancel = PaymentEvent.Cancel
    private val refund = PaymentEvent.Refund(refundId = "rf_1")

    private val allEvents = listOf(submit, retry, awaitConfirmation, succeed, fail, cancel, refund)

    /** The complete legal transition table. Every other (state, event) pair must be rejected. */
    private val legal: Map<Pair<PaymentState, PaymentEvent>, PaymentState> = mapOf(
        (created to submit) to PaymentState.Processing(attempt = 1),
        (created to cancel) to PaymentState.Cancelled,
        (processing to retry) to PaymentState.Processing(attempt = 2),
        (processing to awaitConfirmation) to PaymentState.Pending("txn_1"),
        (processing to succeed) to PaymentState.Success("txn_1"),
        (processing to fail) to PaymentState.Failed(CheckoutError.Timeout),
        (pending to succeed) to PaymentState.Success("txn_1"),
        (pending to fail) to PaymentState.Failed(CheckoutError.Timeout),
        (pending to cancel) to PaymentState.Cancelled,
        (success to refund) to PaymentState.Refunded(transactionId = "txn_1", refundId = "rf_1"),
    )

    @Test
    fun exhaustiveTable_everyStateEventPairMatchesTheSpec() {
        var accepted = 0
        var rejected = 0
        for (state in allStates) {
            for (event in allEvents) {
                val result = PaymentStateMachine.transition(state, event)
                val expected = legal[state to event]
                if (expected != null) {
                    val ok = assertIs<TransitionResult.Accepted>(result, "$state + $event should be accepted")
                    assertEquals(expected, ok.to, "$state + $event")
                    assertEquals(state, ok.from)
                    assertEquals(event, ok.event)
                    accepted++
                } else {
                    val no = assertIs<TransitionResult.Rejected>(result, "$state + $event should be rejected")
                    assertEquals(state, no.from)
                    assertEquals(event, no.event)
                    assertTrue(no.reason.isNotBlank())
                    rejected++
                }
            }
        }
        assertEquals(10, accepted)
        assertEquals(39, rejected)
    }

    @Test
    fun terminalStates_acceptNothing() {
        for (state in listOf(failed, refunded, cancelled)) {
            assertTrue(state.isTerminal, "$state should be terminal")
            for (event in allEvents) {
                assertIs<TransitionResult.Rejected>(PaymentStateMachine.transition(state, event))
            }
        }
    }

    @Test
    fun nonTerminalStates_areFlagged() {
        for (state in listOf(created, processing, pending, success)) {
            assertFalse(state.isTerminal, "$state should not be terminal")
        }
    }

    @Test
    fun success_isFinalOutcomeButRefundable() {
        assertTrue(success.isOutcome)
        assertTrue(PaymentStateMachine.canApply(success, refund))
        assertFalse(PaymentStateMachine.canApply(success, cancel))
    }

    @Test
    fun retry_mustIncreaseAttemptByExactlyOne() {
        val p3 = PaymentState.Processing(attempt = 3)
        assertIs<TransitionResult.Rejected>(PaymentStateMachine.transition(p3, PaymentEvent.Retry(3)))
        assertIs<TransitionResult.Rejected>(PaymentStateMachine.transition(p3, PaymentEvent.Retry(2)))
        assertIs<TransitionResult.Rejected>(PaymentStateMachine.transition(p3, PaymentEvent.Retry(5)))
        val ok = assertIs<TransitionResult.Accepted>(PaymentStateMachine.transition(p3, PaymentEvent.Retry(4)))
        assertEquals(PaymentState.Processing(4), ok.to)
    }

    @Test
    fun pendingConfirmation_mustMatchTheSameTransactionId() {
        val spoofed = PaymentStateMachine.transition(pending, PaymentEvent.Succeed("txn_OTHER"))
        val rejected = assertIs<TransitionResult.Rejected>(spoofed)
        assertTrue(rejected.reason.contains("transaction", ignoreCase = true))
    }

    @Test
    fun refundedKeepsOriginalTransactionId() {
        val result = PaymentStateMachine.transition(PaymentState.Success("txn_9"), PaymentEvent.Refund("rf_9"))
        val ok = assertIs<TransitionResult.Accepted>(result)
        assertEquals(PaymentState.Refunded(transactionId = "txn_9", refundId = "rf_9"), ok.to)
    }

    @Test
    fun stateNames_areStableForLoggingAndAnalytics() {
        assertEquals(
            listOf("CREATED", "PROCESSING", "PENDING", "SUCCESS", "FAILED", "REFUNDED", "CANCELLED"),
            allStates.map { it.name },
        )
    }
}
