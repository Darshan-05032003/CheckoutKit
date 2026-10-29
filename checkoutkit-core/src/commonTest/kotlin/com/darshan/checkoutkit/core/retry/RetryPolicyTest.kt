package com.darshan.checkoutkit.core.retry

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {

    @Test
    fun defaults_areSensibleForPayments() {
        val p = RetryPolicy.Default
        assertEquals(3, p.maxAttempts)
        assertEquals(500.milliseconds, p.initialDelay)
        assertEquals(8.seconds, p.maxDelay)
        assertEquals(2.0, p.multiplier)
        assertEquals(0.5, p.jitterFactor)
        assertEquals(15.seconds, p.attemptTimeout)
        assertEquals(60.seconds, p.totalTimeout)
        assertEquals(1, RetryPolicy.NoRetry.maxAttempts)
    }

    @Test
    fun invalidPolicies_areRejected() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxAttempts = RetryPolicy.MAX_ATTEMPTS_LIMIT + 1) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(initialDelay = (-1).milliseconds) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(initialDelay = 10.seconds, maxDelay = 1.seconds) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(multiplier = 0.5) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(multiplier = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(jitterFactor = -0.1) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(jitterFactor = 1.1) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(attemptTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(attemptTimeout = 10.seconds, totalTimeout = 5.seconds) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(totalTimeout = Duration.INFINITE) }
    }

    @Test
    fun backoff_withoutJitter_isExponentialAndCapped() {
        val p = RetryPolicy(maxAttempts = 8, initialDelay = 500.milliseconds, maxDelay = 8.seconds, multiplier = 2.0, jitterFactor = 0.0)
        val delays = (1..7).map { p.backoffDelay(retryNumber = it, random = Random(1)) }
        assertEquals(
            listOf(500, 1000, 2000, 4000, 8000, 8000, 8000).map { it.milliseconds },
            delays,
        )
    }

    @Test
    fun backoff_withJitter_staysWithinBounds() {
        val p = RetryPolicy(initialDelay = 1.seconds, maxDelay = 30.seconds, jitterFactor = 0.5)
        val random = Random(42)
        repeat(1_000) {
            val d = p.backoffDelay(retryNumber = 2, random = random) // base 2 s
            assertTrue(d >= 1.seconds && d <= 2.seconds, "delay $d out of [1s, 2s]")
        }
    }

    @Test
    fun fullJitter_spreadsAcrossTheWholeWindow() {
        val p = RetryPolicy(initialDelay = 1.seconds, maxDelay = 1.seconds, jitterFactor = 1.0)
        val random = Random(7)
        val samples = (1..2_000).map { p.backoffDelay(1, random).inWholeMilliseconds }
        assertTrue(samples.min() < 100, "min ${samples.min()}")
        assertTrue(samples.max() > 900, "max ${samples.max()}")
        // Roughly uniform: each quarter of the window gets a fair share.
        val quarters = samples.groupingBy { (it / 250).coerceAtMost(3) }.eachCount()
        quarters.values.forEach { assertTrue(it in 350..650, "quarters=$quarters") }
    }

    @Test
    fun backoff_neverOverflowsForHugeRetryNumbers() {
        val p = RetryPolicy(jitterFactor = 0.0)
        assertEquals(p.maxDelay, p.backoffDelay(retryNumber = 10_000, random = Random(0)))
        assertEquals(p.maxDelay, p.backoffDelay(retryNumber = Int.MAX_VALUE, random = Random(0)))
    }

    @Test
    fun backoff_rejectsNonPositiveRetryNumber() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy.Default.backoffDelay(0, Random(0)) }
    }

    @Test
    fun zeroInitialDelay_meansImmediateRetries() {
        val p = RetryPolicy(initialDelay = Duration.ZERO, maxDelay = Duration.ZERO, jitterFactor = 0.5)
        assertEquals(Duration.ZERO, p.backoffDelay(3, Random(0)))
    }
}
