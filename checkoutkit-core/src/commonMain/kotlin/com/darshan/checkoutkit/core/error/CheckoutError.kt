package com.darshan.checkoutkit.core.error

/**
 * Why a payment failed: a closed set of typed errors.
 *
 * Every error has a stable [code] (safe to log and send to analytics) and a [retryable] flag that
 * tells the retry policy whether trying again with the same idempotency key can help. Turn an error
 * into text for the payer with [toUserMessage] (or a [CheckoutMessages] of your own); never show
 * developer details such as [Unknown.detail] to users.
 */
public sealed class CheckoutError(
    /** Stable, machine-readable error code, e.g. `INSUFFICIENT_FUNDS`. */
    public val code: String,
    /** Whether the same request may succeed if retried. */
    public val retryable: Boolean,
) {
    /** The device could not reach the gateway (no connectivity, DNS, connection reset). */
    public data object Network : CheckoutError("NETWORK", retryable = true)

    /** The gateway or bank did not answer in time. */
    public data object Timeout : CheckoutError("TIMEOUT", retryable = true)

    /** The issuer, the risk engine or the payer declined the payment. */
    public data object Declined : CheckoutError("DECLINED", retryable = false)

    /** The payer's account or card does not have enough balance. */
    public data object InsufficientFunds : CheckoutError("INSUFFICIENT_FUNDS", retryable = false)

    /** The UPI id is malformed or does not exist. */
    public data object InvalidVpa : CheckoutError("INVALID_VPA", retryable = false)

    /** The card number, CVV or card details are invalid. */
    public data object InvalidCard : CheckoutError("INVALID_CARD", retryable = false)

    /** The card has expired. */
    public data object CardExpired : CheckoutError("CARD_EXPIRED", retryable = false)

    /** A per-transaction, daily or frequency limit was hit. */
    public data object LimitExceeded : CheckoutError("LIMIT_EXCEEDED", retryable = false)

    /** Wrong UPI PIN, OTP or 3-D Secure challenge, or too many attempts. */
    public data object AuthenticationFailed : CheckoutError("AUTHENTICATION_FAILED", retryable = false)

    /**
     * The gateway is throttling requests. [retryAfterMillis] is the server's hint (HTTP `Retry-After`);
     * the retry executor waits at least that long.
     */
    public data class RateLimited(val retryAfterMillis: Long? = null) : CheckoutError("RATE_LIMITED", retryable = true) {
        init {
            require(retryAfterMillis == null || retryAfterMillis >= 0) { "retryAfterMillis must be >= 0" }
        }
    }

    /**
     * The gateway reported an error of its own (5xx, bank unavailable, an unrecognised code).
     * [gatewayCode] is the sanitised raw code for logs; [isTransient] says whether a retry can help.
     */
    public data class GatewayError(
        val gatewayCode: String? = null,
        val isTransient: Boolean = true,
    ) : CheckoutError("GATEWAY_ERROR", retryable = isTransient)

    /** The SDK was called with input it can't accept (bad amount, reused idempotency key, wrong state). */
    public data class InvalidRequest(val detail: String) : CheckoutError("INVALID_REQUEST", retryable = false)

    /**
     * The gateway's answer did not match the request (different amount or transaction id). The SDK
     * treats this as a failed, possibly tampered response and never reports success.
     */
    public data object ResponseMismatch : CheckoutError("RESPONSE_MISMATCH", retryable = false)

    /** An error the SDK could not classify. [detail] is for developers only, never shown to users. */
    public data class Unknown(val detail: String? = null) : CheckoutError("UNKNOWN", retryable = false)

    public companion object {
        /** Every error code the SDK can produce. */
        public val ALL_CODES: Set<String> = setOf(
            "NETWORK", "TIMEOUT", "DECLINED", "INSUFFICIENT_FUNDS", "INVALID_VPA", "INVALID_CARD",
            "CARD_EXPIRED", "LIMIT_EXCEEDED", "AUTHENTICATION_FAILED", "RATE_LIMITED", "GATEWAY_ERROR",
            "INVALID_REQUEST", "RESPONSE_MISMATCH", "UNKNOWN",
        )
    }
}

/**
 * Thrown by a [com.darshan.checkoutkit.core.gateway.PaymentGateway] implementation to report a typed
 * failure from deep inside its networking code. The SDK unwraps it to [error].
 */
public class GatewayException(public val error: CheckoutError) : Exception("Gateway failed with ${error.code}")
