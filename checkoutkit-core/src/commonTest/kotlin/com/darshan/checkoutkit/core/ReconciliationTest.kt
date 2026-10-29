package com.darshan.checkoutkit.core

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.ChargeRequest
import com.darshan.checkoutkit.core.gateway.FakeGateway
import com.darshan.checkoutkit.core.gateway.FakeGatewayConfig
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.core.state.PaymentState
import com.darshan.checkoutkit.core.testing.ScriptedGateway
import com.darshan.checkoutkit.core.testing.TestClock
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * When every attempt ends with an unknown outcome (timeout / network), the money may still have
 * been captured. The session must ask the gateway before declaring the payment failed.
 */
class ReconciliationTest {

    private val upi = PaymentMethod.Upi("alice@okbank")
    private val request = CheckoutRequest("order-r", 7_500)

    private fun TestScope.kit(gateway: ScriptedGateway) = CheckoutKit(
        gateway,
        CheckoutConfig(
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = 100.milliseconds, jitterFactor = 0.0, attemptTimeout = 2.seconds),
            pendingPolicy = PendingPolicy(initialInterval = 1.seconds, maxPolls = 3),
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = TestClock(),
            random = Random(1),
        ),
    )

    private fun hangingCharges() = ScriptedGateway().apply { fallback = { awaitCancellation() } }

    @Test
    fun timedOutButCapturedAtTheGateway_isReportedAsSuccess() = runTest {
        val gateway = hangingCharges().onStatus({ GatewayResult.Succeeded("txn_late", 7_500) })
        val session = kit(gateway).createSession(request)
        assertEquals(CheckoutResult.Success("txn_late", 7_500), session.submit(upi))
        assertEquals(PaymentState.Success("txn_late"), session.state.value)
        val query = gateway.statusQueries.single()
        assertEquals(session.idempotencyKey, query.idempotencyKey)
        assertNull(query.transactionId)
    }

    @Test
    fun timedOutButPendingAtTheGateway_isPolled() = runTest {
        val gateway = hangingCharges().onStatus(
            { GatewayResult.Pending("txn_p", 7_500) },
            { GatewayResult.Succeeded("txn_p", 7_500) },
        )
        assertEquals(CheckoutResult.Success("txn_p", 7_500), kit(gateway).createSession(request).submit(upi))
    }

    @Test
    fun timedOutAndUnknownToTheGateway_failsWithTheOriginalError() = runTest {
        val gateway = hangingCharges().onStatus({ GatewayResult.Failed(CheckoutError.InvalidRequest("unknown payment")) })
        assertEquals(CheckoutResult.Failed(CheckoutError.Timeout), kit(gateway).createSession(request).submit(upi))
    }

    @Test
    fun networkFailuresThenUnreachableStatus_failWithNetwork() = runTest {
        val gateway = ScriptedGateway().apply { fallback = { GatewayResult.Failed(CheckoutError.Network) } }
            .onStatus({ throw com.darshan.checkoutkit.core.error.GatewayException(CheckoutError.Network) })
        // First lookup fails (retried), second says "unknown payment": the charge never landed.
        assertEquals(CheckoutResult.Failed(CheckoutError.Network), kit(gateway).createSession(request).submit(upi))
        assertEquals(2, gateway.statusQueries.size)
    }

    @Test
    fun gatewayUnreachableForTheLookupToo_isUnresolved_notFailed() = runTest {
        val gateway = hangingCharges()
        gateway.statusFallback = { awaitCancellation() }
        val session = kit(gateway).createSession(request)
        assertEquals(CheckoutResult.Unresolved(CheckoutError.Timeout), session.submit(upi))
        // The outcome is unknown, so the state is not a final failure.
        assertEquals(PaymentState.Processing(2), session.state.value)
        assertEquals(2, gateway.statusQueries.size)

        // Later the host reconciles and learns the money was captured.
        gateway.statusFallback = { GatewayResult.Succeeded("txn_found", 7_500) }
        assertEquals(CheckoutResult.Success("txn_found", 7_500), session.checkStatus())
        assertEquals(PaymentState.Success("txn_found"), session.state.value)
    }

    @Test
    fun checkStatusWhileProcessing_andTheGatewayNeverSawTheCharge_failsWithTimeout() = runTest {
        val gateway = hangingCharges()
        gateway.statusFallback = { awaitCancellation() }
        val session = kit(gateway).createSession(request)
        session.submit(upi)
        gateway.statusFallback = { GatewayResult.Failed(CheckoutError.InvalidRequest("unknown payment")) }
        assertEquals(CheckoutResult.Failed(CheckoutError.Timeout), session.checkStatus())
        assertEquals(PaymentState.Failed(CheckoutError.Timeout), session.state.value)
    }

    @Test
    fun unresolvedResults_areNotReplayed_soASecondSubmitReconcilesAgain() = runTest {
        val gateway = hangingCharges()
        gateway.statusFallback = { awaitCancellation() }
        val session = kit(gateway).createSession(request)
        session.submit(upi)
        gateway.fallback = { GatewayResult.Succeeded("txn_retry", it.amountPaise) }
        assertEquals(CheckoutResult.Success("txn_retry", 7_500), session.submit(upi))
    }

    @Test
    fun definiteFailures_areNotReconciled() = runTest {
        val gateway = ScriptedGateway({ GatewayResult.Failed(CheckoutError.Declined) })
        assertEquals(CheckoutResult.Failed(CheckoutError.Declined), kit(gateway).createSession(request).submit(upi))
        assertTrue(gateway.statusQueries.isEmpty())
    }

    @Test
    fun reconciledSuccessWithTheWrongAmount_isAResponseMismatch() = runTest {
        val gateway = hangingCharges().onStatus({ GatewayResult.Succeeded("txn_late", 1) })
        assertEquals(CheckoutResult.Failed(CheckoutError.ResponseMismatch), kit(gateway).createSession(request).submit(upi))
    }

    @Test
    fun fakeGateway_slowResponseLeg_capturesEvenWhenTheCallerTimesOut() = runTest {
        val fake = FakeGateway(FakeGatewayConfig(latency = 100.milliseconds, latencySpikeChance = 1.0, latencySpike = 10.seconds), TestClock(), Random(1))
        val charge = ChargeRequest(IdempotencyKey("late-key-01"), "order-late", 5_000, "INR", upi)
        assertNull(withTimeoutOrNull(1.seconds) { fake.charge(charge) })
        assertEquals(1, fake.capturedPayments.size) // captured, but the caller never heard back
    }

    @Test
    fun resumedSessionAfterProcessDeath_reconcilesByTheSavedKey() = runTest {
        val gateway = ScriptedGateway().onStatus({ GatewayResult.Succeeded("txn_before_death", 7_500) })
        val key = IdempotencyKey("saved-before-death")
        val session = kit(gateway).resumeSession(request, key)
        assertEquals(PaymentState.Processing(1), session.state.value)
        assertEquals(CheckoutResult.Success("txn_before_death", 7_500), session.checkStatus())
        assertEquals(key, gateway.statusQueries.single().idempotencyKey)
        assertEquals(0, gateway.chargeCount) // never charged again
    }

    @Test
    fun resumedSession_whoseChargeNeverLanded_canBeSubmittedWithTheSameKey() = runTest {
        val gateway = ScriptedGateway()
        val key = IdempotencyKey("saved-before-death")
        val session = kit(gateway).resumeSession(request, key)
        assertEquals(CheckoutResult.Failed(CheckoutError.Timeout), session.checkStatus())
        val fresh = kit(gateway).createSession(request, key)
        assertTrue(fresh.submit(upi) is CheckoutResult.Success)
        assertEquals(key, gateway.requests.single().idempotencyKey)
    }
}
