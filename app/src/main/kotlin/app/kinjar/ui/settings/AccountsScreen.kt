package app.kinjar.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.data.AccountSymbol
import app.kinjar.data.AccountTint
import app.kinjar.ui.AccountBadge
import app.kinjar.ui.icon
import app.kinjar.ui.label
import app.kinjar.ui.tint
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.ui.DragHandle
import app.kinjar.ui.ReorderColumn
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.WriteOperation
import app.kinjar.ui.formatAmount
import app.kinjar.ui.theme.AddButton
import app.kinjar.ui.home.accountColor
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.ConfirmDialog
import app.kinjar.ui.theme.DangerLink
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.InfoButton
import app.kinjar.ui.theme.LabelStyle
import app.kinjar.ui.theme.LoadingOrError
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.SelectPill
import app.kinjar.ui.theme.TitleStyle
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans

@Composable
fun AccountsScreen(onNew: () -> Unit, onEdit: (String) -> Unit, onNewLoan: () -> Unit, onLoan: (String) -> Unit, vm: AccountsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val failed by vm.loadFailed.collectAsStateWithLifecycle()
    val s = state ?: return LoadingOrError(failed, vm::retryLoad)
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val list = rememberLazyListState()

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        state = list,
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.accounts_title), style = TitleStyle, color = c.text, modifier = Modifier.weight(1f))
                AddButton(stringResource(R.string.account_new_title), onNew)
            }
        }
        if (s.accounts.isEmpty()) {
            item { Text(stringResource(R.string.accounts_empty), style = BodyStyle, color = c.muted) }
        } else {
            item {
                OutlinedBox(Modifier.fillMaxWidth(), radius = 18, padding = PaddingValues(vertical = 4.dp)) {
                    ReorderColumn(s.accounts, { it.account.id }, s.reorderFailures, vm::reorder, list) { (account, balance), index, handle, actions ->
                        Column(Modifier.background(c.bg)) {
                            if (index > 0) HorizontalDivider(Modifier.padding(start = 44.dp, end = 14.dp), color = c.border, thickness = 1.dp)
                            Row(
                                actions.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { onEdit(account.id) }.padding(end = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                DragHandle(handle)
                                AccountBadge(account.id)
                                Text(account.name, style = sans(15, FontWeight.Medium), color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
                                Text("${formatAmount(balance, locale = locale)} ${balance.currency}", style = mono(14), color = if (balance.isNegative) c.orange else c.text)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.muted)
                            }
                        }
                    }
                }
            }
            item { WriteFeedback(vm.writes) }
        }
        item { app.kinjar.ui.loans.LoansSection(onNewLoan, onLoan) }
    }
}

@Composable
fun EditAccountScreen(onClose: () -> Unit, vm: EditAccountViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val write by vm.writes.state.collectAsStateWithLifecycle()
    val c = LocalColors.current
    var confirming by rememberSaveable { mutableStateOf(false) }
    // The warning must name the balance being dropped, and a busy save would silently swallow the archive.
    val canRemove = s.balance != null && !write.busy
    LaunchedEffect(s.done) { if (s.done) onClose() }

    Column(
        Modifier.fillMaxSize().background(c.bg).imePadding().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(stringResource(if (s.editing) R.string.account_edit_title else R.string.account_new_title), onClose)
        if (s.loadFailed) LoadFailedBanner(vm::retryLoad)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FieldBox(stringResource(R.string.account_name), s.name, vm::setName)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountBadge(s.appearance)
                Text(stringResource(R.string.account_appearance), style = BodyStyle, color = c.text)
            }
            Text(stringResource(R.string.account_icon), style = LabelStyle, color = c.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountSymbol.entries.forEach { symbol ->
                    val label = stringResource(symbol.label())
                    Box(Modifier.size(44.dp).border(1.dp, if (s.appearance.icon == symbol) c.primary else c.border, RoundedCornerShape(12.dp))
                        .selectable(s.appearance.icon == symbol, enabled = !write.busy, role = Role.RadioButton) { vm.setIcon(symbol) }
                        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                        Icon(symbol.icon(), null, tint = s.appearance.color.tint(), modifier = Modifier.size(24.dp))
                    }
                }
            }
            Text(stringResource(R.string.account_color), style = LabelStyle, color = c.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountTint.entries.forEach { color ->
                    val label = stringResource(color.label())
                    Box(Modifier.size(44.dp).border(if (s.appearance.color == color) 2.dp else 1.dp, if (s.appearance.color == color) c.text else c.border, RoundedCornerShape(12.dp))
                        .selectable(s.appearance.color == color, enabled = !write.busy, role = Role.RadioButton) { vm.setColor(color) }
                        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(24.dp).background(color.tint(), RoundedCornerShape(8.dp)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().toggleable(s.appearance.showInAdd, enabled = !write.busy,
                role = Role.Switch, onValueChange = vm::setShowInAdd), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.account_show_in_add), style = BodyStyle, color = c.text)
                    Text(stringResource(R.string.account_show_in_add_hint), style = LabelStyle, color = c.muted)
                }
                Switch(s.appearance.showInAdd, onCheckedChange = null, enabled = !write.busy)
            }
            Row(Modifier.fillMaxWidth().toggleable(s.appearance.favorite, enabled = !write.busy && s.appearance.showInAdd && (s.appearance.favorite || s.canFavorite),
                role = Role.Switch, onValueChange = vm::setFavorite), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.account_quick_favorite), style = BodyStyle, color = c.text)
                    Text(stringResource(if (!s.appearance.showInAdd) R.string.account_favorite_hidden else if (s.canFavorite || s.appearance.favorite) R.string.account_favorite_hint else R.string.account_favorite_limit), style = LabelStyle, color = c.muted)
                }
                Switch(s.appearance.favorite, onCheckedChange = null, enabled = !write.busy && s.appearance.showInAdd && (s.appearance.favorite || s.canFavorite))
            }
            Text(stringResource(R.string.account_appearance_local), style = LabelStyle, color = c.muted)
            Text(stringResource(R.string.account_currency), style = LabelStyle, color = c.muted)
            if (s.currencyLocked) {
                Text(s.currency.name, style = mono(15), color = c.text)
                Text(stringResource(R.string.account_currency_locked), style = LabelStyle, color = c.muted)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Currency.entries.forEach { cur -> SelectPill(cur.name, s.currency == cur, { vm.setCurrency(cur) }, style = mono(13)) }
                }
            }
            Row(
                Modifier.fillMaxWidth().toggleable(s.excludedFromGoals, role = Role.Switch, onValueChange = vm::setExcludedFromGoals).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.account_exclude_goals), style = BodyStyle, color = c.text, modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.account_exclude_goals), stringResource(R.string.account_exclude_goals_hint))
                Switch(checked = s.excludedFromGoals, onCheckedChange = null)
            }
            if (s.editing) {
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.account_delete_hint), style = LabelStyle, color = c.muted)
                DangerLink(stringResource(R.string.account_delete), enabled = canRemove) { confirming = true }
            }
        }
        WriteFeedback(vm.writes)
        PrimaryButton(stringResource(R.string.account_save), enabled = s.canSave && !write.busy, onClick = vm::save)
    }

    // Stays open until the archive succeeds and closes the screen, so a failure shows here and can be retried.
    val balance = s.balance
    if (confirming && balance != null) {
        ArchiveDialog(s, balance, vm.writes, write.busy, onConfirm = vm::delete, onDismiss = { confirming = false })
    }
}

@Composable
private fun ArchiveDialog(s: EditAccountState, balance: Money, writes: WriteOperation, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val rsd = s.balanceRsd
    ConfirmDialog(stringResource(R.string.account_archive_title, s.name), stringResource(R.string.account_archive_confirm), busy, onConfirm, onDismiss) {
        val approx = if (balance.currency != Currency.RSD && rsd != null) " (≈ ${formatAmount(rsd, locale = locale)} RSD)" else ""
        Text(
            stringResource(R.string.account_archive_balance, "${formatAmount(balance, locale = locale)} ${balance.currency}$approx"),
            style = mono(14), color = c.text,
        )
        Text(
            stringResource(
                when {
                    balance.isZero -> R.string.account_archive_zero
                    balance.isNegative -> R.string.account_archive_rise
                    else -> R.string.account_archive_drop
                },
            ),
            style = sans(14), color = if (!balance.isZero && !balance.isNegative) c.orange else c.text,
        )
        if (s.bills.isNotEmpty()) Text(stringResource(R.string.audit_ui_account_bills, s.bills.joinToString(", ")), style = sans(14), color = c.orange)
        Text(stringResource(R.string.account_archive_history), style = sans(14), color = c.muted)
        WriteFeedback(writes)
    }
}
