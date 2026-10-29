package com.darshan.checkoutkit.ui.internal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.CheckoutSession
import com.darshan.checkoutkit.core.PaymentMethod
import com.darshan.checkoutkit.core.WalletProvider
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.idempotency.IdempotencyKey
import com.darshan.checkoutkit.core.state.PaymentState
import com.darshan.checkoutkit.core.validation.CardBrand
import com.darshan.checkoutkit.core.validation.CardValidator
import com.darshan.checkoutkit.core.validation.CvvValidator
import com.darshan.checkoutkit.core.validation.ExpiryValidator
import com.darshan.checkoutkit.core.validation.FieldError
import com.darshan.checkoutkit.core.validation.VpaValidator
import com.darshan.checkoutkit.ui.CheckoutSheetOptions
import com.darshan.checkoutkit.ui.PaymentMethodOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the sheet is showing. */
internal sealed interface SheetPhase {
    data object Form : SheetPhase
    data class Processing(val attempt: Int) : SheetPhase
    data class Pending(val transactionId: String) : SheetPhase
    data class Success(val transactionId: String) : SheetPhase
    data class Failed(val error: CheckoutError) : SheetPhase
    data class Unresolved(val error: CheckoutError) : SheetPhase
}

/** The single UI state of the payment sheet. Card number and CVV live only here, in memory. */
internal data class CheckoutUiState(
    val visible: Boolean = false,
    val request: CheckoutRequest? = null,
    val tabs: List<PaymentMethodOption> = PaymentMethodOption.entries,
    val tab: PaymentMethodOption = tabs.first(),
    val vpa: String = "",
    val vpaError: FieldError? = null,
    val cardNumber: String = "",
    val cardNumberError: FieldError? = null,
    val cardBrand: CardBrand = CardBrand.UNKNOWN,
    val expiry: String = "",
    val expiryError: FieldError? = null,
    val cvv: String = "",
    val cvvError: FieldError? = null,
    val wallet: WalletProvider? = null,
    val walletError: FieldError? = null,
    val phase: SheetPhase = SheetPhase.Form,
    val checking: Boolean = false,
) {
    /** The payer may close the sheet unless money is in flight. */
    val canDismiss: Boolean get() = phase !is SheetPhase.Processing && !checking
}

/** Everything the payer can do on the sheet. */
internal sealed interface CheckoutAction {
    data class SelectTab(val tab: PaymentMethodOption) : CheckoutAction
    data class VpaChanged(val text: String) : CheckoutAction
    data class CardNumberChanged(val text: String) : CheckoutAction
    data class ExpiryChanged(val text: String) : CheckoutAction
    data class CvvChanged(val text: String) : CheckoutAction
    data class WalletSelected(val provider: WalletProvider) : CheckoutAction
    data object Pay : CheckoutAction
    data object Retry : CheckoutAction
    data object ChangeMethod : CheckoutAction
    data object CheckStatus : CheckoutAction
    data object Close : CheckoutAction
}

/**
 * Drives the sheet: validates input, runs the [CheckoutSession], maps its state to [SheetPhase],
 * and survives configuration changes (it is a ViewModel) and process death (request, idempotency
 * key, tab, UPI id and wallet are kept in [SavedStateHandle]; card number and CVV never are).
 * After process death mid-payment it reconciles with the gateway using the saved key.
 */
internal class CheckoutViewModel(
    private val kit: CheckoutKit,
    private val saved: SavedStateHandle,
    private val options: CheckoutSheetOptions,
) : ViewModel() {

    private val expiryValidator = ExpiryValidator(kit.config.clock)
    private val _state = MutableStateFlow(emptyState())
    val uiState: StateFlow<CheckoutUiState> = _state.asStateFlow()

    private val _results = Channel<CheckoutResult>(Channel.BUFFERED)

    /** Final results, delivered once when the sheet closes. */
    val results: Flow<CheckoutResult> = _results.receiveAsFlow()

    private var session: CheckoutSession? = null
    private var lastMethod: PaymentMethod? = null
    private var lastResult: CheckoutResult? = null
    private var work: Job? = null
    private var watcher: Job? = null

    /** The idempotency key of the current attempt (for tests and diagnostics). */
    val currentKey: IdempotencyKey? get() = session?.idempotencyKey

    init {
        restore()
    }

    /** Opens the sheet for [request]. Ignored while a sheet is already open (e.g. after rotation). */
    fun launch(request: CheckoutRequest) {
        if (_state.value.visible) return
        session = kit.createSession(request)
        lastMethod = null
        lastResult = null
        _state.value = emptyState().copy(visible = true, request = request)
        persist(PHASE_FORM)
    }

    fun onAction(action: CheckoutAction) {
        when (action) {
            is CheckoutAction.SelectTab -> editForm { if (action.tab in it.tabs) it.copy(tab = action.tab) else it }
            is CheckoutAction.VpaChanged -> editForm {
                it.copy(vpa = action.text.filterNot { c -> c == '\n' || c == '\r' || c == '\t' }.take(VpaValidator.MAX_LENGTH), vpaError = null)
            }
            is CheckoutAction.CardNumberChanged -> editForm {
                val digits = action.text.filter { c -> c in '0'..'9' }.take(CardValidator.MAX_LENGTH)
                it.copy(cardNumber = digits, cardBrand = CardValidator.brandOf(digits), cardNumberError = null)
            }
            is CheckoutAction.ExpiryChanged -> editForm { it.copy(expiry = action.text.filter { c -> c in '0'..'9' }.take(4), expiryError = null) }
            is CheckoutAction.CvvChanged -> editForm { it.copy(cvv = action.text.filter { c -> c in '0'..'9' }.take(4), cvvError = null) }
            is CheckoutAction.WalletSelected -> editForm { it.copy(wallet = action.provider, walletError = null) }
            CheckoutAction.Pay -> pay()
            CheckoutAction.Retry -> retry()
            CheckoutAction.ChangeMethod -> changeMethod()
            CheckoutAction.CheckStatus -> checkStatus()
            CheckoutAction.Close -> close()
        }
        if (action !is CheckoutAction.Close && _state.value.visible) persist(null)
    }

    private fun editForm(transform: (CheckoutUiState) -> CheckoutUiState) {
        if (_state.value.phase == SheetPhase.Form) _state.update(transform)
    }

    private fun pay() {
        val ui = _state.value
        if (!ui.visible || ui.phase != SheetPhase.Form) return // double taps land here
        val method = validatedMethod(ui) ?: return
        submit(method)
    }

    private fun validatedMethod(ui: CheckoutUiState): PaymentMethod? = when (ui.tab) {
        PaymentMethodOption.UPI -> {
            val error = VpaValidator.validate(ui.vpa).errorOrNull
            if (error != null) {
                _state.update { it.copy(vpaError = error) }
                null
            } else {
                PaymentMethod.Upi(VpaValidator.normalise(ui.vpa))
            }
        }
        PaymentMethodOption.CARD -> {
            val numberError = CardValidator.validateNumber(ui.cardNumber).errorOrNull
            val expiryError = expiryValidator.validateText(ui.expiry).errorOrNull
            val cvvError = CvvValidator.validate(ui.cvv, ui.cardBrand).errorOrNull
            if (numberError != null || expiryError != null || cvvError != null) {
                _state.update { it.copy(cardNumberError = numberError, expiryError = expiryError, cvvError = cvvError) }
                null
            } else {
                val expiry = ExpiryValidator.parse(ui.expiry)!!
                PaymentMethod.Card(ui.cardNumber, expiry.month, expiry.year, ui.cvv)
            }
        }
        PaymentMethodOption.WALLET -> {
            val wallet = ui.wallet
            if (wallet == null) {
                _state.update { it.copy(walletError = FieldError.NOT_SELECTED) }
                null
            } else {
                PaymentMethod.Wallet(wallet)
            }
        }
    }

    private fun submit(method: PaymentMethod) {
        val current = session ?: return
        lastMethod = method
        _state.update { it.copy(phase = SheetPhase.Processing(1)) }
        persist(PHASE_IN_FLIGHT)
        watch(current)
        work = viewModelScope.launch { applyResult(current.submit(method)) }
    }

    private fun retry() {
        val ui = _state.value
        val failed = ui.phase as? SheetPhase.Failed ?: return
        val method = lastMethod ?: return
        val request = ui.request ?: return
        if (!failed.error.retryable) return
        // Same key: transient failures are not replayed, and the gateway deduplicates by key.
        session = kit.createSession(request, session!!.idempotencyKey)
        submit(method)
    }

    private fun changeMethod() {
        val ui = _state.value
        val request = ui.request ?: return
        if (ui.phase !is SheetPhase.Failed) return
        session = kit.createSession(request)
        lastMethod = null
        lastResult = null
        _state.update { it.copy(phase = SheetPhase.Form, cardNumber = "", cvv = "", expiry = "") }
        persist(PHASE_FORM)
    }

    private fun checkStatus() {
        val ui = _state.value
        val current = session ?: return
        if (ui.checking || (ui.phase !is SheetPhase.Pending && ui.phase !is SheetPhase.Unresolved)) return
        _state.update { it.copy(checking = true) }
        work = viewModelScope.launch {
            val result = current.checkStatus()
            _state.update { it.copy(checking = false) }
            applyResult(result)
        }
    }

    private fun close() {
        val ui = _state.value
        if (!ui.visible || !ui.canDismiss) return
        val result: CheckoutResult = when (val phase = ui.phase) {
            SheetPhase.Form -> {
                session?.cancel()
                CheckoutResult.Cancelled
            }
            is SheetPhase.Pending -> CheckoutResult.Pending(phase.transactionId)
            else -> lastResult ?: return
        }
        work?.cancel()
        watcher?.cancel()
        session = null
        lastMethod = null
        lastResult = null
        _state.value = emptyState()
        clearSaved()
        _results.trySend(result)
    }

    private fun applyResult(result: CheckoutResult) {
        lastResult = result
        val phase = when (result) {
            is CheckoutResult.Success -> SheetPhase.Success(result.transactionId)
            is CheckoutResult.Pending -> SheetPhase.Pending(result.transactionId)
            is CheckoutResult.Failed -> SheetPhase.Failed(result.error)
            is CheckoutResult.Unresolved -> SheetPhase.Unresolved(result.lastError)
            is CheckoutResult.Rejected -> SheetPhase.Failed(result.error)
            is CheckoutResult.Refunded -> SheetPhase.Success(result.transactionId)
            CheckoutResult.Cancelled -> {
                _state.update { it.copy(phase = SheetPhase.Form) }
                close()
                return
            }
        }
        // Card data is wiped as soon as the attempt is over (a retry reuses the in-memory method).
        _state.update { it.copy(phase = phase, cardNumber = "", cvv = "", expiry = "") }
        persist(PHASE_IN_FLIGHT)
    }

    /** Mirrors live session states (retry attempts, pending) while a submit is running. */
    private fun watch(current: CheckoutSession) {
        watcher?.cancel()
        watcher = viewModelScope.launch {
            current.state.collect { state ->
                val phase = _state.value.phase
                if (phase !is SheetPhase.Processing && phase !is SheetPhase.Pending) return@collect
                when (state) {
                    is PaymentState.Processing -> _state.update { it.copy(phase = SheetPhase.Processing(state.attempt)) }
                    is PaymentState.Pending -> _state.update { it.copy(phase = SheetPhase.Pending(state.transactionId)) }
                    else -> Unit
                }
            }
        }
    }

    private fun emptyState() = CheckoutUiState(tabs = options.methods, tab = options.methods.first())

    // --- process death ------------------------------------------------------------------------

    private fun persist(phase: String?) {
        val ui = _state.value
        val request = ui.request ?: return
        saved[K_VISIBLE] = ui.visible
        saved[K_ORDER] = request.orderId
        saved[K_AMOUNT] = request.amountPaise
        saved[K_DESCRIPTION] = request.description
        session?.let { saved[K_KEY] = it.idempotencyKey.value }
        saved[K_TAB] = ui.tab.name
        saved[K_VPA] = ui.vpa
        saved[K_WALLET] = ui.wallet?.name
        if (phase != null) saved[K_PHASE] = phase
    }

    private fun clearSaved() {
        ALL_KEYS.forEach { saved.remove<Any>(it) }
    }

    private fun restore() {
        if (saved.get<Boolean>(K_VISIBLE) != true) return
        val request = runCatching {
            CheckoutRequest(saved.get<String>(K_ORDER)!!, saved.get<Long>(K_AMOUNT)!!, description = saved.get<String>(K_DESCRIPTION).orEmpty())
        }.getOrNull()
        val key = saved.get<String>(K_KEY)?.takeIf { IdempotencyKey.isValid(it) }?.let(::IdempotencyKey)
        if (request == null || key == null) {
            clearSaved()
            return
        }
        val tab = runCatching { PaymentMethodOption.valueOf(saved.get<String>(K_TAB)!!) }.getOrNull()?.takeIf { it in options.methods }
        val wallet = runCatching { WalletProvider.valueOf(saved.get<String>(K_WALLET)!!) }.getOrNull()
        val base = emptyState().copy(
            visible = true,
            request = request,
            tab = tab ?: options.methods.first(),
            vpa = saved.get<String>(K_VPA).orEmpty().take(VpaValidator.MAX_LENGTH),
            wallet = wallet,
        )
        if (saved.get<String>(K_PHASE) == PHASE_IN_FLIGHT) {
            // The charge may have been sent before the process died: never charge again, ask the gateway.
            val resumed = kit.resumeSession(request, key)
            session = resumed
            _state.value = base.copy(phase = SheetPhase.Processing(1))
            watch(resumed)
            work = viewModelScope.launch { applyResult(resumed.checkStatus()) }
        } else {
            session = kit.createSession(request, key)
            _state.value = base
        }
    }

    private companion object {
        const val K_VISIBLE = "checkoutkit.visible"
        const val K_ORDER = "checkoutkit.orderId"
        const val K_AMOUNT = "checkoutkit.amountPaise"
        const val K_DESCRIPTION = "checkoutkit.description"
        const val K_KEY = "checkoutkit.key"
        const val K_PHASE = "checkoutkit.phase"
        const val K_TAB = "checkoutkit.tab"
        const val K_VPA = "checkoutkit.vpa"
        const val K_WALLET = "checkoutkit.wallet"
        val ALL_KEYS = listOf(K_VISIBLE, K_ORDER, K_AMOUNT, K_DESCRIPTION, K_KEY, K_PHASE, K_TAB, K_VPA, K_WALLET)

        const val PHASE_FORM = "FORM"
        const val PHASE_IN_FLIGHT = "IN_FLIGHT"
    }
}
