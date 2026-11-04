package app.kinjar.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import app.kinjar.data.ThemeMode
import app.kinjar.ui.theme.LabelStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
import app.kinjar.ui.WriteFeedback
import app.kinjar.R
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.RoundIconButton
import app.kinjar.ui.theme.SelectPill
import app.kinjar.ui.theme.TitleStyle
import app.kinjar.ui.theme.sans

@Composable
fun SettingsScreen(onBack: () -> Unit, onCategories: () -> Unit, onAccounts: () -> Unit, onRecurring: () -> Unit, onRates: () -> Unit,
                   onFamily: () -> Unit, onBackup: () -> Unit, vm: SettingsViewModel = hiltViewModel(), lockVm: AppLockViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = LocalColors.current

    Column(
        Modifier.fillMaxSize().background(c.bg).imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(stringResource(R.string.settings_title), onBack)
        FieldBox(stringResource(R.string.settings_name), s.name, vm::setName)
        if (s.loadFailed) LoadFailedBanner(vm::retryLoad)
        WriteFeedback(vm.writes)
        PrimaryButton(stringResource(R.string.settings_save_name), enabled = s.memberId != null && s.name.isNotBlank() && !s.saved && !vm.writes.state.collectAsStateWithLifecycle().value.busy, onClick = vm::saveName)
        Text(stringResource(R.string.settings_theme), style = LabelStyle, color = c.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { mode ->
                val label = stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })
                SelectPill(label, s.theme == mode, { vm.setTheme(mode) })
            }
        }
        LanguagePicker()
        AppLockRow(lockVm)
        SettingsRow(stringResource(R.string.settings_categories), onCategories)
        SettingsRow(stringResource(R.string.settings_accounts), onAccounts)
        SettingsRow(stringResource(R.string.settings_recurring), onRecurring)
        SettingsRow(stringResource(R.string.rates_title), onRates)
        SettingsRow(stringResource(R.string.fbs_family), onFamily)
        SettingsRow(stringResource(R.string.fbs_backups), onBackup)
    }
}

@Composable
private fun LanguagePicker() {
    val c = LocalColors.current
    var open by remember { mutableStateOf(false) }
    val selected = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val languages = listOf(
        "" to stringResource(R.string.language_system),
        "en" to stringResource(R.string.language_english),
        "sr-Latn" to stringResource(R.string.language_serbian),
    )
    val label = languages.firstOrNull { it.first == selected }?.second ?: languages.first().second
    Box {
        OutlinedBox(Modifier.fillMaxWidth(), radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_language), style = app.kinjar.ui.theme.CaptionStyle, color = c.muted)
                    Text(label, style = sans(14, FontWeight.Medium), color = c.text)
                }
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = c.muted)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            languages.forEach { (tag, name) ->
                DropdownMenuItem(text = { Text(name, style = sans(14)) }, onClick = {
                    open = false
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                })
            }
        }
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
                Text(stringResource(R.string.settings_app_lock_hint), style = app.kinjar.ui.theme.CaptionStyle, color = c.muted)
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
private fun SettingsRow(title: String, onClick: () -> Unit) {
    val c = LocalColors.current
    OutlinedBox(Modifier.fillMaxWidth(), radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 14.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = sans(15, FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.muted)
        }
    }
}
