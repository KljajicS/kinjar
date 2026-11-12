package app.kinjar.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.theme.ConfirmDialog
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.LoadingOrError
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.SelectPill
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans

@Composable
fun NewProjectScreen(onBack: () -> Unit, onCreated: (String) -> Unit, vm: NewProjectViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val write by vm.writes.state.collectAsStateWithLifecycle()
    LaunchedEffect(write.finished) { if (write.finished) onCreated(form.id) }
    val c = LocalColors.current
    ProjectPage(stringResource(R.string.project_new), { if (!write.busy) onBack() }, footer = {
        WriteFeedback(vm.writes)
        PrimaryButton(stringResource(R.string.project_create), vm.canSave(form) && !write.busy, vm::save)
    }) {
        section {
            FieldBox(stringResource(R.string.project_name), form.name, { text -> vm.edit { it.copy(name = text.take(200)) } })
            Hint(stringResource(R.string.project_currency))
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Currency.EUR, Currency.RSD).forEach { option ->
                    SelectPill(option.name, option == form.currency, { vm.edit { it.copy(currency = option) } })
                }
            }
            AmountField(stringResource(R.string.project_budget_optional), form.budget, form.currency) { text -> vm.edit { it.copy(budget = text) } }
            if (budgetOf(form.budget, form.currency).isFailure) Hint(stringResource(R.string.project_budget_invalid), c.orange)
            Hint(stringResource(R.string.project_new_hint))
        }
    }
}

@Composable
fun CurrencyChangeScreen(onBack: () -> Unit, onDone: () -> Unit, vm: CurrencyChangeViewModel = hiltViewModel()) {
    val loaded by vm.data.collectAsStateWithLifecycle()
    val failed by vm.loadFailed.collectAsStateWithLifecycle()
    val quotes by vm.quotes.collectAsStateWithLifecycle()
    val manual by vm.manual.collectAsStateWithLifecycle()
    val typedBudget by vm.budget.collectAsStateWithLifecycle()
    val tooLarge by vm.tooLarge.collectAsStateWithLifecycle()
    val write by vm.writes.state.collectAsStateWithLifecycle()
    var confirming by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(write.finished) { if (write.finished) onDone() }
    val back = { if (!write.busy) onBack() }
    val title = stringResource(R.string.project_change_currency)
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
    val target = vm.target(project)
    val all = remember(d) { vm.costs(d) }
    val plan = remember(all, target, quotes, manual) { currencyPlan(all, target, quotes, manual) }
    val shown = remember(d) { d.book.costsFor(project.id).map { it.id }.toSet() }
    val before = sum(d.book.costsFor(project.id), project.currency)
    val after = plan.costs.filter { it.first.id in shown }.mapNotNull { it.second?.converted }.fold(Money.zero(target), Money::plus)
    val budgetText = typedBudget ?: vm.suggestedBudget(project, quotes)?.inputText().orEmpty()
    val budget = budgetOf(budgetText, target)
    val suggestion = vm.suggestedBudget(project, quotes)
    val failureText = if (tooLarge) R.string.project_currency_too_large else R.string.write_failed
    ProjectPage(title, back, footer = {
        WriteFeedback(vm.writes, failureText)
        PrimaryButton(stringResource(R.string.project_currency_apply, target.name), plan.ready && budget.isSuccess && !vm.budgetLoading(project, typedBudget, quotes) && !write.busy) {
            vm.writes.clearFailure(); confirming = true
        }
    }) {
        section("preview") {
            Hint(stringResource(R.string.project_currency_hint, project.currency.name, target.name))
            Hint(stringResource(R.string.project_total))
            Text(money(before), style = mono(18, FontWeight.SemiBold), color = c.text)
            Text(
                if (plan.ready) stringResource(R.string.project_currency_after, money(after)) else stringResource(R.string.project_currency_pending),
                style = mono(18, FontWeight.SemiBold), color = if (plan.ready) c.text else c.muted,
            )
            RateInputs(plan.loading, plan.missing, manual, vm::setRate, vm::retryRates)
        }
        section("budget") {
            project.budget?.let { old ->
                Hint(stringResource(R.string.project_currency_budget_old, money(old)))
                when {
                    suggestion != null -> Hint(stringResource(R.string.project_currency_budget_suggested, money(suggestion)))
                    quotes[vm.clock.today()] != null -> Hint(stringResource(R.string.project_currency_budget_manual), c.orange)
                }
            }
            FieldBox("${stringResource(R.string.project_budget_optional)} (${target.name})", budgetText, vm::setBudget)
            if (budget.isFailure) Hint(stringResource(R.string.project_budget_invalid), c.orange)
            budget.getOrNull()?.let { Hint(stringResource(R.string.project_currency_budget_new, money(it))) }
                ?: Hint(stringResource(R.string.project_currency_budget_none))
        }
    }
    if (confirming) ConfirmDialog(stringResource(R.string.project_currency_confirm_title, target.name), stringResource(R.string.project_currency_apply, target.name),
        write.busy, vm::apply, { confirming = false }) {
        Text(stringResource(R.string.project_currency_confirm, all.size, target.name), style = sans(14), color = c.text)
        Text(stringResource(R.string.project_currency_after, money(after)), style = sans(14, FontWeight.Medium), color = c.text)
        WriteFeedback(vm.writes, failureText)
    }
}
