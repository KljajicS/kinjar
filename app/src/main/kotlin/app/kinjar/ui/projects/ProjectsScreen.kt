package app.kinjar.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.domain.Money
import app.kinjar.ui.goals.GoalsViewModel
import app.kinjar.ui.goals.goalsGroup
import app.kinjar.ui.theme.AddButton
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.LoadingOrError
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.SelectPill
import app.kinjar.ui.theme.TitleStyle
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans

@Composable
fun ProjectsScreen(
    onNewProject: () -> Unit, onProject: (String) -> Unit, onNewGoal: () -> Unit, onGoal: (String) -> Unit,
    vm: ProjectsViewModel = hiltViewModel(), goals: GoalsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val failed by vm.loadFailed.collectAsStateWithLifecycle()
    var archived by rememberSaveable { mutableStateOf(false) }
    val c = LocalColors.current
    val list = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        state = list,
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "projects-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.projects_title), style = TitleStyle, color = c.text, modifier = Modifier.weight(1f))
                AddButton(stringResource(R.string.project_new), onNewProject)
            }
        }
        item(key = "projects-filter") {
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectPill(stringResource(R.string.project_active), !archived, { archived = false })
                SelectPill(stringResource(R.string.project_archived), archived, { archived = true })
            }
        }
        val all = state
        if (all == null) item(key = "projects-loading") { LoadingOrError(failed, vm::retry) }
        else {
            val shown = all.filter { it.project.archived == archived }
            if (shown.isEmpty()) item(key = "projects-empty") {
                Text(stringResource(if (archived) R.string.projects_archived_empty else R.string.projects_empty), style = BodyStyle, color = c.muted)
            }
            items(shown, key = { "project-${it.project.id}" }) { ProjectCard(it) { onProject(it.project.id) } }
        }
        goalsGroup(goals, list, onNewGoal, onGoal)
    }
}

@Composable
private fun ProjectCard(s: ProjectSummary, onClick: () -> Unit) {
    val c = LocalColors.current
    OutlinedBox(Modifier.fillMaxWidth(), radius = 16, onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.project.name, style = sans(16, FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                Text(money(s.total), style = mono(15, FontWeight.SemiBold), color = c.text)
            }
            ColorBar(s.byCategory)
            BudgetLine(s.total, s.project.budget, includesUnpaid = !s.unpaid.isZero)
            if (!s.unpaid.isZero) PaymentTotals(s.total - s.unpaid, s.unpaid)
        }
    }
}

@Composable
fun BudgetLine(spent: Money, budget: Money?, includesUnpaid: Boolean = false) {
    val over = budget != null && spent.amount > budget.amount
    Hint(
        when {
            budget == null -> stringResource(if (includesUnpaid) R.string.project_total else R.string.project_spent_lifetime)
            over -> stringResource(R.string.project_over_budget, money(spent - budget), money(budget))
            else -> stringResource(R.string.project_budget_left, money(budget - spent), money(budget))
        },
        if (over) LocalColors.current.orange else LocalColors.current.muted,
    )
}

@Composable
fun PaymentTotals(paid: Money, unpaid: Money) {
    Hint(stringResource(R.string.project_payment_totals, money(paid), money(unpaid)))
}
