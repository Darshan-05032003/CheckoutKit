package com.darshan.checkoutkit.core.validation

/** Card networks the sheet recognises, with their valid number lengths and CVV length. */
public enum class CardBrand(public val displayName: String, public val lengths: Set<Int>, public val cvvLength: Int) {
    VISA("Visa", setOf(13, 16, 19), 3),
    MASTERCARD("Mastercard", setOf(16), 3),
    AMEX("American Express", setOf(15), 4),
    RUPAY("RuPay", setOf(16), 3),
    UNKNOWN("Card", emptySet(), 3),
}

/** Card number checks: normalisation, brand detection, length and the Luhn checksum. */
public object CardValidator {
    /** Longest card number (ISO/IEC 7812). */
    public const val MAX_LENGTH: Int = 19

    /** Removes spaces and dashes a payer may type or paste. */
    public fun normalise(raw: String): String = raw.filterNot { it == ' ' || it == '-' }

    /** Luhn (mod 10) checksum over ASCII digits only. */
    public fun passesLuhn(digits: String): Boolean {
        if (digits.isEmpty() || !digits.all { it.isAsciiDigit() }) return false
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    /** Brand from the number's prefix (IIN ranges); [CardBrand.UNKNOWN] if not recognised. */
    public fun brandOf(digits: String): CardBrand {
        fun prefix(n: Int) = digits.take(n).toIntOrNull()
        val p1 = prefix(1)
        val p2 = if (digits.length >= 2) prefix(2) else null
        val p3 = if (digits.length >= 3) prefix(3) else null
        val p4 = if (digits.length >= 4) prefix(4) else null
        return when {
            p2 == 34 || p2 == 37 -> CardBrand.AMEX
            p1 == 4 -> CardBrand.VISA
            p2 != null && p2 in 51..55 -> CardBrand.MASTERCARD
            p4 != null && p4 in 2221..2720 -> CardBrand.MASTERCARD
            p2 == 60 || p2 == 65 || p2 == 81 || p2 == 82 || p3 == 508 -> CardBrand.RUPAY
            else -> CardBrand.UNKNOWN
        }
    }

    /** Full validation of a typed card number. */
    public fun validateNumber(raw: String): FieldValidation {
        val digits = normalise(raw)
        return when {
            digits.isEmpty() -> FieldValidation.Invalid(FieldError.EMPTY)
            !digits.all { it.isAsciiDigit() } -> FieldValidation.Invalid(FieldError.INVALID_CHARACTERS)
            digits.length > MAX_LENGTH -> FieldValidation.Invalid(FieldError.WRONG_LENGTH)
            else -> {
                val brand = brandOf(digits)
                when {
                    brand == CardBrand.UNKNOWN -> FieldValidation.Invalid(FieldError.UNSUPPORTED_BRAND)
                    digits.length !in brand.lengths -> FieldValidation.Invalid(FieldError.WRONG_LENGTH)
                    !passesLuhn(digits) -> FieldValidation.Invalid(FieldError.INVALID_CHECKSUM)
                    else -> FieldValidation.Valid
                }
            }
        }
    }

    /** `•••• 4242` style mask for display. */
    public fun masked(number: String): String {
        val digits = number.filter { it.isAsciiDigit() }
        return if (digits.length < 4) "••••" else "•••• " + digits.takeLast(4)
    }
}

/** CVV checks: ASCII digits, 3 long (4 for American Express). */
public object CvvValidator {
    /** Validates [cvv] for a card of [brand]. */
    public fun validate(cvv: String, brand: CardBrand): FieldValidation = when {
        cvv.isEmpty() -> FieldValidation.Invalid(FieldError.EMPTY)
        !cvv.all { it.isAsciiDigit() } -> FieldValidation.Invalid(FieldError.INVALID_CHARACTERS)
        cvv.length != brand.cvvLength -> FieldValidation.Invalid(FieldError.WRONG_LENGTH)
        else -> FieldValidation.Valid
    }
}
