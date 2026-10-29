package com.darshan.checkoutkit.core

import com.darshan.checkoutkit.core.gateway.PaymentGateway
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.checkoutkit.core.idempotency.IdempotencyStore
import com.darshan.checkoutkit.core.idempotency.InMemoryIdempotencyStore
import com.darshan.checkoutkit.core.state.PaymentState

/**
 * Entry point of the SDK. Create one per gateway (usually one per app) and start a
 * [CheckoutSession] for every checkout attempt.
 *
 * ```
 * val kit = CheckoutKit(gateway = myGateway)
 * val session = kit.createSession(CheckoutRequest(orderId = "order-42", amountPaise = 49_900))
 * val result = session.submit(PaymentMethod.Upi("alice@okbank"))
 * ```
 */
public class CheckoutKit(
    public val gateway: PaymentGateway,
    public val config: CheckoutConfig = CheckoutConfig(),
    private val idempotencyStore: IdempotencyStore<CheckoutResult> = InMemoryIdempotencyStore(
        ttl = config.idempotencyTtl,
        maxEntries = config.maxIdempotencyEntries,
        clock = config.clock,
    ),
) {
    /**
     * Starts a checkout attempt. Pass a previously persisted [idempotencyKey] to resume an attempt
     * (for example after process death); otherwise a fresh key is generated.
     */
    public fun createSession(
        request: CheckoutRequest,
        idempotencyKey: IdempotencyKey = IdempotencyKey.generate(),
    ): CheckoutSession = CheckoutSession(request, idempotencyKey, gateway, config, idempotencyStore)

    /**
     * Re-opens a checkout whose charge may already have been sent, for example after process death
     * while the payment was in flight. The session starts in `Processing`; call
     * [CheckoutSession.checkStatus] to reconcile it with the gateway by [idempotencyKey] — it is
     * never charged again from here.
     */
    public fun resumeSession(request: CheckoutRequest, idempotencyKey: IdempotencyKey): CheckoutSession =
        CheckoutSession(request, idempotencyKey, gateway, config, idempotencyStore, initialState = PaymentState.Processing(1))
}
