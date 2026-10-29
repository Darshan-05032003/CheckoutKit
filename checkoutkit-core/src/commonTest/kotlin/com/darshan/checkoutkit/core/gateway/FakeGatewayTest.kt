package com.darshan.checkoutkit.core.gateway

import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.checkoutkit.core.testing.TestClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FakeGatewayTest {

    private fun fake(config: FakeGatewayConfig = FakeGatewayConfig(latency = 300.milliseconds)) =
        FakeGateway(config, clock = TestClock(), random = Random(1))

    private fun charge(
        key: String = "key-00001",
        amount: Long = 10_000,
        method: PaymentMethod = PaymentMethod.Upi("alice@okbank"),
    ) = ChargeRequest(IdempotencyKey(key), "order-1", amount, "INR", method)

    @Test
    fun success_capturesAfterTheConfiguredLatency() = runTest {
        val gateway = fake()
        val result = assertIs<GatewayResult.Succeeded>(gateway.charge(charge()))
        assertEquals(10_000, result.amountPaise)
        assertTrue(result.transactionId.startsWith("fake_txn_"))
        assertEquals(300L, currentTime)
        assertEquals(1, gateway.capturedPayments.size)
    }

    @Test
    fun sameKeyTwice_isDeduplicatedServerSide() = runTest {
        val gateway = fake()
        val first = gateway.charge(charge())
        val second = gateway.charge(charge())
        assertEquals(first, second)
        assertEquals(2, gateway.chargeCalls)
        assertEquals(1, gateway.capturedPayments.size)
    }

    @Test
    fun differentKeys_createDifferentTransactions() = runTest {
        val gateway = fake()
        val a = assertIs<GatewayResult.Succeeded>(gateway.charge(charge("key-aaaaa")))
        val b = assertIs<GatewayResult.Succeeded>(gateway.charge(charge("key-bbbbb")))
        assertNotEquals(a.transactionId, b.transactionId)
    }

    @Test
    fun failureOutcomes_returnTypedErrors() = runTest {
        val table = mapOf(
            FakeOutcome.DECLINED to CheckoutError.Declined,
            FakeOutcome.INSUFFICIENT_FUNDS to CheckoutError.InsufficientFunds,
            FakeOutcome.INVALID_VPA to CheckoutError.InvalidVpa,
            FakeOutcome.GATEWAY_ERROR to CheckoutError.GatewayError("HTTP_503"),
        )
        table.forEach { (outcome, error) ->
            val gateway = fake(FakeGatewayConfig(outcome = outcome, latency = Duration.ZERO))
            assertEquals(GatewayResult.Failed(error), gateway.charge(charge()), outcome.name)
            assertTrue(gateway.capturedPayments.isEmpty())
        }
    }

    @Test
    fun timeoutOutcome_neverAnswers() = runTest {
        val gateway = fake(FakeGatewayConfig(outcome = FakeOutcome.TIMEOUT))
        assertNull(withTimeoutOrNull(60.seconds) { gateway.charge(charge()) })
    }

    @Test
    fun pendingThenSuccess_resolvesAfterTheConfiguredPolls() = runTest {
        val gateway = fake(FakeGatewayConfig(outcome = FakeOutcome.PENDING_THEN_SUCCESS, latency = Duration.ZERO, pendingPolls = 2))
        val pending = assertIs<GatewayResult.Pending>(gateway.charge(charge()))
        val query = StatusQuery(IdempotencyKey("key-00001"), pending.transactionId)
        assertIs<GatewayResult.Pending>(gateway.status(query))
        assertIs<GatewayResult.Pending>(gateway.status(query))
        assertEquals(GatewayResult.Succeeded(pending.transactionId, 10_000), gateway.status(query))
        assertEquals(GatewayResult.Succeeded(pending.transactionId, 10_000), gateway.status(query))
    }

    @Test
    fun pendingThenFailure_andPayerCancel() = runTest {
        val failing = fake(FakeGatewayConfig(outcome = FakeOutcome.PENDING_THEN_FAILURE, latency = Duration.ZERO, pendingPolls = 0))
        val p1 = assertIs<GatewayResult.Pending>(failing.charge(charge()))
        assertEquals(GatewayResult.Failed(CheckoutError.Declined), failing.status(StatusQuery(IdempotencyKey("key-00001"), p1.transactionId)))

        val cancelling = fake(FakeGatewayConfig(outcome = FakeOutcome.PAYER_CANCELLED, latency = Duration.ZERO, pendingPolls = 1))
        val p2 = assertIs<GatewayResult.Pending>(cancelling.charge(charge()))
        val q = StatusQuery(IdempotencyKey("key-00001"), p2.transactionId)
        assertIs<GatewayResult.Pending>(cancelling.status(q))
        assertEquals(GatewayResult.Cancelled(p2.transactionId), cancelling.status(q))
    }

    @Test
    fun status_ofAnUnknownPayment_isNotFound() = runTest {
        val gateway = fake()
        val result = assertIs<GatewayResult.Failed>(gateway.status(StatusQuery(IdempotencyKey("key-never"), null)))
        assertIs<CheckoutError.InvalidRequest>(result.error)
    }

    @Test
    fun transientFailures_failFirstAttemptsPerKey() = runTest {
        val gateway = fake(FakeGatewayConfig(latency = Duration.ZERO, transientFailures = 2))
        assertEquals(GatewayResult.Failed(CheckoutError.Network), gateway.charge(charge()))
        assertEquals(GatewayResult.Failed(CheckoutError.Network), gateway.charge(charge()))
        assertIs<GatewayResult.Succeeded>(gateway.charge(charge()))
        // A new key starts its own count.
        assertEquals(GatewayResult.Failed(CheckoutError.Network), gateway.charge(charge("key-other")))
    }

    @Test
    fun rateLimitedOutcome_throttlesTheFirstCallOnly() = runTest {
        val gateway = fake(FakeGatewayConfig(outcome = FakeOutcome.RATE_LIMITED_ONCE, latency = Duration.ZERO))
        assertEquals(GatewayResult.Failed(CheckoutError.RateLimited(retryAfterMillis = 1_000)), gateway.charge(charge()))
        assertIs<GatewayResult.Succeeded>(gateway.charge(charge()))
    }

    @Test
    fun amountMismatch_simulatesATamperedResponse() = runTest {
        val gateway = fake(FakeGatewayConfig(outcome = FakeOutcome.AMOUNT_MISMATCH, latency = Duration.ZERO))
        assertEquals(10_001, assertIs<GatewayResult.Succeeded>(gateway.charge(charge())).amountPaise)
    }

    @Test
    fun latencySpikes_andJitter() = runTest {
        val spiky = fake(FakeGatewayConfig(latency = 100.milliseconds, latencySpikeChance = 1.0, latencySpike = 4.seconds))
        spiky.charge(charge())
        assertEquals(4_000L, currentTime)

        val jittery = FakeGateway(FakeGatewayConfig(latency = 100.milliseconds, latencyJitter = 50.milliseconds), TestClock(), Random(2))
        val before = currentTime
        repeat(20) { i -> jittery.charge(charge("key-jit-$i")) }
        val average = (currentTime - before) / 20
        assertTrue(average in 100L..150L, "average=$average")
    }

    @Test
    fun magicInputs_triggerOutcomesLikeASandbox() = runTest {
        val gateway = fake(FakeGatewayConfig(latency = Duration.ZERO))
        val card = { number: String -> PaymentMethod.Card(number, 12, 2035, "123") }
        assertEquals(GatewayResult.Failed(CheckoutError.Declined), gateway.charge(charge("key-magic-1", method = card("4000000000000002"))))
        assertEquals(GatewayResult.Failed(CheckoutError.InsufficientFunds), gateway.charge(charge("key-magic-2", method = card("4000000000009995"))))
        assertEquals(GatewayResult.Failed(CheckoutError.CardExpired), gateway.charge(charge("key-magic-3", method = card("4000000000000069"))))
        assertEquals(GatewayResult.Failed(CheckoutError.InsufficientFunds), gateway.charge(charge("key-magic-4", method = PaymentMethod.Upi("broke@fakebank"))))
        assertEquals(GatewayResult.Failed(CheckoutError.InvalidVpa), gateway.charge(charge("key-magic-5", method = PaymentMethod.Upi("unknown@fakebank"))))
        assertEquals(GatewayResult.Failed(CheckoutError.Declined), gateway.charge(charge("key-magic-6", method = PaymentMethod.Upi("decline@fakebank"))))
        assertIs<GatewayResult.Succeeded>(gateway.charge(charge("key-magic-7", method = card("4242424242424242"))))
    }

    @Test
    fun refund_ofACapturedPayment_isIdempotentPerRefundKey() = runTest {
        val gateway = fake(FakeGatewayConfig(latency = Duration.ZERO))
        val paid = assertIs<GatewayResult.Succeeded>(gateway.charge(charge()))
        val request = RefundRequest(IdempotencyKey("key-00001-rf"), paid.transactionId, 10_000)
        val first = assertIs<RefundResult.Refunded>(gateway.refund(request))
        assertEquals(first, gateway.refund(request))
        assertEquals(1, gateway.refundedPayments.size)
        assertEquals(10_000, first.amountPaise)
    }

    @Test
    fun refund_isRejectedForUnknownOrUncapturedPaymentsAndWrongAmounts() = runTest {
        val gateway = fake(FakeGatewayConfig(outcome = FakeOutcome.PENDING_THEN_SUCCESS, latency = Duration.ZERO, pendingPolls = 5))
        val pending = assertIs<GatewayResult.Pending>(gateway.charge(charge()))
        assertIs<RefundResult.Failed>(gateway.refund(RefundRequest(IdempotencyKey("refund-key-1"), pending.transactionId, 10_000)))
        assertIs<RefundResult.Failed>(gateway.refund(RefundRequest(IdempotencyKey("refund-key-2"), "fake_txn_nope", 10_000)))

        val paidGateway = fake(FakeGatewayConfig(latency = Duration.ZERO))
        val paid = assertIs<GatewayResult.Succeeded>(paidGateway.charge(charge()))
        assertIs<RefundResult.Failed>(paidGateway.refund(RefundRequest(IdempotencyKey("refund-key-3"), paid.transactionId, 99_999)))
    }

    @Test
    fun config_canBeChangedAtRuntime() = runTest {
        val gateway = fake(FakeGatewayConfig(latency = Duration.ZERO))
        gateway.config = FakeGatewayConfig(outcome = FakeOutcome.DECLINED, latency = Duration.ZERO)
        assertEquals(GatewayResult.Failed(CheckoutError.Declined), gateway.charge(charge()))
    }

    @Test
    fun reset_forgetsEverything() = runTest {
        val gateway = fake(FakeGatewayConfig(latency = Duration.ZERO))
        gateway.charge(charge())
        gateway.reset()
        assertEquals(0, gateway.chargeCalls)
        assertTrue(gateway.capturedPayments.isEmpty())
    }

    @Test
    fun invalidConfig_isRejected() {
        assertFailsWith<IllegalArgumentException> { FakeGatewayConfig(latency = (-1).milliseconds) }
        assertFailsWith<IllegalArgumentException> { FakeGatewayConfig(latencySpikeChance = 1.5) }
        assertFailsWith<IllegalArgumentException> { FakeGatewayConfig(pendingPolls = -1) }
        assertFailsWith<IllegalArgumentException> { FakeGatewayConfig(transientFailures = -1) }
    }
}
