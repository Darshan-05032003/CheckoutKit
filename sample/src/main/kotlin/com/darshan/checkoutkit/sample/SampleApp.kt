package com.darshan.checkoutkit.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darshan.checkoutkit.core.CheckoutResult
import com.darshan.checkoutkit.core.error.toUserMessage
import com.darshan.checkoutkit.core.money.Money
import com.darshan.checkoutkit.ui.CheckoutSheet
import com.darshan.checkoutkit.ui.CheckoutSheetOptions
import com.darshan.checkoutkit.ui.rememberCheckoutSheet

/** Test tags of the sample screen. */
object SampleTags {
    const val PAY = "sample_pay"
    const val RESULT = "sample_result"
    const val REFUND = "sample_refund"
    fun outcome(outcome: SampleOutcome) = "sample_outcome_${outcome.name}"
}

@Composable
fun SampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleApp(viewModel: SampleViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val sheet = rememberCheckoutSheet(
        checkoutKit = viewModel.dependencies.kit,
        options = CheckoutSheetOptions(merchantName = stringResource(R.string.sample_merchant)),
        onResult = viewModel::onCheckoutResult,
    )
    SampleTheme {
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.sample_intro), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.sample_step_outcome), style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup()) {
                    SampleOutcome.entries.forEach { outcome ->
                        OutcomeRow(outcome, selected = outcome == ui.outcome) { viewModel.selectOutcome(outcome) }
                    }
                }
                Text(stringResource(R.string.sample_step_pay), style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { sheet.launch(viewModel.nextRequest()) },
                    modifier = Modifier.fillMaxWidth().testTag(SampleTags.PAY),
                ) {
                    Text(stringResource(R.string.sample_pay, Money.formatInr(SampleViewModel.AMOUNT_PAISE)))
                }
                Text(stringResource(R.string.sample_magic_inputs), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.sample_step_result), style = MaterialTheme.typography.titleMedium)
                ResultCard(ui, onRefund = viewModel::refund)
            }
        }
        CheckoutSheet(sheet)
    }
}

@Composable
private fun OutcomeRow(outcome: SampleOutcome, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp)
            .testTag(SampleTags.outcome(outcome)),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(outcome.title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(outcome.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultCard(ui: SampleUiState, onRefund: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(SampleTags.RESULT)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val result = ui.lastResult
            if (result == null) {
                Text(stringResource(R.string.sample_result_empty))
                return@Column
            }
            Text(resultText(result), style = MaterialTheme.typography.bodyLarge)
            when (val refund = ui.refund) {
                RefundUiState.Idle -> Unit
                RefundUiState.InProgress -> {
                    val description = stringResource(R.string.sample_refund_in_progress)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = description })
                        Text(description)
                    }
                }
                is RefundUiState.Done -> Text(stringResource(R.string.sample_refund_done, refund.refundId))
                is RefundUiState.Error -> Text(stringResource(R.string.sample_refund_error, refund.code), color = MaterialTheme.colorScheme.error)
            }
            if (ui.canRefund) {
                OutlinedButton(onClick = onRefund, modifier = Modifier.testTag(SampleTags.REFUND)) {
                    Text(stringResource(R.string.sample_refund))
                }
            }
        }
    }
}

@Composable
private fun resultText(result: CheckoutResult): String = when (result) {
    is CheckoutResult.Success -> stringResource(R.string.sample_result_success, Money.formatInr(result.amountPaise), result.transactionId)
    is CheckoutResult.Pending -> stringResource(R.string.sample_result_pending, result.transactionId)
    is CheckoutResult.Failed -> stringResource(R.string.sample_result_failed, result.error.code, result.error.toUserMessage().title)
    is CheckoutResult.Unresolved -> stringResource(R.string.sample_result_unresolved, result.lastError.code)
    CheckoutResult.Cancelled -> stringResource(R.string.sample_result_cancelled)
    is CheckoutResult.Refunded -> stringResource(R.string.sample_refund_done, result.refundId)
    is CheckoutResult.Rejected -> stringResource(R.string.sample_result_rejected, result.error.code)
}
