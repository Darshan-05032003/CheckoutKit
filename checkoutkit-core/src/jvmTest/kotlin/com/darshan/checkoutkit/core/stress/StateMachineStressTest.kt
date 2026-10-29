package com.darshan.checkoutkit.core.stress

import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.state.PaymentEvent
import com.darshan.checkoutkit.core.state.PaymentState
import com.darshan.checkoutkit.core.state.PaymentStateHolder
import com.darshan.checkoutkit.core.state.TransitionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateMachineStressTest {

    @Test
    fun tenThousandConcurrentSessions_reachAValidFinalState() {
        val sessions = 10_000
        val m = measureStress("F1 10k concurrent sessions through the state machine") {
            runBlocking(Dispatchers.Default) {
                (0 until sessions).map { i ->
                    async {
                        val holder = PaymentStateHolder()
                        holder.apply(PaymentEvent.Submit)
                        yield()
                        holder.apply(PaymentEvent.Retry(2))
                        when (i % 4) {
                            0 -> holder.apply(PaymentEvent.Succeed("txn_$i"))
                            1 -> {
                                holder.apply(PaymentEvent.AwaitConfirmation("txn_$i"))
                                yield()
                                holder.apply(PaymentEvent.Succeed("txn_$i"))
                                holder.apply(PaymentEvent.Refund("rf_$i"))
                            }
                            2 -> holder.apply(PaymentEvent.Fail(CheckoutError.Timeout))
                            else -> {
                                holder.apply(PaymentEvent.AwaitConfirmation("txn_$i"))
                                holder.apply(PaymentEvent.Cancel)
                            }
                        }
                        holder
                    }
                }.awaitAll()
            }
        }
        val finals = m.value.map { it.state.value }
        println("STRESS [F1 10k sessions] retained heap for 10k holders ~${m.heapDeltaMb} MB, transitions=${m.value.sumOf { it.history.size }}")
        assertEquals(sessions, finals.size)
        assertEquals(sessions / 4, finals.count { it is PaymentState.Success })
        assertEquals(sessions / 4, finals.count { it is PaymentState.Refunded })
        assertEquals(sessions / 4, finals.count { it is PaymentState.Failed })
        assertEquals(sessions / 4, finals.count { it == PaymentState.Cancelled })
        assertTrue(m.duration.inWholeSeconds < 10, "took ${m.duration}")
    }

    @Test
    fun thousandCoroutinesRacingOneSession_exactlyOneWinnerPerStep() {
        val racers = 1_000
        val holder = PaymentStateHolder()
        val m = measureStress("F1 1,000 racers on one session (submit, then succeed vs fail)") {
            runBlocking(Dispatchers.Default) {
                val submits = (1..racers).map { async { holder.apply(PaymentEvent.Submit) } }.awaitAll()
                val outcomes = (1..racers).map { i ->
                    async {
                        if (i % 2 == 0) holder.apply(PaymentEvent.Succeed("txn_race"))
                        else holder.apply(PaymentEvent.Fail(CheckoutError.Network))
                    }
                }.awaitAll()
                submits to outcomes
            }
        }
        val (submits, outcomes) = m.value
        assertEquals(1, submits.count { it is TransitionResult.Accepted })
        assertEquals(1, outcomes.count { it is TransitionResult.Accepted })
        assertEquals(2, holder.history.size)
        assertTrue(holder.state.value.isOutcome)
    }

    @Test
    fun stateFlowCollectorsUnderLoad_neverObserveAnIllegalSequence() {
        val target = 2_000
        val holder = PaymentStateHolder()
        holder.apply(PaymentEvent.Submit)
        val observed = java.util.Collections.synchronizedList(mutableListOf<PaymentState>())
        val m = measureStress("F1 8 writers bumping one session to attempt $target with a live collector") {
            runBlocking(Dispatchers.Default) {
                val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    holder.state.collect { observed += it }
                }
                (1..8).map {
                    async {
                        while (true) {
                            val current = holder.state.value as PaymentState.Processing
                            if (current.attempt >= target) break
                            holder.apply(PaymentEvent.Retry(current.attempt + 1))
                        }
                    }
                }.awaitAll()
                yield()
                collector.cancel()
            }
        }
        val attempts = observed.filterIsInstance<PaymentState.Processing>().map { it.attempt }
        // Collectors may skip conflated values but must never see attempts go backwards.
        assertEquals(attempts.sorted().distinct(), attempts)
        assertEquals(PaymentState.Processing(target), holder.state.value)
        // History is capped; the kept tail must be the last MAX_HISTORY consecutive attempts.
        assertEquals(PaymentStateHolder.MAX_HISTORY, holder.history.size)
        assertEquals((target - PaymentStateHolder.MAX_HISTORY + 1..target).toList(), holder.history.map { (it.to as PaymentState.Processing).attempt })
        println("STRESS [F1 collector consistency] observed=${observed.size} final=${holder.state.value} time=${m.duration.inWholeMilliseconds} ms")
    }
}
