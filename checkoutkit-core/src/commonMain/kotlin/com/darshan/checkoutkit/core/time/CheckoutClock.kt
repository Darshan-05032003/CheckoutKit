package com.darshan.checkoutkit.core.time

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Source of wall-clock time for the SDK.
 *
 * Everything in CheckoutKit that needs "now" (transition timestamps, idempotency expiry, card
 * expiry checks) reads it through this interface, so tests can inject a fixed or manually advanced
 * clock instead of depending on the device time.
 */
public fun interface CheckoutClock {
    /** Current time in milliseconds since the Unix epoch (UTC). */
    public fun nowMillis(): Long

    public companion object {
        /** The real system clock. */
        public val System: CheckoutClock = SystemCheckoutClock
    }
}

@OptIn(ExperimentalTime::class)
private object SystemCheckoutClock : CheckoutClock {
    override fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
