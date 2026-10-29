package com.darshan.checkoutkit.core

/**
 * What the merchant wants to charge.
 *
 * @property orderId the merchant's order reference: 1 to 64 chars of `[A-Za-z0-9._-]`.
 * @property amountPaise amount in paise (1 rupee = 100 paise), `1..MAX_AMOUNT_PAISE`. Never a floating-point value.
 * @property currency ISO 4217 code. Only `INR` is supported.
 * @property description optional text shown on the sheet, at most 140 printable characters.
 */
public data class CheckoutRequest(
    val orderId: String,
    val amountPaise: Long,
    val currency: String = "INR",
    val description: String = "",
) {
    init {
        require(ORDER_ID.matches(orderId)) { "orderId must match ${ORDER_ID.pattern}" }
        require(amountPaise in 1..MAX_AMOUNT_PAISE) { "amountPaise must be in 1..$MAX_AMOUNT_PAISE" }
        require(currency in SUPPORTED_CURRENCIES) { "currency must be one of $SUPPORTED_CURRENCIES" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) { "description longer than $MAX_DESCRIPTION_LENGTH" }
        require(description.none { it.isISOControl() }) { "description must not contain control characters" }
    }

    public companion object {
        /** ₹1,00,00,000 (one crore), the largest single checkout the SDK accepts. */
        public const val MAX_AMOUNT_PAISE: Long = 1_000_000_000L

        /** Longest [description]. */
        public const val MAX_DESCRIPTION_LENGTH: Int = 140

        private val ORDER_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
        private val SUPPORTED_CURRENCIES = setOf("INR")
    }
}
