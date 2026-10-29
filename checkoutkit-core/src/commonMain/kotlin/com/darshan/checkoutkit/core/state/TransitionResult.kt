package com.darshan.checkoutkit.core.state

/** The answer of [PaymentStateMachine.transition]. */
public sealed interface TransitionResult {
    /** The state before the event. */
    public val from: PaymentState

    /** The event that was applied. */
    public val event: PaymentEvent

    /** The event was legal; the payment is now in [to]. */
    public data class Accepted(
        override val from: PaymentState,
        override val event: PaymentEvent,
        val to: PaymentState,
    ) : TransitionResult

    /** The event is illegal in [from]; the state did not change. [reason] is developer-facing. */
    public data class Rejected(
        override val from: PaymentState,
        override val event: PaymentEvent,
        val reason: String,
    ) : TransitionResult
}

/** One accepted move, recorded with the clock time it happened at. */
public data class StateTransition(
    val from: PaymentState,
    val to: PaymentState,
    val event: PaymentEvent,
    val atMillis: Long,
)
