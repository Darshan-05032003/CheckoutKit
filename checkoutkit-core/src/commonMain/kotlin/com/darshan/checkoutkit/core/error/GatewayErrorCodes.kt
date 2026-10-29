package com.darshan.checkoutkit.core.error

/**
 * Maps raw gateway response codes to [CheckoutError]s. Covers common NPCI UPI response codes,
 * ISO 8583 card response codes and HTTP statuses. Use it inside a [com.darshan.checkoutkit.core.gateway.PaymentGateway]
 * implementation.
 *
 * Codes are trimmed and upper-cased. A code that is not a short `[A-Z0-9_]` token (for example one
 * containing newlines, markup or non-ASCII look-alikes) is dropped, so a hostile response can't
 * inject text into logs.
 */
public object GatewayErrorCodes {
    private val SAFE_CODE = Regex("^[A-Z0-9_]{1,24}$")
    private const val MAX_RAW_CODE_LENGTH = 64

    private val KNOWN: Map<String, CheckoutError> = mapOf(
        // UPI (NPCI) response codes
        "Z9" to CheckoutError.InsufficientFunds,
        "ZM" to CheckoutError.AuthenticationFailed, // invalid UPI PIN
        "Z6" to CheckoutError.AuthenticationFailed, // PIN tries exceeded
        "Z8" to CheckoutError.LimitExceeded, // per-transaction limit
        "Z7" to CheckoutError.LimitExceeded, // frequency limit
        "ZA" to CheckoutError.Declined, // declined by payer
        "U16" to CheckoutError.Declined, // risk threshold exceeded
        "ZH" to CheckoutError.InvalidVpa,
        "XH" to CheckoutError.InvalidVpa, // account does not exist
        "U69" to CheckoutError.Timeout, // collect request expired
        "BT" to CheckoutError.Timeout, // bank timeout
        "U90" to CheckoutError.Timeout, // remitter bank slow
        "XY" to CheckoutError.GatewayError("XY"), // remitter bank offline
        "U28" to CheckoutError.GatewayError("U28"), // PSP not available
        // ISO 8583 card response codes
        "05" to CheckoutError.Declined, // do not honour
        "51" to CheckoutError.InsufficientFunds,
        "54" to CheckoutError.CardExpired,
        "14" to CheckoutError.InvalidCard,
        "55" to CheckoutError.AuthenticationFailed, // incorrect PIN
        "61" to CheckoutError.LimitExceeded,
        "91" to CheckoutError.GatewayError("91"), // issuer unavailable
        "96" to CheckoutError.GatewayError("96"), // system malfunction
        // Generic
        "RATE_LIMITED" to CheckoutError.RateLimited(),
        "TIMEOUT" to CheckoutError.Timeout,
        "DECLINED" to CheckoutError.Declined,
    )

    /**
     * Maps [code] (preferred) or, if there is no code, [httpStatus] to an error.
     * [retryAfterMillis] is passed into [CheckoutError.RateLimited] for HTTP 429.
     */
    public fun fromCode(code: String?, httpStatus: Int? = null, retryAfterMillis: Long? = null): CheckoutError {
        // Don't even copy absurdly long codes; nothing legitimate is longer than a few characters.
        if (code != null && code.length > MAX_RAW_CODE_LENGTH) return CheckoutError.GatewayError(gatewayCode = null, isTransient = false)
        val normalised = code?.trim()?.uppercase()
        if (!normalised.isNullOrEmpty()) {
            if (!SAFE_CODE.matches(normalised)) return CheckoutError.GatewayError(gatewayCode = null, isTransient = false)
            return KNOWN[normalised] ?: CheckoutError.GatewayError(gatewayCode = normalised, isTransient = false)
        }
        return when (httpStatus) {
            null -> CheckoutError.Unknown(null)
            400, 422 -> CheckoutError.InvalidRequest("gateway rejected the request (HTTP $httpStatus)")
            401, 403 -> CheckoutError.GatewayError("HTTP_$httpStatus", isTransient = false)
            408, 504 -> CheckoutError.Timeout
            429 -> CheckoutError.RateLimited(retryAfterMillis?.coerceAtLeast(0))
            in 500..599 -> CheckoutError.GatewayError("HTTP_$httpStatus")
            else -> CheckoutError.Unknown("HTTP_$httpStatus")
        }
    }
}
