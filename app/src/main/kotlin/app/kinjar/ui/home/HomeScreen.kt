package app.kinjar.ui.home

import app.kinjar.ui.theme.AddButton

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import app.kinjar.ui.RateStatus
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.categoryIcon
import app.kinjar.ui.formatAmount
import app.kinjar.ui.monthLocale
import app.kinjar.ui.monthName
import app.kinjar.ui.theme.BigAmountStyle
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.CaptionStyle
import app.kinjar.ui.theme.LabelStyle
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.SectionStyle
import app.kinjar.ui.theme.TitleStyle
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    onAdd: () -> Unit, onActivity: () -> Unit, onSettings: () -> Unit,
    onGoal: (String) -> Unit,
    onEditTransaction: (String) -> Unit = {}, vm: HomeViewModel = hiltViewModel(),
    conflicts: TransactionConflictViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val held by conflicts.held.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val c = LocalColors.current
    val s = state
    if (s == null) {
        if (failed) Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) { LoadFailed(vm::retry) }
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
                AddButton(stringResource(R.string.home_add), onAdd)
            }
        }
        if (failed) item { LoadFailed(vm::retry) }
        if (held.failed) item { ConflictCheckFailed(R.string.audit_conflict_list_failed, conflicts::retry) }
        item { SavingsCard(s, locale) }
        item { MonthSection(s, locale) }
        s.topGoal?.let { goal -> item { HomeGoal(goal) { onGoal(goal.goal.id) } } }
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
private fun SavingsCard(s: HomeState, locale: Locale) {
    val c = LocalColors.current
    val eur = s.summary.savingsEur
    val rsd = s.summary.savingsRsd
    val primary = eur ?: rsd
    Column(
        Modifier.fillMaxWidth().background(c.surface2, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.home_total_savings).uppercase(locale), style = sans(13, FontWeight.Medium, 0.06.em), color = c.muted)
        if (primary == null) {
            Text("-", style = BigAmountStyle, color = c.muted)
            Text(stringResource(R.string.home_savings_unavailable), style = LabelStyle, color = c.orange)
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatAmount(primary, locale = locale), style = BigAmountStyle, color = c.text)
                Text(primary.currency.name, style = mono(22, FontWeight.SemiBold), color = c.text, modifier = Modifier.padding(bottom = 5.dp))
            }
            if (eur != null && rsd != null) Text("≈ ${formatAmount(rsd, locale = locale)} RSD", style = mono(17), color = c.muted)
            if (eur == null) Text(stringResource(R.string.home_eur_unavailable), style = LabelStyle, color = c.orange)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_all_balances), style = LabelStyle, color = c.muted, modifier = Modifier.weight(1f))
            rateSource(s.rateStatus, locale)?.let { Text(it, style = LabelStyle, color = c.muted) }
        }
    }
}

@Composable
private fun rateSource(status: RateStatus, locale: Locale): String? {
    val snapshot = status.snapshot
    if (Currency.EUR in snapshot.overrides) return stringResource(R.string.home_rate_manual)
    val quote = snapshot.cached[Currency.EUR] ?: return null
    return stringResource(R.string.home_rate_nbs, quote.effectiveDate.format(DateTimeFormatter.ofPattern("d MMM", monthLocale(locale))))
}

@Composable
private fun MonthSection(s: HomeState, locale: Locale) {
    val c = LocalColors.current
    val month = YearMonth.from(s.today)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(stringResource(R.string.home_this_month), style = SectionStyle, color = c.text)
            Text(stringResource(R.string.home_month_range, month.lengthOfMonth(), monthName(month, locale)), style = LabelStyle, color = c.muted)
        }
        MonthCard(s.summary.income, s.summary.spent, s.summary.difference, locale)
        if (s.summary.monthPartial) Text(stringResource(R.string.home_partial), style = CaptionStyle, color = c.orange)
    }
}

@Composable
private fun MonthCard(income: Money, spent: Money, difference: Money, locale: Locale) {
    val c = LocalColors.current
    OutlinedBox(Modifier.fillMaxWidth(), radius = 18, padding = PaddingValues(16.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = LocalDensity.current.fontScale > 1.3f || maxWidth < 280.dp
            val incomeStat = @Composable { m: Modifier -> MonthStat(stringResource(R.string.home_income), income, sign = true, c.green, m, locale) }
            val spentStat = @Composable { m: Modifier -> MonthStat(stringResource(R.string.home_spent), spent, sign = false, c.red, m, locale) }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    incomeStat(Modifier.fillMaxWidth())
                    spentStat(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    incomeStat(Modifier.weight(1f))
                    spentStat(Modifier.weight(1f))
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = c.border)
        val tone = when {
            difference.isZero -> c.text
            difference.isNegative -> c.red
            else -> c.green
        }
        MonthStat(stringResource(R.string.home_difference), difference, sign = true, tone, Modifier.fillMaxWidth(), locale)
    }
}

/** Amount and currency share one wrapping Text so a large font or narrow screen breaks the line instead of clipping digits. */
@Composable
private fun MonthStat(title: String, value: Money, sign: Boolean, color: Color, modifier: Modifier, locale: Locale) {
    val c = LocalColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = LabelStyle, color = c.muted)
        Text(
            buildAnnotatedString {
                append(formatAmount(value, sign = sign, locale = locale))
                append(' ')
                withStyle(sans(13).toSpanStyle()) { append(value.currency.name) }
            },
            style = mono(20, FontWeight.SemiBold), color = color,
        )
    }
}


@Composable
private fun HomeGoal(p: app.kinjar.domain.GoalProgress, onClick: () -> Unit) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val ratio = p.ratio?.toFloat()?.coerceIn(0f, 1f)
    OutlinedBox(Modifier.fillMaxWidth(), radius = 18, padding = PaddingValues(16.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(p.goal.name, style = SectionStyle, color = c.text, modifier = Modifier.weight(1f))
            Text(ratio?.let { "${(it * 100).toInt()}%" } ?: "-", style = mono(16), color = c.primary)
        }
        Spacer(Modifier.size(12.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { ratio ?: 0f }, modifier = Modifier.fillMaxWidth(),
            color = if (p.reached) c.green else c.primary, trackColor = c.track,
        )
        Spacer(Modifier.size(8.dp))
        Text("${p.funded?.let { formatAmount(it, locale = locale) } ?: "?"} / ${formatAmount(p.goal.target, locale = locale)} ${p.goal.target.currency}", style = LabelStyle, color = c.muted)
        if (ratio == null) Text(stringResource(R.string.goals_unknown), style = CaptionStyle, color = c.orange)
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
