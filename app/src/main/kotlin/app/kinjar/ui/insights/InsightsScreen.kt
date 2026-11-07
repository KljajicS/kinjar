package app.kinjar.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.domain.Category
import app.kinjar.domain.Currency
import app.kinjar.domain.InsightsSummary
import app.kinjar.domain.Money
import app.kinjar.domain.MonthTotals
import app.kinjar.domain.Slice
import app.kinjar.ui.RateNotice
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.formatAmount
import app.kinjar.ui.formatCompact
import app.kinjar.ui.monthLocale
import app.kinjar.ui.monthName
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.CaptionStyle
import app.kinjar.ui.theme.LabelStyle
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.TitleStyle
import app.kinjar.ui.theme.colorOf
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun InsightsScreen(vm: InsightsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val failed by vm.loadFailed.collectAsStateWithLifecycle()
    val s = state ?: return app.kinjar.ui.theme.LoadingOrError(failed, vm::retryLoad)
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val sum = s.summary

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.insights_title), style = TitleStyle, color = c.text)
                    Text(
                        if (sum.comparedDays != null) stringResource(R.string.home_month_range, sum.month.lengthOfMonth(), monthName(sum.month, locale))
                        else stringResource(R.string.insights_month_range, sum.month.lengthOfMonth(), monthName(sum.month, locale)),
                        style = LabelStyle, color = c.muted,
                    )
                }
                IconButton(onClick = vm::previousMonth, enabled = s.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.insights_prev), tint = if (s.canGoBack) c.text else c.border)
                }
                Text("${monthName(sum.month, locale, TextStyle.SHORT_STANDALONE)} ${sum.month.year}", style = sans(15, FontWeight.Medium), color = c.text)
                IconButton(onClick = vm::nextMonth, enabled = s.canGoForward) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.insights_next), tint = if (s.canGoForward) c.text else c.border)
                }
            }
        }
        item { RateNotice(s.rateStatus) }
        if (!s.hasData) {
            item { Text(stringResource(R.string.insights_empty), style = BodyStyle, color = c.muted) }
            return@LazyColumn
        }
        item { SpendingCard(sum, s.categories, s.selected, vm::select) }
        s.selected?.let { slice -> item { DrilldownCard(s, slice, vm::closeCategory) } }
        item { MonthsCard(sum) }
        item { ChangesCard(sum, s.categories) }
    }
}

@Composable
private fun categoryName(id: String?, categories: Map<String, Category>): String =
    if (id == null) stringResource(R.string.insights_other) else categoryDisplayName(id, categories[id]?.name)

@Composable
private fun categoryColor(id: String?, categories: Map<String, Category>): Color =
    id?.let { categories[it]?.color }?.let(::colorOf) ?: LocalColors.current.muted

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().background(c.surface2, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
private fun CardTitle(text: String, trailing: @Composable () -> Unit = {}) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = sans(17, FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun PartialNote(partial: Boolean) {
    if (partial) Text(stringResource(R.string.home_partial), style = CaptionStyle, color = LocalColors.current.orange)
}

@Composable
private fun SpendingCard(sum: InsightsSummary, categories: Map<String, Category>, selected: Slice?, onSelect: (Slice) -> Unit) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val legend = sum.slices.map { slice -> Triple(slice, categoryColor(slice.categoryId, categories), categoryName(slice.categoryId, categories)) }
    Card {
        CardTitle(stringResource(R.string.insights_where))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                Donut(
                    legend.map { (slice, color, _) -> color to slice.share / 100f },
                    stringResource(R.string.insights_chart_spending, legend.joinToString { "${it.third} ${it.first.share}%" }),
                    Modifier.size(140.dp),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatCompact(sum.spent, locale), style = mono(20, FontWeight.SemiBold), color = c.text)
                    Text(stringResource(R.string.insights_spent_unit), style = CaptionStyle, color = c.muted)
                }
            }
            Column(Modifier.weight(1f)) {
                legend.forEach { (slice, color, label) ->
                    val active = selected?.categoryId == slice.categoryId && selected != null
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp)
                            .background(if (active) c.primary.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(8.dp))
                            .semantics { this.selected = active }
                            .clickable(role = Role.Button) { onSelect(slice) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Text(label, style = sans(14), color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${slice.share}%", style = mono(13), color = c.muted)
                    }
                }
                if (legend.isEmpty()) Text(stringResource(R.string.insights_no_spending), style = LabelStyle, color = c.muted)
            }
        }
        if (legend.isNotEmpty()) Text(stringResource(R.string.insights_tap_category), style = LabelStyle, color = c.muted)
        PartialNote(sum.spendingPartial)
    }
}

@Composable
private fun DrilldownCard(s: InsightsState, slice: Slice, onClose: () -> Unit) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val dates = DateTimeFormatter.ofPattern("d MMM", monthLocale(locale))
    Card {
        CardTitle("${categoryName(slice.categoryId, s.categories)} · ${monthName(s.summary.month, locale)}") {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.add_close), tint = c.muted) }
        }
        if (s.drilldown.isEmpty()) Text(stringResource(R.string.insights_drilldown_empty), style = LabelStyle, color = c.muted)
        s.drilldown.forEachIndexed { i, t ->
            if (i > 0) HorizontalDivider(color = c.border, thickness = 1.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    val title = t.merchant.ifBlank { t.note }.ifBlank { categoryName(t.categoryId, s.categories) }
                    Text(title, style = sans(14, FontWeight.Medium), color = c.text, maxLines = 1)
                    Text(listOfNotNull(t.date.format(dates), s.accountNames[t.accountId]).joinToString(" · "), style = CaptionStyle, color = c.muted)
                }
                Text("-${formatAmount(t.money, locale = locale)} ${t.money.currency}", style = mono(14), color = c.text)
            }
        }
    }
}

@Composable
private fun MonthsCard(sum: InsightsSummary) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    var tapped by remember(sum.month) { mutableStateOf<YearMonth?>(null) }
    val labels = sum.months.map { monthName(it.month, locale, TextStyle.SHORT_STANDALONE) }
    val max = niceMax(sum.months.maxOf { maxOf(it.income.amount.toFloat(), it.expenses.amount.toFloat()) })
    fun axisLabel(value: Float) = formatCompact(Money.of(BigDecimal(value.toDouble()), Currency.RSD), locale)
    val selectedIndex = sum.months.indexOfFirst { it.month == tapped }.takeIf { it >= 0 }
    Card {
        CardTitle(stringResource(R.string.insights_income_vs)) {
            LegendDot(c.primary, stringResource(R.string.insights_income))
            LegendDot(c.orange, stringResource(R.string.insights_expenses), Modifier.padding(start = 10.dp))
        }
        BarChart(
            sum.months.mapIndexed { i, m ->
                BarGroup(labels[i], m.income.amount.toFloat(), m.expenses.amount.toFloat(),
                    stringResource(R.string.insights_chart_month, "${monthName(m.month, locale)} ${m.month.year}", formatAmount(m.income, locale = locale), formatAmount(m.expenses, locale = locale)))
            },
            max, listOf(axisLabel(max), axisLabel(max / 2), "0"),
            selectedIndex, sum.months.lastIndex,
            onSelect = { i -> sum.months[i].month.let { tapped = if (tapped == it) null else it } },
            incomeColor = c.primary, expenseColor = c.orange,
        )
        Text(stringResource(R.string.insights_monthly_totals), style = LabelStyle, color = c.muted)
        selectedIndex?.let { MonthDetail(sum.months[it]) }
        PartialNote(sum.monthsPartial)
    }
}

@Composable
private fun MonthDetail(m: MonthTotals) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    Column(
        Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${monthName(m.month, locale)} ${m.month.year}", style = sans(15, FontWeight.SemiBold), color = c.text)
        DetailRow(stringResource(R.string.home_income), formatAmount(m.income, sign = true, locale = locale), c.green)
        DetailRow(stringResource(R.string.home_spent), formatAmount(m.expenses, locale = locale), c.text)
        DetailRow(stringResource(R.string.home_difference), formatAmount(m.difference, sign = true, locale = locale), if (m.difference.isNegative) c.orange else c.green)
    }
}

@Composable
private fun DetailRow(label: String, value: String, color: Color) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = sans(14), color = c.muted, modifier = Modifier.weight(1f))
        Text("$value RSD", style = mono(14, FontWeight.SemiBold), color = color)
    }
}

@Composable
private fun ChangesCard(sum: InsightsSummary, categories: Map<String, Category>) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val previous = sum.month.minusMonths(1)
    Card {
        Column {
            CardTitle(stringResource(R.string.insights_changes_title))
            Text(comparedWith(sum, previous, locale), style = LabelStyle, color = c.muted)
        }
        if (sum.changes.isEmpty()) {
            Text(stringResource(R.string.insights_changes_empty), style = LabelStyle, color = c.muted)
        } else Column(Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp)) {
            sum.changes.forEachIndexed { i, change ->
                if (i > 0) HorizontalDivider(color = c.border, thickness = 1.dp)
                val up = !change.delta.isNegative
                val tone = if (up) c.orange else c.green
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).background(categoryColor(change.categoryId, categories), CircleShape))
                    Text(categoryName(change.categoryId, categories), style = sans(14), color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("${formatAmount(change.delta, sign = true, locale = locale)} RSD", style = mono(14), color = c.text)
                    Text(
                        change.percent?.let { (if (it.signum() > 0) "+" else "") + it.toPlainString() + "%" } ?: stringResource(R.string.insights_new_spending),
                        style = mono(13), color = tone,
                    )
                }
            }
        }
        Text(stringResource(R.string.insights_changes_footer), style = LabelStyle, color = c.muted)
        PartialNote(sum.changesPartial)
    }
}

@Composable
private fun comparedWith(sum: InsightsSummary, previous: YearMonth, locale: Locale): String {
    val days = sum.comparedDays
    return if (days == null) stringResource(R.string.insights_compared_full, monthName(previous, locale))
    else stringResource(R.string.insights_compared_partial, minOf(days, previous.lengthOfMonth()), monthName(previous, locale))
}

@Composable
private fun LegendDot(color: Color, label: String, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Text(label, style = CaptionStyle, color = c.muted)
    }
}
