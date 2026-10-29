package com.darshan.checkoutkit.ui.internal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.darshan.checkoutkit.core.WalletProvider
import com.darshan.checkoutkit.core.error.CheckoutError
import com.darshan.checkoutkit.core.error.RecoveryAction
import com.darshan.checkoutkit.core.error.toUserMessage
import com.darshan.checkoutkit.core.money.Money
import com.darshan.checkoutkit.core.validation.CardBrand
import com.darshan.checkoutkit.ui.CheckoutSheetOptions
import com.darshan.checkoutkit.ui.CheckoutSheetTestTags
import com.darshan.checkoutkit.ui.PaymentMethodOption
import com.darshan.checkoutkit.ui.R

/** Stateless body of the payment sheet; everything comes from [state] and goes out via [onAction]. */
@Composable
internal fun CheckoutSheetContent(
    state: CheckoutUiState,
    options: CheckoutSheetOptions,
    onAction: (CheckoutAction) -> Unit,
) {
    // Tapjacking defence: ignore touches while another app's window overlays the sheet.
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = previous }
    }
    val amount = state.request?.let { Money.formatInr(it.amountPaise) }.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(state, options, amount, onAction)
        when (val phase = state.phase) {
            SheetPhase.Form -> Form(state, amount, onAction)
            is SheetPhase.Processing -> Progress(
                tag = CheckoutSheetTestTags.PROCESSING,
                title = stringResource(R.string.checkoutkit_processing_title),
                message = stringResource(R.string.checkoutkit_processing_message) +
                    if (phase.attempt > 1) "\n" + stringResource(R.string.checkoutkit_processing_retry, phase.attempt) else "",
            )
            is SheetPhase.Pending -> {
                Progress(
                    tag = CheckoutSheetTestTags.PENDING,
                    title = stringResource(R.string.checkoutkit_pending_title),
                    message = stringResource(R.string.checkoutkit_pending_message),
                )
                CheckStatusButton(state.checking, onAction)
            }
            is SheetPhase.Success -> Outcome(
                tag = CheckoutSheetTestTags.SUCCESS,
                title = stringResource(R.string.checkoutkit_success_title),
                message = stringResource(R.string.checkoutkit_success_message, amount, phase.transactionId),
            ) {
                Button(onClick = { onAction(CheckoutAction.Close) }, modifier = Modifier.fillMaxWidth().testTag(CheckoutSheetTestTags.PRIMARY_ACTION)) {
                    Text(stringResource(R.string.checkoutkit_done))
                }
            }
            is SheetPhase.Failed -> Outcome(
                tag = CheckoutSheetTestTags.FAILED,
                title = stringResource(ErrorStrings.titleOf(phase.error)),
                message = stringResource(ErrorStrings.messageOf(phase.error)),
            ) {
                FailureActions(phase.error, onAction)
            }
            is SheetPhase.Unresolved -> Outcome(
                tag = CheckoutSheetTestTags.UNRESOLVED,
                title = stringResource(R.string.checkoutkit_unresolved_title),
                message = stringResource(R.string.checkoutkit_unresolved_message),
            ) {
                CheckStatusButton(state.checking, onAction)
            }
        }
        Text(
            text = stringResource(R.string.checkoutkit_secured_by),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header(state: CheckoutUiState, options: CheckoutSheetOptions, amount: String, onAction: (CheckoutAction) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = options.merchantName ?: stringResource(R.string.checkoutkit_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            state.request?.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val label = stringResource(R.string.checkoutkit_amount_label)
            Text(
                text = amount,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { contentDescription = "$label $amount" },
            )
        }
        if (state.canDismiss) {
            TextButton(onClick = { onAction(CheckoutAction.Close) }, modifier = Modifier.testTag(CheckoutSheetTestTags.CLOSE_BUTTON)) {
                Text(stringResource(if (state.phase == SheetPhase.Form) R.string.checkoutkit_cancel else R.string.checkoutkit_close))
            }
        }
    }
}

@Composable
private fun Form(state: CheckoutUiState, amount: String, onAction: (CheckoutAction) -> Unit) {
    if (state.tabs.size > 1) {
        SecondaryTabRow(selectedTabIndex = state.tabs.indexOf(state.tab).coerceAtLeast(0)) {
            state.tabs.forEach { option ->
                Tab(
                    selected = option == state.tab,
                    onClick = { onAction(CheckoutAction.SelectTab(option)) },
                    text = { Text(stringResource(option.labelRes())) },
                    modifier = Modifier.testTag(CheckoutSheetTestTags.tab(option.name)),
                )
            }
        }
    }
    when (state.tab) {
        PaymentMethodOption.UPI -> UpiForm(state, onAction)
        PaymentMethodOption.CARD -> CardForm(state, onAction)
        PaymentMethodOption.WALLET -> WalletForm(state, onAction)
    }
    Button(
        onClick = { onAction(CheckoutAction.Pay) },
        enabled = state.phase == SheetPhase.Form,
        modifier = Modifier.fillMaxWidth().testTag(CheckoutSheetTestTags.PAY_BUTTON),
    ) {
        Text(stringResource(R.string.checkoutkit_pay_amount, amount))
    }
}

@Composable
private fun UpiForm(state: CheckoutUiState, onAction: (CheckoutAction) -> Unit) {
    OutlinedTextField(
        value = state.vpa,
        onValueChange = { onAction(CheckoutAction.VpaChanged(it)) },
        label = { Text(stringResource(R.string.checkoutkit_vpa_label)) },
        placeholder = { Text(stringResource(R.string.checkoutkit_vpa_placeholder)) },
        singleLine = true,
        isError = state.vpaError != null,
        supportingText = {
            Text(
                state.vpaError?.let { stringResource(ErrorStrings.fieldErrorOf(it, InputField.VPA)) }
                    ?: stringResource(R.string.checkoutkit_vpa_hint),
            )
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth().testTag(CheckoutSheetTestTags.VPA_FIELD),
    )
}

@Composable
private fun CardForm(state: CheckoutUiState, onAction: (CheckoutAction) -> Unit) {
    OutlinedTextField(
        value = state.cardNumber,
        onValueChange = { onAction(CheckoutAction.CardNumberChanged(it)) },
        label = { Text(stringResource(R.string.checkoutkit_card_number_label)) },
        singleLine = true,
        isError = state.cardNumberError != null,
        visualTransformation = CardNumberTransformation(state.cardBrand),
        supportingText = {
            val error = state.cardNumberError
            when {
                error != null -> Text(stringResource(ErrorStrings.fieldErrorOf(error, InputField.CARD_NUMBER)))
                state.cardBrand != CardBrand.UNKNOWN -> Text(stringResource(R.string.checkoutkit_card_brand, state.cardBrand.displayName))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(CheckoutSheetTestTags.CARD_NUMBER_FIELD),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.expiry,
            onValueChange = { onAction(CheckoutAction.ExpiryChanged(it)) },
            label = { Text(stringResource(R.string.checkoutkit_expiry_label)) },
            singleLine = true,
            isError = state.expiryError != null,
            visualTransformation = ExpiryTransformation,
            supportingText = state.expiryError?.let { e -> { Text(stringResource(ErrorStrings.fieldErrorOf(e, InputField.EXPIRY))) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f).testTag(CheckoutSheetTestTags.EXPIRY_FIELD),
        )
        val cvvDescription = stringResource(R.string.checkoutkit_cvv_hidden_description)
        OutlinedTextField(
            value = state.cvv,
            onValueChange = { onAction(CheckoutAction.CvvChanged(it)) },
            label = { Text(stringResource(R.string.checkoutkit_cvv_label)) },
            singleLine = true,
            isError = state.cvvError != null,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = state.cvvError?.let { e -> { Text(stringResource(ErrorStrings.fieldErrorOf(e, InputField.CVV))) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier = Modifier
                .weight(1f)
                .testTag(CheckoutSheetTestTags.CVV_FIELD)
                .semantics { contentDescription = cvvDescription },
        )
    }
}

@Composable
private fun WalletForm(state: CheckoutUiState, onAction: (CheckoutAction) -> Unit) {
    Text(stringResource(R.string.checkoutkit_wallet_choose), style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup()) {
        WalletProvider.entries.forEach { provider ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = state.wallet == provider, role = Role.RadioButton, onClick = { onAction(CheckoutAction.WalletSelected(provider)) })
                    .padding(vertical = 8.dp)
                    .testTag(CheckoutSheetTestTags.wallet(provider)),
            ) {
                RadioButton(selected = state.wallet == provider, onClick = null)
                Text(provider.displayName, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
    state.walletError?.let {
        Text(stringResource(ErrorStrings.fieldErrorOf(it, InputField.WALLET)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Progress(tag: String, title: String, message: String) {
    val description = stringResource(R.string.checkoutkit_progress_description)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag(tag),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp).semantics { contentDescription = description })
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Outcome(tag: String, title: String, message: String, actions: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        actions()
    }
}

@Composable
private fun FailureActions(error: CheckoutError, onAction: (CheckoutAction) -> Unit) {
    val action = error.toUserMessage().action
    val primary = when (action) {
        RecoveryAction.RETRY -> CheckoutAction.Retry
        RecoveryAction.CHANGE_METHOD, RecoveryAction.EDIT_DETAILS -> CheckoutAction.ChangeMethod
        RecoveryAction.CONTACT_SUPPORT -> CheckoutAction.Close
    }
    Button(onClick = { onAction(primary) }, modifier = Modifier.fillMaxWidth().testTag(CheckoutSheetTestTags.PRIMARY_ACTION)) {
        Text(stringResource(ErrorStrings.actionLabelOf(action)))
    }
}

@Composable
private fun CheckStatusButton(checking: Boolean, onAction: (CheckoutAction) -> Unit) {
    OutlinedButton(
        onClick = { onAction(CheckoutAction.CheckStatus) },
        enabled = !checking,
        modifier = Modifier.fillMaxWidth().testTag(CheckoutSheetTestTags.CHECK_STATUS),
    ) {
        Text(stringResource(R.string.checkoutkit_check_status))
    }
}

private fun PaymentMethodOption.labelRes(): Int = when (this) {
    PaymentMethodOption.UPI -> R.string.checkoutkit_tab_upi
    PaymentMethodOption.CARD -> R.string.checkoutkit_tab_card
    PaymentMethodOption.WALLET -> R.string.checkoutkit_tab_wallet
}

