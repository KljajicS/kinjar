package app.kinjar.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.domain.Category
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.LoadingOrError
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.colorOf
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans

@Composable
fun ProjectLinkScreen(onBack: () -> Unit, vm: ProjectLinkViewModel = hiltViewModel()) {
    val loaded by vm.data.collectAsStateWithLifecycle()
    val failed by vm.loadFailed.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val quotes by vm.quotes.collectAsStateWithLifecycle()
    val manual by vm.manual.collectAsStateWithLifecycle()
    val write by vm.writes.state.collectAsStateWithLifecycle()
    LaunchedEffect(write.finished) { if (write.finished) onBack() }
    val back = { if (!write.busy) onBack() }
    val title = stringResource(R.string.project_link)
    val d = loaded
    val project = d?.project(vm.id)
    if (d == null || project == null || project.archived) {
        ProjectPage(title, back) {
            when {
                d == null -> item { LoadingOrError(failed, vm::retry) }
                project == null -> section { Hint(stringResource(R.string.project_not_found)) }
                else -> section { Hint(stringResource(R.string.project_archived_notice)) }
            }
        }
        return
    }
    val c = LocalColors.current
    val today = vm.clock.today()
    val candidates = remember(d, project, today) { d.candidates(project, today) }
    val names = d.household.associate { it.id to categoryDisplayName(it.id, it.name) }
    val accountNames = remember(d) { d.accounts.associate { it.id to it.name } }
    val query = form.query.trim().lowercase()
    val visible = if (query.isEmpty()) candidates else candidates.filter { tx ->
        listOf(tx.merchant, tx.note, names[tx.categoryId].orEmpty(), tx.money.amount.toPlainString()).any { it.lowercase().contains(query) }
    }
    val chosen = remember(candidates, form.selected) { candidates.filter { it.id in form.selected } }
    val plan = remember(chosen, project, quotes, manual) { linkPlan(chosen, project, quotes, manual) }
    val total = plan.items.mapNotNull { it.second?.converted }.fold(Money.zero(project.currency), Money::plus)
    val waiting = plan.items.count { it.second == null }

    ProjectPage(title, back, footer = {
        Text(stringResource(R.string.project_link_summary, chosen.size, money(total)), style = sans(14, FontWeight.Medium), color = c.text)
        if (waiting > 0) Hint(stringResource(R.string.project_link_waiting, waiting), c.orange)
        WriteFeedback(vm.writes)
        PrimaryButton(stringResource(R.string.project_link_save, chosen.size), plan.ready && !write.busy, vm::save)
    }) {
        section("controls") {
            vm.fresh?.let { fresh ->
                when {
                    candidates.none { it.id == fresh } -> Hint(stringResource(R.string.project_fresh_ineligible), c.orange)
                    fresh in form.selected -> Hint(stringResource(R.string.project_fresh_selected), c.text)
                }
            }
            SourceCategories(project.sourceCategoryIds.mapNotNull(d.householdById::get), names)
            ProjectCategoryField(d.categories(project.id), form.categoryId, !write.busy, vm::setCategory)
            FieldBox(stringResource(R.string.project_search), form.query, vm::setQuery)
            RateInputs(plan.loading, plan.missing, manual, vm::setRate, vm::retryRates)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.selectAll(visible.map { it.id }) }, enabled = visible.isNotEmpty()) {
                    Text(stringResource(R.string.project_select_all, visible.size), color = c.primary)
                }
                TextButton(onClick = vm::clear, enabled = form.selected.isNotEmpty()) { Text(stringResource(R.string.project_clear_selection), color = c.primary) }
            }
            if (form.limited) Hint(stringResource(R.string.project_link_limit, MAX_BATCH), c.orange)
            if (visible.isEmpty()) Hint(stringResource(if (candidates.isEmpty()) R.string.project_link_none else R.string.project_link_no_match))
        }
        items(visible, key = { it.id }) { tx ->
            Candidate(tx, tx.id in form.selected, d.householdById[tx.categoryId], names[tx.categoryId], accountNames[tx.accountId]) { vm.toggle(tx.id) }
        }
    }
}

@Composable
private fun SourceCategories(categories: List<Category>, names: Map<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Hint(stringResource(R.string.project_link_sources))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ColorDot(colorOf(category.color))
                    Hint(names[category.id] ?: category.name, LocalColors.current.text)
                }
            }
        }
    }
}

@Composable
private fun Candidate(tx: Transaction, selected: Boolean, category: Category?, categoryName: String?, account: String?, onToggle: () -> Unit) {
    val c = LocalColors.current
    OutlinedBox(Modifier.fillMaxWidth().toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() }), radius = 14, selected = selected) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Checkbox(selected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = c.primary, uncheckedColor = c.muted))
            ColorDot(category?.let { colorOf(it.color) } ?: c.muted)
            Column(Modifier.weight(1f)) {
                Text(tx.merchant.ifBlank { categoryName.orEmpty() }, style = sans(15, FontWeight.Medium), color = c.text, maxLines = 2)
                Hint(listOfNotNull(date(tx.date), categoryName, account).joinToString(" · "))
            }
            Text(money(tx.money), style = mono(14, FontWeight.SemiBold), color = c.text)
        }
    }
}
