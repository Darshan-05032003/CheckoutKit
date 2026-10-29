package com.darshan.checkoutkit.core

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.core.state.PaymentEvent
import com.darshan.checkoutkit.core.state.PaymentState
import com.darshan.checkoutkit.core.testing.ScriptedGateway
import com.darshan.checkoutkit.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CheckoutSessionTest {

    private val upi = PaymentMethod.Upi("alice@okbank")
    private val request = CheckoutRequest(orderId = "order-1", amountPaise = 49_900)
    private val policy = RetryPolicy(
        maxAttempts = 3, initialDelay = 100.milliseconds, maxDelay = 1.seconds,
        jitterFactor = 0.0, attemptTimeout = 5.seconds, totalTimeout = 30.seconds,
    )

    private fun TestScope.kit(gateway: ScriptedGateway) = CheckoutKit(
        gateway = gateway,
        config = CheckoutConfig(
            retryPolicy = policy,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = TestClock(),
            random = Random(3),
        ),
    )

    @Test
    fun success_movesCreatedProcessingSuccess() = runTest {
        val gateway = ScriptedGateway()
        val session = kit(gateway).createSession(request)
        assertEquals(PaymentState.Created, session.state.value)

        val result = session.submit(upi)

        val success = assertIs<CheckoutResult.Success>(result)
        assertEquals(49_900, success.amountPaise)
        assertEquals(PaymentState.Success(success.transactionId), session.state.value)
        assertEquals(1, gateway.chargeCount)
        val sent = gateway.requests.single()
        assertEquals(session.idempotencyKey, sent.idempotencyKey)
        assertEquals("order-1", sent.orderId)
        assertEquals(upi, sent.method)
    }

    @Test
    fun retryableFailure_isRetriedWithTheSameIdempotencyKey() = runTest {
        val gateway = ScriptedGateway(
            { GatewayResult.Failed(CheckoutError.Network) },
            { GatewayResult.Failed(CheckoutError.Timeout) },
        )
        val session = kit(gateway).createSession(request)

        val result = session.submit(upi)

        assertIs<CheckoutResult.Success>(result)
        assertEquals(3, gateway.chargeCount)
        assertEquals(1, gateway.requests.map { it.idempotencyKey }.toSet().size)
        assertEquals(
            listOf(PaymentEvent.Submit, PaymentEvent.Retry(2), PaymentEvent.Retry(3)),
            session.history.map { it.event }.take(3),
        )
        assertEquals(300L, testScheduler.currentTime) // 100 ms + 200 ms backoff, virtual time
    }

    @Test
    fun nonRetryableFailure_failsImmediately() = runTest {
        val gateway = ScriptedGateway({ GatewayResult.Failed(CheckoutError.Declined) })
        val session = kit(gateway).createSession(request)

        assertEquals(CheckoutResult.Failed(CheckoutError.Declined), session.submit(upi))
        assertEquals(PaymentState.Failed(CheckoutError.Declined), session.state.value)
        assertEquals(1, gateway.chargeCount)
    }

    @Test
    fun duplicateSubmitAfterSuccess_returnsTheSameResultWithoutCharging() = runTest {
        val gateway = ScriptedGateway()
        val session = kit(gateway).createSession(request)
        val first = session.submit(upi)
        val second = session.submit(upi)
        assertEquals(first, second)
        assertEquals(1, gateway.chargeCount)
    }

    @Test
    fun duplicateSubmitAfterDecline_returnsTheSameDecline() = runTest {
        val gateway = ScriptedGateway({ GatewayResult.Failed(CheckoutError.Declined) })
        val session = kit(gateway).createSession(request)
        session.submit(upi)
        assertEquals(CheckoutResult.Failed(CheckoutError.Declined), session.submit(upi))
        assertEquals(1, gateway.chargeCount)
    }

    @Test
    fun doubleTap_concurrentSubmits_chargeOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = ScriptedGateway({ gate.await(); ScriptedGateway.succeeded(it) })
        val session = kit(gateway).createSession(request)
        val taps = (1..50).map { async { session.submit(upi) } }
        runCurrent()
        gate.complete(Unit)
        val results = taps.awaitAll()
        assertEquals(1, results.toSet().size)
        assertEquals(1, gateway.chargeCount)
    }

    @Test
    fun cancelBeforeSubmit_endsCancelled_andLaterSubmitIsRejected() = runTest {
        val gateway = ScriptedGateway()
        val session = kit(gateway).createSession(request)
        assertTrue(session.cancel())
        assertEquals(PaymentState.Cancelled, session.state.value)
        val result = session.submit(upi)
        assertIs<CheckoutResult.Cancelled>(result)
        assertEquals(0, gateway.chargeCount)
    }

    @Test
    fun cancelWhileProcessing_isRefused() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = ScriptedGateway({ gate.await(); ScriptedGateway.succeeded(it) })
        val session = kit(gateway).createSession(request)
        val pay = async { session.submit(upi) }
        runCurrent()
        assertEquals(false, session.cancel())
        gate.complete(Unit)
        assertIs<CheckoutResult.Success>(pay.await())
    }

    @Test
    fun interruptedSubmit_staysProcessing_andResubmitResumesWithTheSameKey() = runTest {
        val gateway = ScriptedGateway({ awaitCancellation() })
        val session = kit(gateway).createSession(request)
        val job = launch { session.submit(upi) }
        runCurrent()
        job.cancel() // e.g. the screen was destroyed mid-payment
        runCurrent()
        assertEquals(PaymentState.Processing(1), session.state.value)

        val resumed = session.submit(upi)

        assertIs<CheckoutResult.Success>(resumed)
        assertEquals(2, gateway.chargeCount)
        assertEquals(1, gateway.requests.map { it.idempotencyKey }.toSet().size)
    }

    @Test
    fun exhaustedTransientFailure_isNotCached_soTheUserCanTryAgain() = runTest {
        val gateway = ScriptedGateway(
            { GatewayResult.Failed(CheckoutError.Network) },
            { GatewayResult.Failed(CheckoutError.Network) },
            { GatewayResult.Failed(CheckoutError.Network) },
        )
        val kit = kit(gateway)
        val key = IdempotencyKey("transient-key-1")
        val first = kit.createSession(request, key).submit(upi)
        assertEquals(CheckoutResult.Failed(CheckoutError.Network), first)
        assertEquals(3, gateway.chargeCount)

        // "Try again" restores the same key; because the failure was transient it is re-executed
        // (and the gateway can deduplicate on its side), not replayed.
        val again = kit.createSession(request, key).submit(upi)
        assertIs<CheckoutResult.Success>(again)
        assertEquals(4, gateway.chargeCount)
    }

    @Test
    fun sameKeyReusedWithADifferentAmount_isRejected() = runTest {
        val gateway = ScriptedGateway()
        val kit = kit(gateway)
        val key = IdempotencyKey("restored-key-1")
        kit.createSession(request, key).submit(upi)
        val tampered = kit.createSession(request.copy(amountPaise = 1), key).submit(upi)
        val rejected = assertIs<CheckoutResult.Rejected>(tampered)
        assertIs<CheckoutError.InvalidRequest>(rejected.error)
        assertEquals(1, gateway.chargeCount)
    }

    @Test
    fun secondSessionWithTheSameKey_getsTheSameResult_andSyncsItsState() = runTest {
        val gateway = ScriptedGateway()
        val kit = kit(gateway)
        val key = IdempotencyKey("restored-key-2")
        val first = kit.createSession(request, key).submit(upi)
        val twin = kit.createSession(request, key)
        assertEquals(first, twin.submit(upi))
        assertEquals(PaymentState.Success((first as CheckoutResult.Success).transactionId), twin.state.value)
        assertEquals(1, gateway.chargeCount)
    }

    @Test
    fun gatewayPending_movesToPending() = runTest {
        val gateway = ScriptedGateway({ GatewayResult.Pending("txn_p", it.amountPaise) })
        val session = kit(gateway).createSession(request)
        assertEquals(CheckoutResult.Pending("txn_p"), session.submit(upi))
        assertEquals(PaymentState.Pending("txn_p"), session.state.value)
    }

    @Test
    fun everySessionGetsItsOwnKey() = runTest {
        val kit = kit(ScriptedGateway())
        val keys = (1..100).map { kit.createSession(request).idempotencyKey }
        assertEquals(100, keys.toSet().size)
    }

    @Test
    fun gatewayExceptions_areRetriedAndMapped() = runTest {
        val gateway = ScriptedGateway({ error("bug in gateway adapter") })
        val session = kit(gateway).createSession(request)
        val failed = assertIs<CheckoutResult.Failed>(session.submit(upi))
        assertIs<CheckoutError.Unknown>(failed.error)
    }
}
