package com.darshan.checkoutkit.sample

import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.FakeOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SampleViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SampleViewModel(SampleDependencies.create(latency = 100.milliseconds, dispatcher = dispatcher, fast = true))

    @Test
    fun startsEmpty_withSuccessSelected() {
        val vm = viewModel()
        assertThat(vm.uiState.value.outcome).isEqualTo(SampleOutcome.SUCCESS)
        assertThat(vm.uiState.value.lastResult).isNull()
        assertThat(vm.uiState.value.refund).isEqualTo(RefundUiState.Idle)
    }

    @Test
    fun selectingAnOutcome_reconfiguresTheFakeGateway() {
        val vm = viewModel()
        vm.selectOutcome(SampleOutcome.DECLINED)
        assertThat(vm.uiState.value.outcome).isEqualTo(SampleOutcome.DECLINED)
        assertThat(vm.dependencies.gateway.config.outcome).isEqualTo(FakeOutcome.DECLINED)
    }

    @Test
    fun everyOutcome_producesTheExpectedResultThroughTheRealCheckoutStack() = runTest(dispatcher) {
        val expected = mapOf(
            SampleOutcome.SUCCESS to CheckoutResult.Success::class,
            SampleOutcome.PENDING_THEN_SUCCESS to CheckoutResult.Success::class,
            SampleOutcome.NETWORK_RETRY to CheckoutResult.Success::class,
            SampleOutcome.REFUND to CheckoutResult.Success::class,
            SampleOutcome.DECLINED to CheckoutResult.Failed::class,
            SampleOutcome.INSUFFICIENT_FUNDS to CheckoutResult.Failed::class,
            SampleOutcome.INVALID_VPA to CheckoutResult.Failed::class,
            SampleOutcome.TIMEOUT to CheckoutResult.Failed::class,
            SampleOutcome.GATEWAY_ERROR to CheckoutResult.Failed::class,
            SampleOutcome.CANCEL to CheckoutResult.Cancelled::class,
        )
        val vm = viewModel()
        val errors = mutableMapOf<SampleOutcome, CheckoutError>()
        expected.forEach { (outcome, type) ->
            vm.selectOutcome(outcome)
            var result: CheckoutResult? = null
            val session = vm.dependencies.kit.createSession(vm.nextRequest())
            launch { result = session.submit(PaymentMethod.Upi("alice@okbank")) }
            advanceUntilIdle()
            assertThat(result).isInstanceOf(type.java)
            (result as? CheckoutResult.Failed)?.let { errors[outcome] = it.error }
        }
        assertThat(errors[SampleOutcome.DECLINED]).isEqualTo(CheckoutError.Declined)
        assertThat(errors[SampleOutcome.INSUFFICIENT_FUNDS]).isEqualTo(CheckoutError.InsufficientFunds)
        assertThat(errors[SampleOutcome.INVALID_VPA]).isEqualTo(CheckoutError.InvalidVpa)
        assertThat(errors[SampleOutcome.TIMEOUT]).isEqualTo(CheckoutError.Timeout)
        assertThat(errors[SampleOutcome.GATEWAY_ERROR]).isEqualTo(CheckoutError.GatewayError("HTTP_503"))
    }

    @Test
    fun successThenRefund() = runTest(dispatcher) {
        val vm = viewModel()
        vm.selectOutcome(SampleOutcome.REFUND)
        var paid: CheckoutResult? = null
        launch { paid = vm.dependencies.kit.createSession(vm.nextRequest()).submit(PaymentMethod.Upi("a@okbank")) }
        advanceUntilIdle()
        vm.onCheckoutResult(paid!!)
        assertThat(vm.uiState.value.canRefund).isTrue()
        vm.refund()
        assertThat(vm.uiState.value.refund).isEqualTo(RefundUiState.InProgress)
        advanceUntilIdle()
        assertThat(vm.uiState.value.refund).isInstanceOf(RefundUiState.Done::class.java)
        assertThat(vm.uiState.value.canRefund).isFalse()
        assertThat(vm.dependencies.gateway.refundedPayments).hasSize(1)
    }

    @Test
    fun refundOfAPaymentTheGatewayDoesNotKnow_showsAnError() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onCheckoutResult(CheckoutResult.Success("fake_txn_missing", 49_900))
        vm.refund()
        advanceUntilIdle()
        assertThat(vm.uiState.value.refund).isEqualTo(RefundUiState.Error("INVALID_REQUEST"))
    }

    @Test
    fun ordersGetUniqueIds() {
        val vm = viewModel()
        val ids = (1..50).map { vm.nextRequest().orderId }
        assertThat(ids.toSet()).hasSize(50)
        assertThat(vm.nextRequest().amountPaise).isEqualTo(SampleViewModel.AMOUNT_PAISE)
    }
}
