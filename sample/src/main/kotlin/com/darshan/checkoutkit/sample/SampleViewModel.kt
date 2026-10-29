package com.darshan.checkoutkit.sample

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.darshan.checkoutkit.core.CheckoutConfig
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.PendingPolicy
import com.darshan.checkoutkit.core.gateway.FakeGateway
import com.darshan.checkoutkit.core.gateway.FakeGatewayConfig
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.checkoutkit.core.retry.RetryPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The sandbox gateway and the SDK instance the sample uses. Plain constructor wiring, no DI framework. */
class SampleDependencies private constructor(
    val gateway: FakeGateway,
    val kit: CheckoutKit,
    val latency: Duration,
) {
    companion object {
        /** [fast] shortens timeouts and polling for tests. */
        fun create(
            latency: Duration = 800.milliseconds,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            fast: Boolean = false,
        ): SampleDependencies {
            val gateway = FakeGateway(FakeGatewayConfig(latency = latency))
            val config = CheckoutConfig(
                retryPolicy = if (fast) {
                    RetryPolicy(maxAttempts = 3, initialDelay = 20.milliseconds, maxDelay = 100.milliseconds, attemptTimeout = 500.milliseconds, totalTimeout = 5.seconds)
                } else {
                    RetryPolicy(maxAttempts = 3, initialDelay = 1.seconds, maxDelay = 4.seconds, attemptTimeout = 3.seconds, totalTimeout = 20.seconds)
                },
                pendingPolicy = if (fast) {
                    PendingPolicy(initialInterval = 50.milliseconds, maxInterval = 100.milliseconds, maxPolls = 20)
                } else {
                    PendingPolicy(initialInterval = 1500.milliseconds, maxInterval = 4.seconds, maxPolls = 15)
                },
                dispatcher = dispatcher,
            )
            return SampleDependencies(gateway, CheckoutKit(gateway, config), latency)
        }
    }
}

/** Refund progress for the last successful payment. */
sealed interface RefundUiState {
    data object Idle : RefundUiState
    data object InProgress : RefundUiState
    data class Done(val refundId: String) : RefundUiState
    data class Error(val code: String) : RefundUiState
}

/** Everything the sample screen shows. */
data class SampleUiState(
    val outcome: SampleOutcome = SampleOutcome.SUCCESS,
    val lastResult: CheckoutResult? = null,
    val refund: RefundUiState = RefundUiState.Idle,
    val refundKey: IdempotencyKey? = null,
) {
    val canRefund: Boolean
        get() = lastResult is CheckoutResult.Success && refund !is RefundUiState.Done && refund != RefundUiState.InProgress
}

/** Holds the selected outcome, the last checkout result and refund progress. */
class SampleViewModel(
    val dependencies: SampleDependencies = SampleDependencies.create(),
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SampleUiState(outcome = saved.get<String>(KEY_OUTCOME)?.let { runCatching { SampleOutcome.valueOf(it) }.getOrNull() } ?: SampleOutcome.SUCCESS),
    )
    val uiState: StateFlow<SampleUiState> = _uiState.asStateFlow()
    private var orderCounter = 0
    private val orderPrefix = "sample-" + Random.nextLong(0, Long.MAX_VALUE).toString(36)

    init {
        dependencies.gateway.config = _uiState.value.outcome.gatewayConfig(dependencies.latency)
    }

    fun selectOutcome(outcome: SampleOutcome) {
        dependencies.gateway.config = outcome.gatewayConfig(dependencies.latency)
        saved[KEY_OUTCOME] = outcome.name
        _uiState.update { it.copy(outcome = outcome) }
    }

    /** A new order for the next checkout. */
    fun nextRequest(): CheckoutRequest =
        CheckoutRequest(orderId = "$orderPrefix-${++orderCounter}", amountPaise = AMOUNT_PAISE, description = "CheckoutKit demo order")

    fun onCheckoutResult(result: CheckoutResult) {
        _uiState.update { it.copy(lastResult = result, refund = RefundUiState.Idle, refundKey = IdempotencyKey.generate()) }
    }

    fun refund() {
        val state = _uiState.value
        val paid = state.lastResult as? CheckoutResult.Success ?: return
        val key = state.refundKey ?: return
        if (!state.canRefund) return
        _uiState.update { it.copy(refund = RefundUiState.InProgress) }
        viewModelScope.launch {
            val refund = when (val r = dependencies.kit.refund(paid.transactionId, paid.amountPaise, key)) {
                is CheckoutResult.Refunded -> RefundUiState.Done(r.refundId)
                is CheckoutResult.Failed -> RefundUiState.Error(r.error.code)
                is CheckoutResult.Rejected -> RefundUiState.Error(r.error.code)
                else -> RefundUiState.Error("UNKNOWN")
            }
            _uiState.update { it.copy(refund = refund) }
        }
    }

    companion object {
        const val AMOUNT_PAISE: Long = 49_900
        private const val KEY_OUTCOME = "sample.outcome"

        val Factory = viewModelFactory {
            initializer { SampleViewModel(SampleDependencies.create(), createSavedStateHandle()) }
        }
    }
}
