package com.darshan.checkoutkit.core

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.ChargeRequest
import com.darshan.checkoutkit.core.gateway.GatewayResult
import com.darshan.checkoutkit.core.gateway.PaymentGateway
import com.darshan.checkoutkit.core.gateway.RefundRequest
import com.darshan.checkoutkit.core.gateway.StatusQuery
import com.darshan.checkoutkit.core.idempotency.IdempotencyConflictException
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.checkoutkit.core.idempotency.IdempotencyStore
import com.darshan.checkoutkit.core.retry.AttemptResult
import com.darshan.checkoutkit.core.retry.RetryExecutor
import com.darshan.checkoutkit.core.retry.RetryOutcome
import com.darshan.checkoutkit.core.state.PaymentEvent
import com.darshan.checkoutkit.core.state.PaymentState
import com.darshan.checkoutkit.core.state.PaymentStateHolder
import com.darshan.checkoutkit.core.state.StateTransition
import com.darshan.checkoutkit.core.state.TransitionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * One checkout attempt: a [CheckoutRequest], its [idempotencyKey] and its live [state].
 *
 * - [submit] is idempotent: double taps and repeated calls share one charge and return the same
 *   result; retries reuse the same key. A pending payment is polled per [PendingPolicy].
 * - The gateway's answers are verified: a captured amount or transaction id that doesn't match the
 *   request fails the payment with [CheckoutError.ResponseMismatch] instead of reporting success.
 * - The state only moves through [com.darshan.checkoutkit.core.state.PaymentStateMachine].
 * - Cancelling the coroutine that called [submit] leaves the session in `Processing` or `Pending`
 *   (the outcome is unknown); calling [submit] or [checkStatus] again resumes with the same key.
 */
public class CheckoutSession internal constructor(
    /** What is being paid for. */
    public val request: CheckoutRequest,
    /** Identifies this attempt; sent with every charge, retry and status call. Persist it to resume. */
    public val idempotencyKey: IdempotencyKey,
    private val gateway: PaymentGateway,
    private val config: CheckoutConfig,
    private val store: IdempotencyStore<CheckoutResult>,
    initialState: PaymentState = PaymentState.Created,
) {
    private val holder = PaymentStateHolder(initial = initialState, clock = config.clock)
    private val executor = RetryExecutor(config.retryPolicy, config.random)
    private val refundMutex = Mutex()
    private val chargesInFlight = MutableStateFlow(0)

    /** The live payment state. */
    public val state: StateFlow<PaymentState> = holder.state

    /** Accepted transitions so far, oldest first. */
    public val history: List<StateTransition> get() = holder.history

    /** Key used for the refund of this payment (derived from [idempotencyKey]). */
    public val refundKey: IdempotencyKey = IdempotencyKey(idempotencyKey.value.take(MAX_BASE_KEY) + "-rf")

    /** Pays with [method]. Safe to call repeatedly; see the class docs. */
    public suspend fun submit(method: PaymentMethod): CheckoutResult = withContext(config.dispatcher) {
        val fingerprint = "${request.orderId}|${request.amountPaise}|${request.currency}|${method.fingerprint}"
        try {
            val result = store.getOrExecute(idempotencyKey, fingerprint, ::isReplayable) { charge(method) }
            if (result.replayed) syncTo(result.value)
            // Prefer this session's own state: it may have moved on (e.g. refunded) since the result was cached.
            if (result.replayed) resultOf(holder.state.value) ?: result.value else result.value
        } catch (_: IdempotencyConflictException) {
            CheckoutResult.Rejected(CheckoutError.InvalidRequest("idempotency key reused for a different request"))
        }
    }

    /**
     * Cancels the checkout before anything was sent. Returns `false` once the payment is in flight
     * or pending, because money may already be on its way; resolve those with [checkStatus].
     */
    public fun cancel(): Boolean =
        holder.applyIf(PaymentEvent.Cancel) { it == PaymentState.Created } is TransitionResult.Accepted

    /**
     * Asks the gateway for the status of a pending or unresolved payment. For any other state it just
     * returns the current outcome.
     */
    public suspend fun checkStatus(): CheckoutResult = withContext(config.dispatcher) {
        when (val current = holder.state.value) {
            is PaymentState.Pending -> {
                queryStatus(current.transactionId)?.let { return@withContext it }
                resultOf(holder.state.value) ?: CheckoutResult.Pending(current.transactionId)
            }
            is PaymentState.Processing ->
                if (chargesInFlight.value > 0) rejected("a submit is still in progress")
                else reconcile(CheckoutError.Timeout)
            else -> resultOf(current) ?: rejected("nothing to check yet (state ${current.name})")
        }
    }

    /**
     * Fully refunds a successful payment. Concurrent calls refund once. On failure the session stays
     * `Success` and the error is returned, so the refund can be retried.
     */
    public suspend fun refund(): CheckoutResult = withContext(config.dispatcher) {
        refundMutex.withLock {
            val paid = when (val current = holder.state.value) {
                is PaymentState.Refunded -> return@withContext CheckoutResult.Refunded(current.transactionId, current.refundId)
                is PaymentState.Success -> current
                else -> return@withContext rejected("refund needs SUCCESS, state is ${current.name}")
            }
            val refundRequest = RefundRequest(refundKey, paid.transactionId, request.amountPaise)
            when (val result = executeRefund(gateway, executor, refundRequest)) {
                is CheckoutResult.Refunded -> {
                    holder.apply(PaymentEvent.Refund(result.refundId))
                    resultOf(holder.state.value) ?: result
                }
                else -> result
            }
        }
    }

    private suspend fun charge(method: PaymentMethod): CheckoutResult {
        chargesInFlight.update { it + 1 }
        try {
            return chargeLocked(method)
        } finally {
            chargesInFlight.update { it - 1 }
        }
    }

    private suspend fun chargeLocked(method: PaymentMethod): CheckoutResult {
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
            is RetryOutcome.Success -> onChargeAnswer(outcome.value)
            // Timeouts and network errors leave the outcome unknown: the gateway may have captured
            // the money and only the answer got lost. Ask before declaring the payment failed.
            is RetryOutcome.Failure -> if (outcome.error.retryable) reconcile(outcome.error) else fail(outcome.error)
        }
    }

    private suspend fun onChargeAnswer(response: GatewayResult): CheckoutResult = when (response) {
        is GatewayResult.Succeeded ->
            if (!isValidId(response.transactionId) || response.amountPaise != request.amountPaise) {
                fail(CheckoutError.ResponseMismatch)
            } else {
                holder.apply(PaymentEvent.Succeed(response.transactionId))
                resultOf(holder.state.value) ?: CheckoutResult.Success(response.transactionId, request.amountPaise)
            }
        is GatewayResult.Pending ->
            if (!isValidId(response.transactionId) || response.amountPaise != request.amountPaise) {
                fail(CheckoutError.ResponseMismatch)
            } else {
                holder.apply(PaymentEvent.AwaitConfirmation(response.transactionId))
                pollPending(response.transactionId)
            }
        // A charge can't be "cancelled" before it is pending; treat it as a payer decline.
        is GatewayResult.Cancelled -> fail(CheckoutError.Declined)
        is GatewayResult.Failed -> fail(response.error)
    }

    /**
     * Looks the payment up by idempotency key after an unknown outcome (retried under the same
     * policy). If the gateway can't be reached either, the state stays `Processing` and the result
     * is [CheckoutResult.Unresolved]: never report "failed" for money that may have been taken.
     */
    private suspend fun reconcile(cause: CheckoutError): CheckoutResult {
        val lookup = executor.execute {
            when (val r = gateway.status(StatusQuery(idempotencyKey, null))) {
                is GatewayResult.Failed -> if (r.error.retryable) AttemptResult.Failure(r.error) else AttemptResult.Success(r)
                else -> AttemptResult.Success(r)
            }
        }
        val response = when (lookup) {
            is RetryOutcome.Failure -> return CheckoutResult.Unresolved(cause)
            is RetryOutcome.Success -> lookup.value
        }
        return when (response) {
            is GatewayResult.Succeeded, is GatewayResult.Pending, is GatewayResult.Cancelled -> onChargeAnswer(response)
            // "Unknown payment" (or another failed query) means the charge never landed.
            is GatewayResult.Failed ->
                if (response.error.retryable || response.error is CheckoutError.InvalidRequest) fail(cause) else fail(response.error)
        }
    }

    /** Polls until the payment leaves Pending or the policy is exhausted (then it stays Pending). */
    private suspend fun pollPending(transactionId: String): CheckoutResult {
        val policy = config.pendingPolicy
        for (poll in 1..policy.maxPolls) {
            delay(policy.intervalBefore(poll))
            if (holder.state.value !is PaymentState.Pending) break // resolved elsewhere (checkStatus)
            queryStatus(transactionId)?.let { return it }
        }
        return resultOf(holder.state.value) ?: CheckoutResult.Pending(transactionId)
    }

    /** One status call. Returns the final result, or `null` if the payment is still pending. */
    private suspend fun queryStatus(transactionId: String): CheckoutResult? {
        val response = try {
            withTimeoutOrNull(config.retryPolicy.attemptTimeout) {
                gateway.status(StatusQuery(idempotencyKey, transactionId))
            } ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null // a failed status *query* says nothing about the payment; poll again
        }
        return when (response) {
            is GatewayResult.Pending -> null
            is GatewayResult.Succeeded ->
                if (response.transactionId != transactionId || response.amountPaise != request.amountPaise) {
                    fail(CheckoutError.ResponseMismatch)
                } else {
                    holder.apply(PaymentEvent.Succeed(transactionId))
                    resultOf(holder.state.value)
                }
            is GatewayResult.Cancelled ->
                if (response.transactionId != transactionId) {
                    fail(CheckoutError.ResponseMismatch)
                } else {
                    holder.apply(PaymentEvent.Cancel)
                    resultOf(holder.state.value)
                }
            is GatewayResult.Failed ->
                if (response.error.retryable) null else fail(response.error)
        }
    }

    private fun fail(error: CheckoutError): CheckoutResult {
        holder.apply(PaymentEvent.Fail(error))
        return resultOf(holder.state.value) ?: CheckoutResult.Failed(error)
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
        const val MAX_BASE_KEY = 61

        fun isValidId(id: String): Boolean = id.isNotBlank() && id.length <= PaymentState.MAX_ID_LENGTH

        /** Final outcomes are replayed; transient failures and pending results are re-checked. */
        fun isReplayable(result: CheckoutResult): Boolean = when (result) {
            is CheckoutResult.Success, is CheckoutResult.Refunded -> true
            is CheckoutResult.Failed -> !result.error.retryable
            is CheckoutResult.Pending, CheckoutResult.Cancelled, is CheckoutResult.Rejected, is CheckoutResult.Unresolved -> false
        }
    }
}
