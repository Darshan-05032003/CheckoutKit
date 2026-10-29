package com.darshan.checkoutkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckoutRequestTest {

    @Test
    fun validRequest_isAccepted() {
        val r = CheckoutRequest(orderId = "order-42", amountPaise = 49_900, description = "Coffee")
        assertEquals("INR", r.currency)
        assertEquals(49_900, r.amountPaise)
    }

    @Test
    fun zeroNegativeAndHugeAmounts_areRejected() {
        listOf(0L, -1L, Long.MIN_VALUE, CheckoutRequest.MAX_AMOUNT_PAISE + 1, Long.MAX_VALUE).forEach { amount ->
            assertFailsWith<IllegalArgumentException>("amount=$amount") { CheckoutRequest("order-1", amount) }
        }
        CheckoutRequest("order-1", CheckoutRequest.MAX_AMOUNT_PAISE)
        CheckoutRequest("order-1", 1)
    }

    @Test
    fun maliciousOrderIds_areRejected() {
        listOf("", " ", "a".repeat(65), "order 1", "order‮1", "javascript:alert(1)", "o/../../x", "ордер-1").forEach { id ->
            assertFailsWith<IllegalArgumentException>(id) { CheckoutRequest(id, 100) }
        }
    }

    @Test
    fun onlyIsoUpperCaseCurrencies_andInrForNow() {
        assertFailsWith<IllegalArgumentException> { CheckoutRequest("order-1", 100, currency = "inr") }
        assertFailsWith<IllegalArgumentException> { CheckoutRequest("order-1", 100, currency = "USD") }
    }

    @Test
    fun descriptionIsBounded_andControlCharsRejected() {
        assertFailsWith<IllegalArgumentException> { CheckoutRequest("order-1", 100, description = "x".repeat(141)) }
        assertFailsWith<IllegalArgumentException> { CheckoutRequest("order-1", 100, description = "line\nbreak") }
    }
}
