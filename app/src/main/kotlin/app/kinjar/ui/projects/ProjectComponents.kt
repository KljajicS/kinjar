package app.kinjar.ui.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kinjar.R
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.ProjectCategory
import app.kinjar.domain.ProjectCost
import app.kinjar.domain.TransactionType
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.formatAmount
import app.kinjar.ui.formatDate
import app.kinjar.ui.settings.ScreenHeader
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.KinjarDatePickerDialog
import app.kinjar.ui.theme.LabelStyle
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.colorOf
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans
import java.time.LocalDate

@Composable
fun money(value: Money): String = "${formatAmount(value, locale = LocalLocale.current.platformLocale)} ${value.currency}"

@Composable
fun date(value: LocalDate): String = formatDate(value, LocalLocale.current.platformLocale)

@Composable
fun Hint(text: String, color: Color = LocalColors.current.muted) = Text(text, style = LabelStyle, color = color)

@Composable
fun categoryColor(category: ProjectCategory?): Color = category?.let { colorOf(it.color) } ?: LocalColors.current.muted

@Composable
fun categoryName(category: ProjectCategory?): String = category?.name ?: stringResource(R.string.project_uncategorized)

@Composable
fun ColorDot(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).background(color, CircleShape))

/** System Back runs the same guarded action as the header arrow. */
@Composable
fun ProjectPage(
    title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, footer: @Composable ColumnScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(LocalColors.current.bg).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ScreenHeader(title, onBack) }
            actions()
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        footer()
    }
}

fun LazyListScope.section(key: Any? = null, content: @Composable ColumnScope.() -> Unit) = item(key = key) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** Segments in category colors, widths by share of the total. */
@Composable
fun ColorBar(totals: List<CategoryTotal>, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val all = totals.sumOf { it.total.amount }
    Row(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.track).clearAndSetSemantics {}) {
        if (all.signum() > 0) totals.forEach { part ->
            Box(Modifier.weight(part.total.amount.toFloat() / all.toFloat()).fillMaxHeight().background(categoryColor(part.category)))
        }
    }
}

@Composable
fun CostRow(cost: ProjectCost, data: ProjectData, stale: Boolean, onClick: (() -> Unit)?) {
    val c = LocalColors.current
    val category = cost.categoryId?.let { id -> data.book.categories.find { it.id == id } }
    val tx = cost.transactionId?.let(data.transactionsById::get)
    val household = tx?.categoryId?.let(data.householdById::get)
    val title = when {
        cost.unpaid -> cost.note.ifBlank { stringResource(R.string.project_unpaid) }
        cost.transactionId == null -> cost.note.ifBlank { stringResource(R.string.project_past_cost) }
        tx?.merchant?.isNotBlank() == true -> tx.merchant
        household != null -> categoryDisplayName(household.id, household.name)
        else -> stringResource(R.string.project_linked_expense)
    }
    val kind = stringResource(when {
        cost.unpaid -> R.string.project_unpaid
        cost.transactionId == null -> R.string.project_past_cost
        else -> R.string.project_linked_expense
    })
    OutlinedBox(Modifier.fillMaxWidth(), radius = 14, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorDot(categoryColor(category), 12.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = sans(15, FontWeight.Medium), color = c.text, maxLines = 2)
                Hint("${date(cost.date)} · ${categoryName(category)} · $kind")
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(cost.converted), style = mono(14, FontWeight.SemiBold), color = c.text)
                if (cost.original != cost.converted) Hint(money(cost.original))
            }
        }
        if (cost.transactionId != null && tx?.type != TransactionType.EXPENSE) {
            Hint(stringResource(R.string.project_cost_excluded), c.orange)
        } else if (stale) Hint(stringResource(R.string.project_cost_changed), c.orange)
    }
}

@Composable
fun AmountField(label: String, value: String, currency: Currency, onChange: (String) -> Unit) {
    FieldBox("$label (${currency.name})", value, { if (it.length <= 15) onChange(it) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
fun DateField(label: String, value: LocalDate, enabled: Boolean = true, onPick: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedBox(Modifier.fillMaxWidth(), radius = 14, onClick = if (enabled) ({ open = true }) else null) {
        Hint(label)
        Text(date(value), style = BodyStyle, color = LocalColors.current.text)
    }
    if (open) KinjarDatePickerDialog(value, { open = false }) { onPick(it); open = false }
}

/** [options] pair an id ("" for none) with a label and an optional color dot. */
@Composable
fun ChoiceField(label: String, value: String, color: Color?, options: List<Triple<String, String, Color?>>, enabled: Boolean = true, onPick: (String) -> Unit) {
    val c = LocalColors.current
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedBox(Modifier.fillMaxWidth(), radius = 14, onClick = if (enabled) ({ open = true }) else null) {
        Hint(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            color?.let { ColorDot(it) }
            Text(value, style = BodyStyle, color = c.text)
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        containerColor = c.bg,
        title = { Text(label, style = sans(18, FontWeight.SemiBold), color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                options.forEach { (id, text, dot) ->
                    TextButton(onClick = { onPick(id); open = false }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            dot?.let { ColorDot(it, 12.dp) }
                            Text(text, style = BodyStyle, color = c.text)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.dialog_cancel), color = c.text) } },
    )
}

@Composable
fun ProjectCategoryField(categories: List<ProjectCategory>, selected: String?, enabled: Boolean = true, onPick: (String?) -> Unit) {
    val active = categories.filter { !it.archived || it.id == selected }
    val current = active.find { it.id == selected }
    ChoiceField(
        stringResource(R.string.project_category), categoryName(current), categoryColor(current),
        listOf(Triple("", stringResource(R.string.project_uncategorized), LocalColors.current.muted)) +
            active.map { Triple(it.id, it.name, colorOf(it.color)) },
        enabled,
    ) { onPick(it.ifBlank { null }) }
}

/** One field per missing day and currency, so a typed rate only ever applies to the date it names. */
@Composable
fun RateInputs(loading: Boolean, missing: List<RateKey>, manual: Map<RateKey, String>, onRate: (RateKey, String) -> Unit, onRetry: () -> Unit) {
    val c = LocalColors.current
    if (loading) Hint(stringResource(R.string.project_rates_loading))
    if (missing.isEmpty()) return
    Hint(stringResource(R.string.project_rates_missing), c.orange)
    TextButton(onClick = onRetry) { Text(stringResource(R.string.project_rates_retry), color = c.primary) }
    missing.forEach { key ->
        FieldBox(
            stringResource(R.string.project_rate_label, key.currency.name, date(key.date)), manual[key].orEmpty(),
            { if (it.length <= 16) onRate(key, it) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
}
