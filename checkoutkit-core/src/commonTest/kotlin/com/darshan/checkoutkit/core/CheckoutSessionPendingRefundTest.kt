package com.darshan.checkoutkit.core

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.gateway.RefundResult
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.core.state.PaymentState
import com.darshan.checkoutkit.core.testing.ScriptedGateway
import com.darshan.checkoutkit.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CheckoutSessionPendingRefundTest {

    private val upi = PaymentMethod.Upi("alice@okbank")
    private val request = CheckoutRequest(orderId = "order-p", amountPaise = 12_345)
    private val pendingPolicy = PendingPolicy(initialInterval = 1.seconds, maxInterval = 4.seconds, multiplier = 2.0, maxPolls = 5)

    private fun TestScope.kit(gateway: ScriptedGateway) = CheckoutKit(
        gateway,
        CheckoutConfig(
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = 100.milliseconds, jitterFactor = 0.0),
            pendingPolicy = pendingPolicy,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = TestClock(),
            random = Random(1),
        ),
    )

    private fun pendingCharge() = ScriptedGateway({ GatewayResult.Pending("txn_p", it.amountPaise) })

    @Test
    fun pending_isPolledUntilSuccess_withGrowingIntervals() = runTest {
        val gateway = pendingCharge().onStatus(
            { GatewayResult.Pending("txn_p", 12_345) },
            { GatewayResult.Pending("txn_p", 12_345) },
            { GatewayResult.Succeeded("txn_p", 12_345) },
        )
        val session = kit(gateway).createSession(request)
        assertEquals(CheckoutResult.Success("txn_p", 12_345), session.submit(upi))
        assertEquals(PaymentState.Success("txn_p"), session.state.value)
        assertEquals(3, gateway.statusQueries.size)
        assertEquals(1_000L + 2_000 + 4_000, testScheduler.currentTime)
        gateway.statusQueries.forEach { assertEquals(session.idempotencyKey, it.idempotencyKey); assertEquals("txn_p", it.transactionId) }
    }

    @Test
    fun pending_thenFailure() = runTest {
        val gateway = pendingCharge().onStatus({ GatewayResult.Failed(CheckoutError.Declined) })
        val session = kit(gateway).createSession(request)
        assertEquals(CheckoutResult.Failed(CheckoutError.Declined), session.submit(upi))
        assertEquals(PaymentState.Failed(CheckoutError.Declined), session.state.value)
    }

    @Test
    fun pending_thenPayerCancelled() = runTest {
        val gateway = pendingCharge().onStatus({ GatewayResult.Cancelled("txn_p") })
        val session = kit(gateway).createSession(request)
        assertEquals(CheckoutResult.Cancelled, session.submit(upi))
        assertEquals(PaymentState.Cancelled, session.state.value)
    }

    @Test
    fun pollingExhausted_staysPending_andCheckStatusResolvesLater() = runTest {
        val gateway = pendingCharge()
        val session = kit(gateway).createSession(request)
        assertEquals(CheckoutResult.Pending("txn_p"), session.submit(upi))
        assertEquals(5, gateway.statusQueries.size)
        assertEquals(PaymentState.Pending("txn_p"), session.state.value)

        gateway.onStatus({ GatewayResult.Succeeded("txn_p", 12_345) })
        assertEquals(CheckoutResult.Success("txn_p", 12_345), session.checkStatus())
        assertEquals(PaymentState.Success("txn_p"), session.state.value)
    }

    @Test
    fun statusCallErrors_duringPolling_areTolerated() = runTest {
        val gateway = pendingCharge().onStatus(
            { throw com.darshan.checkoutkit.core.error.GatewayException(CheckoutError.Network) },
            { error("adapter bug") },
            { GatewayResult.Succeeded("txn_p", 12_345) },
        )
        assertIs<CheckoutResult.Success>(kit(gateway).createSession(request).submit(upi))
    }

    @Test
    fun checkStatus_outsidePending_returnsTheCurrentOutcome() = runTest {
        val session = kit(ScriptedGateway()).createSession(request)
        assertIs<CheckoutResult.Rejected>(session.checkStatus())
        val paid = session.submit(upi)
        assertEquals(paid, session.checkStatus())
    }

    @Test
    fun capturedAmountDifferentFromRequest_failsAsResponseMismatch() = runTest {
        val gateway = ScriptedGateway({ GatewayResult.Succeeded("txn_x", it.amountPaise + 1) })
        val session = kit(gateway).createSession(request)
        assertEquals(CheckoutResult.Failed(CheckoutError.ResponseMismatch), session.submit(upi))
        assertEquals(PaymentState.Failed(CheckoutError.ResponseMismatch), session.state.value)
    }

    @Test
    fun pendingAmountDifferentFromRequest_failsAsResponseMismatch() = runTest {
        val gateway = ScriptedGateway({ GatewayResult.Pending("txn_x", 1) })
        assertEquals(CheckoutResult.Failed(CheckoutError.ResponseMismatch), kit(gateway).createSession(request).submit(upi))
    }

    @Test
    fun spoofedConfirmationForAnotherTransaction_failsAsResponseMismatch() = runTest {
        val gateway = pendingCharge().onStatus({ GatewayResult.Succeeded("txn_OTHER", 12_345) })
        val session = kit(gateway).createSession(request)
        assertEquals(CheckoutResult.Failed(CheckoutError.ResponseMismatch), session.submit(upi))
        assertEquals(PaymentState.Failed(CheckoutError.ResponseMismatch), session.state.value)
    }

    @Test
    fun malformedTransactionIds_failSafelyInsteadOfCrashing() = runTest {
        listOf("", "   ", "t".repeat(10_000)).forEach { badId ->
            val gateway = ScriptedGateway({ GatewayResult.Succeeded(badId, it.amountPaise) })
            assertEquals(CheckoutResult.Failed(CheckoutError.ResponseMismatch), kit(gateway).createSession(request).submit(upi))
        }
    }

    @Test
    fun chargeReportingCancelledDirectly_isTreatedAsDeclined() = runTest {
        val gateway = ScriptedGateway({ GatewayResult.Cancelled("txn_c") })
        assertEquals(CheckoutResult.Failed(CheckoutError.Declined), kit(gateway).createSession(request).submit(upi))
    }

    @Test
    fun cancelWhilePending_isRefused_soAPossiblyPaidOrderIsNeverHidden() = runTest {
        val gate = CompletableDeferred<GatewayResult>()
        val gateway = pendingCharge().onStatus({ gate.await() })
        val session = kit(gateway).createSession(request)
        val pay = async { session.submit(upi) }
        testScheduler.advanceTimeBy(500)
        runCurrent()
        assertEquals(PaymentState.Pending("txn_p"), session.state.value)
        assertFalse(session.cancel())
        gate.complete(GatewayResult.Succeeded("txn_p", 12_345))
        assertIs<CheckoutResult.Success>(pay.await())
    }

    @Test
    fun refund_movesSuccessToRefunded() = runTest {
        val gateway = ScriptedGateway()
        val session = kit(gateway).createSession(request)
        val paid = assertIs<CheckoutResult.Success>(session.submit(upi))
        val refunded = assertIs<CheckoutResult.Refunded>(session.refund())
        assertEquals(paid.transactionId, refunded.transactionId)
        assertEquals(PaymentState.Refunded(paid.transactionId, refunded.refundId), session.state.value)
        val sent = gateway.refunds.single()
        assertEquals(12_345, sent.amountPaise)
        assertEquals(paid.transactionId, sent.transactionId)
        assertEquals(session.idempotencyKey.value + "-rf", sent.refundKey.value)
    }

    @Test
    fun concurrentRefunds_refundOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = ScriptedGateway().onRefund({ gate.await(); RefundResult.Refunded("rf_1", it.amountPaise) })
        val session = kit(gateway).createSession(request)
        session.submit(upi)
        val refunds = (1..20).map { async { session.refund() } }
        runCurrent()
        gate.complete(Unit)
        assertEquals(1, refunds.awaitAll().toSet().size)
        assertEquals(1, gateway.refunds.size)
    }

    @Test
    fun refund_beforeSuccess_isRejected() = runTest {
        val gateway = ScriptedGateway()
        val session = kit(gateway).createSession(request)
        assertIs<CheckoutResult.Rejected>(session.refund())
        assertEquals(0, gateway.refunds.size)
    }

    @Test
    fun failedRefund_keepsTheSessionPaid() = runTest {
        val gateway = ScriptedGateway().onRefund(
            { RefundResult.Failed(CheckoutError.GatewayError("HTTP_503")) },
            { RefundResult.Failed(CheckoutError.GatewayError("HTTP_503")) },
        )
        val session = kit(gateway).createSession(request)
        val paid = assertIs<CheckoutResult.Success>(session.submit(upi))
        assertEquals(CheckoutResult.Failed(CheckoutError.GatewayError("HTTP_503")), session.refund())
        assertEquals(PaymentState.Success(paid.transactionId), session.state.value)
        assertEquals(2, gateway.refunds.size) // retried with the same refund key
        assertEquals(1, gateway.refunds.map { it.refundKey }.toSet().size)
    }

    @Test
    fun refundForADifferentAmount_isAResponseMismatch() = runTest {
        val gateway = ScriptedGateway().onRefund({ RefundResult.Refunded("rf_1", 1) })
        val session = kit(gateway).createSession(request)
        session.submit(upi)
        assertEquals(CheckoutResult.Failed(CheckoutError.ResponseMismatch), session.refund())
        assertIs<PaymentState.Success>(session.state.value)
    }

    @Test
    fun pendingPolicy_validation() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { PendingPolicy(maxPolls = 0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { PendingPolicy(maxPolls = PendingPolicy.MAX_POLLS_LIMIT + 1) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { PendingPolicy(initialInterval = 0.seconds) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { PendingPolicy(initialInterval = 5.seconds, maxInterval = 1.seconds) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { PendingPolicy(multiplier = 0.9) }
        assertEquals(listOf(1, 2, 4, 4, 4).map { it.seconds }, (1..5).map { pendingPolicy.intervalBefore(it) })
    }
}
