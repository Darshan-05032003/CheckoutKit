package com.darshan.checkoutkit.core.validation

import com.darshan.checkoutkit.core.testing.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExpiryAndCvvValidatorTest {

    // 2026-10-29T12:00:00Z
    private val clock = TestClock(nowMillis = 1_793_275_200_000L)
    private val expiry = ExpiryValidator(clock)

    @Test
    fun parse_acceptsMmYyAndMmYyyy() {
        assertEquals(ExpiryDate(12, 2030), ExpiryValidator.parse("12/30"))
        assertEquals(ExpiryDate(1, 2031), ExpiryValidator.parse("0131"))
        assertEquals(ExpiryDate(7, 2029), ExpiryValidator.parse("07/2029"))
        assertNull(ExpiryValidator.parse("1/3"))
        assertNull(ExpiryValidator.parse("ab/cd"))
        assertNull(ExpiryValidator.parse(""))
        assertNull(ExpiryValidator.parse("12/30/99"))
    }

    @Test
    fun currentMonth_isStillValid_lastMonthIsExpired() {
        assertEquals(FieldValidation.Valid, expiry.validate(10, 2026))
        assertEquals(FieldValidation.Invalid(FieldError.EXPIRED), expiry.validate(9, 2026))
        assertEquals(FieldValidation.Invalid(FieldError.EXPIRED), expiry.validate(12, 2025))
        assertEquals(FieldValidation.Valid, expiry.validate(11, 2026))
    }

    @Test
    fun invalidMonthsAndFarFutureYears_areRejected() {
        assertEquals(FieldValidation.Invalid(FieldError.INVALID_MONTH), expiry.validate(0, 2030))
        assertEquals(FieldValidation.Invalid(FieldError.INVALID_MONTH), expiry.validate(13, 2030))
        assertEquals(FieldValidation.Invalid(FieldError.TOO_FAR_IN_FUTURE), expiry.validate(1, 2047))
        assertEquals(FieldValidation.Valid, expiry.validate(12, 2046))
    }

    @Test
    fun validateText_combinesParsingAndRules() {
        assertEquals(FieldValidation.Invalid(FieldError.EMPTY), expiry.validateText(""))
        assertEquals(FieldValidation.Invalid(FieldError.INVALID_FORMAT), expiry.validateText("9/9"))
        assertEquals(FieldValidation.Valid, expiry.validateText("12/30"))
    }

    @Test
    fun monthBoundary_usesIndianStandardTime() {
        // 2026-10-31T19:00:00Z is already 1 November 00:30 in India: October cards are expired there.
        val lateOctoberUtc = ExpiryValidator(TestClock(nowMillis = 1_793_473_200_000L))
        assertEquals(FieldValidation.Invalid(FieldError.EXPIRED), lateOctoberUtc.validate(10, 2026))
        val utcValidator = ExpiryValidator(TestClock(nowMillis = 1_793_473_200_000L), utcOffsetMinutes = 0)
        assertEquals(FieldValidation.Valid, utcValidator.validate(10, 2026))
    }

    @Test
    fun cvv_dependsOnTheBrand() {
        assertEquals(FieldValidation.Valid, CvvValidator.validate("123", CardBrand.VISA))
        assertEquals(FieldValidation.Valid, CvvValidator.validate("1234", CardBrand.AMEX))
        assertEquals(FieldValidation.Invalid(FieldError.WRONG_LENGTH), CvvValidator.validate("1234", CardBrand.VISA))
        assertEquals(FieldValidation.Invalid(FieldError.WRONG_LENGTH), CvvValidator.validate("123", CardBrand.AMEX))
        assertEquals(FieldValidation.Invalid(FieldError.EMPTY), CvvValidator.validate("", CardBrand.VISA))
        assertEquals(FieldValidation.Invalid(FieldError.INVALID_CHARACTERS), CvvValidator.validate("12a", CardBrand.VISA))
        assertEquals(FieldValidation.Invalid(FieldError.INVALID_CHARACTERS), CvvValidator.validate("١٢٣", CardBrand.VISA))
    }
}
