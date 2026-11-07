package app.kinjar.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.settings.ScreenHeader
import app.kinjar.ui.sync.FamilyViewModel
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.PrimaryButton

@Composable
fun FamilySetupScreen(onBack: () -> Unit, onDone: () -> Unit,
                      vm: SetupViewModel = hiltViewModel(), family: FamilyViewModel = hiltViewModel()) {
    val choices by vm.state.collectAsStateWithLifecycle()
    val state by family.state.collectAsStateWithLifecycle()
    val writes by family.writes.state.collectAsStateWithLifecycle()
    val c = LocalColors.current
    val resources = LocalContext.current.resources
    BackHandler { if (!writes.busy) onBack() }
    LaunchedEffect(state.completedEnrollment, writes.busy) {
        if (state.completedEnrollment && !writes.busy) onDone()
    }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.setup_family_accounts)) { if (!writes.busy) onBack() }
        Text(stringResource(R.string.setup_family_accounts_hint), style = BodyStyle, color = c.muted)
        if (!state.hasLocalMember) SetupAccounts(choices.accounts, vm::toggleAccount, vm::addAccount)
        else Text(stringResource(R.string.setup_accounts_saved), style = BodyStyle, color = c.muted)
        WriteFeedback(family.writes, state.failureText)
        PrimaryButton(stringResource(R.string.setup_cloud_start), enabled = state.account != null && choices.accounts.any { it.enabled } && !writes.busy) {
            family.createWithAccounts(choices.selectedAccounts { resources.getString(it) })
        }
    }
}
