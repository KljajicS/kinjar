package app.kinjar.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kinjar.R
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.ProjectCategory
import app.kinjar.domain.ProjectCost
import app.kinjar.ui.formatCompact
import app.kinjar.ui.insights.BarChart
import app.kinjar.ui.insights.BarGroup
import app.kinjar.ui.insights.Donut
import app.kinjar.ui.insights.niceMax
import app.kinjar.ui.monthName
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.SelectPill
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.time.format.TextStyle

/** Reads the frozen converted amounts only, so the chart never changes with today's rates. */
@Composable
fun ProjectChart(costs: List<ProjectCost>, categories: List<ProjectCategory>, currency: Currency, today: YearMonth) {
    var monthly by rememberSaveable { mutableStateOf(false) }
    OutlinedBox(Modifier.fillMaxWidth(), radius = 16) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectPill(stringResource(R.string.project_chart_category), !monthly, { monthly = false })
                SelectPill(stringResource(R.string.project_chart_month), monthly, { monthly = true })
            }
            if (monthly) MonthBars(monthTotals(costs, currency, today), currency)
            else CategoryDonut(categoryTotals(costs, categories, currency), sum(costs, currency))
        }
    }
}

@Composable
private fun CategoryDonut(totals: List<CategoryTotal>, all: Money) {
    val c = LocalColors.current
    if (all.isZero) return Hint(stringResource(R.string.project_chart_empty))
    val share = { part: Money -> part.amount.multiply(BigDecimal(100)).divide(all.amount, 0, RoundingMode.HALF_UP).toInt() }
    val names = totals.map { categoryName(it.category) }
    val colors = totals.map { categoryColor(it.category) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Donut(
            totals.mapIndexed { i, t -> colors[i] to t.total.amount.divide(all.amount, 4, RoundingMode.HALF_UP).toFloat() },
            stringResource(R.string.project_chart_category_description, totals.indices.joinToString { "${names[it]} ${share(totals[it].total)}%" }),
            Modifier.size(120.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            totals.forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorDot(colors[i])
                    Column(Modifier.weight(1f)) {
                        Text(names[i], style = sans(13, FontWeight.Medium), color = c.text, maxLines = 1)
                        Hint("${money(t.total)} · ${share(t.total)}%")
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthBars(months: List<Pair<YearMonth, Money>>, currency: Currency) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    var tapped by rememberSaveable { mutableStateOf<String?>(null) }
    val max = niceMax(months.maxOf { it.second.amount.toFloat() })
    fun axis(value: Float) = formatCompact(Money.of(BigDecimal(value.toDouble()), currency), locale)
    val selected = months.indexOfFirst { it.first.toString() == tapped }.takeIf { it >= 0 }
    BarChart(
        months.map { (month, total) ->
            BarGroup(
                monthName(month, locale, TextStyle.NARROW_STANDALONE), 0f, total.amount.toFloat(),
                stringResource(R.string.project_chart_month_description, "${monthName(month, locale)} ${month.year}", money(total)),
            )
        },
        max, listOf(axis(max), axis(max / 2), "0"), selected, months.lastIndex,
        onSelect = { i -> months[i].first.toString().let { tapped = if (tapped == it) null else it } },
        incomeColor = Color.Transparent, expenseColor = c.primary,
    )
    val shown = selected?.let { months[it] } ?: months.last()
    Text(
        "${monthName(shown.first, locale)} ${shown.first.year}: ${money(shown.second)}",
        style = mono(13, FontWeight.Medium), color = c.text,
    )
}
