package app.kinjar.ui.sync

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.settings.ScreenHeader
import app.kinjar.ui.theme.*
import java.time.LocalDate

@Composable
fun BackupScreen(onBack: () -> Unit, onContinue: () -> Unit, vm: FamilyViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val writes by vm.writes.state.collectAsStateWithLifecycle()
    val c = LocalColors.current
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var passwordDialog by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var copyId by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), vm::export)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importUri = uri; password = ""; repeated = ""; passwordDialog = true }
    }
    fun beginExport(copy: String? = null) { copyId = copy; importUri = null; password = ""; repeated = ""; passwordDialog = true }

    LazyColumn(Modifier.fillMaxSize().background(c.bg).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader(stringResource(R.string.fbs_backups), onBack) }
        item { WriteFeedback(vm.writes, R.string.fbs_backup_failed) }
        item {
            OutlinedBox(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.fbs_backup_hint), style = BodyStyle, color = c.muted)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.fbs_export), enabled = !writes.busy) { beginExport() }
            }
        }
        item { PrimaryButton(stringResource(R.string.fbs_import), enabled = !writes.busy) { importer.launch(arrayOf("application/octet-stream", "application/*", "*/*")) } }
        if (state.savedBackup) item { Text(stringResource(R.string.fbs_exported), style = BodyStyle, color = c.text) }
        if (state.copies.isNotEmpty()) {
            item { Text(stringResource(R.string.fbs_retained), style = SectionStyle, color = c.text) }
            item { Text(stringResource(R.string.fbs_retained_hint), style = CaptionStyle, color = c.muted) }
            items(state.copies, key = { it.id }) { copy ->
                OutlinedBox(Modifier.fillMaxWidth()) {
                    Text(displayTime(copy.createdAt), style = BodyStyle, color = c.text)
                    TextButton(onClick = { beginExport(copy.id) }, enabled = !writes.busy) { Text(stringResource(R.string.fbs_export_copy)) }
                }
            }
        }
        if (state.hasLocalMember) item { TextButton(onClick = onContinue, enabled = !writes.busy) { Text(stringResource(R.string.fbs_continue)) } }
    }
    if (passwordDialog) ConfirmDialog(stringResource(if (importUri == null) R.string.fbs_backup_password else R.string.fbs_open_backup),
        stringResource(R.string.dialog_ok), writes.busy,
        onConfirm = {
            if (password.length in 12..1024 && (importUri != null || password == repeated)) {
                val chars = password.toCharArray()
                val source = importUri
                password = ""; repeated = ""; passwordDialog = false
                if (source != null) vm.inspectBackup(source, chars)
                else { vm.prepareExport(chars, copyId); exporter.launch("kinjar-${LocalDate.now()}.kinjar") }
            }
        }, onDismiss = { password = ""; repeated = ""; passwordDialog = false }) {
        Text(stringResource(R.string.fbs_password_hint), color = c.muted)
        FieldBox(stringResource(R.string.fbs_passphrase), password, { if (it.length <= 1024) password = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
        if (importUri == null) FieldBox(stringResource(R.string.fbs_repeat_password), repeated, { if (it.length <= 1024) repeated = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
        if (password.isNotEmpty() && password.length < 12) Text(stringResource(R.string.fbs_password_short), color = c.orange)
        if (repeated.isNotEmpty() && password != repeated) Text(stringResource(R.string.fbs_password_mismatch), color = c.orange)
    }
    state.backupDate?.let { date ->
        ConfirmDialog(stringResource(R.string.fbs_replace), stringResource(R.string.fbs_restore), writes.busy,
            onConfirm = vm::confirmBackupRestore, onDismiss = vm::cancelBackupRestore) {
            Text(stringResource(R.string.fbs_backup_date, displayTime(date)), color = c.text)
            Text(stringResource(if (state.enrolled) R.string.fbs_shared_restore_warning else R.string.fbs_local_restore_warning), color = c.text)
            WriteFeedback(vm.writes, R.string.fbs_backup_failed)
        }
    }
}
