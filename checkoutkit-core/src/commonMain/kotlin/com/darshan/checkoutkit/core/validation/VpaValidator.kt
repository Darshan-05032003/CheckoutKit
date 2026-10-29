package com.darshan.checkoutkit.core.validation

/**
 * UPI virtual payment address checks (`username@handle`).
 *
 * Rules: lower-cased and trimmed of spaces; at most 255 chars; only ASCII (rejects homoglyph and
 * RTL-override tricks); username 2+ chars of `[a-z0-9._-]` starting with a letter or digit; handle
 * a letter followed by letters/digits, no dots, no query strings.
 */
public object VpaValidator {
    /** Longest VPA accepted. */
    public const val MAX_LENGTH: Int = 255

    private val PATTERN = Regex("^[a-z0-9][a-z0-9._-]{1,254}@[a-z][a-z0-9]{1,63}$")

    /** Trims spaces and lower-cases. */
    public fun normalise(raw: String): String = raw.trim { it == ' ' }.lowercase()

    /** Validates a typed VPA. */
    public fun validate(raw: String): FieldValidation {
        val vpa = normalise(raw)
        return when {
            vpa.isEmpty() -> FieldValidation.Invalid(FieldError.EMPTY)
            vpa.length > MAX_LENGTH -> FieldValidation.Invalid(FieldError.TOO_LONG)
            vpa.any { it.code > 127 } -> FieldValidation.Invalid(FieldError.INVALID_CHARACTERS)
            !PATTERN.matches(vpa) -> FieldValidation.Invalid(FieldError.INVALID_FORMAT)
            else -> FieldValidation.Valid
        }
    }
}
