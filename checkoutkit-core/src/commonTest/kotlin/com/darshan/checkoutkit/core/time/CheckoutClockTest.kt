package com.darshan.checkoutkit.core.time

import kotlin.test.Test
import kotlin.test.assertTrue

class CheckoutClockTest {
    @Test
    fun systemClock_returnsCurrentEpochMillis() {
        val now = CheckoutClock.System.nowMillis()
        // 2023-11-14 < now < 2100-01-01
        assertTrue(now > 1_700_000_000_000L && now < 4_102_444_800_000L, "now=$now")
    }

    @Test
    fun systemClock_isMonotonicEnoughForTimestamps() {
        val a = CheckoutClock.System.nowMillis()
        val b = CheckoutClock.System.nowMillis()
        assertTrue(b >= a)
    }

    @Test
    fun clock_canBeALambda() {
        val fixed = CheckoutClock { 42L }
        assertTrue(fixed.nowMillis() == 42L)
    }
}
