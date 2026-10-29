package com.darshan.checkoutkit.core.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardValidatorTest {

    @Test
    fun luhn_acceptsKnownTestCards_andRejectsOneDigitTypos() {
        listOf("4242424242424242", "4111111111111111", "5555555555554444", "378282246310005", "6522123412341238", "4000000000009995").forEach {
            assertTrue(CardValidator.passesLuhn(it), it)
        }
        listOf("4242424242424241", "4111111111111112", "378282246310006", "0000000000000001").forEach {
            assertFalse(CardValidator.passesLuhn(it), it)
        }
    }

    @Test
    fun luhn_rejectsNonDigitsAndEmpty() {
        assertFalse(CardValidator.passesLuhn(""))
        assertFalse(CardValidator.passesLuhn("4242 4242 4242 4242"))
        assertFalse(CardValidator.passesLuhn("٤٢٤٢٤٢٤٢٤٢٤٢٤٢٤٢")) // Arabic-Indic digits are not ASCII digits
    }

    @Test
    fun brand_isDetectedFromThePrefix() {
        assertEquals(CardBrand.VISA, CardValidator.brandOf("4242"))
        assertEquals(CardBrand.MASTERCARD, CardValidator.brandOf("5555"))
        assertEquals(CardBrand.MASTERCARD, CardValidator.brandOf("2221000000000009"))
        assertEquals(CardBrand.AMEX, CardValidator.brandOf("3782"))
        assertEquals(CardBrand.AMEX, CardValidator.brandOf("34"))
        assertEquals(CardBrand.RUPAY, CardValidator.brandOf("6522"))
        assertEquals(CardBrand.RUPAY, CardValidator.brandOf("6080"))
        assertEquals(CardBrand.RUPAY, CardValidator.brandOf("8112"))
        assertEquals(CardBrand.UNKNOWN, CardValidator.brandOf("9999"))
        assertEquals(CardBrand.UNKNOWN, CardValidator.brandOf(""))
    }

    @Test
    fun brandLengthsAndCvvLengths() {
        assertEquals(setOf(13, 16, 19), CardBrand.VISA.lengths)
        assertEquals(setOf(15), CardBrand.AMEX.lengths)
        assertEquals(4, CardBrand.AMEX.cvvLength)
        assertEquals(3, CardBrand.VISA.cvvLength)
    }

    @Test
    fun validateNumber_coversEveryFailure() {
        assertEquals(FieldValidation.Valid, CardValidator.validateNumber("4242 4242 4242 4242"))
        assertEquals(FieldValidation.Valid, CardValidator.validateNumber("4242-4242-4242-4242"))
        assertEquals(FieldValidation.Invalid(FieldError.EMPTY), CardValidator.validateNumber("  "))
        assertEquals(FieldValidation.Invalid(FieldError.INVALID_CHARACTERS), CardValidator.validateNumber("4242abcd42424242"))
        assertEquals(FieldValidation.Invalid(FieldError.WRONG_LENGTH), CardValidator.validateNumber("424242424242"))
        assertEquals(FieldValidation.Invalid(FieldError.INVALID_CHECKSUM), CardValidator.validateNumber("4242424242424241"))
        assertEquals(FieldValidation.Invalid(FieldError.UNSUPPORTED_BRAND), CardValidator.validateNumber("9999999999999995"))
        assertEquals(FieldValidation.Invalid(FieldError.WRONG_LENGTH), CardValidator.validateNumber("4".repeat(10_000)))
    }

    @Test
    fun normalise_keepsOnlyDigitsAndSeparators() {
        assertEquals("4242424242424242", CardValidator.normalise(" 4242 4242-4242 4242 "))
    }

    @Test
    fun masked_showsOnlyTheLastFour() {
        assertEquals("•••• 4242", CardValidator.masked("4242424242424242"))
        assertEquals("••••", CardValidator.masked("42"))
    }
}
