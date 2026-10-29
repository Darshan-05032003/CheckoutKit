package com.darshan.checkoutkit.core

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * How a pending payment is polled with [com.darshan.checkoutkit.core.gateway.PaymentGateway.status].
 * The wait before poll `n` is `min(maxInterval, initialInterval * multiplier^(n-1))`; after
 * [maxPolls] polls the session stays `Pending` and the host can call `checkStatus()` later.
 */
public data class PendingPolicy(
    val initialInterval: Duration = 2.seconds,
    val maxInterval: Duration = 10.seconds,
    val multiplier: Double = 1.5,
    val maxPolls: Int = 20,
) {
    init {
        require(initialInterval.isPositive() && initialInterval.isFinite()) { "initialInterval must be positive and finite" }
        require(maxInterval >= initialInterval && maxInterval.isFinite()) { "maxInterval must be finite and >= initialInterval" }
        require(multiplier.isFinite() && multiplier >= 1.0) { "multiplier must be finite and >= 1.0" }
        require(maxPolls in 1..MAX_POLLS_LIMIT) { "maxPolls must be in 1..$MAX_POLLS_LIMIT" }
    }

    /** Wait before poll number [poll] (1-based). */
    public fun intervalBefore(poll: Int): Duration {
        require(poll >= 1) { "poll must be >= 1" }
        val grown = initialInterval.toDouble(DurationUnit.MILLISECONDS) * multiplier.pow(poll - 1)
        val max = maxInterval.toDouble(DurationUnit.MILLISECONDS)
        return (if (grown.isNaN() || grown > max) max else grown).milliseconds
    }

    public companion object {
        /** Hard cap on polls per pending payment. */
        public const val MAX_POLLS_LIMIT: Int = 100
    }
}
