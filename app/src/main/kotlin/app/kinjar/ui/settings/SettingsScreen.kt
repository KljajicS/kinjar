package app.kinjar.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import app.kinjar.data.security.LockPurpose
import app.kinjar.ui.security.message
import app.kinjar.R
import app.kinjar.ui.theme.CaptionStyle
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.RoundIconButton
import app.kinjar.ui.theme.TitleStyle
import app.kinjar.ui.theme.sans

@Composable
fun SettingsScreen(onBack: () -> Unit, onProfile: () -> Unit, onCategories: () -> Unit, onRecurring: () -> Unit, onRates: () -> Unit,
                   onFamily: () -> Unit, onBackup: () -> Unit, lockVm: AppLockViewModel = hiltViewModel()) {
    val c = LocalColors.current

    Column(
        Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(stringResource(R.string.settings_title), onBack)
        SettingsRow(stringResource(R.string.settings_profile), onProfile, stringResource(R.string.settings_profile_hint))
        AppLockRow(lockVm)
        SettingsRow(stringResource(R.string.settings_categories), onCategories)
        SettingsRow(stringResource(R.string.settings_recurring), onRecurring)
        SettingsRow(stringResource(R.string.rates_title), onRates)
        SettingsRow(stringResource(R.string.fbs_family), onFamily)
        SettingsRow(stringResource(R.string.fbs_backups), onBackup)
    }
}

@Composable
private fun AppLockRow(vm: AppLockViewModel) {
    val c = LocalColors.current
    val s by vm.state.collectAsStateWithLifecycle()
    val idle = s.operation == null
    OutlinedBox(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).toggleable(s.enabled, enabled = idle, role = Role.Switch) { vm.toggle() },
        radius = 14,
        padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_app_lock), style = sans(15, FontWeight.Medium), color = c.text)
                Text(stringResource(R.string.settings_app_lock_hint), style = CaptionStyle, color = c.muted)
            }
            Switch(
                checked = s.enabled,
                onCheckedChange = null,
                enabled = idle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.onPrimary, checkedTrackColor = c.primary,
                    uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surface2, uncheckedBorderColor = c.border,
                ),
            )
        }
    }
    s.error?.takeIf { it.purpose != LockPurpose.UNLOCK }?.let { Text(stringResource(it.failure.message), color = c.orange) }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.add_close), onBack)
        Text(title, style = TitleStyle, color = c.text)
    }
}

@Composable
fun LoadFailedBanner(onRetry: () -> Unit) {
    Text(stringResource(R.string.audit_ui_load_failed), color = LocalColors.current.orange)
    TextButton(onClick = onRetry) { Text(stringResource(R.string.audit_ui_retry)) }
}

@Composable
private fun SettingsRow(title: String, onClick: () -> Unit, subtitle: String? = null) {
    val c = LocalColors.current
    OutlinedBox(Modifier.fillMaxWidth(), radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 14.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = sans(15, FontWeight.Medium), color = c.text)
                if (subtitle != null) Text(subtitle, style = CaptionStyle, color = c.muted)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.muted)
        }
    }
}
