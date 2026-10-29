package com.darshan.checkoutkit.core.state

import app.cash.turbine.test
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.testing.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PaymentStateHolderTest {

    @Test
    fun startsInCreated_withEmptyHistory() {
        val holder = PaymentStateHolder()
        assertEquals(PaymentState.Created, holder.state.value)
        assertTrue(holder.history.isEmpty())
    }

    @Test
    fun acceptedTransition_updatesStateAndRecordsTimestampedHistory() {
        val clock = TestClock(nowMillis = 1_000)
        val holder = PaymentStateHolder(clock = clock)

        holder.apply(PaymentEvent.Submit)
        clock.advanceBy(250)
        holder.apply(PaymentEvent.Succeed("txn_1"))

        assertEquals(PaymentState.Success("txn_1"), holder.state.value)
        assertEquals(
            listOf(
                StateTransition(PaymentState.Created, PaymentState.Processing(1), PaymentEvent.Submit, 1_000),
                StateTransition(PaymentState.Processing(1), PaymentState.Success("txn_1"), PaymentEvent.Succeed("txn_1"), 1_250),
            ),
            holder.history,
        )
    }

    @Test
    fun rejectedTransition_leavesStateAndHistoryUntouched() {
        val holder = PaymentStateHolder()
        val result = holder.apply(PaymentEvent.Refund("rf"))
        assertIs<TransitionResult.Rejected>(result)
        assertEquals(PaymentState.Created, holder.state.value)
        assertTrue(holder.history.isEmpty())
    }

    @Test
    fun stateFlow_emitsEveryAcceptedState() = runTest {
        val holder = PaymentStateHolder()
        holder.state.test {
            assertEquals(PaymentState.Created, awaitItem())
            holder.apply(PaymentEvent.Submit)
            assertEquals(PaymentState.Processing(1), awaitItem())
            holder.apply(PaymentEvent.AwaitConfirmation("txn_7"))
            assertEquals(PaymentState.Pending("txn_7"), awaitItem())
            holder.apply(PaymentEvent.Fail(CheckoutError.Declined))
            assertEquals(PaymentState.Failed(CheckoutError.Declined), awaitItem())
            holder.apply(PaymentEvent.Submit) // rejected: no emission
            expectNoEvents()
        }
    }

    @Test
    fun snapshot_isConsistentWithState() {
        val holder = PaymentStateHolder()
        holder.apply(PaymentEvent.Submit)
        val snap = holder.snapshot.value
        assertEquals(holder.state.value, snap.state)
        assertEquals(1, snap.history.size)
    }

    @Test
    fun concurrentSubmits_exactlyOneWins() = runTest {
        repeat(20) {
            val holder = PaymentStateHolder()
            val results = withContext(Dispatchers.Default) {
                (1..200).map { async { holder.apply(PaymentEvent.Submit) } }.awaitAll()
            }
            assertEquals(1, results.count { it is TransitionResult.Accepted })
            assertEquals(199, results.count { it is TransitionResult.Rejected })
            assertEquals(1, holder.history.size)
        }
    }

    @Test
    fun canStartFromAnyRestoredState() {
        val holder = PaymentStateHolder(initial = PaymentState.Pending("txn_r"))
        assertIs<TransitionResult.Accepted>(holder.apply(PaymentEvent.Succeed("txn_r")))
    }

    @Test
    fun history_isBoundedSoRetryStormsCannotGrowMemoryWithoutLimit() {
        val holder = PaymentStateHolder()
        holder.apply(PaymentEvent.Submit)
        for (attempt in 2..500) holder.apply(PaymentEvent.Retry(attempt))
        assertEquals(PaymentStateHolder.MAX_HISTORY, holder.history.size)
        // Oldest entries are dropped first; the newest is always kept.
        assertEquals(PaymentState.Processing(500), holder.history.last().to)
        assertEquals(PaymentState.Processing(500 - PaymentStateHolder.MAX_HISTORY + 1), holder.history.first().to)
    }
}
