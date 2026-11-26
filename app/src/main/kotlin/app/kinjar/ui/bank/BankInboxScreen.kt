package app.kinjar.ui.bank

import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.kinjar.R
import app.kinjar.data.BankDraft
import app.kinjar.data.BankInbox
import app.kinjar.data.BudgetRepository
import app.kinjar.domain.Account
import app.kinjar.domain.BankApp
import app.kinjar.domain.CategoryKind
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import app.kinjar.domain.bankMovement
import app.kinjar.domain.newId
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.WriteOperation
import app.kinjar.ui.add.AccountPicker
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.formatAmount
import app.kinjar.ui.formatDate
import app.kinjar.ui.parseMoneyInput
import app.kinjar.ui.settings.LoadFailedBanner
import app.kinjar.ui.settings.Loadable
import app.kinjar.ui.settings.ScreenHeader
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.KinjarDatePickerDialog
import app.kinjar.ui.theme.LabelStyle
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.SelectPill
import app.kinjar.ui.theme.mono
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.combine

data class BankInboxState(val drafts: List<BankDraft>, val bindings: Map<String, BankApp>, val accounts: List<Account>) {
    fun suggestedAccount(draft: BankDraft): Account? = accounts.singleOrNull {
        bindings[it.id] == draft.bank && it.currency == draft.parsed.money?.currency
    }

    fun forAccount(accountId: String?): List<BankDraft> = if (accountId == null) drafts else {
        drafts.filter { bindings[accountId] == it.bank && suggestedAccount(it)?.id.let { id -> id == null || id == accountId } }
    }
}

@HiltViewModel
class BankInboxViewModel @Inject constructor(private val repo: BudgetRepository, private val inbox: BankInbox) : ViewModel() {
    val load = Loadable(viewModelScope, combine(inbox.drafts, inbox.bindings, repo.accounts, ::BankInboxState))
    val reviewData = Loadable(viewModelScope, combine(repo.categories, repo.financial, ::Pair))
    val writes = WriteOperation(viewModelScope)

    fun bind(accountId: String, bank: BankApp?) = writes.run(finish = false) { inbox.bind(accountId, bank) }
    fun skip(id: String) = writes.run { inbox.skip(id) }
    fun save(id: String, transaction: Transaction) = writes.run {
        repo.saveBankDraft(id, transaction.copy(paidBy = requireNotNull(repo.currentMember()).id))
    }
    fun link(id: String, transactionId: String, accountId: String, movement: Money) = writes.run {
        repo.linkBankDraft(id, transactionId, accountId, movement)
    }
}

@StringRes
private fun BankApp.label(): Int = when (this) {
    BankApp.RAIFFEISEN -> R.string.bank_raiffeisen
    BankApp.YETTEL -> R.string.bank_yettel
}

@StringRes
private fun TransactionType.label(): Int = when (this) {
    TransactionType.EXPENSE -> R.string.bank_expense
    TransactionType.INCOME -> R.string.bank_income
    TransactionType.TRANSFER -> R.string.bank_transfer
    TransactionType.LOAN_RECEIPT -> R.string.bank_income
}

@Composable
fun BankInboxEntry(onOpen: () -> Unit, vm: BankInboxViewModel = hiltViewModel()) {
    val state by vm.load.state.collectAsStateWithLifecycle()
    val failed by vm.load.failed.collectAsStateWithLifecycle()
    val count = state?.drafts?.size ?: 0
    if (count > 0) TextButton(onClick = onOpen) {
        Text(pluralStringResource(R.plurals.bank_pending, count, count), color = LocalColors.current.primary)
    }
    if (failed) LoadFailedBanner(vm.load::retry)
}

@Composable
fun BankAccountSettings(accountId: String, onInbox: () -> Unit, vm: BankInboxViewModel = hiltViewModel()) {
    val state by vm.load.state.collectAsStateWithLifecycle()
    val failed by vm.load.failed.collectAsStateWithLifecycle()
    val writes by vm.writes.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun hasAccess() = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    var allowed by remember { mutableStateOf(hasAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = hasAccess() }
    val s = state ?: run {
        if (failed) LoadFailedBanner(vm.load::retry)
        return
    }
    val bank = s.bindings[accountId]
    Text(stringResource(R.string.bank_notifications), style = BodyStyle, color = LocalColors.current.text)
    Picker(
        stringResource(R.string.bank_app),
        bank?.let { stringResource(it.label()) } ?: stringResource(R.string.bank_off),
        listOf(null to stringResource(R.string.bank_off)) + BankApp.entries.map { it to stringResource(it.label()) },
        enabled = !writes.busy,
    ) { vm.bind(accountId, it) }
    if (bank != null) {
        if (!allowed) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
            Text(stringResource(R.string.bank_grant_access))
        }
        Text(stringResource(R.string.bank_review_before_save), style = LabelStyle, color = LocalColors.current.muted)
        val count = s.forAccount(accountId).size
        TextButton(onClick = onInbox) { Text(pluralStringResource(R.plurals.bank_pending, count, count)) }
    }
    WriteFeedback(vm.writes)
}

@Composable
fun BankInboxScreen(accountId: String?, onBack: () -> Unit, onReview: (String) -> Unit, vm: BankInboxViewModel = hiltViewModel()) {
    val state by vm.load.state.collectAsStateWithLifecycle()
    val failed by vm.load.failed.collectAsStateWithLifecycle()
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    Column(Modifier.fillMaxSize().background(c.bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(stringResource(R.string.bank_notifications), onBack)
        if (failed) LoadFailedBanner(vm.load::retry)
        val drafts = state?.forAccount(accountId) ?: return@Column
        if (drafts.isEmpty()) Text(stringResource(R.string.bank_empty), style = BodyStyle, color = c.muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(drafts, key = { it.alert.id }) { draft ->
                OutlinedBox(Modifier.fillMaxWidth().clickable(role = Role.Button) { onReview(draft.alert.id) }, radius = 16, padding = PaddingValues(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(draft.parsed.counterparty ?: stringResource(R.string.bank_review), style = BodyStyle, color = c.text)
                            Text(stringResource(draft.bank.label()), style = LabelStyle, color = c.muted)
                        }
                        draft.parsed.money?.let { Text("${formatAmount(it, locale = locale)} ${it.currency}", color = c.text, style = mono(13)) }
                    }
                }
            }
        }
    }
}

@Composable
fun BankReviewScreen(id: String, onBack: () -> Unit, vm: BankInboxViewModel = hiltViewModel()) {
    val state by vm.load.state.collectAsStateWithLifecycle()
    val data by vm.reviewData.state.collectAsStateWithLifecycle()
    val inboxFailed by vm.load.failed.collectAsStateWithLifecycle()
    val dataFailed by vm.reviewData.failed.collectAsStateWithLifecycle()
    val writes by vm.writes.state.collectAsStateWithLifecycle()
    LaunchedEffect(writes.finished) { if (writes.finished) onBack() }
    val c = LocalColors.current
    Column(Modifier.fillMaxSize().background(c.bg).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(stringResource(R.string.bank_review), onBack)
        if (inboxFailed) LoadFailedBanner(vm.load::retry)
        if (dataFailed) LoadFailedBanner(vm.reviewData::retry)
        val s = state ?: return@Column
        val (categories, financial) = data ?: return@Column
        val draft = s.drafts.firstOrNull { it.alert.id == id }
        if (draft == null) {
            if (!writes.finished && !writes.busy) Text(stringResource(R.string.bank_unavailable), color = c.muted)
            return@Column
        }
        val parsed = draft.parsed
        var accountId by rememberSaveable(id) { mutableStateOf(s.suggestedAccount(draft)?.id) }
        var otherId by rememberSaveable(id) { mutableStateOf<String?>(null) }
        var type by rememberSaveable(id) { mutableStateOf(parsed.direction) }
        var incoming by rememberSaveable(id) { mutableStateOf(parsed.direction == TransactionType.INCOME) }
        var amount by rememberSaveable(id) { mutableStateOf(parsed.money?.amount?.toPlainString().orEmpty()) }
        var otherAmount by rememberSaveable(id) { mutableStateOf("") }
        var categoryId by rememberSaveable(id) { mutableStateOf<String?>(null) }
        var merchant by rememberSaveable(id) { mutableStateOf(parsed.counterparty.orEmpty()) }
        var dateText by rememberSaveable(id) {
            mutableStateOf((parsed.date ?: Instant.ofEpochMilli(draft.alert.received_at).atZone(ZoneId.systemDefault()).toLocalDate()).toString())
        }
        var datePicker by rememberSaveable(id) { mutableStateOf(false) }
        var accountPicker by rememberSaveable(id) { mutableStateOf<String?>(null) }
        var original by rememberSaveable(id) { mutableStateOf(false) }
        var linking by rememberSaveable(id) { mutableStateOf(false) }
        val date = LocalDate.parse(dateText)
        val account = s.accounts.find { it.id == accountId }
        val other = s.accounts.find { it.id == otherId }
        val movement = account?.let { parseMoneyInput(amount, it.currency) }?.takeUnless { it.isZero }
        val transfer = type == TransactionType.TRANSFER
        val fx = transfer && other != null && account != null && other.currency != account.currency
        val otherMoney = other?.let { parseMoneyInput(otherAmount, it.currency) }?.takeUnless { it.isZero }
        val validCategories = categories.filter { it.kind == if (type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE }
        val category = validCategories.find { it.id == categoryId }
        val canSave = account != null && movement != null && type != null && (if (transfer) other != null && other.id != account.id && (!fx || otherMoney != null) else category != null)
        val locale = LocalLocale.current.platformLocale
        val signed = if (type == TransactionType.INCOME || transfer && incoming) movement else movement?.let { -it }
        val matches = if (account == null || signed == null) emptyList() else financial.transactions.filter {
            it.date == date && it.bankMovement(account.id) == signed && (type == null || it.type == type)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(draft.bank.label()), style = LabelStyle, color = c.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.TRANSFER).forEach { value ->
                    SelectPill(stringResource(value.label()), type == value, {
                        type = value
                        categoryId = null
                        linking = false
                    })
                }
            }
            FieldBox(stringResource(R.string.bank_amount, account?.currency?.name ?: parsed.money?.currency?.name.orEmpty()), amount,
                { amount = it; linking = false }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            FieldBox(stringResource(R.string.bank_counterparty), merchant, { merchant = it })
            ReviewField(stringResource(R.string.bank_account), account?.name ?: stringResource(R.string.bank_choose), enabled = !writes.busy) { accountPicker = "bank" }
            if (transfer) {
                if (!parsed.cashWithdrawal) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectPill(stringResource(R.string.bank_money_out), !incoming, { incoming = false; linking = false })
                    SelectPill(stringResource(R.string.bank_money_in), incoming, { incoming = true; linking = false })
                }
                ReviewField(stringResource(if (incoming) R.string.bank_from else R.string.bank_to), other?.name ?: stringResource(R.string.bank_choose),
                    enabled = !writes.busy) { accountPicker = "other" }
                if (fx) FieldBox(stringResource(R.string.bank_amount, requireNotNull(other).currency.name), otherAmount,
                    { otherAmount = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            } else if (type != null) {
                Picker(stringResource(R.string.bank_category), category?.let { categoryDisplayName(it.id, it.name) } ?: stringResource(R.string.bank_choose),
                    validCategories.map { it.id to categoryDisplayName(it.id, it.name) }, enabled = !writes.busy) { categoryId = it }
            }
            ReviewField(stringResource(R.string.bank_date), formatDate(date, locale), enabled = !writes.busy) { datePicker = true }
            parsed.balances.forEach { balance ->
                val amountLabel = "${formatAmount(Money.of(balance.amount, balance.currency), locale = locale)} ${balance.currency}"
                Text(stringResource(if (balance.available) R.string.bank_available else R.string.bank_reported, amountLabel), style = LabelStyle, color = c.muted)
            }
            if (matches.isNotEmpty()) {
                TextButton(onClick = { linking = !linking }, enabled = !writes.busy) { Text(stringResource(R.string.bank_link)) }
                if (linking) matches.forEach { t ->
                    ReviewField(stringResource(t.type.label()), t.merchant.ifBlank { formatDate(t.date, locale) }, enabled = !writes.busy) {
                        vm.link(id, t.id, requireNotNull(account).id, requireNotNull(signed))
                    }
                }
            }
            TextButton(onClick = { original = !original }) { Text(stringResource(R.string.bank_original)) }
            if (original) Text(draft.alert.body, style = LabelStyle, color = c.muted)
        }
        WriteFeedback(vm.writes)
        PrimaryButton(stringResource(R.string.bank_save), enabled = canSave && !writes.busy) {
            val bankAccount = requireNotNull(account)
            val bankMoney = requireNotNull(movement)
            val source = if (transfer && incoming) requireNotNull(other) else bankAccount
            val destination = if (!transfer) null else if (incoming) bankAccount else other
            val sent = if (transfer && incoming && fx) requireNotNull(otherMoney) else bankMoney
            val received = if (!fx) null else if (incoming) bankMoney else otherMoney
            vm.save(id, Transaction(newId(), requireNotNull(type), sent, source.id, destination?.id,
                if (transfer) null else category?.id, date, "", merchant.trim(), "", TransactionSource.MANUAL, received))
        }
        TextButton(onClick = { vm.skip(id) }, enabled = !writes.busy) { Text(stringResource(R.string.bank_skip)) }
        if (accountPicker != null) {
            val choosingBank = accountPicker == "bank"
            AccountPicker(if (choosingBank) s.accounts else s.accounts.filter { it.id != accountId },
                if (choosingBank) accountId else otherId, financial.home.balances, onSelect = {
                    if (choosingBank) {
                        if (s.accounts.firstOrNull { candidate -> candidate.id == it }?.currency != (account?.currency ?: parsed.money?.currency)) {
                            amount = ""
                        }
                        accountId = it
                        if (otherId == it) otherId = null
                    } else otherId = it
                    linking = false
                    accountPicker = null
                }, onDismiss = { accountPicker = null })
        }
        if (datePicker) KinjarDatePickerDialog(date, onDismiss = { datePicker = false }, onPick = { dateText = it.toString(); datePicker = false; linking = false })
    }
}

@Composable
private fun ReviewField(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    OutlinedBox(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = LabelStyle, color = c.muted)
        Text(value, style = BodyStyle, color = c.text)
    }
}

@Composable
private fun <T> Picker(label: String, value: String, options: List<Pair<T, String>>, enabled: Boolean, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ReviewField(label, value, enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}
