package com.darshan.checkoutkit.core.retry

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * How a charge request is retried and timed out.
 *
 * The delay before retry number `n` (1-based) is `min(maxDelay, initialDelay * multiplier^(n-1))`,
 * then shortened by a random amount of up to [jitterFactor] of itself. With `jitterFactor = 1.0`
 * this is "full jitter" (uniform in `[0, base]`); with `0.0` it is plain exponential backoff.
 * Jitter keeps thousands of clients that failed at the same moment from retrying at the same moment.
 *
 * @property maxAttempts total attempts including the first one, `1..MAX_ATTEMPTS_LIMIT`.
 * @property initialDelay delay before the first retry.
 * @property maxDelay upper bound for any single delay.
 * @property multiplier growth factor per retry, `>= 1.0`.
 * @property jitterFactor fraction of the delay that is randomised, `0.0..1.0`.
 * @property attemptTimeout how long one gateway call may take before it counts as a timeout.
 * @property totalTimeout how long the whole charge (all attempts and delays) may take.
 */
public data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 500.milliseconds,
    val maxDelay: Duration = 8.seconds,
    val multiplier: Double = 2.0,
    val jitterFactor: Double = 0.5,
    val attemptTimeout: Duration = 15.seconds,
    val totalTimeout: Duration = 60.seconds,
) {
    init {
        require(maxAttempts in 1..MAX_ATTEMPTS_LIMIT) { "maxAttempts must be in 1..$MAX_ATTEMPTS_LIMIT, was $maxAttempts" }
        require(!initialDelay.isNegative()) { "initialDelay must be >= 0" }
        require(maxDelay >= initialDelay) { "maxDelay must be >= initialDelay" }
        require(multiplier.isFinite() && multiplier >= 1.0) { "multiplier must be a finite number >= 1.0" }
        require(jitterFactor in 0.0..1.0) { "jitterFactor must be in 0.0..1.0" }
        require(attemptTimeout.isPositive() && attemptTimeout.isFinite()) { "attemptTimeout must be positive and finite" }
        require(totalTimeout.isFinite() && totalTimeout >= attemptTimeout) { "totalTimeout must be finite and >= attemptTimeout" }
    }

    /** The delay to wait before retry number [retryNumber] (1 = first retry). */
    public fun backoffDelay(retryNumber: Int, random: Random): Duration {
        require(retryNumber >= 1) { "retryNumber must be >= 1" }
        val initialMs = initialDelay.toDouble(DurationUnit.MILLISECONDS)
        val maxMs = maxDelay.toDouble(DurationUnit.MILLISECONDS)
        val grown = initialMs * multiplier.pow(retryNumber - 1)
        val base = if (grown.isNaN() || grown > maxMs) maxMs else grown
        val jittered = base * (1.0 - jitterFactor * random.nextDouble())
        return jittered.milliseconds
    }

    public companion object {
        /** Hard cap on attempts so a misconfigured host can't start a retry storm. */
        public const val MAX_ATTEMPTS_LIMIT: Int = 10

        /** 3 attempts, 0.5 s → 8 s exponential backoff with 50 % jitter, 15 s per attempt, 60 s total. */
        public val Default: RetryPolicy = RetryPolicy()

        /** A single attempt, no retries. */
        public val NoRetry: RetryPolicy = RetryPolicy(maxAttempts = 1)
    }
}
