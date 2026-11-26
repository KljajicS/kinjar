package app.kinjar.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kinjar.R
import app.kinjar.domain.*
import app.kinjar.ui.*
import app.kinjar.ui.theme.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun weeklyRange(start: LocalDate, locale: Locale, today: LocalDate): String {
    val end = start.plusDays(6)
    val pattern = if (start.year != today.year || end.year != today.year) "d MMM yy" else "d MMM"
    val formatter = DateTimeFormatter.ofPattern(pattern, monthLocale(locale))
    val first = if (start.month == end.month && start.year == end.year) start.dayOfMonth.toString() else start.format(formatter)
    return "$first - ${end.format(formatter)}"
}

@Composable
internal fun WeeklyBudgetsCard(s: HomeState, onEdit: () -> Unit) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val week = weekStart(s.today)
    var selectedCategory by rememberSaveable(week) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().background(c.surface2, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.weekly_title), style = SectionStyle, color = c.text, modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text(stringResource(R.string.categories_title), color = c.primary) }
        }
        Text(weeklyRange(week, locale, s.today), style = LabelStyle, color = c.muted)
        s.budgets.forEach { p ->
            val categoryId = p.budget.categoryId
            val expanded = selectedCategory == categoryId
            val ratio = p.ratio
            val remaining = p.remaining
            val tone = when {
                ratio == null -> c.muted
                ratio > BigDecimal.ONE -> c.red
                ratio >= BigDecimal("0.8") -> c.orange
                else -> c.primary
            }
            Column(
                Modifier.fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(if (expanded) R.string.weekly_hide_expenses else R.string.weekly_show_expenses),
                    ) { selectedCategory = if (expanded) null else categoryId }
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(categoryDisplayName(categoryId, s.categories[categoryId]?.name), style = sans(15, FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
                    Text("${p.spent?.let { formatAmount(it, locale = locale) } ?: "?"} / ${formatAmount(requireNotNull(p.budget.limit), locale = locale)}", style = mono(13), color = c.muted)
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
                }
                Box(Modifier.fillMaxWidth().height(8.dp).background(c.track, RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth(ratio?.toFloat()?.coerceIn(0f, 1f) ?: 0f).height(8.dp).background(tone, RoundedCornerShape(4.dp)))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val label = when {
                        remaining == null -> stringResource(R.string.weekly_unknown)
                        remaining.isNegative -> stringResource(R.string.weekly_over, formatAmount(-remaining, locale = locale))
                        remaining.isZero -> stringResource(R.string.weekly_reached)
                        else -> stringResource(R.string.weekly_left, formatAmount(remaining, locale = locale))
                    }
                    Text(label, style = CaptionStyle, color = if (ratio == null || ratio >= BigDecimal("0.8")) tone else c.muted, modifier = Modifier.weight(1f))
                    Text(ratio?.let { "${it.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)}%" } ?: "?", style = mono(12), color = tone)
                }
            }
            if (expanded) {
                ExpenseRows(
                    p.expenses, s.categories, s.accountNames,
                    Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(14.dp)).padding(12.dp),
                )
            }
        }
        s.upcomingBudgets.forEach { budget ->
            Text(stringResource(R.string.weekly_scheduled, categoryDisplayName(budget.categoryId, s.categories[budget.categoryId]?.name),
                budget.limit?.let { "${formatAmount(it, locale = locale)} RSD" } ?: stringResource(R.string.weekly_no_limit), formatDate(budget.effectiveFrom, locale)), style = CaptionStyle, color = c.muted)
        }
        Text(stringResource(R.string.weekly_footer), style = CaptionStyle, color = c.muted)
    }
}
