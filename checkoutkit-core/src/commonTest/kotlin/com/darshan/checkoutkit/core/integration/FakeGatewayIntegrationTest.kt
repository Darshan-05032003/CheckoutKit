package com.darshan.checkoutkit.core.integration

import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.PendingPolicy
import com.darshan.checkoutkit.core.WalletProvider
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.FakeGateway
import com.darshan.checkoutkit.core.gateway.FakeGatewayConfig
import com.darshan.checkoutkit.core.gateway.FakeOutcome
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.core.state.PaymentState
import com.darshan.checkoutkit.core.testing.TestClock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Every FakeGateway outcome through the real CheckoutKit stack (session, retry, idempotency, polling). */
class FakeGatewayIntegrationTest {

    private val request = CheckoutRequest("order-fake", 49_900, description = "Sample order")

    private fun TestScope.stack(config: FakeGatewayConfig): Pair<CheckoutKit, FakeGateway> {
        val gateway = FakeGateway(config, TestClock(), Random(4))
        val kit = CheckoutKit(
            gateway,
            CheckoutConfig(
                retryPolicy = RetryPolicy(maxAttempts = 3, initialDelay = 500.milliseconds, attemptTimeout = 5.seconds, totalTimeout = 30.seconds),
                pendingPolicy = PendingPolicy(initialInterval = 1.seconds, maxInterval = 2.seconds, maxPolls = 10),
                dispatcher = StandardTestDispatcher(testScheduler),
                clock = TestClock(),
                random = Random(4),
            ),
        )
        return kit to gateway
    }

    private suspend fun CheckoutKit.pay(method: PaymentMethod = PaymentMethod.Upi("alice@okbank")) =
        createSession(request).let { it to it.submit(method) }

    @Test
    fun success() = runTest {
        val (kit, gateway) = stack(FakeGatewayConfig(latency = 400.milliseconds))
        val (session, result) = kit.pay()
        assertIs<CheckoutResult.Success>(result)
        assertIs<PaymentState.Success>(session.state.value)
        assertEquals(1, gateway.capturedPayments.size)
    }

    @Test
    fun pendingThenSuccess() = runTest {
        val (kit, _) = stack(FakeGatewayConfig(outcome = FakeOutcome.PENDING_THEN_SUCCESS, pendingPolls = 3))
        val (session, result) = kit.pay()
        assertIs<CheckoutResult.Success>(result)
        assertEquals(listOf("CREATED", "PROCESSING", "PENDING", "SUCCESS"), listOf("CREATED") + session.history.map { it.to.name })
    }

    @Test
    fun declinedInsufficientFundsInvalidVpaAndGatewayError() = runTest {
        mapOf(
            FakeOutcome.DECLINED to CheckoutError.Declined,
            FakeOutcome.INSUFFICIENT_FUNDS to CheckoutError.InsufficientFunds,
            FakeOutcome.INVALID_VPA to CheckoutError.InvalidVpa,
            FakeOutcome.GATEWAY_ERROR to CheckoutError.GatewayError("HTTP_503"),
        ).forEach { (outcome, error) ->
            val (kit, gateway) = stack(FakeGatewayConfig(outcome = outcome))
            val (session, result) = kit.pay()
            assertEquals(CheckoutResult.Failed(error), result, outcome.name)
            assertEquals(PaymentState.Failed(error), session.state.value)
            // Only the transient gateway error is retried.
            assertEquals(if (error.retryable) 3 else 1, gateway.chargeCalls, outcome.name)
        }
    }

    @Test
    fun timeout_afterAllAttempts() = runTest {
        val (kit, gateway) = stack(FakeGatewayConfig(outcome = FakeOutcome.TIMEOUT))
        val (_, result) = kit.pay()
        assertEquals(CheckoutResult.Failed(CheckoutError.Timeout), result)
        assertEquals(3, gateway.chargeCalls)
    }

    @Test
    fun transientNetworkFailures_recoverWithoutDoubleCharge() = runTest {
        val (kit, gateway) = stack(FakeGatewayConfig(transientFailures = 2))
        val (_, result) = kit.pay()
        assertIs<CheckoutResult.Success>(result)
        assertEquals(3, gateway.chargeCalls)
        assertEquals(1, gateway.capturedPayments.size)
    }

    @Test
    fun payerCancelsInTheirUpiApp() = runTest {
        val (kit, _) = stack(FakeGatewayConfig(outcome = FakeOutcome.PAYER_CANCELLED, pendingPolls = 1))
        val (session, result) = kit.pay()
        assertEquals(CheckoutResult.Cancelled, result)
        assertEquals(PaymentState.Cancelled, session.state.value)
    }

    @Test
    fun tamperedAmount_isNeverReportedAsSuccess() = runTest {
        val (kit, _) = stack(FakeGatewayConfig(outcome = FakeOutcome.AMOUNT_MISMATCH))
        assertEquals(CheckoutResult.Failed(CheckoutError.ResponseMismatch), kit.pay().second)
    }

    @Test
    fun refund_endToEnd() = runTest {
        val (kit, gateway) = stack(FakeGatewayConfig())
        val (session, paid) = kit.pay(PaymentMethod.Wallet(WalletProvider.PHONEPE))
        assertIs<CheckoutResult.Success>(paid)
        assertIs<CheckoutResult.Refunded>(session.refund())
        assertIs<PaymentState.Refunded>(session.state.value)
        assertEquals(1, gateway.refundedPayments.size)
    }

    @Test
    fun cardMagicNumbers() = runTest {
        val (kit, _) = stack(FakeGatewayConfig())
        assertEquals(
            CheckoutResult.Failed(CheckoutError.InsufficientFunds),
            kit.pay(PaymentMethod.Card("4000000000009995", 12, 2035, "123")).second,
        )
    }
}
