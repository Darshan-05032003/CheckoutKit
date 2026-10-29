package com.darshan.checkoutkit.core.state

import com.darshan.checkoutkit.core.error.CheckoutError

/** Something that happened to a payment. Fed to [PaymentStateMachine.transition]. */
public sealed interface PaymentEvent {
    /** The payer pressed Pay; the charge request is about to be sent. */
    public data object Submit : PaymentEvent

    /** A failed attempt is being retried; [attempt] must be the current attempt + 1. */
    public data class Retry(val attempt: Int) : PaymentEvent {
        init {
            require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        }
    }

    /** The gateway accepted the request but the outcome is not known yet. */
    public data class AwaitConfirmation(val transactionId: String) : PaymentEvent {
        init {
            requireValidId(transactionId, "transactionId")
        }
    }

    /** The gateway confirmed the capture of [transactionId]. */
    public data class Succeed(val transactionId: String) : PaymentEvent {
        init {
            requireValidId(transactionId, "transactionId")
        }
    }

    /** The payment failed with [error]. */
    public data class Fail(val error: CheckoutError) : PaymentEvent

    /** The payer cancelled. */
    public data object Cancel : PaymentEvent

    /** The captured payment was refunded with [refundId]. */
    public data class Refund(val refundId: String) : PaymentEvent {
        init {
            requireValidId(refundId, "refundId")
        }
    }
}
