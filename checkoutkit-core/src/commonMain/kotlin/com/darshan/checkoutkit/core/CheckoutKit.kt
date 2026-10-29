package com.darshan.checkoutkit.core

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.PaymentGateway
import com.darshan.checkoutkit.core.gateway.RefundRequest
import com.darshan.checkoutkit.core.idempotency.IdempotencyConflictException
import com.darshan.checkoutkit.core.retry.RetryExecutor
import kotlinx.coroutines.withContext
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
    /** The payment processor every session of this kit talks to. */
    public val gateway: PaymentGateway,
    /** Retry, polling, threading, clock and idempotency settings shared by all sessions. */
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

    private val refundStore = InMemoryIdempotencyStore<CheckoutResult>(
        ttl = config.idempotencyTtl,
        maxEntries = config.maxIdempotencyEntries,
        clock = config.clock,
    )
    private val refundExecutor = RetryExecutor(config.retryPolicy, config.random)

    /**
     * Fully refunds a captured payment by its [transactionId], for hosts that only kept the
     * [CheckoutResult.Success] (for example from the payment sheet). Calls with the same [refundKey]
     * are idempotent: concurrent or repeated calls refund once and return the same result. Persist
     * the key if a refund may need to be retried later.
     *
     * Returns [CheckoutResult.Refunded], [CheckoutResult.Failed] (gateway refused, or its answer did
     * not match), or [CheckoutResult.Rejected] if [refundKey] was used for another refund.
     */
    public suspend fun refund(
        transactionId: String,
        amountPaise: Long,
        refundKey: IdempotencyKey = IdempotencyKey.generate(),
    ): CheckoutResult {
        require(transactionId.isNotBlank() && transactionId.length <= PaymentState.MAX_ID_LENGTH) { "invalid transactionId" }
        require(amountPaise in 1..CheckoutRequest.MAX_AMOUNT_PAISE) { "amountPaise must be in 1..${CheckoutRequest.MAX_AMOUNT_PAISE}" }
        return withContext(config.dispatcher) {
            try {
                refundStore.getOrExecute(
                    key = refundKey,
                    fingerprint = "$transactionId|$amountPaise",
                    shouldCache = { it is CheckoutResult.Refunded || (it is CheckoutResult.Failed && !it.error.retryable) },
                ) {
                    executeRefund(gateway, refundExecutor, RefundRequest(refundKey, transactionId, amountPaise))
                }.value
            } catch (_: IdempotencyConflictException) {
                CheckoutResult.Rejected(CheckoutError.InvalidRequest("refund key reused for a different refund"))
            }
        }
    }

    /**
     * Re-opens a checkout whose charge may already have been sent, for example after process death
     * while the payment was in flight. The session starts in `Processing`; call
     * [CheckoutSession.checkStatus] to reconcile it with the gateway by [idempotencyKey] — it is
     * never charged again from here.
     */
    public fun resumeSession(request: CheckoutRequest, idempotencyKey: IdempotencyKey): CheckoutSession =
        CheckoutSession(request, idempotencyKey, gateway, config, idempotencyStore, initialState = PaymentState.Processing(1))
}
