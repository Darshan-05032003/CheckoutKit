package com.darshan.checkoutkit.core.stress

import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.ChargeRequest
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.gateway.PaymentGateway
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.checkoutkit.core.retry.AttemptResult
import com.darshan.checkoutkit.core.retry.RetryExecutor
import com.darshan.checkoutkit.core.retry.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RetryIdempotencyStressTest {

    /** Lock-free gateway that charges each idempotency key at most once, like a real PSP. */
    private class CountingGateway(
        private val latencyMs: Long,
        private val behaviour: (ChargeRequest, Int) -> GatewayResult? = { _, _ -> null },
    ) : ChargeOnlyGateway() {
        val calls = AtomicInteger()
        val charges = ConcurrentHashMap<IdempotencyKey, GatewayResult>()
        val callsPerKey = ConcurrentHashMap<IdempotencyKey, AtomicInteger>()

        override suspend fun charge(request: ChargeRequest): GatewayResult {
            calls.incrementAndGet()
            val n = callsPerKey.computeIfAbsent(request.idempotencyKey) { AtomicInteger() }.incrementAndGet()
            if (latencyMs > 0) delay(latencyMs)
            behaviour(request, n)?.let { return it }
            return charges.computeIfAbsent(request.idempotencyKey) {
                GatewayResult.Succeeded("txn_${request.orderId}", request.amountPaise)
            }
        }
    }

    @Test
    fun sameIdempotencyKeyFired1000TimesConcurrently_oneSession_exactlyOneCharge() {
        val gateway = CountingGateway(latencyMs = 20)
        val kit = CheckoutKit(gateway, CheckoutConfig(retryPolicy = RetryPolicy.NoRetry))
        val session = kit.createSession(CheckoutRequest("order-dup-1", 99_900))
        val m = measureStress("F2 same key x1000 concurrent submits on one session") {
            runBlocking(Dispatchers.Default) {
                (1..1_000).map { async { session.submit(PaymentMethod.Upi("dup@okbank")) } }.awaitAll()
            }
        }
        assertEquals(1, gateway.calls.get())
        assertEquals(1, m.value.toSet().size)
        assertTrue(m.value.first() is CheckoutResult.Success)
    }

    @Test
    fun sameIdempotencyKeyFired1000TimesConcurrently_1000Sessions_exactlyOneCharge() {
        val gateway = CountingGateway(latencyMs = 20)
        val kit = CheckoutKit(gateway, CheckoutConfig(retryPolicy = RetryPolicy.NoRetry))
        val key = IdempotencyKey("shared-key-0001")
        val m = measureStress("F2 same key x1000 across 1000 sessions") {
            runBlocking(Dispatchers.Default) {
                (1..1_000).map {
                    async { kit.createSession(CheckoutRequest("order-dup-2", 99_900), key).let { s -> s.submit(PaymentMethod.Upi("dup@okbank")) to s.state.value } }
                }.awaitAll()
            }
        }
        assertEquals(1, gateway.calls.get())
        assertEquals(1, m.value.map { it.first }.toSet().size)
        assertEquals(1, m.value.map { it.second }.toSet().size) // every session synced to the same state
    }

    @Test
    fun retryStorm_10kSessions_flakyGatewayWithTimeouts() {
        val sessions = 10_000
        val gateway = CountingGateway(latencyMs = 0) { req, n ->
            val r = Random(req.orderId.hashCode() * 31 + n).nextInt(100)
            when {
                r < 30 -> GatewayResult.Failed(CheckoutError.Network)
                r < 45 -> GatewayResult.Failed(CheckoutError.Timeout)
                r < 50 -> GatewayResult.Failed(CheckoutError.Declined)
                else -> null
            }
        }
        val policy = RetryPolicy(maxAttempts = 3, initialDelay = 5.milliseconds, maxDelay = 40.milliseconds, jitterFactor = 1.0, attemptTimeout = 1.seconds, totalTimeout = 5.seconds)
        val kit = CheckoutKit(gateway, CheckoutConfig(retryPolicy = policy, maxIdempotencyEntries = sessions))
        val m = measureStress("F2 retry storm 10k sessions, 30% network / 15% timeout / 5% decline per attempt") {
            runBlocking(Dispatchers.Default) {
                (1..sessions).map { i -> async { kit.createSession(CheckoutRequest("order-$i", 1_000)).submit(PaymentMethod.Upi("s$i@okbank")) } }.awaitAll()
            }
        }
        val byType = m.value.groupingBy { it::class.simpleName }.eachCount()
        val maxCallsPerKey = gateway.callsPerKey.values.maxOf { it.get() }
        println("STRESS [F2 retry storm] outcomes=$byType gatewayCalls=${gateway.calls.get()} maxCallsPerKey=$maxCallsPerKey")
        assertEquals(sessions, m.value.size)
        assertTrue(maxCallsPerKey <= 3, "a session exceeded maxAttempts")
        assertEquals(sessions, gateway.callsPerKey.size)
    }

    @Test
    fun slowGatewayHangs_perAttemptTimeoutBoundsEverySession() {
        val sessions = 2_000
        val callsPerKey = ConcurrentHashMap<IdempotencyKey, AtomicInteger>()
        val hanging = object : ChargeOnlyGateway() {
            override suspend fun charge(request: ChargeRequest): GatewayResult {
                val n = callsPerKey.computeIfAbsent(request.idempotencyKey) { AtomicInteger() }.incrementAndGet()
                if (n == 1) delay(10_000) // first attempt hangs far beyond the timeout
                return GatewayResult.Succeeded("txn_${request.orderId}", request.amountPaise)
            }
        }
        val policy = RetryPolicy(maxAttempts = 2, initialDelay = 10.milliseconds, jitterFactor = 1.0, attemptTimeout = 200.milliseconds, totalTimeout = 2.seconds)
        val kit = CheckoutKit(hanging, CheckoutConfig(retryPolicy = policy, maxIdempotencyEntries = sessions))
        val m = measureStress("F2 2k sessions whose first attempt hangs 10 s (200 ms attempt timeout)") {
            runBlocking(Dispatchers.Default) {
                (1..sessions).map { i -> async { kit.createSession(CheckoutRequest("order-h$i", 1_000)).submit(PaymentMethod.Upi("h@okbank")) } }.awaitAll()
            }
        }
        assertTrue(m.value.all { it is CheckoutResult.Success })
        assertTrue(callsPerKey.values.all { it.get() == 2 })
        assertTrue(m.duration < 2.seconds, "took ${m.duration}")
    }

    @Test
    fun idempotencyStore_memoryFor10kRetainedResults() {
        val sessions = 10_000
        val gateway = CountingGateway(latencyMs = 0)
        val kit = CheckoutKit(gateway, CheckoutConfig(retryPolicy = RetryPolicy.NoRetry, maxIdempotencyEntries = sessions))
        val m = measureStress("F2 10k cached idempotent results retained") {
            runBlocking(Dispatchers.Default) {
                (1..sessions).map { i -> async { kit.createSession(CheckoutRequest("order-m$i", 1_000)) .also { it.submit(PaymentMethod.Upi("m@okbank")) } } }.awaitAll()
            }
        }
        // Replaying all 10k keys must not reach the gateway again.
        runBlocking(Dispatchers.Default) { m.value.map { s -> async { kit.createSession(s.request, s.idempotencyKey).submit(PaymentMethod.Upi("m@okbank")) } }.awaitAll() }
        assertEquals(sessions, gateway.calls.get())
    }

    @Test
    fun jitter_preventsAThunderingHerd() = runTest {
        fun herd(jitter: Double): Int {
            val retryTimes = mutableListOf<Long>()
            val scope = this
            val dispatcher = StandardTestDispatcher(testScheduler)
            val start = testScheduler.currentTime
            val jobs = (1..10_000).map { i ->
                scope.launch(dispatcher) {
                    val executor = RetryExecutor(RetryPolicy(maxAttempts = 2, initialDelay = 1.seconds, maxDelay = 1.seconds, jitterFactor = jitter), Random(i))
                    executor.execute<Unit> { attempt ->
                        if (attempt == 2) retryTimes += testScheduler.currentTime - start
                        if (attempt == 1) AttemptResult.Failure(CheckoutError.Network) else AttemptResult.Success(Unit)
                    }
                }
            }
            testScheduler.advanceUntilIdle()
            check(jobs.all { it.isCompleted })
            // Peak retries landing in the same 10 ms window.
            return retryTimes.groupingBy { it / 10 }.eachCount().values.max()
        }
        val noJitterPeak = herd(0.0)
        val fullJitterPeak = herd(1.0)
        println("STRESS [F2 thundering herd] 10k clients failing at t=0: peak retries per 10 ms window: no jitter=$noJitterPeak, full jitter=$fullJitterPeak")
        assertEquals(10_000, noJitterPeak)
        assertTrue(fullJitterPeak < 250, "full jitter peak $fullJitterPeak")
    }
}
