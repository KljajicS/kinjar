package app.kinjar.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kinjar.R
import app.kinjar.domain.TransactionType
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.formatAmount
import app.kinjar.ui.formatDate
import app.kinjar.ui.scan.ReceiptDetails
import app.kinjar.ui.scan.ReceiptOpenAction
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.RoundIconButton
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans

@Composable
internal fun TransactionDetails(s: AddState, onClose: () -> Unit, onEdit: () -> Unit) {
    val transaction = s.editing ?: return
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    Column(Modifier.fillMaxSize().background(c.bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            RoundIconButton(Icons.Default.Close, stringResource(R.string.add_close), onClose)
            Text(stringResource(R.string.transaction_details), style = sans(15, FontWeight.Medium), color = c.text)
            TextButton(onClick = onEdit, enabled = !s.loanLocked) {
                Text(stringResource(R.string.transaction_edit), color = if (s.loanLocked) c.muted else c.primary)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(when (transaction.type) {
                    TransactionType.EXPENSE -> R.string.add_expense
                    TransactionType.INCOME -> R.string.add_income
                    TransactionType.TRANSFER -> R.string.add_transfer
                    TransactionType.LOAN_RECEIPT -> R.string.loan_received
                }), style = sans(13), color = c.muted)
                Text(formatAmount(transaction.money, locale = locale), style = mono(40, FontWeight.SemiBold), color = c.text)
                Text(transaction.money.currency.name, style = sans(14), color = c.muted)
                transaction.received?.let { received ->
                    Text("→ ${formatAmount(received, locale = locale)} ${received.currency}", style = mono(20, FontWeight.Medium), color = c.text)
                }
                if (transaction.merchant.isNotBlank()) Text(transaction.merchant, style = sans(18, FontWeight.Medium), color = c.text)
            }
            OutlinedBox(Modifier.fillMaxWidth(), radius = 14, padding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailRow(stringResource(R.string.add_date), formatDate(transaction.date, locale))
                    HorizontalDivider(color = c.border)
                    val account = s.accounts.firstOrNull { it.id == transaction.accountId }
                    DetailRow(stringResource(R.string.add_account), account?.name ?: stringResource(R.string.transaction_unavailable))
                    if (transaction.type == TransactionType.TRANSFER) {
                        val destination = s.accounts.firstOrNull { it.id == transaction.toAccountId }
                        DetailRow(stringResource(R.string.add_to_account), destination?.name ?: stringResource(R.string.transaction_unavailable))
                        transaction.received?.let { received ->
                            HorizontalDivider(color = c.border)
                            DetailRow(stringResource(R.string.transfer_sent), "${formatAmount(transaction.money, locale = locale)} ${transaction.money.currency}")
                            DetailRow(stringResource(R.string.transfer_received_label), "${formatAmount(received, locale = locale)} ${received.currency}")
                        }
                    }
                    if (transaction.type.categoryKind != null) {
                        HorizontalDivider(color = c.border)
                        val category = s.allCategories.firstOrNull { it.id == transaction.categoryId }
                        DetailRow(stringResource(R.string.scan_category), category?.let { categoryDisplayName(it.id, it.name) }
                            ?: stringResource(if (transaction.categoryId == null) R.string.loan_uncategorized else R.string.transaction_unavailable))
                    }
                    if (transaction.note.isNotBlank()) {
                        HorizontalDivider(color = c.border)
                        Text(transaction.note, style = sans(14), color = c.text)
                    }
                }
            }
            s.savedReceipt?.let { ReceiptDetails(it, initiallyExpanded = true, showOpenAction = false) }
            if (s.loanLocked) Text(stringResource(R.string.audit_finance_loan_locked), style = sans(13), color = c.muted)
        }
        s.savedReceipt?.let { ReceiptOpenAction(it, primary = true) }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = sans(13), color = c.muted)
        Text(value, modifier = Modifier.weight(1.5f), style = sans(14, FontWeight.Medium), color = c.text)
    }
}
