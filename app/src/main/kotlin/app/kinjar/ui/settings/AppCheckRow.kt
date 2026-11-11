package app.kinjar.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.BuildConfig
import app.kinjar.R
import app.kinjar.data.auth.AppCheckStatus
import app.kinjar.data.auth.AppCheckStage
import app.kinjar.ui.theme.CaptionStyle
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox

@Composable
internal fun AppCheckRow(vm: AppCheckViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = LocalColors.current
    var showDetails by rememberSaveable { mutableStateOf(false) }
    OutlinedBox(Modifier.fillMaxWidth(), radius = 14) {
        Column {
            TextButton(onClick = vm::check, enabled = !state.busy) {
                Text(stringResource(if (state.busy) R.string.app_check_running else R.string.app_check_action))
            }
            Text(stringResource(R.string.app_check_hint), style = CaptionStyle, color = colors.muted)
            if (state.cooldown) Text(stringResource(R.string.app_check_cooldown), style = CaptionStyle, color = colors.orange)
            state.result?.let { result ->
                val message = when (result.status) {
                    AppCheckStatus.VERIFIED -> R.string.app_check_verified
                    AppCheckStatus.NOT_CONFIGURED -> R.string.app_check_unavailable
                    AppCheckStatus.DEBUG_BUILD -> R.string.app_check_debug
                    AppCheckStatus.NETWORK -> R.string.app_check_network
                    AppCheckStatus.TIMEOUT -> R.string.app_check_timeout
                    AppCheckStatus.THROTTLED, AppCheckStatus.COOLDOWN -> R.string.app_check_cooldown
                    else -> R.string.app_check_failed
                }
                Text(stringResource(message), style = CaptionStyle, color = colors.text)
                SelectionContainer {
                    Text(
                        stringResource(R.string.app_check_details, result.code, BuildConfig.VERSION_NAME, state.checkedAt.toString()),
                        style = CaptionStyle, color = colors.muted,
                    )
                }
                TextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.app_check_show_details)) }
                if (showDetails) AlertDialog(
                    onDismissRequest = { showDetails = false },
                    title = { Text(stringResource(R.string.app_check_show_details)) },
                    text = {
                        SelectionContainer {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                val stageLabel = when (result.stage) {
                                    AppCheckStage.INITIALIZATION -> R.string.app_check_stage_initialization
                                    AppCheckStage.TOKEN_REQUEST -> R.string.app_check_stage_token
                                    AppCheckStage.PLAY_INTEGRITY -> R.string.app_check_stage_integrity
                                    AppCheckStage.CONFIGURATION -> R.string.app_check_stage_configuration
                                    AppCheckStage.COMPLETE -> R.string.app_check_stage_complete
                                    AppCheckStage.NOT_STARTED -> R.string.app_check_stage_not_started
                                }
                                Text(stringResource(R.string.app_check_stage, stringResource(stageLabel)))
                                Text(stringResource(R.string.app_check_details, result.code, BuildConfig.VERSION_NAME, state.checkedAt.toString()))
                                Text(stringResource(R.string.app_check_error_types,
                                    result.errorTypes.joinToString(" > ") { it.name }.ifEmpty { stringResource(R.string.app_check_no_error) }))
                                Text(stringResource(R.string.app_check_details_privacy))
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.add_close)) } },
                )
            }
        }
    }
}
