package app.kinjar.ui.setup

import app.kinjar.ui.theme.AddButton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.ui.WriteFeedback
import app.kinjar.R
import app.kinjar.domain.Currency
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.SectionStyle
import app.kinjar.ui.theme.SelectPill
import app.kinjar.ui.theme.TitleStyle
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans

@Composable
fun SetupScreen(onDone: () -> Unit, onFamily: () -> Unit, onBackup: () -> Unit, vm: SetupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = LocalColors.current
    val resources = LocalContext.current.resources
    LaunchedEffect(state.done) { if (state.done) onDone() }

    var adding by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var newCurrency by rememberSaveable { mutableStateOf(Currency.RSD) }

    Column(
        Modifier.fillMaxSize().background(c.bg).imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = TitleStyle, color = c.text)
        if (app.kinjar.BuildConfig.FIREBASE_SYNC_ENABLED) androidx.compose.material3.TextButton(onClick = onFamily) { Text(stringResource(R.string.fbs_join_or_recover)) }
        androidx.compose.material3.TextButton(onClick = onBackup) { Text(stringResource(R.string.fbs_import)) }
        FieldBox(stringResource(R.string.setup_name_label), state.name, vm::setName)

        Text(stringResource(R.string.setup_accounts_title), style = SectionStyle, color = c.text, modifier = Modifier.padding(top = 6.dp))
        state.accounts.forEachIndexed { i, a ->
            OutlinedBox(Modifier.fillMaxWidth(), radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 14.dp), selected = a.enabled, onClick = { vm.toggleAccount(i) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.label?.let { stringResource(it) } ?: a.name, style = sans(14, FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
                    Text(a.currency.name, style = mono(13), color = c.muted)
                }
            }
        }

        if (adding) {
            FieldBox(stringResource(R.string.setup_account_name), newName, { newName = it })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Currency.entries.forEach { cur -> SelectPill(cur.name, newCurrency == cur, { newCurrency = cur }, style = mono(13)) }
            }
            OutlinedBox(Modifier.fillMaxWidth(), radius = 14, padding = PaddingValues(14.dp), onClick = { vm.addAccount(newName, newCurrency); newName = ""; adding = false }) {
                Text(stringResource(R.string.setup_add_account), style = sans(14, FontWeight.Medium), color = c.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        } else {
            AddButton(stringResource(R.string.setup_add_account)) { adding = true }
        }

        Spacer(Modifier.size(10.dp))
        WriteFeedback(vm.writes)
        PrimaryButton(stringResource(R.string.setup_done), enabled = state.canFinish && !vm.writes.state.collectAsStateWithLifecycle().value.busy, onClick = { vm.finish { resources.getString(it) } })
    }
}
