package com.darshan.checkoutkit.core

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.FakeGateway
import com.darshan.checkoutkit.core.gateway.FakeGatewayConfig
import com.darshan.checkoutkit.core.gateway.RefundResult
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.core.testing.ScriptedGateway
import com.darshan.checkoutkit.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Refunds by transaction id, for hosts that only kept the CheckoutResult (e.g. from the sheet). */
class KitRefundTest {

    private fun config(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) = CheckoutConfig(
        retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = 10.milliseconds, jitterFactor = 0.0),
        dispatcher = StandardTestDispatcher(scheduler),
        clock = TestClock(),
        random = Random(1),
    )

    @Test
    fun refundsACapturedPayment_andRepeatsAreIdempotentPerKey() = runTest {
        val fake = FakeGateway(FakeGatewayConfig(latency = Duration.ZERO), TestClock(), Random(1))
        val kit = CheckoutKit(fake, config(testScheduler))
        val paid = assertIs<CheckoutResult.Success>(kit.createSession(CheckoutRequest("order-k1", 9_900)).submit(PaymentMethod.Upi("a@okbank")))
        val key = IdempotencyKey("refund-key-k1")
        val first = assertIs<CheckoutResult.Refunded>(kit.refund(paid.transactionId, paid.amountPaise, key))
        assertEquals(first, kit.refund(paid.transactionId, paid.amountPaise, key))
        assertEquals(1, fake.refundedPayments.size)
    }

    @Test
    fun concurrentRefundsWithOneKey_refundOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = ScriptedGateway().onRefund({ gate.await(); RefundResult.Refunded("rf_once", it.amountPaise) })
        val kit = CheckoutKit(gateway, config(testScheduler))
        val key = IdempotencyKey("refund-key-k2")
        val calls = (1..25).map { async { kit.refund("txn_k2", 500, key) } }
        runCurrent()
        gate.complete(Unit)
        assertEquals(1, calls.awaitAll().toSet().size)
        assertEquals(1, gateway.refunds.size)
    }

    @Test
    fun refundForADifferentAmount_isAResponseMismatch() = runTest {
        val gateway = ScriptedGateway().onRefund({ RefundResult.Refunded("rf_bad", 1) })
        val kit = CheckoutKit(gateway, config(testScheduler))
        assertEquals(CheckoutResult.Failed(CheckoutError.ResponseMismatch), kit.refund("txn_k3", 500, IdempotencyKey("refund-key-k3")))
    }

    @Test
    fun gatewayRefusal_isReturnedAsFailed() = runTest {
        val fake = FakeGateway(FakeGatewayConfig(latency = Duration.ZERO), TestClock(), Random(1))
        val kit = CheckoutKit(fake, config(testScheduler))
        val result = assertIs<CheckoutResult.Failed>(kit.refund("fake_txn_unknown", 500, IdempotencyKey("refund-key-k4")))
        assertIs<CheckoutError.InvalidRequest>(result.error)
    }

    @Test
    fun invalidArguments_areRejected() = runTest {
        val kit = CheckoutKit(ScriptedGateway(), config(testScheduler))
        assertFailsWith<IllegalArgumentException> { kit.refund("", 500) }
        assertFailsWith<IllegalArgumentException> { kit.refund("txn", 0) }
        assertFailsWith<IllegalArgumentException> { kit.refund("t".repeat(200), 500) }
    }

    @Test
    fun sameRefundKeyForADifferentTransaction_isRejected() = runTest {
        val kit = CheckoutKit(ScriptedGateway(), config(testScheduler))
        val key = IdempotencyKey("refund-key-k6")
        kit.refund("txn_a", 500, key)
        assertIs<CheckoutResult.Rejected>(kit.refund("txn_b", 500, key))
    }
}
