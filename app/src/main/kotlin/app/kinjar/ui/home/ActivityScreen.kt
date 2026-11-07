package app.kinjar.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.ui.conflict.ConflictCheckFailed
import app.kinjar.ui.conflict.TransactionConflictViewModel
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionType
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.monthLocale
import app.kinjar.ui.settings.ScreenHeader
import app.kinjar.ui.settings.LoadFailedBanner
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.CaptionStyle
import app.kinjar.ui.theme.LabelStyle
import app.kinjar.ui.theme.LoadingOrError
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.KinjarDateRangePickerDialog
import app.kinjar.ui.theme.sans
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal fun matchesActivity(t: Transaction, from: LocalDate?, to: LocalDate?, category: String?, type: String?): Boolean =
    (from == null || t.date >= from) && (to == null || t.date <= to) &&
        (category == null || t.categoryId == category) && (type == null || t.type.name == type)

@Composable
fun ActivityScreen(onBack: () -> Unit, onEdit: (String) -> Unit, vm: HomeViewModel = hiltViewModel(),
                   conflicts: TransactionConflictViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val held by conflicts.held.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val c = LocalColors.current
    var from by rememberSaveable { mutableStateOf<String?>(null) }
    var to by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val s = state
    val start = from?.let(LocalDate::parse)
    val end = to?.let(LocalDate::parse)
    val locale = monthLocale(LocalLocale.current.platformLocale)
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern("d MMM yyyy", locale) }
    val rows = remember(s?.recent, held, from, to, category, type) {
        held.mergeInto(s?.recent.orEmpty()).filter { matchesActivity(it.transaction, start, end, category, type) }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.activity_title), onBack) }
        if (s == null) {
            item { LoadingOrError(failed, vm::retry) }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterField(
                        stringResource(R.string.activity_date_range),
                        if (start != null && end != null) "${start.format(dateFormat)} - ${end.format(dateFormat)}"
                        else stringResource(R.string.activity_all_dates),
                        Icons.Outlined.CalendarMonth,
                        Modifier.fillMaxWidth(),
                    ) { picking = true }
                    val all = stringResource(R.string.activity_all)
                    val categories = s.recent.mapNotNull { r ->
                        r.transaction.categoryId?.let { it to categoryDisplayName(it, r.categoryName) }
                    }.distinctBy { it.first }.sortedBy { it.second }
                    val types = TransactionType.entries.map { it.name to stringResource(when (it) {
                        TransactionType.INCOME -> R.string.add_income
                        TransactionType.EXPENSE -> R.string.add_expense
                        TransactionType.TRANSFER -> R.string.add_transfer
                        TransactionType.LOAN_RECEIPT -> R.string.loan_received
                    }) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterMenu(stringResource(R.string.loan_category), category, categories, all, Modifier.weight(1f)) { category = it }
                        FilterMenu(stringResource(R.string.activity_type), type, types, all, Modifier.weight(1f)) { type = it }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(R.plurals.activity_count, rows.size, rows.size), style = LabelStyle, color = c.muted, modifier = Modifier.weight(1f))
                    if (from != null || to != null || category != null || type != null) {
                        TextButton(onClick = { from = null; to = null; category = null; type = null }) {
                            Text(stringResource(R.string.activity_clear), style = sans(14, FontWeight.Medium), color = c.primary)
                        }
                    }
                }
            }
            if (failed) item { LoadFailedBanner(vm::retry) }
            if (held.failed) item { ConflictCheckFailed(R.string.audit_conflict_list_failed, conflicts::retry) }
            if (rows.isEmpty()) item { Text(stringResource(R.string.activity_empty), style = BodyStyle, color = c.muted) }
            items(rows, key = { it.transaction.id }) {
                RecentRow(it, s.accountNames, s.currentMemberId, s.today, onEdit, held = it.transaction.id in held.ids)
            }
        }
    }
    if (picking) KinjarDateRangePickerDialog(
        start = start, end = end,
        onDismiss = { picking = false },
        onPick = { first, last -> from = first.toString(); to = last.toString(); picking = false },
    )
}

@Composable
private fun FilterField(label: String, value: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalColors.current
    OutlinedBox(
        modifier.heightIn(min = 56.dp).semantics(mergeDescendants = true) { contentDescription = "$label, $value" },
        radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = CaptionStyle, color = c.muted)
                Text(value, style = sans(14, FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun FilterMenu(label: String, selected: String?, options: List<Pair<String, String>>, all: String, modifier: Modifier, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterField(label, options.firstOrNull { it.first == selected }?.second ?: all, Icons.Default.KeyboardArrowDown, Modifier.fillMaxWidth()) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(all, style = sans(14)) }, onClick = { onSelect(null); open = false })
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name, style = sans(14)) }, onClick = { onSelect(id); open = false })
            }
        }
    }
}
