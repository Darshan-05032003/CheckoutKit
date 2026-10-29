package com.darshan.checkoutkit.core.validation

import com.darshan.checkoutkit.core.time.CheckoutClock
import com.darshan.checkoutkit.core.time.YearMonth
import com.darshan.checkoutkit.core.time.yearMonthOf

/** A card expiry: [month] 1..12 and a four-digit [year]. */
public data class ExpiryDate(val month: Int, val year: Int)

/**
 * Card expiry checks. A card is valid through the last day of its expiry month, judged in
 * [utcOffsetMinutes] (India Standard Time by default) using the injected [clock].
 */
public class ExpiryValidator(
    private val clock: CheckoutClock = CheckoutClock.System,
    private val utcOffsetMinutes: Int = IST_OFFSET_MINUTES,
) {
    /** Validates a parsed expiry. */
    public fun validate(month: Int, year: Int): FieldValidation {
        if (month !in 1..12) return FieldValidation.Invalid(FieldError.INVALID_MONTH)
        val now = yearMonthOf(clock.nowMillis(), utcOffsetMinutes)
        val expiry = YearMonth(year, month)
        return when {
            expiry < now -> FieldValidation.Invalid(FieldError.EXPIRED)
            year > now.year + MAX_YEARS_AHEAD -> FieldValidation.Invalid(FieldError.TOO_FAR_IN_FUTURE)
            else -> FieldValidation.Valid
        }
    }

    /** Validates typed text such as `12/30`, `1230` or `12/2030`. */
    public fun validateText(text: String): FieldValidation {
        if (text.isBlank()) return FieldValidation.Invalid(FieldError.EMPTY)
        val parsed = parse(text) ?: return FieldValidation.Invalid(FieldError.INVALID_FORMAT)
        return validate(parsed.month, parsed.year)
    }

    public companion object {
        /** UTC+05:30. */
        public const val IST_OFFSET_MINUTES: Int = 330

        /** Cards are not issued for more than this many years ahead. */
        public const val MAX_YEARS_AHEAD: Int = 20

        private val PATTERN = Regex("^([0-9]{2})/?([0-9]{2}|[0-9]{4})$")

        /** Parses `MM/YY`, `MMYY`, `MM/YYYY` or `MMYYYY`; `null` if the text has another shape. */
        public fun parse(text: String): ExpiryDate? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val month = match.groupValues[1].toInt()
            val rawYear = match.groupValues[2]
            val year = if (rawYear.length == 2) 2000 + rawYear.toInt() else rawYear.toInt()
            return ExpiryDate(month, year)
        }
    }
}
