package com.darshan.checkoutkit.core.stress

import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.PendingPolicy
import com.darshan.checkoutkit.core.gateway.FakeGateway
import com.darshan.checkoutkit.core.gateway.FakeGatewayConfig
import com.darshan.checkoutkit.core.gateway.FakeOutcome
import com.darshan.checkoutkit.core.retry.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FakeGatewayStressTest {

    @Test
    fun tenThousandSessions_mixedOutcomes_latencySpikes_neverDoubleCapture() {
        val sessions = 10_000
        val gateway = FakeGateway(
            FakeGatewayConfig(latency = 2.milliseconds, latencyJitter = 3.milliseconds, latencySpikeChance = 0.05, latencySpike = 300.milliseconds, transientFailures = 1),
            random = Random(7),
        )
        val policy = RetryPolicy(maxAttempts = 4, initialDelay = 5.milliseconds, maxDelay = 50.milliseconds, jitterFactor = 1.0, attemptTimeout = 100.milliseconds, totalTimeout = 5.seconds)
        val kit = CheckoutKit(gateway, CheckoutConfig(retryPolicy = policy, maxIdempotencyEntries = sessions))
        val payers = listOf("ok", "ok", "ok", "decline", "broke", "unknown")
        val m = measureStress("F4 10k sessions vs FakeGateway (2-5 ms latency, 5% 300 ms spikes, 1 transient failure each)") {
            runBlocking(Dispatchers.Default) {
                (1..sessions).map { i ->
                    async {
                        kit.createSession(CheckoutRequest("order-$i", 100L + i)).submit(PaymentMethod.Upi("${payers[i % payers.size]}@fakebank"))
                    }
                }.awaitAll()
            }
        }
        val byType = m.value.groupingBy { it::class.simpleName }.eachCount()
        val successes = m.value.count { it is CheckoutResult.Success }
        println("STRESS [F4 10k mixed] outcomes=$byType chargeCalls=${gateway.chargeCalls} captured=${gateway.capturedPayments.size}")
        // Exactly one capture per successful session, never more.
        assertEquals(successes, gateway.capturedPayments.size)
        assertEquals(gateway.capturedPayments.size, gateway.capturedPayments.map { it.idempotencyKey }.toSet().size)
        assertTrue(successes >= sessions / 2 - 100, "successes=$successes")
    }

    @Test
    fun pendingPollingStorm_5000PendingPayments() {
        val sessions = 5_000
        val gateway = FakeGateway(FakeGatewayConfig(outcome = FakeOutcome.PENDING_THEN_SUCCESS, latency = 1.milliseconds, pendingPolls = 3), random = Random(3))
        val kit = CheckoutKit(
            gateway,
            CheckoutConfig(
                retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = 5.milliseconds, attemptTimeout = 1.seconds),
                pendingPolicy = PendingPolicy(initialInterval = 10.milliseconds, maxInterval = 40.milliseconds, multiplier = 2.0, maxPolls = 10),
                maxIdempotencyEntries = sessions,
            ),
        )
        val m = measureStress("F4 5k pending payments polled concurrently (3 pending polls each)") {
            runBlocking(Dispatchers.Default) {
                (1..sessions).map { i -> async { kit.createSession(CheckoutRequest("order-p$i", 500)).submit(PaymentMethod.Upi("p@fakebank")) } }.awaitAll()
            }
        }
        println("STRESS [F4 polling storm] statusCalls=${gateway.statusCalls} (${gateway.statusCalls.toDouble() / sessions} per payment)")
        assertTrue(m.value.all { it is CheckoutResult.Success })
        assertEquals(sessions * 4, gateway.statusCalls)
    }

    @Test
    fun latencySpikeSweep_findTheBreakingPoint() = runTest {
        val results = mutableListOf<String>()
        for (spikeChance in listOf(0.0, 0.1, 0.3, 0.5, 0.7, 0.9)) {
            val gateway = FakeGateway(FakeGatewayConfig(latency = 150.milliseconds, latencySpikeChance = spikeChance, latencySpike = 8.seconds), random = Random(11))
            val kit = CheckoutKit(
                gateway,
                CheckoutConfig(
                    retryPolicy = RetryPolicy(maxAttempts = 3, initialDelay = 500.milliseconds, jitterFactor = 1.0, attemptTimeout = 3.seconds, totalTimeout = 20.seconds),
                    dispatcher = StandardTestDispatcher(testScheduler),
                    random = Random(11),
                    maxIdempotencyEntries = 2_000,
                ),
            )
            val outcomes = (1..2_000).map { i -> async { kit.createSession(CheckoutRequest("order-s$i", 999)).submit(PaymentMethod.Upi("s@fakebank")) } }.awaitAll()
            val ok = outcomes.count { it is CheckoutResult.Success }
            val unresolved = outcomes.count { it is CheckoutResult.Unresolved }
            val errors = outcomes.filterIsInstance<CheckoutResult.Failed>().groupingBy { it.error.code }.eachCount()
            results += "spike=${(spikeChance * 100).toInt()}% success=$ok unresolved=$unresolved failed=$errors captured=${gateway.capturedPayments.size} chargeCalls=${gateway.chargeCalls} statusCalls=${gateway.statusCalls}"
            // Money taken must never be reported as failed ("debited but shown as failed").
            val capturedOrders = gateway.capturedPayments.map { it.orderId }.toSet()
            val failedButCaptured = outcomes.withIndex().count { (i, r) -> r is CheckoutResult.Failed && "order-s${i + 1}" in capturedOrders }
            assertEquals(0, failedButCaptured, "spike=$spikeChance: captured but reported failed")
        }
        println("STRESS [F4 spike sweep, 8 s spikes vs 3 s attempt timeout, 3 attempts] " + results.joinToString(" | "))
    }
}
