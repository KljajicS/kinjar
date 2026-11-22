package app.kinjar.ui.home

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.kinjar.R
import app.kinjar.ui.theme.LocalColors

@Composable
internal fun HomeSyncButton(state: HomeSyncState, onSync: () -> Unit, onForce: () -> Unit, onSetup: () -> Unit) {
    val c = LocalColors.current
    val busy = state == HomeSyncState.SYNCING
    val label = stringResource(when (state) {
        HomeSyncState.SYNC -> R.string.home_sync
        HomeSyncState.SYNCING -> R.string.home_syncing
        HomeSyncState.SYNCED -> R.string.home_force_sync
        HomeSyncState.RETRY -> R.string.home_sync_retry
        HomeSyncState.SETUP -> R.string.home_sync_setup
    })
    val syncedLabel = stringResource(R.string.home_synced)
    val tint = when (state) {
        HomeSyncState.SYNCED -> c.green
        HomeSyncState.RETRY -> c.red
        HomeSyncState.SYNCING, HomeSyncState.SETUP -> c.muted
        HomeSyncState.SYNC -> c.primary
    }
    FilledTonalIconButton(
        onClick = when (state) {
            HomeSyncState.SETUP -> onSetup
            HomeSyncState.SYNCED -> onForce
            else -> onSync
        },
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = label
            if (state == HomeSyncState.SYNCED) stateDescription = syncedLabel
        },
        enabled = !busy,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = tint.copy(alpha = c.tintAlpha), contentColor = tint,
            disabledContainerColor = tint.copy(alpha = c.tintAlpha), disabledContentColor = tint,
        ),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = tint, strokeWidth = 2.dp)
        else Icon(when (state) {
            HomeSyncState.SYNCED -> Icons.Outlined.Check
            HomeSyncState.RETRY -> Icons.Outlined.ErrorOutline
            HomeSyncState.SETUP -> Icons.Outlined.CloudOff
            else -> Icons.Outlined.Sync
        }, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}
