package com.darshan.checkoutkit.core.time

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarMathTest {
    @Test
    fun knownInstants() {
        assertEquals(YearMonth(1970, 1), yearMonthOf(0, 0))
        assertEquals(YearMonth(1969, 12), yearMonthOf(-1, 0))
        assertEquals(YearMonth(2000, 2), yearMonthOf(951_782_400_000L, 0)) // 2000-02-29
        assertEquals(YearMonth(2026, 10), yearMonthOf(1_793_275_200_000L, 330))
        assertEquals(YearMonth(2026, 11), yearMonthOf(1_793_473_200_000L, 330)) // 31 Oct 19:00Z = 1 Nov IST
        assertEquals(YearMonth(2026, 10), yearMonthOf(1_793_473_200_000L, 0))
        assertEquals(YearMonth(2099, 12), yearMonthOf(4_102_444_799_000L, 0)) // 2099-12-31T23:59:59Z
    }
}
