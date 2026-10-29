package com.darshan.checkoutkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PaymentMethodTest {

    @Test
    fun card_toString_neverLeaksPanOrCvv() {
        val card = PaymentMethod.Card(number = "4242424242424242", expiryMonth = 12, expiryYear = 2030, cvv = "123")
        val text = card.toString()
        assertFalse(text.contains("4242424242424242"))
        assertFalse(text.contains("123"))
        assertTrue(text.contains("4242")) // last four only
        assertEquals("4242", card.last4)
    }

    @Test
    fun card_equality_usesAllFields() {
        val a = PaymentMethod.Card("4242424242424242", 12, 2030, "123")
        val b = PaymentMethod.Card("4242424242424242", 12, 2030, "123")
        val c = PaymentMethod.Card("4242424242424242", 12, 2030, "999")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun fingerprints_differPerMethodAndNeverContainSecrets() {
        val card = PaymentMethod.Card("4242424242424242", 12, 2030, "123")
        val upi = PaymentMethod.Upi("alice@okbank")
        val wallet = PaymentMethod.Wallet(WalletProvider.PAYTM)
        val prints = listOf(card, upi, wallet).map { it.fingerprint }
        assertEquals(3, prints.toSet().size)
        assertFalse(card.fingerprint.contains("4242424242424242"))
        assertFalse(card.fingerprint.contains("123"))
    }

    @Test
    fun methodTypes_haveStableNames() {
        assertEquals("UPI", PaymentMethod.Upi("a@b").type)
        assertEquals("CARD", PaymentMethod.Card("4242424242424242", 1, 2030, "123").type)
        assertEquals("WALLET", PaymentMethod.Wallet(WalletProvider.PHONEPE).type)
    }
}
