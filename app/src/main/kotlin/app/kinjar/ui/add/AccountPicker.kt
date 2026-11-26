package app.kinjar.ui.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinjar.R
import app.kinjar.domain.Account
import app.kinjar.domain.Money
import app.kinjar.ui.AccountBadge
import app.kinjar.ui.LocalAccountAppearances
import app.kinjar.ui.formatAmount
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.sans

internal fun matchingAccounts(accounts: List<Account>, query: String) = accounts.filter {
    !it.archived && (it.name.contains(query.trim(), ignoreCase = true) || it.currency.name.contains(query.trim(), ignoreCase = true))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPicker(accounts: List<Account>, selectedId: String?, balances: Map<String, Money>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val preferences = LocalAccountAppearances.current
    val favorites = preferences.favorites(accounts).map { it.id }.toSet()
    var search by rememberSaveable { mutableStateOf("") }
    val matching = matchingAccounts(accounts, search).sortedBy { if (it.id in favorites) 0 else 1 }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.add_choose_account), style = sans(20, FontWeight.SemiBold), color = c.text)
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.account_search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
            if (matching.isEmpty()) Text(stringResource(R.string.account_search_empty), color = c.muted)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(matching, key = { it.id }) { account ->
                    val selected = account.id == selectedId
                    Row(Modifier.fillMaxWidth().semantics { this.selected = selected }
                        .clickable(role = Role.RadioButton) { onSelect(account.id); onDismiss() }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AccountBadge(account.id)
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = sans(15, FontWeight.Medium), color = c.text)
                            Text(listOfNotNull(account.currency.name, balances[account.id]?.let { formatAmount(it, locale = locale) },
                                if (account.id in favorites) stringResource(R.string.account_favorite_badge) else null).joinToString(" · "), style = sans(12), color = c.muted)
                        }
                        if (selected) Icon(Icons.Outlined.Check, stringResource(R.string.account_selected), tint = c.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun QuickAccounts(s: AddState, onAccount: (String) -> Unit) =
    QuickAccounts(s.accounts, s.accountId, s.balances, s.type, s.rsdOnly, onAccount)

@Composable
internal fun QuickAccounts(
    accounts: List<Account>, accountId: String?, balances: Map<String, Money>, type: app.kinjar.domain.TransactionType?,
    rsdOnly: Boolean = false, onAccount: (String) -> Unit,
) {
    val c = LocalColors.current
    val available = accounts.filterNot { it.archived }.filter { !rsdOnly || it.currency == app.kinjar.domain.Currency.RSD }
    val favorites = LocalAccountAppearances.current.favorites(accounts).filter { it in available }
    val label = stringResource(if (type == app.kinjar.domain.TransactionType.INCOME) R.string.add_deposit_into else R.string.add_pay_from)
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = sans(12), color = c.muted, modifier = Modifier.weight(1f))
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.account_all, available.size)) }
        }
        if (favorites.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            favorites.forEach { account ->
                val selected = account.id == accountId
                val shape = RoundedCornerShape(28.dp)
                Row(Modifier.weight(1f).heightIn(min = 56.dp).clip(shape)
                    .background(if (selected) c.primary.copy(alpha = c.tintAlpha) else c.surface2)
                    .then(if (selected) Modifier.border(1.dp, c.primary, shape) else Modifier)
                    .semantics { this.selected = selected }
                    .clickable(role = Role.RadioButton) { onAccount(account.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountBadge(account.id, Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(account.name, style = sans(12, FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(account.currency.name, style = sans(10), color = c.muted)
                            if (selected) Icon(Icons.Outlined.Check, stringResource(R.string.account_selected), tint = c.primary, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
        if (favorites.none { it.id == accountId }) {
            AccountBox(label, available, accountId, onAccount, Modifier.fillMaxWidth(), balances,
                selectedAccount = accounts.firstOrNull { it.id == accountId })
        }
        if (available.isEmpty()) Text(stringResource(R.string.account_add_empty), style = sans(12), color = c.muted)
    }
    if (open) AccountPicker(available, accountId, balances, onAccount) { open = false }
}
