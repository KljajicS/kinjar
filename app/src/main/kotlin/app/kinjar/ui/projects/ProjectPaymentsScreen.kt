package app.kinjar.ui.projects

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.domain.*
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.parseMoneyInput
import app.kinjar.ui.theme.*
import kotlinx.serialization.encodeToString

@Composable
fun ProjectPaymentsScreen(onBack: () -> Unit, income: Boolean = false, tabs: @Composable (Boolean) -> Unit = {}, vm: ProjectPaymentsViewModel = hiltViewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val write by vm.writes.state.collectAsStateWithLifecycle()
    LaunchedEffect(write.finished) { if (write.finished) onBack() }
    val d = data
    val project = d?.project(vm.id)
    if (d == null || project == null) {
        ProjectPage(stringResource(R.string.project_payments), onBack) { item { LoadingOrError(failed, vm::retry) } }
        return
    }
    val back = { if (!write.busy) onBack() }
    if (income || vm.mode == "settle") BulkSettlementScreen(project, d, vm, back, income, tabs)
    else PaymentHistory(project, d, vm, back)
}

@Composable
private fun financeBase(d: ProjectData, id: String): ProjectFinance {
    val json = rememberSaveable(id) { EventJson.encodeToString(d.book.finance(id)) }
    return remember(json) { EventJson.decodeFromString<ProjectFinance>(json) }
}

@Composable
private fun PaymentHistory(project: Project, d: ProjectData, vm: ProjectPaymentsViewModel, back: () -> Unit) {
    val finance = d.book.finance(project.id)
    val write by vm.writes.state.collectAsStateWithLifecycle()
    var removal by rememberSaveable { mutableStateOf<String?>(null) }
    var expectedJson by rememberSaveable { mutableStateOf("") }
    ProjectPage(stringResource(R.string.project_payments), back) {
        section {
            if (finance.settlements.isEmpty()) Hint(stringResource(R.string.project_history_empty))
            finance.settlements.forEach { settlement ->
                OutlinedBox(Modifier.fillMaxWidth(), radius = 14) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${date(settlement.payment.date)} · ${money(settlement.payment.converted)}", color = LocalColors.current.text)
                        if (settlement.type == TransactionType.INCOME) Hint(stringResource(R.string.project_income_linked))
                        settlement.allocations.forEach { (id, paid) ->
                            val cost = d.book.costs.find { it.id == id }
                            Text("${cost?.note.orEmpty()} · ${money(paid)}", color = LocalColors.current.text)
                            cost?.let { Hint(stringResource(R.string.project_estimate, money(it.converted))) }
                        }
                        if (d.book.paymentNeedsReview(settlement.payment, settlement.type)) Hint(stringResource(R.string.project_payment_changed), LocalColors.current.orange)
                        if (!project.archived) TextButton(onClick = { removal = settlement.id; expectedJson = EventJson.encodeToString(finance) }, enabled = !write.busy) { Text(stringResource(R.string.project_undo_settlement)) }
                    }
                }
            }
            WriteFeedback(vm.writes)
        }
    }
    removal?.let { id ->
        ConfirmDialog(stringResource(R.string.project_undo_settlement), stringResource(R.string.dialog_ok), write.busy,
            onConfirm = {
                val expected = EventJson.decodeFromString<ProjectFinance>(expectedJson)
                vm.save(expected.copy(settlements = expected.settlements.filterNot { it.id == id }), expected, finish = false)
                removal = null
            }, onDismiss = { if (!write.busy) removal = null }) { Hint(stringResource(R.string.project_remove_payment_body)) }
    }
}

@Composable
private fun PaymentChoice(tx: Transaction, checked: Boolean, select: () -> Unit) {
    OutlinedBox(Modifier.fillMaxWidth(), radius = 14, onClick = select) {
        Row {
            Checkbox(checked, { select() })
            Column {
                Text("${tx.merchant.ifBlank { tx.note }} · ${money(tx.money)}", color = LocalColors.current.text)
                Hint(date(tx.date))
            }
        }
    }
}

@Composable
private fun BulkSettlementScreen(project: Project, d: ProjectData, vm: ProjectPaymentsViewModel, back: () -> Unit, income: Boolean, tabs: @Composable (Boolean) -> Unit) {
    val base = financeBase(d, project.id)
    val sourceJson = rememberSaveable { EventJson.encodeToString(d.book.costs.filter { it.projectId == project.id && it.unpaid && d.book.settlementFor(it.id) == null }) }
    val sources = remember(sourceJson) { EventJson.decodeFromString<List<ProjectCost>>(sourceJson) }
    var selected by rememberSaveable { mutableStateOf(ArrayList(vm.selected)) }
    var stage by rememberSaveable { mutableIntStateOf(if (income && vm.selected.isEmpty()) 0 else 1) }
    var txId by rememberSaveable { mutableStateOf<String?>(null) }
    var carryDifference by rememberSaveable { mutableStateOf(true) }
    var inputsJson by rememberSaveable { mutableStateOf("{}") }
    val inputs = EventJson.decodeFromString<Map<String, String>>(inputsJson)
    val settlementId = rememberSaveable { newId() }
    val quotes by vm.quotes.collectAsStateWithLifecycle()
    val manual by vm.manual.collectAsStateWithLifecycle()
    val write by vm.writes.state.collectAsStateWithLifecycle()
    val chosen = sources.filter { it.id in selected }
    val candidates = if (income) d.book.transactions.filter {
        it.type == TransactionType.INCOME && it.id !in d.book.linkedTransactionIds() && it.date <= vm.clock.today()
    }.sortedWith(compareByDescending<Transaction> { it.date }.thenBy { it.id }) else d.candidates(project, vm.clock.today())
    val tx = candidates.find { it.id == txId }
    LaunchedEffect(txId) { tx?.let(vm::ratesFor) }
    val plan = linkPlan(listOfNotNull(tx), project, quotes, manual)
    val payment = plan.items.singleOrNull()?.let { (t, conversion) -> conversion?.let { paymentSnapshot(t, it) } }
    val allocations = if (income) chosen.associate { it.id to it.converted } else chosen.mapNotNull { cost -> parseMoneyInput(inputs[cost.id] ?: cost.converted.inputText(), project.currency)?.takeIf { !it.isNegative && !it.isZero }?.let { cost.id to it } }.toMap()
    val assigned = allocations.values.fold(Money.zero(project.currency), Money::plus)
    val stale = chosen.size != selected.size || base != d.book.finance(project.id) || chosen.any { source -> d.book.costs.find { it.id == source.id } != source }
    val valid = !stale && !project.archived && chosen.isNotEmpty() && chosen.size <= MAX_BATCH && allocations.size == chosen.size && payment != null && (income && carryDifference || payment.converted == assigned)
    val goBack = { if (!write.busy) { if (stage > if (income && vm.selected.isEmpty()) 0 else 1) stage-- else back() } }
    val chooseLabel = if (income) R.string.project_choose_income else R.string.project_choose_expense
    key(stage) {
        ProjectPage(stringResource(if (income && stage == 1) R.string.project_received_incomes else if (income && stage == 2) R.string.project_review_income else if (income) R.string.project_link_unpaid_income else if (stage == 1) chooseLabel else R.string.project_adjust_allocations), goBack, footer = {
            if (stale) Hint(stringResource(R.string.project_payment_stale), LocalColors.current.orange)
            WriteFeedback(vm.writes)
            PrimaryButton(stringResource(if (stage == 1 && income) R.string.project_review_income else if (stage == 2 || stage == 1 && valid) R.string.project_mark_selected_paid else chooseLabel), !write.busy && !stale && !project.archived && when (stage) { 0 -> chosen.size in 1..MAX_BATCH; 1 -> chosen.size in 1..MAX_BATCH && payment != null; else -> valid }) {
                if (stage == 0 || stage == 1 && (income || !valid)) stage++ else vm.save(base.copy(settlements = base.settlements + ProjectSettlement(settlementId, requireNotNull(payment), allocations, if (income) TransactionType.INCOME else TransactionType.EXPENSE)), base, chosen)
            }
        }) {
            if (stage == 0) item("tabs") { tabs(write.busy) }
            section {
                Hint(stringResource(R.string.project_selected_total, chosen.size, money(sum(chosen, project.currency))))
                when (stage) {
                    0 -> {
                        Hint(stringResource(R.string.project_income_settle_hint))
                        if (sources.isEmpty()) Hint(stringResource(R.string.project_unpaid_gone))
                        sources.forEach { cost ->
                            OutlinedBox(Modifier.fillMaxWidth(), radius = 14, selected = cost.id in selected,
                                onClick = { selected = ArrayList(if (cost.id in selected) selected - cost.id else (selected + cost.id).take(MAX_BATCH)) }) {
                                Row {
                                    Checkbox(cost.id in selected, null)
                                    Column { Text(cost.note.ifBlank { stringResource(R.string.project_unpaid) }, color = LocalColors.current.text); Hint(money(cost.converted)) }
                                }
                            }
                        }
                    }
                    1 -> {
                        if (candidates.isEmpty()) Hint(stringResource(if (income) R.string.project_income_none else R.string.project_payment_no_candidates))
                        candidates.forEach { t -> PaymentChoice(t, t.id == txId) { txId = t.id } }
                        RateInputs(plan.loading, plan.missing, manual, vm::setRate, vm::retryRates)
                    }
                    else -> {
                        if (income) {
                            payment?.let {
                                Hint(stringResource(R.string.project_income_received, money(it.converted)))
                                chosen.forEach { cost ->
                                    Text("${cost.note.ifBlank { stringResource(R.string.project_unpaid) }} · ${money(cost.converted)}", color = LocalColors.current.text)
                                }
                                val difference = it.converted - assigned
                                val balance = d.book.incomeBalance(project.id) + difference
                                Hint(stringResource(R.string.project_income_keep_costs))
                                Row(Modifier.fillMaxWidth().toggleable(carryDifference, enabled = !write.busy, role = Role.Checkbox) { checked -> carryDifference = checked }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(carryDifference, null, enabled = !write.busy)
                                    Text(stringResource(R.string.project_carry_difference), color = LocalColors.current.text)
                                }
                                if (!carryDifference && !difference.isZero) {
                                    Hint(stringResource(R.string.project_carry_required), LocalColors.current.red)
                                } else {
                                    Hint(stringResource(when { difference.isNegative -> R.string.project_income_shortfall; difference.isZero -> R.string.project_income_exact; else -> R.string.project_income_credit }, money(if (difference.isNegative) -difference else difference)), if (difference.isNegative) LocalColors.current.red else LocalColors.current.green)
                                    Hint(stringResource(R.string.project_income_resulting_balance, money(balance)), if (balance.isNegative) LocalColors.current.red else LocalColors.current.green)
                                }
                                val remaining = sources.filter { cost -> cost.id !in selected }
                                if (!difference.isNegative && !difference.isZero && remaining.isNotEmpty()) {
                                    Hint(stringResource(R.string.project_income_other_unpaid, remaining.size, money(sum(remaining, project.currency)), money(difference)), LocalColors.current.orange)
                                    TextButton(onClick = { stage = 0 }, enabled = !write.busy) { Text(stringResource(R.string.project_change_selection)) }
                                }
                            }
                        } else {
                            payment?.let { Hint(stringResource(R.string.project_selected_payment, money(it.converted))) }
                            Hint(stringResource(R.string.project_allocation_hint))
                            chosen.forEach { cost ->
                                AmountField(cost.note.ifBlank { stringResource(R.string.project_unpaid) }, inputs[cost.id] ?: cost.converted.inputText(), project.currency) { text -> inputsJson = EventJson.encodeToString(inputs + (cost.id to text)) }
                                Hint(stringResource(R.string.project_estimate, money(cost.converted)))
                            }
                            payment?.let { Hint(stringResource(R.string.project_unassigned, money(it.converted - assigned)), if (valid) LocalColors.current.green else LocalColors.current.red) }
                        }
                    }
                }
            }
        }
    }
}
