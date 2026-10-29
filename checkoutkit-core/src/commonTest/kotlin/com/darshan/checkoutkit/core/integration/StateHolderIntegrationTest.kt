package com.darshan.checkoutkit.core.integration

import app.cash.turbine.test
import com.darshan.checkoutkit.core.state.PaymentEvent
import com.darshan.checkoutkit.core.state.PaymentState
import com.darshan.checkoutkit.core.state.PaymentStateHolder
import com.darshan.checkoutkit.core.state.TransitionResult
import com.darshan.checkoutkit.core.testing.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Holder + clock + live StateFlow observer + real multi-threaded dispatcher, wired together. */
class StateHolderIntegrationTest {

    @Test
    fun fullLifecycle_observedByCollector_withClockTimestamps() = runTest {
        val clock = TestClock(nowMillis = 0)
        val holder = PaymentStateHolder(clock = clock)
        holder.state.test {
            assertEquals(PaymentState.Created, awaitItem())
            listOf(
                PaymentEvent.Submit,
                PaymentEvent.Retry(2),
                PaymentEvent.AwaitConfirmation("txn_a"),
                PaymentEvent.Succeed("txn_a"),
                PaymentEvent.Refund("rf_a"),
            ).forEach { event ->
                clock.advanceBy(100)
                assertIs<TransitionResult.Accepted>(holder.apply(event))
            }
            assertEquals(PaymentState.Processing(1), awaitItem())
            assertEquals(PaymentState.Processing(2), awaitItem())
            assertEquals(PaymentState.Pending("txn_a"), awaitItem())
            assertEquals(PaymentState.Success("txn_a"), awaitItem())
            assertEquals(PaymentState.Refunded("txn_a", "rf_a"), awaitItem())
        }
        assertEquals(listOf(100L, 200L, 300L, 400L, 500L), holder.history.map { it.atMillis })
    }

    @Test
    fun lateGatewaySuccessRacingPayerCancel_onlyOneOutcomeIsRecorded() = runTest {
        repeat(50) {
            val holder = PaymentStateHolder(initial = PaymentState.Pending("txn_r"))
            val results = withContext(Dispatchers.Default) {
                listOf(
                    async { holder.apply(PaymentEvent.Succeed("txn_r")) },
                    async { holder.apply(PaymentEvent.Cancel) },
                ).awaitAll()
            }
            assertEquals(1, results.count { it is TransitionResult.Accepted })
            assertEquals(1, holder.history.size)
            val final = holder.state.value
            assertEquals(true, final == PaymentState.Cancelled || final == PaymentState.Success("txn_r"))
        }
    }
}
