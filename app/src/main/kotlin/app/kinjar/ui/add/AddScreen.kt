package app.kinjar.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.domain.Account
import app.kinjar.domain.Currency
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.formatAmount
import app.kinjar.ui.monthLocale
import app.kinjar.ui.theme.KinjarDatePickerDialog
import app.kinjar.ui.theme.CaptionStyle
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.RoundIconButton
import app.kinjar.ui.theme.colorOf
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Serves new entries, bill payments and, on the edit route, corrections of an existing transaction. */
@Composable
fun AddScreen(onClose: () -> Unit, vm: AddViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.saved) { if (s.saved) onClose() }
    AddContent(s, onClose, vm::setType, vm::press, vm::setCategory, vm::setAccount,
        vm::setToAccount, vm::setDate, vm::setMerchant, vm::skip, vm::save, vm::delete, vm::retryLoad)
}

@Composable
fun AddContent(
    s: AddState, onClose: () -> Unit, onType: (TransactionType) -> Unit, onKey: (Char) -> Unit,
    onCategory: (String?) -> Unit, onAccount: (String) -> Unit, onToAccount: (String) -> Unit,
    onDate: (LocalDate) -> Unit, onMerchant: (String) -> Unit, onSkip: () -> Unit, onSave: () -> Unit,
    onDelete: () -> Unit = {}, onRetry: () -> Unit = {},
) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val separator = DecimalFormatSymbols(locale).decimalSeparator
    val readOnly = s.missing || s.loanLocked || s.loading || s.loadFailed
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(c.bg).imePadding().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            RoundIconButton(Icons.Default.Close, stringResource(R.string.add_close), { if (!s.saving) onClose() })
            val title = when {
                s.receipt != null -> R.string.scan_review
                s.bill != null -> R.string.add_bill_title
                s.editing != null && s.typeLocked || readOnly -> R.string.audit_finance_edit_title
                else -> null
            }
            if (title != null) Text(stringResource(title), style = sans(15, FontWeight.Medium), color = c.text)
            else TypeSwitch(s.type, onType, Modifier.padding(start = 12.dp).weight(1f, fill = false))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (s.loadFailed) {
                Text(stringResource(R.string.audit_finance_load_failed), style = sans(13), color = c.orange)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.audit_finance_retry)) }
            }
            when {
                s.loading -> CircularProgressIndicator(color = c.primary)
                s.loadFailed -> Unit
                s.missing -> Text(stringResource(R.string.audit_finance_missing), style = sans(15), color = c.orange)
                s.loanLocked -> Text(stringResource(R.string.audit_finance_loan_locked), style = sans(15), color = c.orange)
                else -> Fields(s, locale, separator, onCategory, onAccount, onToAccount, onDate, onMerchant, onSkip) { confirmDelete = true }
            }
        }

        if (!readOnly) {
            Keypad(separator, onKey = onKey)
            val account = s.accounts.firstOrNull { it.id == s.accountId }
            val saveLabel = when {
                s.editing != null -> stringResource(R.string.audit_finance_save_changes)
                account == null || s.type == TransactionType.TRANSFER -> stringResource(s.type.saveLabel())
                s.type == TransactionType.INCOME -> stringResource(R.string.add_save_income_into, account.name)
                else -> stringResource(R.string.add_save_expense_from, account.name)
            }
            PrimaryButton(saveLabel, enabled = s.canSave, onClick = onSave)
        }
    }

    val editing = s.editing
    if (confirmDelete && editing != null && !readOnly) {
        DeleteDialog(editing.source, s.saving, onDismiss = { confirmDelete = false }) { confirmDelete = false; onDelete() }
    }
}

@Composable
private fun Fields(
    s: AddState, locale: Locale, separator: Char,
    onCategory: (String?) -> Unit, onAccount: (String) -> Unit, onToAccount: (String) -> Unit,
    onDate: (LocalDate) -> Unit, onMerchant: (String) -> Unit, onSkip: () -> Unit, onDelete: () -> Unit,
) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.amount.text.ifEmpty { "0" }.replace('.', separator), style = mono(48, FontWeight.SemiBold, (-0.03).em), color = c.text, maxLines = 1)
            Text(s.currency.name, style = sans(18, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
        }
    }

    if (s.type == TransactionType.EXPENSE) CategoryGrid(s.categories, s.categoryId, onCategory)

    if (s.type == TransactionType.TRANSFER) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountBox(stringResource(R.string.add_account), s.accounts, s.accountId, onAccount, Modifier.weight(1f))
            AccountBox(stringResource(R.string.add_to_account), s.accounts, s.toAccountId, onToAccount, Modifier.weight(1f))
        }
    } else {
        AccountBox(
            stringResource(if (s.type == TransactionType.INCOME) R.string.add_deposit_into else R.string.add_pay_from),
            s.accounts.filter { !it.archived && (!s.rsdOnly || it.currency == Currency.RSD) },
            s.accountId, onAccount, Modifier.fillMaxWidth(),
        )
    }
    DateBox(s.date, onDate, Modifier.fillMaxWidth(), enabled = s.bill == null)
    if (s.billInFuture) Text(stringResource(R.string.audit_finance_bill_future), style = sans(13), color = c.orange)
    else if (s.date > s.today) Text(stringResource(R.string.audit_finance_future_date), style = sans(13), color = c.muted)
    s.error?.let { Text(stringResource(it), style = sans(13), color = c.orange) }
    s.saveError?.let { Text(stringResource(it), style = sans(13), color = c.orange) }
    if (s.rsdOnly && s.accounts.none { it.id == s.accountId && it.currency == Currency.RSD }) {
        Text(stringResource(R.string.scan_rsd_account), style = sans(13), color = c.orange)
    } else if (s.accounts.none { it.id == s.accountId && it.currency == s.currency } ||
        s.type == TransactionType.TRANSFER && s.toAccountId != null && s.accounts.none { it.id == s.toAccountId }) {
        Text(stringResource(R.string.add_account_unavailable), style = sans(13), color = c.orange)
    }
    if (s.type == TransactionType.EXPENSE && s.categoryId != null && s.categories.none { it.id == s.categoryId }) {
        Text(stringResource(R.string.scan_category_changed), style = sans(13), color = c.orange)
    }

    FieldBox(stringResource(R.string.add_merchant), s.merchant, onMerchant)
    (s.receipt ?: s.savedReceipt)?.let { receipt ->
        Text(stringResource(R.string.scan_original, formatAmount(receipt.total, locale = locale), receipt.items.size), style = sans(12), color = c.muted)
    }
    s.savedReceipt?.let { app.kinjar.ui.scan.ReceiptDetails(it) }
    if (s.bill != null) {
        app.kinjar.ui.theme.DangerLink(stringResource(R.string.add_skip_bill), onClick = onSkip)
    }
    if (s.editing != null) {
        app.kinjar.ui.theme.DangerLink(stringResource(if (s.editing.source == TransactionSource.RECEIPT) R.string.receipt_delete else R.string.audit_finance_delete), enabled = !s.saving, onClick = onDelete)
    }
}

@Composable
private fun DeleteDialog(source: TransactionSource, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = LocalColors.current
    if (source == TransactionSource.RECEIPT) {
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            containerColor = c.bg,
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = c.orange) },
            title = { Text(stringResource(R.string.receipt_delete_title), style = sans(18, FontWeight.SemiBold), color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.receipt_warning), style = sans(16, FontWeight.SemiBold), color = c.orange)
                    Text(stringResource(R.string.audit_finance_delete_receipt), style = sans(14), color = c.text)
                    Text(stringResource(R.string.audit_finance_delete_body), style = sans(13), color = c.muted)
                }
            },
            confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.receipt_delete), color = if (busy) c.muted else c.orange) } },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.receipt_keep), color = c.text) } },
        )
        return
    }
    val body = listOfNotNull(
        stringResource(R.string.audit_finance_delete_body),
        when (source) {
            TransactionSource.RECURRING -> stringResource(R.string.audit_finance_delete_bill)
            TransactionSource.RECEIPT -> stringResource(R.string.audit_finance_delete_receipt)
            TransactionSource.MANUAL -> null
        },
    ).joinToString("\n\n")
    app.kinjar.ui.theme.ConfirmDialog(stringResource(R.string.audit_finance_delete_title),
        stringResource(R.string.audit_finance_delete), busy, onConfirm, onDismiss) {
        Text(body, style = sans(14), color = c.text)
    }
}

@Composable
private fun TypeSwitch(selected: TransactionType, onSelect: (TransactionType) -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    Row(modifier.widthIn(max = 270.dp).background(c.surface2, RoundedCornerShape(14.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.TRANSFER).forEach { t ->
            val active = t == selected
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp)
                    .background(if (active) (if (c.dark) c.track else c.bg) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
                    .selectable(selected = active, role = Role.Tab) { onSelect(t) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(t.label()), style = sans(14, FontWeight.Medium), color = if (active) c.text else c.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CategoryGrid(categories: List<app.kinjar.domain.Category>, selectedId: String?, onSelect: (String?) -> Unit) {
    val c = LocalColors.current
    val rows = (categories.size + 3) / 4
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false,
        modifier = Modifier.height((rows * 62 + (rows - 1) * 8).dp),
    ) {
        items(categories, key = { it.id }) { cat ->
            val label = categoryDisplayName(cat.id, cat.name)
            OutlinedBox(Modifier.height(62.dp), radius = 14, padding = PaddingValues(0.dp), selected = selectedId == cat.id, onClick = { onSelect(cat.id) }) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(10.dp).background(colorOf(cat.color), CircleShape))
                    Spacer(Modifier.size(6.dp))
                    Text(label, style = sans(12, FontWeight.Medium), color = c.text, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun AccountBox(label: String, accounts: List<Account>, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    var open by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.id == selectedId }
    val value = selected?.let { "${it.name} · ${it.currency}" } ?: stringResource(R.string.add_choose_account)
    Box(modifier) {
        OutlinedBox(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label, $value" },
            radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), onClick = { open = true },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = CaptionStyle, color = c.muted)
                    Text(value, style = sans(14, FontWeight.Medium), color = c.text, modifier = Modifier.padding(top = 2.dp))
                }
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = c.muted)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            accounts.forEach { a ->
                DropdownMenuItem(text = { Text("${a.name} · ${a.currency}", style = sans(14)) }, onClick = { onSelect(a.id); open = false })
            }
        }
    }
}

@Composable
fun DateBox(date: LocalDate, onPick: (LocalDate) -> Unit, modifier: Modifier, label: String = stringResource(R.string.add_date), enabled: Boolean = true) {
    val c = LocalColors.current
    val locale = monthLocale(LocalLocale.current.platformLocale)
    val today = LocalDate.now()
    val formatted = date.format(DateTimeFormatter.ofPattern("d MMM", locale))
    val value = when (date) {
        today -> stringResource(R.string.home_today) + ", " + formatted
        today.minusDays(1) -> stringResource(R.string.home_yesterday) + ", " + formatted
        else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }
    var open by remember { mutableStateOf(false) }

    OutlinedBox(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$label, $value" },
        radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), onClick = if (enabled) ({ open = true }) else null,
    ) {
        Text(label, style = CaptionStyle, color = c.muted)
        Text(value, style = sans(14, FontWeight.Medium), color = c.text, modifier = Modifier.padding(top = 2.dp))
    }

    if (open) KinjarDatePickerDialog(date, onDismiss = { open = false }, onPick = { onPick(it); open = false })
}

/** The decimal key shows the locale separator but always sends '.', the only separator [AmountInput] stores. */
@Composable
private fun Keypad(separator: Char, onKey: (Char) -> Unit) {
    val c = LocalColors.current
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "<")
    val backspace = stringResource(R.string.add_backspace)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    val semantics = if (k == "<") Modifier.semantics { contentDescription = backspace } else Modifier
                    val shown = when (k) {
                        "<" -> "⌫"
                        "." -> separator.toString()
                        else -> k
                    }
                    Box(
                        Modifier.weight(1f).height(52.dp).background(c.surface2, RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button) { onKey(k[0]) }.then(semantics),
                        contentAlignment = Alignment.Center,
                    ) { Text(shown, style = mono(22), color = c.text) }
                }
            }
        }
    }
}

private fun TransactionType.label() = when (this) {
    TransactionType.EXPENSE -> R.string.add_expense
    TransactionType.INCOME -> R.string.add_income
    TransactionType.TRANSFER -> R.string.add_transfer
    TransactionType.LOAN_RECEIPT -> R.string.loan_received
}

private fun TransactionType.saveLabel() = when (this) {
    TransactionType.EXPENSE -> R.string.add_save_expense
    TransactionType.INCOME -> R.string.add_save_income
    TransactionType.TRANSFER -> R.string.add_save_transfer
    TransactionType.LOAN_RECEIPT -> R.string.loan_received
}
