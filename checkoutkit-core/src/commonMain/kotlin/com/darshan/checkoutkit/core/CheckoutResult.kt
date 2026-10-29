package com.darshan.checkoutkit.core

import com.darshan.checkoutkit.core.error.CheckoutError

/** The outcome of [CheckoutSession.submit]. */
public sealed interface CheckoutResult {
    /** Money was captured. */
    public data class Success(val transactionId: String, val amountPaise: Long) : CheckoutResult

    /** The gateway accepted the payment but has not confirmed it yet. Check again later. */
    public data class Pending(val transactionId: String) : CheckoutResult

    /** The payment failed. */
    public data class Failed(val error: CheckoutError) : CheckoutResult

    /** The payer cancelled. */
    public data object Cancelled : CheckoutResult

    /** The payment was captured and later refunded. */
    public data class Refunded(val transactionId: String, val refundId: String) : CheckoutResult

    /**
     * The outcome is unknown: every attempt timed out or failed on the network **and** the gateway
     * could not be reached to look the payment up. Money may or may not have been taken, so the
     * payer must not be told it failed. Call [CheckoutSession.checkStatus] (or [CheckoutSession.submit]
     * again, which reuses the idempotency key) to resolve it.
     */
    public data class Unresolved(val lastError: CheckoutError) : CheckoutResult

    /** Nothing was sent to the gateway because the request was invalid. */
    public data class Rejected(val error: CheckoutError) : CheckoutResult
}
