package com.darshan.checkoutkit.core.gateway

import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey

/**
 * The payment processor CheckoutKit talks to. Implement it to connect a real PSP; use
 * `FakeGateway` for the sample app and tests.
 *
 * Implementations must honour [ChargeRequest.idempotencyKey]: a repeated charge with the same key
 * must return the original outcome and never charge twice. Network failures may be thrown as
 * exceptions (`IOException` → network error) or returned as [GatewayResult.Failed].
 */
public interface PaymentGateway {
    /** Starts (or, for a known idempotency key, looks up) a charge. */
    public suspend fun charge(request: ChargeRequest): GatewayResult
}

/** One charge request as sent to a [PaymentGateway]. */
public data class ChargeRequest(
    val idempotencyKey: IdempotencyKey,
    val orderId: String,
    val amountPaise: Long,
    val currency: String,
    val method: PaymentMethod,
)

/** What a gateway answered. */
public sealed interface GatewayResult {
    /** Money was captured. */
    public data class Succeeded(val transactionId: String, val amountPaise: Long) : GatewayResult

    /** Accepted, outcome not known yet (for example a UPI collect request waiting for the payer). */
    public data class Pending(val transactionId: String, val amountPaise: Long) : GatewayResult

    /** The charge failed. */
    public data class Failed(val error: CheckoutError) : GatewayResult
}
