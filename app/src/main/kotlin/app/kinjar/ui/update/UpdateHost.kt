package app.kinjar.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.kinjar.R
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun UpdateHost(vm: UpdateViewModel, onInstall: (File) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.state.map { it.handoff }.distinctUntilChanged().collect { file ->
                if (file != null) vm.consumeHandoff()?.let(onInstall)
            }
        }
    }
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = vm::dismiss,
        title = { Text(stringResource(R.string.update_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.update_current_version, vm.currentVersion))
                state.release?.let { Text(stringResource(R.string.update_available, it.versionName)) }
                when {
                    state.checking -> Text(stringResource(R.string.update_checking))
                    state.verifying -> Text(stringResource(R.string.update_verifying))
                    state.downloading -> {
                        val total = state.release?.sizeBytes ?: 1L
                        val progress = (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
                        Text(stringResource(R.string.update_downloading, (progress * 100).toInt()))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    state.ready -> Text(stringResource(R.string.update_ready))
                }
                state.notice?.let { Text(stringResource(it.message())) }
            }
        },
        confirmButton = {
            when {
                state.busy -> Unit
                state.ready -> TextButton(onClick = vm::install) { Text(stringResource(R.string.update_install)) }
                state.release != null -> TextButton(onClick = vm::download) { Text(stringResource(R.string.update_download)) }
                state.notice == UpdateNotice.CHECK_FAILED -> TextButton(onClick = vm::check) {
                    Text(stringResource(R.string.update_retry))
                }
            }
        },
        dismissButton = {
            if (state.downloading || state.verifying) TextButton(onClick = vm::cancelDownload) {
                Text(stringResource(R.string.update_cancel))
            } else TextButton(onClick = vm::dismiss) {
                Text(stringResource(if (state.release != null) R.string.update_later else R.string.update_close))
            }
        },
    )
}

private fun UpdateNotice.message(): Int = when (this) {
    UpdateNotice.CURRENT -> R.string.update_current
    UpdateNotice.CHECK_FAILED -> R.string.update_check_failed
    UpdateNotice.INCOMPATIBLE -> R.string.update_incompatible
    UpdateNotice.DOWNLOAD_FAILED -> R.string.update_download_failed
    UpdateNotice.VERIFY_FAILED -> R.string.update_verify_failed
    UpdateNotice.PERMISSION_REQUIRED -> R.string.update_permission_required
    UpdateNotice.INSTALL_FAILED -> R.string.update_install_failed
}
