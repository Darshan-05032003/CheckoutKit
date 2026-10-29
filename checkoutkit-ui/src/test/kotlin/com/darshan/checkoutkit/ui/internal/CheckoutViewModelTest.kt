package com.darshan.checkoutkit.ui.internal

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PendingPolicy
import com.darshan.checkoutkit.core.WalletProvider
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.gateway.FakeGateway
import com.darshan.checkoutkit.core.gateway.FakeGatewayConfig
import com.darshan.checkoutkit.core.gateway.FakeOutcome
import com.darshan.checkoutkit.core.retry.RetryPolicy
import com.darshan.checkoutkit.core.time.CheckoutClock
import com.darshan.checkoutkit.core.validation.CardBrand
import com.darshan.checkoutkit.core.validation.FieldError
import com.darshan.checkoutkit.ui.CheckoutSheetOptions
import com.darshan.checkoutkit.ui.PaymentMethodOption
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    // 2026-10-29T12:00:00Z
    private val clock = CheckoutClock { 1_793_275_200_000L }
    private val gateway = FakeGateway(FakeGatewayConfig(latency = 300.milliseconds), clock, Random(1))
    private val kit = CheckoutKit(
        gateway,
        CheckoutConfig(
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = 100.milliseconds, jitterFactor = 0.0, attemptTimeout = 2.seconds),
            pendingPolicy = PendingPolicy(initialInterval = 1.seconds, maxPolls = 5),
            dispatcher = dispatcher,
            clock = clock,
            random = Random(1),
        ),
    )
    private val request = CheckoutRequest("order-vm-1", 49_900, description = "Coffee beans")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle(), options: CheckoutSheetOptions = CheckoutSheetOptions()) =
        CheckoutViewModel(kit, saved, options)

    private fun TestScope.launched(vm: CheckoutViewModel = viewModel()): CheckoutViewModel {
        vm.launch(request)
        runCurrent()
        return vm
    }

    @Test
    fun launch_showsTheFormForTheRequest() = runTest(dispatcher) {
        val vm = launched()
        val ui = vm.uiState.value
        assertThat(ui.visible).isTrue()
        assertThat(ui.request).isEqualTo(request)
        assertThat(ui.phase).isEqualTo(SheetPhase.Form)
        assertThat(ui.tab).isEqualTo(PaymentMethodOption.UPI)
        assertThat(ui.tabs).containsExactly(PaymentMethodOption.UPI, PaymentMethodOption.CARD, PaymentMethodOption.WALLET).inOrder()
    }

    @Test
    fun invalidVpa_showsAnError_andNeverCallsTheGateway() = runTest(dispatcher) {
        val vm = launched()
        vm.onAction(CheckoutAction.VpaChanged("not-a-vpa"))
        vm.onAction(CheckoutAction.Pay)
        advanceUntilIdle()
        assertThat(vm.uiState.value.vpaError).isEqualTo(FieldError.INVALID_FORMAT)
        assertThat(vm.uiState.value.phase).isEqualTo(SheetPhase.Form)
        assertThat(gateway.chargeCalls).isEqualTo(0)
        // Editing the field clears its error.
        vm.onAction(CheckoutAction.VpaChanged("alice@okbank"))
        assertThat(vm.uiState.value.vpaError).isNull()
    }

    @Test
    fun upiSuccess_goesThroughProcessing_andDeliversTheResultOnDone() = runTest(dispatcher) {
        val vm = launched()
        vm.onAction(CheckoutAction.VpaChanged("alice@okbank"))
        vm.results.test {
            vm.onAction(CheckoutAction.Pay)
            assertThat(vm.uiState.value.phase).isEqualTo(SheetPhase.Processing(1))
            advanceUntilIdle()
            val phase = vm.uiState.value.phase
            assertThat(phase).isInstanceOf(SheetPhase.Success::class.java)
            expectNoEvents()
            vm.onAction(CheckoutAction.Close)
            val result = awaitItem()
            assertThat(result).isEqualTo(CheckoutResult.Success((phase as SheetPhase.Success).transactionId, 49_900))
        }
        assertThat(vm.uiState.value.visible).isFalse()
    }

    @Test
    fun doubleTapOnPay_chargesOnce() = runTest(dispatcher) {
        val vm = launched()
        vm.onAction(CheckoutAction.VpaChanged("alice@okbank"))
        repeat(10) { vm.onAction(CheckoutAction.Pay) }
        advanceUntilIdle()
        assertThat(gateway.chargeCalls).isEqualTo(1)
        assertThat(gateway.capturedPayments).hasSize(1)
    }

    @Test
    fun cardValidation_reportsEachFieldError() = runTest(dispatcher) {
        val vm = launched()
        vm.onAction(CheckoutAction.SelectTab(PaymentMethodOption.CARD))
        vm.onAction(CheckoutAction.CardNumberChanged("4242424242424241"))
        vm.onAction(CheckoutAction.ExpiryChanged("0925"))
        vm.onAction(CheckoutAction.CvvChanged("12"))
        vm.onAction(CheckoutAction.Pay)
        val ui = vm.uiState.value
        assertThat(ui.cardNumberError).isEqualTo(FieldError.INVALID_CHECKSUM)
        assertThat(ui.expiryError).isEqualTo(FieldError.EXPIRED)
        assertThat(ui.cvvError).isEqualTo(FieldError.WRONG_LENGTH)
        assertThat(ui.phase).isEqualTo(SheetPhase.Form)
    }

    @Test
    fun cardInputs_areFilteredAndCapped() = runTest(dispatcher) {
        val vm = launched()
        vm.onAction(CheckoutAction.CardNumberChanged("4242 4242-abcd 4242 4242 9999 9999"))
        vm.onAction(CheckoutAction.ExpiryChanged("12/309"))
        vm.onAction(CheckoutAction.CvvChanged("12a345"))
        val ui = vm.uiState.value
        assertThat(ui.cardNumber).isEqualTo("4242424242424242999")
        assertThat(ui.cardBrand).isEqualTo(CardBrand.VISA)
        assertThat(ui.expiry).isEqualTo("1230")
        assertThat(ui.cvv).isEqualTo("1234")
    }

    @Test
    fun validCard_pays_andCardDataIsWipedAfterwards() = runTest(dispatcher) {
        val vm = launched()
        vm.onAction(CheckoutAction.SelectTab(PaymentMethodOption.CARD))
        vm.onAction(CheckoutAction.CardNumberChanged("4242424242424242"))
        vm.onAction(CheckoutAction.ExpiryChanged("1230"))
        vm.onAction(CheckoutAction.CvvChanged("123"))
        vm.onAction(CheckoutAction.Pay)
        advanceUntilIdle()
        val ui = vm.uiState.value
        assertThat(ui.phase).isInstanceOf(SheetPhase.Success::class.java)
        assertThat(ui.cardNumber).isEmpty()
        assertThat(ui.cvv).isEmpty()
        assertThat(ui.expiry).isEmpty()
    }

    @Test
    fun wallet_mustBeSelected() = runTest(dispatcher) {
        val vm = launched()
        vm.onAction(CheckoutAction.SelectTab(PaymentMethodOption.WALLET))
        vm.onAction(CheckoutAction.Pay)
        assertThat(vm.uiState.value.walletError).isEqualTo(FieldError.NOT_SELECTED)
        vm.onAction(CheckoutAction.WalletSelected(WalletProvider.PAYTM))
        assertThat(vm.uiState.value.walletError).isNull()
        vm.onAction(CheckoutAction.Pay)
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isInstanceOf(SheetPhase.Success::class.java)
        assertThat(gateway.capturedPayments.single().methodType).isEqualTo("WALLET")
    }

    @Test
    fun decline_thenChangeMethod_startsAFreshAttemptWithANewKey() = runTest(dispatcher) {
        gateway.config = FakeGatewayConfig(outcome = FakeOutcome.DECLINED, latency = 300.milliseconds)
        val vm = launched()
        val firstKey = vm.currentKey
        vm.onAction(CheckoutAction.VpaChanged("alice@okbank"))
        vm.onAction(CheckoutAction.Pay)
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(SheetPhase.Failed(CheckoutError.Declined))
        vm.onAction(CheckoutAction.ChangeMethod)
        assertThat(vm.uiState.value.phase).isEqualTo(SheetPhase.Form)
        assertThat(vm.currentKey).isNotEqualTo(firstKey)
        assertThat(vm.uiState.value.vpa).isEqualTo("alice@okbank")
    }

    @Test
    fun transientFailure_thenRetry_reusesTheSameKey() = runTest(dispatcher) {
        gateway.config = FakeGatewayConfig(outcome = FakeOutcome.GATEWAY_ERROR, latency = 300.milliseconds)
        val vm = launched()
        val key = vm.currentKey
        vm.onAction(CheckoutAction.VpaChanged("alice@okbank"))
        vm.onAction(CheckoutAction.Pay)
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(SheetPhase.Failed(CheckoutError.GatewayError("HTTP_503")))
        gateway.config = FakeGatewayConfig(latency = 300.milliseconds)
        vm.onAction(CheckoutAction.Retry)
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isInstanceOf(SheetPhase.Success::class.java)
        assertThat(vm.currentKey).isEqualTo(key)
        assertThat(gateway.capturedPayments.single().idempotencyKey).isEqualTo(key)
    }

    @Test
    fun pendingThenSuccess_showsPendingFirst() = runTest(dispatcher) {
        gateway.config = FakeGatewayConfig(outcome = FakeOutcome.PENDING_THEN_SUCCESS, latency = 300.milliseconds, pendingPolls = 2)
        val vm = launched()
        vm.onAction(CheckoutAction.VpaChanged("alice@okbank"))
        vm.onAction(CheckoutAction.Pay)
        testScheduler.advanceTimeBy(500)
        runCurrent()
        assertThat(vm.uiState.value.phase).isInstanceOf(SheetPhase.Pending::class.java)
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isInstanceOf(SheetPhase.Success::class.java)
    }

    @Test
    fun closeOnTheForm_cancels_andDeliversCancelled() = runTest(dispatcher) {
        val vm = launched()
        vm.results.test {
            vm.onAction(CheckoutAction.Close)
            assertThat(awaitItem()).isEqualTo(CheckoutResult.Cancelled)
        }
        assertThat(vm.uiState.value.visible).isFalse()
        assertThat(gateway.chargeCalls).isEqualTo(0)
    }

    @Test
    fun closeWhileProcessing_isIgnored() = runTest(dispatcher) {
        val vm = launched()
        vm.onAction(CheckoutAction.VpaChanged("alice@okbank"))
        vm.onAction(CheckoutAction.Pay)
        runCurrent()
        assertThat(vm.uiState.value.canDismiss).isFalse()
        vm.onAction(CheckoutAction.Close)
        assertThat(vm.uiState.value.visible).isTrue()
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isInstanceOf(SheetPhase.Success::class.java)
    }

    @Test
    fun savedState_neverContainsCardNumberOrCvv() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = launched(viewModel(saved))
        vm.onAction(CheckoutAction.SelectTab(PaymentMethodOption.CARD))
        vm.onAction(CheckoutAction.CardNumberChanged("4242424242424242"))
        vm.onAction(CheckoutAction.CvvChanged("123"))
        vm.onAction(CheckoutAction.VpaChanged("alice@okbank"))
        val values = saved.keys().map { saved.get<Any>(it).toString() }
        assertThat(values.none { it.contains("4242424242424242") }).isTrue()
        assertThat(saved.keys().none { it.contains("cvv", ignoreCase = true) || it.contains("number", ignoreCase = true) }).isTrue()
        assertThat(values).contains("alice@okbank")
    }

    @Test
    fun processDeathMidPayment_isReconciledWithTheSavedKey_withoutChargingAgain() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = launched(viewModel(saved))
        vm.onAction(CheckoutAction.VpaChanged("alice@okbank"))
        vm.onAction(CheckoutAction.Pay)
        testScheduler.advanceTimeBy(200) // request reached the gateway (150 ms leg), answer not back yet
        runCurrent()
        val key = vm.currentKey

        // Process death: a new view model is created from the same saved state.
        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        advanceUntilIdle()
        assertThat(restored.uiState.value.visible).isTrue()
        assertThat(restored.uiState.value.phase).isInstanceOf(SheetPhase.Success::class.java)
        assertThat(restored.currentKey).isEqualTo(key)
        assertThat(gateway.capturedPayments).hasSize(1)
    }

    @Test
    fun relaunchWhileVisible_isIgnored_soRotationDoesNotRestartThePayment() = runTest(dispatcher) {
        val vm = launched()
        val key = vm.currentKey
        vm.launch(request)
        assertThat(vm.currentKey).isEqualTo(key)
    }

    @Test
    fun onlyTheConfiguredMethodsAreOffered() = runTest(dispatcher) {
        val vm = launched(viewModel(options = CheckoutSheetOptions(methods = listOf(PaymentMethodOption.CARD))))
        assertThat(vm.uiState.value.tabs).containsExactly(PaymentMethodOption.CARD)
        assertThat(vm.uiState.value.tab).isEqualTo(PaymentMethodOption.CARD)
        vm.onAction(CheckoutAction.SelectTab(PaymentMethodOption.UPI))
        assertThat(vm.uiState.value.tab).isEqualTo(PaymentMethodOption.CARD)
    }

    @Test
    fun timeoutWithNoCapture_endsAsTimeoutFailure() = runTest(dispatcher) {
        gateway.config = FakeGatewayConfig(outcome = FakeOutcome.TIMEOUT, latency = 300.milliseconds)
        val vm = launched()
        vm.onAction(CheckoutAction.VpaChanged("alice@okbank"))
        vm.onAction(CheckoutAction.Pay)
        advanceUntilIdle()
        // TIMEOUT never records a payment, so reconciliation learns it never landed.
        assertThat(vm.uiState.value.phase).isEqualTo(SheetPhase.Failed(CheckoutError.Timeout))
    }

    @Test
    fun capturedButUnreachable_isUnresolved_thenCheckStatusResolvesIt() = runTest(dispatcher) {
        gateway.config = FakeGatewayConfig(latency = 300.milliseconds, latencySpikeChance = 1.0, latencySpike = 10.seconds)
        val vm = launched()
        vm.onAction(CheckoutAction.VpaChanged("alice@okbank"))
        vm.onAction(CheckoutAction.Pay)
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(SheetPhase.Unresolved(CheckoutError.Timeout))
        assertThat(vm.uiState.value.canDismiss).isTrue()
        gateway.config = FakeGatewayConfig(latency = 300.milliseconds)
        vm.onAction(CheckoutAction.CheckStatus)
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isInstanceOf(SheetPhase.Success::class.java)
        assertThat(gateway.capturedPayments).hasSize(1)
    }
}
