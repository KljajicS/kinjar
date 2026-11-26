package app.kinjar.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.ui.conflict.ConflictCheckFailed
import app.kinjar.ui.conflict.TransactionConflictViewModel
import app.kinjar.data.RecentTx
import app.kinjar.domain.CategoryKind
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import app.kinjar.ui.formatTransactionDate
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.categoryIcon
import app.kinjar.ui.formatAmount
import app.kinjar.ui.monthName
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.CaptionStyle
import app.kinjar.ui.theme.LabelStyle
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.SectionStyle
import app.kinjar.ui.theme.TitleStyle
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

@Composable
fun HomeScreen(
    onSyncSettings: () -> Unit, onActivity: () -> Unit, onSettings: () -> Unit,
    onCategories: () -> Unit,
    onOpenBankInbox: () -> Unit,
    onReviewBankAccount: (String) -> Unit,
    onEditTransaction: (String) -> Unit = {}, vm: HomeViewModel = hiltViewModel(),
    conflicts: TransactionConflictViewModel = hiltViewModel(),
    sync: HomeSyncViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val held by conflicts.held.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val syncState by sync.state.collectAsStateWithLifecycle()
    val c = LocalColors.current
    val s = state
    if (s == null) {
        Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
            if (failed) LoadFailed(vm::retry) else CircularProgressIndicator(color = c.primary)
        }
        return
    }
    val locale = LocalLocale.current.platformLocale
    val recent = held.mergeInto(s.recent)

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val settings = stringResource(R.string.settings_title)
                Box(
                    Modifier.size(44.dp).background(c.primary.copy(alpha = c.tintAlpha), CircleShape)
                        .clickable(role = Role.Button, onClick = onSettings)
                        .semantics { contentDescription = settings },
                    contentAlignment = Alignment.Center,
                ) { Text(s.memberName.take(1).uppercase(), style = sans(16, FontWeight.SemiBold), color = c.primary) }
                Column(Modifier.weight(1f)) {
                    Text(monthName(YearMonth.from(s.today), locale), style = TitleStyle, color = c.text)
                    Text(s.today.year.toString(), style = LabelStyle, color = c.muted)
                }
                HomeSyncButton(syncState, { sync.synchronize() }, { sync.synchronize(force = true) }, onSyncSettings)
            }
            app.kinjar.ui.bank.BankInboxEntry(onOpenBankInbox)
        }
        s.summary.bankReconciliationNeeded.forEach { id ->
            item(key = "bank-reconciliation:$id") {
                TextButton(onClick = { onReviewBankAccount(id) }) {
                    Text(stringResource(R.string.bank_balance_alert, s.accountNames[id].orEmpty()), color = c.orange)
                }
            }
        }
        if (failed) item { LoadFailed(vm::retry) }
        if (held.failed) item { ConflictCheckFailed(R.string.audit_conflict_list_failed, conflicts::retry) }
        item { OverviewCard(s, locale) }
        if (s.budgets.isNotEmpty() || s.upcomingBudgets.isNotEmpty()) item { WeeklyBudgetsCard(s, onCategories) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_recent), style = SectionStyle, color = c.text, modifier = Modifier.weight(1f))
                    TextButton(onClick = onActivity) { Text(stringResource(R.string.home_see_all), color = c.primary) }
                }
                if (recent.isEmpty()) Text(stringResource(R.string.home_empty), style = BodyStyle, color = c.muted)
            }
        }
        items(recent, key = { it.transaction.id }) {
            RecentRow(it, s.accountNames, s.currentMemberId, s.today, onEditTransaction, held = it.transaction.id in held.ids)
        }
    }
}

@Composable
private fun LoadFailed(onRetry: () -> Unit) {
    val c = LocalColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.audit_finance_load_failed), style = BodyStyle, color = c.orange)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.audit_finance_retry)) }
    }
}

@Composable
fun accountColor(index: Int): Color {
    val c = LocalColors.current
    return listOf(c.green, c.primary, c.orange)[index % 3]
}

@Composable
fun AccountTile(name: String, color: Color) {
    val c = LocalColors.current
    Box(Modifier.size(40.dp).background(color.copy(alpha = c.tintAlpha), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = sans(15, FontWeight.SemiBold), color = color)
    }
}

@Composable
private fun OverviewCard(s: HomeState, locale: Locale) {
    val c = LocalColors.current
    Column(
        Modifier.fillMaxWidth().background(c.surface2, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        OverviewColumns(
            first = { CurrencyBalance(s.summary.nativeBalance(Currency.EUR), locale, it) },
            second = { CurrencyBalance(s.summary.nativeBalance(Currency.RSD), locale, it) },
        )
        val totals = listOfNotNull(s.summary.savingsEur, s.summary.savingsRsd)
        if (totals.isNotEmpty()) {
            Text(totals.joinToString(" · ") { "≈ ${formatAmount(it, locale = locale)} ${it.currency.name}" },
                style = mono(11), color = c.muted, modifier = Modifier.padding(top = 12.dp))
        }
        if (totals.size < 2) {
            Text(stringResource(R.string.home_savings_unavailable), style = CaptionStyle, color = c.orange, modifier = Modifier.padding(top = 8.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 18.dp), color = c.border)
        Text(monthName(YearMonth.from(s.today), locale), style = sans(13, FontWeight.SemiBold), color = c.text)
        Spacer(Modifier.size(14.dp))
        OverviewColumns(
            first = { MonthlyAmount(s.summary.income, income = true, locale, it) },
            second = { MonthlyAmount(s.summary.spent, income = false, locale, it) },
        )
        if (s.summary.monthPartial) {
            Text(stringResource(R.string.home_partial), style = CaptionStyle, color = c.orange, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun OverviewColumns(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (LocalDensity.current.fontScale > 1.3f || maxWidth < 280.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CurrencyBalance(value: Money, locale: Locale, modifier: Modifier) {
    val c = LocalColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(value.currency.name, style = LabelStyle, color = c.muted)
        Text(formatAmount(value, locale = locale), style = mono(28, FontWeight.SemiBold), color = c.text)
    }
}

@Composable
private fun MonthlyAmount(value: Money, income: Boolean, locale: Locale, modifier: Modifier) {
    val c = LocalColors.current
    val color = if (income) c.green else c.red
    val label = stringResource(if (income) R.string.home_income else R.string.home_spent)
    val amount = formatAmount(value, locale = locale)
    Column(
        modifier.clearAndSetSemantics { contentDescription = "$label: $amount ${value.currency.name}" },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            buildAnnotatedString {
                append(if (income) "+" else "−")
                withStyle(SpanStyle(fontSize = 4.sp)) { append('\u00A0') }
                append(amount)
            },
            style = mono(20, FontWeight.Medium), color = color,
        )
        Text(value.currency.name, style = sans(11), color = color)
    }
}

@Composable
internal fun RecentRow(r: RecentTx, accountNames: Map<String, String>, me: String?, today: LocalDate, onEdit: (String) -> Unit, held: Boolean = false) {
    val c = LocalColors.current
    val t = r.transaction
    val category = t.categoryId?.let { id -> categoryDisplayName(id, r.categoryName) }
    val typeLabel = when (t.type) {
        TransactionType.EXPENSE -> R.string.add_expense
        TransactionType.INCOME -> R.string.add_income
        TransactionType.TRANSFER -> R.string.add_transfer
        TransactionType.LOAN_RECEIPT -> R.string.loan_received
    }
    val title = t.merchant.ifBlank { category ?: stringResource(typeLabel) }
    val who = if (t.paidBy == me) stringResource(R.string.member_you) else r.paidByName ?: ""
    val source = when (t.source) {
        TransactionSource.MANUAL -> R.string.source_manual
        TransactionSource.RECEIPT -> R.string.source_receipt
        TransactionSource.RECURRING -> R.string.source_recurring
    }
    val account = listOfNotNull(accountNames[t.accountId], t.toAccountId?.let { accountNames[it] }).joinToString(" → ").ifBlank { null }
    val locale = LocalLocale.current.platformLocale
    val icon = when (t.type) {
        TransactionType.TRANSFER -> Icons.Outlined.SwapHoriz
        TransactionType.LOAN_RECEIPT -> Icons.Outlined.Handshake
        TransactionType.INCOME -> categoryIcon(r.categoryIcon, t.categoryId, CategoryKind.INCOME)
        TransactionType.EXPENSE -> categoryIcon(r.categoryIcon, t.categoryId, CategoryKind.EXPENSE)
    }
    val (iconTint, tile) = if (t.type == TransactionType.INCOME) c.green to c.green.copy(alpha = c.tintAlpha) else c.muted to c.surface2
    val amountColor = when (t.type) {
        TransactionType.INCOME -> c.green
        TransactionType.EXPENSE -> c.red
        else -> c.text
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (held) Modifier.border(2.dp, c.red, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)) else Modifier)
            .clickable(role = Role.Button, onClickLabel = stringResource(if (held) R.string.conflict_title else R.string.audit_finance_edit_hint)) { onEdit(t.id) }
            .then(if (held) Modifier.padding(10.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(tile, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = sans(15, FontWeight.Medium), color = c.text, maxLines = 1)
            Text(listOfNotNull(category, account, who.ifBlank { null }, stringResource(source)).joinToString(" · "), style = LabelStyle, color = c.muted, maxLines = 1)
            if (held) Text(stringResource(R.string.conflict_flag), style = sans(13, FontWeight.SemiBold), color = c.red, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val (sent, received) = recentAmounts(t, locale)
            Text(sent, style = mono(15), color = amountColor)
            received?.let { Text(it, style = mono(13), color = c.muted) }
            Text(formatTransactionDate(t.date, today, stringResource(R.string.home_today), locale), style = CaptionStyle, color = c.muted)
        }
    }
}

/** Sent amount line, plus the destination amount for a transfer that changed currency. */
internal fun recentAmounts(t: Transaction, locale: Locale): Pair<String, String?> {
    val signed = if (t.type == TransactionType.EXPENSE) -t.money else t.money
    val sent = "${formatAmount(signed, sign = t.type != TransactionType.TRANSFER, locale = locale)} ${t.money.currency}"
    val received = t.received
        ?.takeIf { t.type == TransactionType.TRANSFER && it.currency != t.money.currency }
        ?.let { "→ ${formatAmount(it, locale = locale)} ${it.currency}" }
    return sent to received
}
