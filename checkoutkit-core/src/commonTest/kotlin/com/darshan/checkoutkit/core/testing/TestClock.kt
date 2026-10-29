package com.darshan.checkoutkit.core.testing

import com.darshan.checkoutkit.core.time.CheckoutClock

/** Manually advanced clock for deterministic tests. */
class TestClock(var nowMillis: Long = 1_700_000_000_000L) : CheckoutClock {
    override fun nowMillis(): Long = nowMillis

    fun advanceBy(millis: Long) {
        nowMillis += millis
    }
}
