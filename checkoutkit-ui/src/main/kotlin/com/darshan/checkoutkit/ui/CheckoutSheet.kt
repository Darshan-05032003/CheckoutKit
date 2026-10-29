package com.darshan.checkoutkit.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.darshan.checkoutkit.core.CheckoutKit
import com.darshan.checkoutkit.core.CheckoutRequest
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.WalletProvider
import com.darshan.checkoutkit.ui.internal.CheckoutAction
import com.darshan.checkoutkit.ui.internal.CheckoutSheetContent
import com.darshan.checkoutkit.ui.internal.CheckoutViewModel

/** Payment methods the sheet can offer, as tabs, in the order given. */
public enum class PaymentMethodOption {
    /** UPI collect request to a VPA. */
    UPI,

    /** Credit/debit card (Visa, Mastercard, RuPay, American Express). */
    CARD,

    /** Prepaid wallet. */
    WALLET,
}

/**
 * Appearance and behaviour of the payment sheet.
 *
 * @property methods tabs to show, in order; must not be empty.
 * @property merchantName shown in the sheet header.
 * @property secureWindow when `true` (default) the sheet's window sets `FLAG_SECURE`, so card
 *   details can't be captured by screenshots, screen recording or the recent-apps thumbnail.
 */
public data class CheckoutSheetOptions(
    val methods: List<PaymentMethodOption> = PaymentMethodOption.entries,
    val merchantName: String? = null,
    val secureWindow: Boolean = true,
) {
    init {
        require(methods.isNotEmpty()) { "at least one payment method is required" }
        require(methods.distinct().size == methods.size) { "methods must not repeat" }
    }
}

/**
 * Controls one payment sheet. Get it from [rememberCheckoutSheet], show it with [CheckoutSheet],
 * open it with [launch].
 */
@Stable
public class CheckoutSheetState internal constructor(
    internal val viewModel: CheckoutViewModel,
    internal val options: CheckoutSheetOptions,
) {
    /** `true` while the sheet is open. Observable from composition. */
    public val isVisible: Boolean get() = viewModel.uiState.value.visible

    /**
     * Opens the sheet to pay for [request]. A new checkout attempt (with a new idempotency key)
     * starts every time; calls while the sheet is already open are ignored.
     */
    public fun launch(request: CheckoutRequest) {
        viewModel.launch(request)
    }

    /**
     * Closes the sheet as if the payer pressed Close. Ignored while a payment is processing, because
     * money may be in flight.
     */
    public fun dismiss() {
        viewModel.onAction(CheckoutAction.Close)
    }
}

/**
 * Creates (or, after rotation and process death, restores) a payment sheet bound to [checkoutKit].
 *
 * [onResult] is called exactly once per launch, when the sheet closes, with the final
 * [CheckoutResult]: `Success`, `Pending` (closed while waiting for the payer), `Failed`,
 * `Unresolved` (the outcome could not be confirmed; don't let the payer pay again) or `Cancelled`.
 *
 * ```
 * val sheet = rememberCheckoutSheet(kit) { result -> viewModel.onCheckoutResult(result) }
 * Button(onClick = { sheet.launch(CheckoutRequest("order-42", 49_900)) }) { Text("Pay") }
 * CheckoutSheet(sheet)
 * ```
 *
 * Create the [CheckoutKit] once (for example in your Application or DI graph) and pass the same
 * instance every time.
 */
@Composable
public fun rememberCheckoutSheet(
    checkoutKit: CheckoutKit,
    options: CheckoutSheetOptions = CheckoutSheetOptions(),
    onResult: (CheckoutResult) -> Unit,
): CheckoutSheetState {
    val factory = remember(checkoutKit, options) {
        viewModelFactory { initializer { CheckoutViewModel(checkoutKit, createSavedStateHandle(), options) } }
    }
    val viewModel: CheckoutViewModel = viewModel(key = VIEW_MODEL_KEY, factory = factory)
    val state = remember(viewModel, options) { CheckoutSheetState(viewModel, options) }
    val currentOnResult by rememberUpdatedState(onResult)
    LaunchedEffect(viewModel) {
        viewModel.results.collect { currentOnResult(it) }
    }
    return state
}

/**
 * The drop-in payment sheet: a modal bottom sheet with UPI, card and wallet tabs, input validation
 * (VPA format, Luhn, expiry, CVV), and live processing / pending / success / failure states bound
 * to the CheckoutKit state machine. Place it once in the screen that calls [CheckoutSheetState.launch].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun CheckoutSheet(state: CheckoutSheetState, modifier: Modifier = Modifier) {
    val ui by state.viewModel.uiState.collectAsStateWithLifecycle()
    if (!ui.visible) return
    val viewModel = state.viewModel
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || viewModel.uiState.value.canDismiss },
    )
    ModalBottomSheet(
        onDismissRequest = { viewModel.onAction(CheckoutAction.Close) },
        modifier = modifier.testTag(CheckoutSheetTestTags.SHEET),
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(
            securePolicy = if (state.options.secureWindow) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
            shouldDismissOnBackPress = ui.canDismiss,
            shouldDismissOnClickOutside = ui.canDismiss,
        ),
    ) {
        CheckoutSheetContent(ui, state.options, viewModel::onAction)
    }
}

/**
 * Semantics test tags of the sheet, so host apps can drive it from their own UI tests
 * (`composeRule.onNodeWithTag(CheckoutSheetTestTags.PAY_BUTTON).performClick()`).
 */
public object CheckoutSheetTestTags {
    public const val SHEET: String = "checkoutkit_sheet"
    public const val VPA_FIELD: String = "checkoutkit_vpa"
    public const val CARD_NUMBER_FIELD: String = "checkoutkit_card_number"
    public const val EXPIRY_FIELD: String = "checkoutkit_expiry"
    public const val CVV_FIELD: String = "checkoutkit_cvv"
    public const val PAY_BUTTON: String = "checkoutkit_pay"
    public const val PROCESSING: String = "checkoutkit_processing"
    public const val PENDING: String = "checkoutkit_pending"
    public const val SUCCESS: String = "checkoutkit_success"
    public const val FAILED: String = "checkoutkit_failed"
    public const val UNRESOLVED: String = "checkoutkit_unresolved"
    public const val PRIMARY_ACTION: String = "checkoutkit_primary_action"
    public const val CHECK_STATUS: String = "checkoutkit_check_status"
    public const val CLOSE_BUTTON: String = "checkoutkit_close"

    /** Tag of the tab for [option] (`"UPI"`, `"CARD"`, `"WALLET"`). */
    public fun tab(option: String): String = "checkoutkit_tab_$option"

    /** Tag of the wallet row for [provider]. */
    public fun wallet(provider: WalletProvider): String = "checkoutkit_wallet_${provider.name}"
}

private const val VIEW_MODEL_KEY = "com.darshan.checkoutkit.ui.CheckoutSheet"
