package com.darshan.checkoutkit.core.integration

import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.core.state.PaymentState
import com.darshan.checkoutkit.core.testing.ScriptedGateway
import com.darshan.checkoutkit.core.testing.TestClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** CheckoutKit + session + retry executor + idempotency store + gateway, on virtual time. */
class RetryIdempotencyIntegrationTest {

    @Test
    fun slowGateway_timesOutThenSucceeds_andDuplicatesDuringRetryShareTheResult() = runTest {
        var calls = 0
        val gateway = ScriptedGateway()
        gateway.fallback = { req ->
            calls++
            if (calls == 1) delay(20.seconds) // first attempt hangs past the attempt timeout
            GatewayResult.Succeeded("txn_slow", req.amountPaise)
        }
        val kit = CheckoutKit(
            gateway,
            CheckoutConfig(
                retryPolicy = RetryPolicy(maxAttempts = 3, initialDelay = 1.seconds, jitterFactor = 0.5, attemptTimeout = 3.seconds, totalTimeout = 20.seconds),
                dispatcher = StandardTestDispatcher(testScheduler),
                clock = TestClock(),
                random = Random(11),
            ),
        )
        val session = kit.createSession(CheckoutRequest("order-int-1", 1_00))
        val results = (1..10).map { async { session.submit(PaymentMethod.Upi("bob@okaxis")) } }.awaitAll()

        assertEquals(1, results.toSet().size)
        assertEquals(CheckoutResult.Success("txn_slow", 1_00), results.first())
        assertEquals(2, gateway.chargeCount) // the hung attempt + one retry, never 10 or 20
        assertEquals(PaymentState.Success("txn_slow"), session.state.value)
        // 3 s attempt timeout + jittered backoff in [0.5 s, 1 s]
        val elapsed = testScheduler.currentTime
        assertEquals(true, elapsed in 3_500L..4_000L, "elapsed=$elapsed")
    }

    @Test
    fun manySessions_eachChargedOnce_evenWithRetries() = runTest {
        val seen = mutableMapOf<String, Int>()
        val gateway = ScriptedGateway()
        gateway.fallback = { req ->
            val attempt = (seen[req.orderId] ?: 0) + 1
            seen[req.orderId] = attempt
            val index = req.orderId.removePrefix("order-").toInt()
            if (index % 3 == 0 && attempt == 1) GatewayResult.Failed(CheckoutError.Network)
            else GatewayResult.Succeeded("txn_${req.orderId}", req.amountPaise)
        }
        val kit = CheckoutKit(
            gateway,
            CheckoutConfig(retryPolicy = RetryPolicy(maxAttempts = 3, initialDelay = 10.milliseconds), dispatcher = StandardTestDispatcher(testScheduler), random = Random(5)),
        )
        val results = (1..60).map { i -> async { kit.createSession(CheckoutRequest("order-$i", 500)).submit(PaymentMethod.Upi("c@okicici")) } }.awaitAll()
        results.forEachIndexed { i, r -> assertEquals(CheckoutResult.Success("txn_order-${i + 1}", 500), r) }
        val perOrder = gateway.requests.groupingBy { it.orderId }.eachCount()
        (1..60).forEach { i -> assertEquals(if (i % 3 == 0) 2 else 1, perOrder["order-$i"], "order-$i") }
        // Every retry of one order reused that order's single idempotency key.
        gateway.requests.groupBy { it.orderId }.values.forEach { reqs -> assertEquals(1, reqs.map { it.idempotencyKey }.toSet().size) }
    }
}
