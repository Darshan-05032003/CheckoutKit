package com.darshan.checkoutkit.core.state

import com.darshan.checkoutkit.core.time.CheckoutClock
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The current state of a payment together with every accepted transition that led to it. */
public data class PaymentSnapshot(
    val state: PaymentState,
    val history: List<StateTransition>,
)

/**
 * Thread-safe holder of one payment's state.
 *
 * [apply] runs the event through [PaymentStateMachine] and publishes the result with a
 * compare-and-set loop, so when many coroutines race (double taps, a late gateway callback and a
 * user cancel at the same time) each transition is applied atomically and exactly one of the
 * competing events wins.
 */
public class PaymentStateHolder(
    initial: PaymentState = PaymentState.Created,
    private val clock: CheckoutClock = CheckoutClock.System,
) {
    private val _snapshot = MutableStateFlow(PaymentSnapshot(initial, emptyList()))

    /** State and history, always consistent with each other. */
    public val snapshot: StateFlow<PaymentSnapshot> = _snapshot.asStateFlow()

    /** The current payment state. */
    public val state: StateFlow<PaymentState> = MappedStateFlow(_snapshot) { it.state }

    /** Accepted transitions, oldest first. Only the last [MAX_HISTORY] are kept. */
    public val history: List<StateTransition> get() = _snapshot.value.history

    /** Applies [event] atomically. A rejected event changes nothing. */
    public fun apply(event: PaymentEvent): TransitionResult = applyIf(event) { true }

    /** Applies [event] only if the current state satisfies [precondition], atomically. */
    internal fun applyIf(event: PaymentEvent, precondition: (PaymentState) -> Boolean): TransitionResult {
        while (true) {
            val current = _snapshot.value
            if (!precondition(current.state)) {
                return TransitionResult.Rejected(current.state, event, "precondition failed in ${current.state.name}")
            }
            val result = PaymentStateMachine.transition(current.state, event)
            if (result !is TransitionResult.Accepted) return result
            val record = StateTransition(result.from, result.to, event, clock.nowMillis())
            val kept = if (current.history.size >= MAX_HISTORY) current.history.drop(current.history.size - MAX_HISTORY + 1) else current.history
            val next = PaymentSnapshot(result.to, kept + record)
            if (_snapshot.compareAndSet(current, next)) return result
        }
    }

    public companion object {
        /**
         * Most transitions kept in [history]. A normal payment needs fewer than 10; the cap stops a
         * retry storm from growing memory (and the copy cost of each transition) without bound.
         */
        public const val MAX_HISTORY: Int = 32
    }
}

/** Read-only [StateFlow] view that maps another state flow without needing a coroutine scope. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R get() = transform(source.value)
    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        error("StateFlow collection never completes")
    }
}
