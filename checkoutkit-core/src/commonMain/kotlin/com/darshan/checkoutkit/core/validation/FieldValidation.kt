package com.darshan.checkoutkit.core.validation

/** Why an input field is invalid. The UI maps each value to a localised message. */
public enum class FieldError {
    EMPTY,
    INVALID_CHARACTERS,
    INVALID_FORMAT,
    WRONG_LENGTH,
    TOO_LONG,
    INVALID_CHECKSUM,
    UNSUPPORTED_BRAND,
    INVALID_MONTH,
    EXPIRED,
    TOO_FAR_IN_FUTURE,
    NOT_SELECTED,
}

/** Result of validating one input field. */
public sealed interface FieldValidation {
    /** The input is acceptable. */
    public data object Valid : FieldValidation

    /** The input is rejected because of [error]. */
    public data class Invalid(val error: FieldError) : FieldValidation

    /** `true` for [Valid]. */
    public val isValid: Boolean get() = this is Valid

    /** The error, or `null` when valid. */
    public val errorOrNull: FieldError? get() = (this as? Invalid)?.error
}

internal fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
