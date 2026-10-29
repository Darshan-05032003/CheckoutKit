package com.darshan.checkoutkit.core.state

import com.darshan.checkoutkit.core.error.CheckoutError

/**
 * The lifecycle of one checkout attempt.
 *
 * ```
 * Created ──Submit──▶ Processing ──AwaitConfirmation──▶ Pending ──Succeed──▶ Success ──Refund──▶ Refunded
 *    │                  │  ▲ Retry                         │  │
 *    │                  ├──┘                               │  └──Cancel──▶ Cancelled
 *    └──Cancel──▶ Cancelled      Processing/Pending ──Fail──▶ Failed
 * ```
 *
 * Only [PaymentStateMachine] decides which moves are legal; anything else is rejected.
 */
public sealed interface PaymentState {
    /** Stable upper-case name, safe for logs and analytics. */
    public val name: String

    /** `true` when no further transition is possible. */
    public val isTerminal: Boolean

    /** `true` when the payment has a final money outcome (success, failure, refund or cancel). */
    public val isOutcome: Boolean get() = isTerminal || this is Success

    /** The checkout was created and nothing has been sent to the gateway yet. */
    public data object Created : PaymentState {
        override val name: String get() = "CREATED"
        override val isTerminal: Boolean get() = false
    }

    /** The charge request is in flight. [attempt] starts at 1 and grows with each retry. */
    public data class Processing(val attempt: Int = 1) : PaymentState {
        init {
            require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        }

        override val name: String get() = "PROCESSING"
        override val isTerminal: Boolean get() = false
    }

    /** The gateway accepted the request and is waiting for the payer or bank (e.g. a UPI collect). */
    public data class Pending(val transactionId: String) : PaymentState {
        init {
            requireValidId(transactionId, "transactionId")
        }

        override val name: String get() = "PENDING"
        override val isTerminal: Boolean get() = false
    }

    /** Money was captured. Can still move to [Refunded]. */
    public data class Success(val transactionId: String) : PaymentState {
        init {
            requireValidId(transactionId, "transactionId")
        }

        override val name: String get() = "SUCCESS"
        override val isTerminal: Boolean get() = false
    }

    /** The payment failed with a typed [error]. Terminal. */
    public data class Failed(val error: CheckoutError) : PaymentState {
        override val name: String get() = "FAILED"
        override val isTerminal: Boolean get() = true
    }

    /** A captured payment was fully refunded. Terminal. */
    public data class Refunded(val transactionId: String, val refundId: String) : PaymentState {
        init {
            requireValidId(transactionId, "transactionId")
            requireValidId(refundId, "refundId")
        }

        override val name: String get() = "REFUNDED"
        override val isTerminal: Boolean get() = true
    }

    /** The payer cancelled before paying (or cancelled the pending request). Terminal. */
    public data object Cancelled : PaymentState {
        override val name: String get() = "CANCELLED"
        override val isTerminal: Boolean get() = true
    }

    public companion object {
        /** Longest transaction or refund id accepted from a gateway. */
        public const val MAX_ID_LENGTH: Int = 128
    }
}

internal fun requireValidId(value: String, field: String) {
    require(value.isNotBlank()) { "$field must not be blank" }
    require(value.length <= PaymentState.MAX_ID_LENGTH) { "$field longer than ${PaymentState.MAX_ID_LENGTH}" }
}
