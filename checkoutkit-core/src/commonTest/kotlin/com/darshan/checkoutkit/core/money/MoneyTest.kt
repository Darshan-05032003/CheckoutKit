package com.darshan.checkoutkit.core.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun formatsPaiseWithIndianGrouping() {
        assertEquals("₹0.01", Money.formatInr(1))
        assertEquals("₹4.99", Money.formatInr(499))
        assertEquals("₹499.00", Money.formatInr(49_900))
        assertEquals("₹1,000.00", Money.formatInr(100_000))
        assertEquals("₹1,23,456.78", Money.formatInr(12_345_678))
        assertEquals("₹1,00,00,000.00", Money.formatInr(1_000_000_000))
        assertEquals("₹0.00", Money.formatInr(0))
        assertEquals("-₹5.50", Money.formatInr(-550))
        assertEquals("₹92,23,37,20,36,85,47,758.07", Money.formatInr(Long.MAX_VALUE))
        assertEquals("-₹92,23,37,20,36,85,47,758.08", Money.formatInr(Long.MIN_VALUE))
    }

    @Test
    fun parsesRupeeTextToPaiseExactly() {
        assertEquals(49_900, Money.parseInrToPaise("499"))
        assertEquals(49_950, Money.parseInrToPaise("499.5"))
        assertEquals(49_955, Money.parseInrToPaise("499.55"))
        assertEquals(12_345_678, Money.parseInrToPaise("1,23,456.78"))
        assertEquals(null, Money.parseInrToPaise("499.555"))
        assertEquals(null, Money.parseInrToPaise("-1"))
        assertEquals(null, Money.parseInrToPaise("1e5"))
        assertEquals(null, Money.parseInrToPaise(""))
        assertEquals(null, Money.parseInrToPaise("99999999999999999999"))
        assertFailsWith<IllegalArgumentException> { Money.requirePositive(0) }
    }
}
