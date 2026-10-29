package com.darshan.checkoutkit.core.error

/**
 * Why a payment failed.
 *
 * Every error has a stable [code] (safe to log and send to analytics) and a [retryable] flag that
 * tells the retry policy whether trying again with the same idempotency key can help.
 */
public sealed class CheckoutError(
    /** Stable, machine-readable error code, e.g. `NETWORK`. */
    public val code: String,
    /** Whether the same request may succeed if retried. */
    public val retryable: Boolean,
) {
    /** The device could not reach the gateway (no connectivity, DNS, connection reset). */
    public data object Network : CheckoutError("NETWORK", retryable = true)

    /** The gateway did not answer within the per-attempt or total timeout. */
    public data object Timeout : CheckoutError("TIMEOUT", retryable = true)

    /** The issuer or payer declined the payment. */
    public data object Declined : CheckoutError("DECLINED", retryable = false)

    /** An error the SDK could not classify. [detail] is for developers only, never shown to users. */
    public data class Unknown(val detail: String? = null) : CheckoutError("UNKNOWN", retryable = false)
}
