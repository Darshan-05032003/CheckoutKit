package com.darshan.checkoutkit.core.validation

import kotlin.test.Test
import kotlin.test.assertEquals

class VpaValidatorTest {

    @Test
    fun validVpas_areAccepted_andNormalised() {
        listOf("alice@okbank", "alice.smith@okhdfcbank", "9876543210@ybl", "a_b-c@paytm", "ALICE@OKAXIS", "  bob@upi  ").forEach {
            assertEquals(FieldValidation.Valid, VpaValidator.validate(it), it)
        }
        assertEquals("alice@okaxis", VpaValidator.normalise("  ALICE@OKAXIS "))
    }

    @Test
    fun malformedVpas_areRejected() {
        val bad = mapOf(
            "" to FieldError.EMPTY,
            "alice" to FieldError.INVALID_FORMAT,
            "@okbank" to FieldError.INVALID_FORMAT,
            "alice@" to FieldError.INVALID_FORMAT,
            "alice@@okbank" to FieldError.INVALID_FORMAT,
            "al ice@okbank" to FieldError.INVALID_FORMAT,
            "a@okbank" to FieldError.INVALID_FORMAT,
            "alice@ok.bank" to FieldError.INVALID_FORMAT,
            "alice@9bank" to FieldError.INVALID_FORMAT,
            ".alice@okbank" to FieldError.INVALID_FORMAT,
            "alice@okbank?am=1" to FieldError.INVALID_FORMAT,
            "javascript:alert(1)@x" to FieldError.INVALID_FORMAT,
            "аlice@okbank" to FieldError.INVALID_CHARACTERS, // Cyrillic 'а'
            "alice‮@okbank" to FieldError.INVALID_CHARACTERS,
            "alice@okbank\n" to FieldError.INVALID_FORMAT,
            ("a".repeat(256) + "@okbank") to FieldError.TOO_LONG,
        )
        bad.forEach { (vpa, error) -> assertEquals(FieldValidation.Invalid(error), VpaValidator.validate(vpa), vpa.take(30)) }
    }
}
