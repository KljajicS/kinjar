package app.kinjar.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.data.ThemeMode
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.theme.CaptionStyle
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.LabelStyle
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.SelectPill
import app.kinjar.ui.theme.sans

@Composable
fun ProfileScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val busy = vm.writes.state.collectAsStateWithLifecycle().value.busy
    val c = LocalColors.current

    Column(
        Modifier.fillMaxSize().background(c.bg).imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(stringResource(R.string.settings_profile), onBack)
        FieldBox(stringResource(R.string.settings_name), s.name, vm::setName)
        if (s.loadFailed) LoadFailedBanner(vm::retryLoad)
        WriteFeedback(vm.writes)
        PrimaryButton(stringResource(R.string.settings_save_name), enabled = s.memberId != null && s.name.isNotBlank() && !s.saved && !busy, onClick = vm::saveName)
        Text(stringResource(R.string.settings_theme), style = LabelStyle, color = c.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { mode ->
                val label = stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })
                SelectPill(label, s.theme == mode, { vm.setTheme(mode) })
            }
        }
        LanguagePicker()
    }
}

@Composable
private fun LanguagePicker() {
    val c = LocalColors.current
    var open by remember { mutableStateOf(false) }
    // Matched by language because Android 13+ per-app settings may store a regional tag such as sr-Latn-RS.
    val selected = AppCompatDelegate.getApplicationLocales()[0]?.language.orEmpty()
    val languages = listOf(
        Triple("", "", stringResource(R.string.language_system)),
        Triple("en", "en", stringResource(R.string.language_english)),
        Triple("sr", "sr-Latn", stringResource(R.string.language_serbian)),
    )
    val label = (languages.firstOrNull { it.first == selected } ?: languages.first()).third
    Box {
        OutlinedBox(Modifier.fillMaxWidth(), radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_language), style = CaptionStyle, color = c.muted)
                    Text(label, style = sans(14, FontWeight.Medium), color = c.text)
                }
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = c.muted)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            languages.forEach { (_, tag, name) ->
                DropdownMenuItem(text = { Text(name, style = sans(14)) }, onClick = {
                    open = false
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                })
            }
        }
    }
}
