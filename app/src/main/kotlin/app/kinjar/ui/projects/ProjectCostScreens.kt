package app.kinjar.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.LoadingOrError
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.SelectPill
import app.kinjar.ui.theme.sans

@Composable
fun PastCostScreen(onBack: () -> Unit, vm: PastCostViewModel = hiltViewModel()) {
    val loaded by vm.data.collectAsStateWithLifecycle()
    val failed by vm.loadFailed.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val quotes by vm.quotes.collectAsStateWithLifecycle()
    val manual by vm.manual.collectAsStateWithLifecycle()
    val write by vm.writes.state.collectAsStateWithLifecycle()
    LaunchedEffect(write.finished) { if (write.finished) onBack() }
    val back = { if (!write.busy) onBack() }
    val d = loaded
    val project = d?.project(vm.id)
    ProjectPage(stringResource(R.string.project_record_past), back) {
        when {
            d == null -> item { LoadingOrError(failed, vm::retry) }
            project == null -> section { Hint(stringResource(R.string.project_not_found)) }
            project.archived -> section { Hint(stringResource(R.string.project_archived_notice)) }
            else -> section {
                val c = LocalColors.current
                val currency = form.currency ?: project.currency
                val busy = write.busy
                Hint(stringResource(R.string.project_past_hint))
                AmountField(stringResource(R.string.project_amount), form.amount, currency) { text -> vm.edit { it.copy(amount = text) } }
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Currency.entries.forEach { option -> SelectPill(option.name, option == currency, { vm.edit { it.copy(currency = option) } }) }
                }
                DateField(stringResource(R.string.project_date), form.date, !busy) { picked -> vm.edit { it.copy(date = picked) } }
                val future = form.date > vm.clock.today()
                if (future) Hint(stringResource(R.string.project_future_date), c.orange)
                ProjectCategoryField(d.categories(project.id), form.categoryId, !busy) { id -> vm.edit { it.copy(categoryId = id) } }
                FieldBox(stringResource(R.string.project_note), form.note, { text -> vm.edit { it.copy(note = text.take(500)) } })
                val original = form.original(project)
                val (conversion, missing) = original?.let { vm.conversion(it, project.currency, form.date, quotes, manual) } ?: Pair(null, emptyList<RateKey>())
                if (original != null && !future) {
                    ConversionStatus(original, conversion?.converted, original.currency != project.currency && quotes[form.date] == null, missing.isEmpty())
                    RateInputs(false, missing, manual, vm::setRate, vm::retryRates)
                }
                WriteFeedback(vm.writes)
                PrimaryButton(stringResource(R.string.project_past_save), conversion != null && !future && !busy, vm::save)
            }
        }
    }
}

@Composable
fun CostReviewScreen(onBack: () -> Unit, vm: CostReviewViewModel = hiltViewModel()) {
    val loaded by vm.data.collectAsStateWithLifecycle()
    val failed by vm.loadFailed.collectAsStateWithLifecycle()
    val quotes by vm.quotes.collectAsStateWithLifecycle()
    val manual by vm.manual.collectAsStateWithLifecycle()
    val write by vm.writes.state.collectAsStateWithLifecycle()
    LaunchedEffect(write.finished) { if (write.finished) onBack() }
    val back = { if (!write.busy) onBack() }
    val d = loaded
    val review = d?.let(vm::review)
    ProjectPage(stringResource(R.string.project_review), back) {
        when {
            d == null -> item { LoadingOrError(failed, vm::retry) }
            review == null -> section { Hint(stringResource(R.string.project_review_gone)) }
            review.project.archived -> section { Hint(stringResource(R.string.project_archived_notice)) }
            else -> section {
                val c = LocalColors.current
                val (cost, tx) = review.cost to review.transaction
                val (conversion, missing) = vm.conversion(tx.money, review.project.currency, tx.date, quotes, manual)
                Hint(stringResource(R.string.project_review_hint))
                Hint(stringResource(R.string.project_review_saved))
                Text("${date(cost.date)} · ${money(cost.original)} = ${money(cost.converted)}", style = sans(15, FontWeight.Medium), color = c.text)
                Hint(stringResource(R.string.project_review_now))
                Text("${date(tx.date)} · ${money(tx.money)}", style = sans(15, FontWeight.Medium), color = c.text)
                ConversionStatus(tx.money, conversion?.converted, tx.money.currency != review.project.currency && quotes[tx.date] == null, missing.isEmpty())
                RateInputs(false, missing, manual, vm::setRate, vm::retryRates)
                WriteFeedback(vm.writes)
                PrimaryButton(stringResource(R.string.project_review_save), conversion != null && !write.busy, vm::save)
            }
        }
    }
}

@Composable
private fun ConversionStatus(original: Money, converted: Money?, loading: Boolean, complete: Boolean) {
    val c = LocalColors.current
    when {
        loading -> Hint(stringResource(R.string.project_rates_loading))
        converted == null && complete -> Hint(stringResource(R.string.project_too_small), c.orange)
        converted == null -> Unit
        converted == original -> Text(money(converted), style = sans(15, FontWeight.SemiBold), color = c.text)
        else -> Text(stringResource(R.string.project_converted, money(original), money(converted)), style = sans(15, FontWeight.SemiBold), color = c.text)
    }
}
