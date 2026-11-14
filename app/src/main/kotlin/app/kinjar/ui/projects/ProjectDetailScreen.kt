package app.kinjar.ui.projects

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.domain.Project
import app.kinjar.domain.ProjectCost
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.theme.ConfirmDialog
import app.kinjar.ui.theme.LoadingOrError
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.RoundIconButton
import app.kinjar.ui.theme.SectionStyle
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans
import java.time.YearMonth

private const val RECENT = 5

class ProjectActions(
    val onBack: () -> Unit,
    val onSettings: () -> Unit = {},
    val onLink: () -> Unit = {},
    val onAllCosts: () -> Unit = {},
    val onReview: (String) -> Unit = {},
    val onSettle: (String) -> Unit = {},
    val onEdit: (ProjectCost) -> Unit = {},
)

@Composable
fun ProjectDetailScreen(actions: ProjectActions, all: Boolean = false, vm: ProjectViewModel = hiltViewModel()) {
    val loaded by vm.data.collectAsStateWithLifecycle()
    val failed by vm.loadFailed.collectAsStateWithLifecycle()
    val write by vm.writes.state.collectAsStateWithLifecycle()
    // A removed cost drops out of the book, which closes its dialog; removal ids are never reused.
    var acting by rememberSaveable { mutableStateOf<String?>(null) }
    val back = { if (!write.busy) actions.onBack() }
    val d = loaded
    val project = d?.project(vm.id)
    if (d == null || project == null) {
        ProjectPage(stringResource(R.string.projects_title), back) {
            if (d == null) item { LoadingOrError(failed, vm::retry) } else section { Hint(stringResource(R.string.project_not_found)) }
        }
        return
    }
    val c = LocalColors.current
    val costs = remember(d, project.id) { d.book.costsFor(project.id) }
    val stored = remember(d, project.id) { d.book.costs.filter { it.projectId == project.id }.sortedByDescending { it.date } }
    val stale = remember(d, stored) { stored.filter(d.book::needsReview).map { it.id }.toSet() }
    val settings: @Composable RowScope.() -> Unit = {
        if (!all) RoundIconButton(Icons.Outlined.Settings, stringResource(R.string.project_settings), actions.onSettings)
    }
    ProjectPage(if (all) stringResource(R.string.project_all_costs) else project.name, back, settings) {
        if (!all) overview(project, d, costs, stale, actions, YearMonth.from(vm.clock.today()))
        if (costs.isEmpty()) section("empty") { Hint(stringResource(R.string.project_costs_empty)) }
        items(if (all) stored else costs.take(RECENT), key = { it.id }) { cost ->
            CostRow(cost, d, cost.id in stale) { vm.writes.clearFailure(); acting = cost.id }
        }
        if (!all && (stored.size > RECENT || stored.size != costs.size)) item("all") {
            TextButton(onClick = actions.onAllCosts) { Text(stringResource(R.string.project_see_all, stored.size), color = c.primary) }
        }
    }
    stored.find { it.id == acting }?.let { cost ->
        CostDialog(cost, project, cost.id in stale, write.busy, vm,
            onEdit = { acting = null; actions.onEdit(cost) },
            onReview = { acting = null; actions.onReview(cost.id) },
            onSettle = { acting = null; actions.onSettle(cost.id) }) { acting = null }
    }
}

private fun LazyListScope.overview(
    project: Project, d: ProjectData, costs: List<ProjectCost>, stale: Set<String>, actions: ProjectActions, month: YearMonth,
) {
    section("overview") {
        val c = LocalColors.current
        val total = sum(costs, project.currency)
        if (project.archived) Hint(stringResource(R.string.project_archived_notice), c.orange)
        Hint(stringResource(R.string.project_total))
        Text(money(total), style = mono(32, FontWeight.SemiBold), color = c.text)
        BudgetLine(total, project.budget, includesUnpaid = costs.any { it.unpaid })
        if (costs.any { it.unpaid }) PaymentTotals(sum(costs.filterNot { it.unpaid }, project.currency), sum(costs.filter { it.unpaid }, project.currency))
        if (stale.isNotEmpty()) Hint(stringResource(R.string.project_review_needed, stale.size), c.orange)
        ProjectChart(costs, d.categories(project.id), project.currency, month)
        if (!project.archived) {
            PrimaryButton(stringResource(R.string.project_link), onClick = actions.onLink)
        }
        Text(stringResource(R.string.project_recent), style = SectionStyle, color = c.text)
    }
}

@Composable
private fun CostDialog(cost: ProjectCost, project: Project, stale: Boolean, busy: Boolean, vm: ProjectViewModel, onEdit: () -> Unit, onReview: () -> Unit, onSettle: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val linked = cost.transactionId != null
    var confirming by rememberSaveable(cost.id) { mutableStateOf(false) }
    val title = stringResource(if (linked) R.string.project_unlink_title else R.string.project_remove_title)
    ConfirmDialog(
        if (confirming) title else stringResource(R.string.project_cost_details),
        stringResource(if (confirming) (if (linked) R.string.project_unlink else R.string.project_remove) else R.string.dialog_ok),
        busy,
        onConfirm = { if (confirming) vm.removeCost(cost) else onDismiss() },
        onDismiss = { if (confirming) confirming = false else onDismiss() },
    ) {
        if (confirming) {
            Text(stringResource(if (linked) R.string.project_unlink_body else R.string.project_remove_body), style = sans(14), color = c.text)
        } else {
            if (cost.unpaid) Hint(stringResource(R.string.project_unpaid), c.orange)
            Text("${date(cost.date)} · ${money(cost.converted)}", style = sans(14, FontWeight.Medium), color = c.text)
            if (cost.original != cost.converted) {
                Hint(stringResource(R.string.project_original_amount, money(cost.original)))
                Hint(stringResource(R.string.project_rate_source, cost.rateSource, date(cost.rateDate)))
            }
            if (cost.note.isNotBlank() && linked) Hint(cost.note)
            if (stale) Hint(stringResource(R.string.project_cost_changed), c.orange)
            if (!project.archived) {
                TextButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.transaction_edit), color = c.primary) }
                if (cost.unpaid) TextButton(onClick = onSettle, enabled = !busy) { Text(stringResource(R.string.project_link_payment), color = c.primary) }
                if (stale) TextButton(onClick = onReview, enabled = !busy) { Text(stringResource(R.string.project_review), color = c.primary) }
                TextButton(onClick = { confirming = true }, enabled = !busy) {
                    Text(stringResource(if (linked) R.string.project_unlink else R.string.project_remove), color = c.orange)
                }
            } else Hint(stringResource(R.string.project_archived_notice))
        }
        WriteFeedback(vm.writes)
    }
}
