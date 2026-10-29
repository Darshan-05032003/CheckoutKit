package com.darshan.checkoutkit.core.state

import com.darshan.checkoutkit.core.state.PaymentEvent.AwaitConfirmation
import com.darshan.checkoutkit.core.state.PaymentEvent.Cancel
import com.darshan.checkoutkit.core.state.PaymentEvent.Fail
import com.darshan.checkoutkit.core.state.PaymentEvent.Refund
import com.darshan.checkoutkit.core.state.PaymentEvent.Retry
import com.darshan.checkoutkit.core.state.PaymentEvent.Submit
import com.darshan.checkoutkit.core.state.PaymentEvent.Succeed

/**
 * The pure transition function of a payment. It has no state and no side effects, so it is safe to
 * call from any thread and trivial to test exhaustively.
 *
 * Legal moves:
 * - Created: Submit → Processing(1), Cancel → Cancelled
 * - Processing(n): Retry(n+1) → Processing(n+1), AwaitConfirmation → Pending, Succeed → Success, Fail → Failed
 * - Pending(t): Succeed(t) → Success (same transaction id only), Fail → Failed, Cancel → Cancelled
 * - Success(t): Refund → Refunded
 * - Failed, Refunded, Cancelled: terminal
 */
public object PaymentStateMachine {

    /** Applies [event] to [state] and returns the new state or the reason it was rejected. */
    public fun transition(state: PaymentState, event: PaymentEvent): TransitionResult {
        val next: PaymentState? = when (state) {
            PaymentState.Created -> when (event) {
                Submit -> PaymentState.Processing(attempt = 1)
                Cancel -> PaymentState.Cancelled
                else -> null
            }

            is PaymentState.Processing -> when (event) {
                is Retry -> if (event.attempt == state.attempt + 1) {
                    PaymentState.Processing(event.attempt)
                } else {
                    return reject(state, event, "retry attempt must be ${state.attempt + 1}, was ${event.attempt}")
                }
                is AwaitConfirmation -> PaymentState.Pending(event.transactionId)
                is Succeed -> PaymentState.Success(event.transactionId)
                is Fail -> PaymentState.Failed(event.error)
                else -> null
            }

            is PaymentState.Pending -> when (event) {
                is Succeed -> if (event.transactionId == state.transactionId) {
                    PaymentState.Success(event.transactionId)
                } else {
                    return reject(state, event, "confirmation is for a different transaction id")
                }
                is Fail -> PaymentState.Failed(event.error)
                Cancel -> PaymentState.Cancelled
                else -> null
            }

            is PaymentState.Success -> when (event) {
                is Refund -> PaymentState.Refunded(state.transactionId, event.refundId)
                else -> null
            }

            is PaymentState.Failed, is PaymentState.Refunded, PaymentState.Cancelled -> null
        }
        return if (next == null) {
            reject(state, event, "${eventName(event)} is not allowed in ${state.name}")
        } else {
            TransitionResult.Accepted(from = state, event = event, to = next)
        }
    }

    /** `true` if [event] would be accepted in [state]. */
    public fun canApply(state: PaymentState, event: PaymentEvent): Boolean =
        transition(state, event) is TransitionResult.Accepted

    private fun reject(state: PaymentState, event: PaymentEvent, reason: String) =
        TransitionResult.Rejected(from = state, event = event, reason = reason)

    private fun eventName(event: PaymentEvent): String = when (event) {
        Submit -> "Submit"
        is Retry -> "Retry"
        is AwaitConfirmation -> "AwaitConfirmation"
        is Succeed -> "Succeed"
        is Fail -> "Fail"
        Cancel -> "Cancel"
        is Refund -> "Refund"
    }
}
