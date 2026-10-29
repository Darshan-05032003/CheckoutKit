package com.darshan.checkoutkit.ui.stress

import androidx.lifecycle.SavedStateHandle
import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.gateway.FakeGateway
import com.darshan.checkoutkit.core.gateway.FakeGatewayConfig
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.ui.CheckoutSheetOptions
import com.darshan.checkoutkit.ui.PaymentMethodOption
import com.darshan.checkoutkit.ui.internal.CheckoutAction
import com.darshan.checkoutkit.ui.internal.CheckoutViewModel
import com.darshan.checkoutkit.ui.internal.SheetPhase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

@OptIn(ExperimentalCoroutinesApi::class)
class SheetViewModelStressTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun kit(gateway: FakeGateway) = CheckoutKit(gateway, CheckoutConfig(retryPolicy = RetryPolicy.NoRetry, dispatcher = dispatcher, random = Random(1), maxIdempotencyEntries = 2_000))

    @Test
    fun thousandPayTapsAndTenThousandKeystrokes_chargeOnce() = runTest(dispatcher) {
        val gateway = FakeGateway(FakeGatewayConfig(latency = 500.milliseconds), random = Random(1))
        val vm = CheckoutViewModel(kit(gateway), SavedStateHandle(), CheckoutSheetOptions())
        vm.launch(CheckoutRequest("order-storm", 10_000))
        val time = measureTime {
            repeat(10_000) { i -> vm.onAction(CheckoutAction.VpaChanged("user${i % 97}@okbank")) }
            repeat(1_000) { vm.onAction(CheckoutAction.Pay) }
            advanceUntilIdle()
        }
        println("STRESS [F5 UI] 10k keystrokes + 1k Pay taps on one sheet: ${time.inWholeMilliseconds} ms, gateway charges=${gateway.chargeCalls}")
        assertThat(gateway.chargeCalls).isEqualTo(1)
        assertThat(vm.uiState.value.phase).isInstanceOf(SheetPhase.Success::class.java)
    }

    @Test
    fun thousandSheetsOpenedPaidAndClosedInARow_noLeakedWork() = runTest(dispatcher) {
        val gateway = FakeGateway(FakeGatewayConfig(latency = 50.milliseconds), random = Random(2))
        val saved = SavedStateHandle()
        val vm = CheckoutViewModel(kit(gateway), saved, CheckoutSheetOptions(methods = listOf(PaymentMethodOption.UPI)))
        val results = mutableListOf<CheckoutResult>()
        val collector = launch { vm.results.collect { results += it } }
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val time = measureTime {
            repeat(1_000) { i ->
                vm.launch(CheckoutRequest("order-$i", 100L + i))
                vm.onAction(CheckoutAction.VpaChanged("p$i@okbank"))
                vm.onAction(CheckoutAction.Pay)
                advanceUntilIdle()
                vm.onAction(CheckoutAction.Close)
                runCurrent()
            }
        }
        System.gc()
        val after = runtime.totalMemory() - runtime.freeMemory()
        println("STRESS [F5 UI] 1,000 sequential sheets: ${time.inWholeMilliseconds} ms, heap delta ${(after - before) / 1024 / 1024} MB, saved keys left=${saved.keys().size}")
        collector.cancel()
        assertThat(results).hasSize(1_000)
        assertThat(results.all { it is CheckoutResult.Success }).isTrue()
        assertThat(gateway.capturedPayments).hasSize(1_000)
        assertThat(saved.keys()).isEmpty()
    }
}
