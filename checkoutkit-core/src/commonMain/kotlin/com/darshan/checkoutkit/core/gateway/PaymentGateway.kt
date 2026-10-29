package com.darshan.checkoutkit.core.gateway

import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey

/**
 * The payment processor CheckoutKit talks to. This is the SDK's plug-in point: implement it to
 * connect a real PSP or your own backend, or use [FakeGateway] for the sample app and tests.
 *
 * Contract for implementations:
 * - **Idempotency:** a repeated [charge] with the same [ChargeRequest.idempotencyKey] must return
 *   the original outcome and never charge twice. The same applies to [refund] and
 *   [RefundRequest.refundKey].
 * - **Errors:** return [GatewayResult.Failed] with a typed error (see
 *   [com.darshan.checkoutkit.core.error.GatewayErrorCodes]) or throw
 *   [com.darshan.checkoutkit.core.error.GatewayException]; an `IOException` counts as a network error.
 * - **Truthfulness:** report the amount actually captured. The SDK compares it with the request
 *   and fails the payment on any mismatch.
 * - **Cancellation:** calls must be cancellable (suspend, no blocking without `withContext`).
 */
public interface PaymentGateway {
    /** Starts (or, for a known idempotency key, looks up) a charge. */
    public suspend fun charge(request: ChargeRequest): GatewayResult

    /** Current status of a charge, used to resolve [GatewayResult.Pending] payments. */
    public suspend fun status(query: StatusQuery): GatewayResult

    /** Fully refunds a captured charge. */
    public suspend fun refund(request: RefundRequest): RefundResult
}

/** One charge request as sent to a [PaymentGateway]. */
public data class ChargeRequest(
    val idempotencyKey: IdempotencyKey,
    val orderId: String,
    val amountPaise: Long,
    val currency: String,
    val method: PaymentMethod,
)

/** Asks for the status of the charge made with [idempotencyKey] (and [transactionId] if known). */
public data class StatusQuery(
    val idempotencyKey: IdempotencyKey,
    val transactionId: String?,
)

/** Refunds [amountPaise] of [transactionId]. [refundKey] makes the refund idempotent. */
public data class RefundRequest(
    val refundKey: IdempotencyKey,
    val transactionId: String,
    val amountPaise: Long,
)

/** What a gateway answered to a charge or status call. */
public sealed interface GatewayResult {
    /** Money was captured. */
    public data class Succeeded(val transactionId: String, val amountPaise: Long) : GatewayResult

    /** Accepted, outcome not known yet (for example a UPI collect request waiting for the payer). */
    public data class Pending(val transactionId: String, val amountPaise: Long) : GatewayResult

    /** The payer cancelled the pending payment (for example in their UPI app). */
    public data class Cancelled(val transactionId: String) : GatewayResult

    /** The charge failed. From a status call, a `retryable` error means the *query* failed. */
    public data class Failed(val error: CheckoutError) : GatewayResult
}

/** What a gateway answered to a refund. */
public sealed interface RefundResult {
    /** The refund went through. */
    public data class Refunded(val refundId: String, val amountPaise: Long) : RefundResult

    /** The refund failed. */
    public data class Failed(val error: CheckoutError) : RefundResult
}
