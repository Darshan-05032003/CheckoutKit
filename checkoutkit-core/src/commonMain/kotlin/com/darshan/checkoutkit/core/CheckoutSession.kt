package com.darshan.checkoutkit.core

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.ChargeRequest
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.idempotency.IdempotencyConflictException
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.checkoutkit.core.idempotency.IdempotencyStore
import com.darshan.checkoutkit.core.gateway.PaymentGateway
import com.darshan.checkoutkit.core.retry.AttemptResult
import com.darshan.checkoutkit.core.retry.RetryExecutor
import com.darshan.checkoutkit.core.retry.RetryOutcome
import com.darshan.checkoutkit.core.state.PaymentEvent
import com.darshan.checkoutkit.core.state.PaymentState
import com.darshan.checkoutkit.core.state.PaymentStateHolder
import com.darshan.checkoutkit.core.state.StateTransition
import com.darshan.checkoutkit.core.state.TransitionResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * One checkout attempt: a [CheckoutRequest], its [idempotencyKey] and its [state].
 *
 * - [submit] is idempotent: double taps and repeated calls share one charge and return the same
 *   result; retries reuse the same key.
 * - The state only moves through [com.darshan.checkoutkit.core.state.PaymentStateMachine].
 * - Cancelling the coroutine that called [submit] leaves the session in `Processing` (the outcome is
 *   unknown); calling [submit] again resumes with the same key.
 */
public class CheckoutSession internal constructor(
    public val request: CheckoutRequest,
    public val idempotencyKey: IdempotencyKey,
    private val gateway: PaymentGateway,
    private val config: CheckoutConfig,
    private val store: IdempotencyStore<CheckoutResult>,
) {
    private val holder = PaymentStateHolder(clock = config.clock)
    private val executor = RetryExecutor(config.retryPolicy, config.random)

    /** The live payment state. */
    public val state: StateFlow<PaymentState> = holder.state

    /** Accepted transitions so far, oldest first. */
    public val history: List<StateTransition> get() = holder.history

    /** Pays with [method]. Safe to call repeatedly; see the class docs. */
    public suspend fun submit(method: PaymentMethod): CheckoutResult = withContext(config.dispatcher) {
        val fingerprint = "${request.orderId}|${request.amountPaise}|${request.currency}|${method.fingerprint}"
        try {
            val result = store.getOrExecute(idempotencyKey, fingerprint, ::isReplayable) { charge(method) }
            if (result.replayed) syncTo(result.value)
            result.value
        } catch (_: IdempotencyConflictException) {
            CheckoutResult.Rejected(CheckoutError.InvalidRequest("idempotency key reused for a different request"))
        }
    }

    /** Cancels the checkout. Allowed before submitting (and while pending); returns `false` otherwise. */
    public fun cancel(): Boolean = holder.apply(PaymentEvent.Cancel) is TransitionResult.Accepted

    private suspend fun charge(method: PaymentMethod): CheckoutResult {
        when (val current = holder.state.value) {
            PaymentState.Created -> if (holder.apply(PaymentEvent.Submit) is TransitionResult.Rejected) {
                return resultOf(holder.state.value) ?: rejected("cannot submit in ${holder.state.value.name}")
            }
            is PaymentState.Processing -> Unit // resuming an interrupted attempt with the same key
            else -> return resultOf(current) ?: rejected("cannot submit in ${current.name}")
        }

        val chargeRequest = ChargeRequest(idempotencyKey, request.orderId, request.amountPaise, request.currency, method)
        val outcome = executor.execute(
            onRetry = { _, _, _ ->
                val attempt = (holder.state.value as? PaymentState.Processing)?.attempt ?: 1
                holder.apply(PaymentEvent.Retry(attempt + 1))
            },
        ) {
            when (val response = gateway.charge(chargeRequest)) {
                is GatewayResult.Failed -> AttemptResult.Failure(response.error)
                else -> AttemptResult.Success(response)
            }
        }

        return when (outcome) {
            is RetryOutcome.Failure -> fail(outcome.error)
            is RetryOutcome.Success -> when (val response = outcome.value) {
                is GatewayResult.Succeeded -> {
                    holder.apply(PaymentEvent.Succeed(response.transactionId))
                    CheckoutResult.Success(response.transactionId, request.amountPaise)
                }
                is GatewayResult.Pending -> {
                    holder.apply(PaymentEvent.AwaitConfirmation(response.transactionId))
                    CheckoutResult.Pending(response.transactionId)
                }
                is GatewayResult.Failed -> fail(response.error)
            }
        }
    }

    private fun fail(error: CheckoutError): CheckoutResult {
        holder.apply(PaymentEvent.Fail(error))
        return CheckoutResult.Failed(error)
    }

    /** Brings a session that did not run the charge itself (it joined or replayed) in line with [result]. */
    private fun syncTo(result: CheckoutResult) {
        if (holder.state.value != PaymentState.Created) return
        when (result) {
            is CheckoutResult.Success -> {
                holder.apply(PaymentEvent.Submit)
                holder.apply(PaymentEvent.Succeed(result.transactionId))
            }
            is CheckoutResult.Pending -> {
                holder.apply(PaymentEvent.Submit)
                holder.apply(PaymentEvent.AwaitConfirmation(result.transactionId))
            }
            is CheckoutResult.Failed -> {
                holder.apply(PaymentEvent.Submit)
                holder.apply(PaymentEvent.Fail(result.error))
            }
            else -> Unit
        }
    }

    private fun resultOf(state: PaymentState): CheckoutResult? = when (state) {
        is PaymentState.Success -> CheckoutResult.Success(state.transactionId, request.amountPaise)
        is PaymentState.Pending -> CheckoutResult.Pending(state.transactionId)
        is PaymentState.Failed -> CheckoutResult.Failed(state.error)
        is PaymentState.Refunded -> CheckoutResult.Refunded(state.transactionId, state.refundId)
        PaymentState.Cancelled -> CheckoutResult.Cancelled
        PaymentState.Created, is PaymentState.Processing -> null
    }

    private fun rejected(detail: String) = CheckoutResult.Rejected(CheckoutError.InvalidRequest(detail))

    private companion object {
        /** Final outcomes are replayed; transient failures and pending results are re-checked. */
        fun isReplayable(result: CheckoutResult): Boolean = when (result) {
            is CheckoutResult.Success, is CheckoutResult.Refunded -> true
            is CheckoutResult.Failed -> !result.error.retryable
            is CheckoutResult.Pending, CheckoutResult.Cancelled, is CheckoutResult.Rejected -> false
        }
    }
}
