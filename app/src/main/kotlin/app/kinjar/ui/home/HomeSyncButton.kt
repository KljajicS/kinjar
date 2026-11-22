package app.kinjar.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kinjar.R
import app.kinjar.ui.theme.LocalColors

@Composable
internal fun HomeSyncButton(state: HomeSyncState, onSync: () -> Unit, onForce: () -> Unit, onSetup: () -> Unit) {
    val c = LocalColors.current
    val synced = state == HomeSyncState.SYNCED
    val busy = state == HomeSyncState.SYNCING
    val label = stringResource(when (state) {
        HomeSyncState.SYNC -> R.string.home_sync
        HomeSyncState.SYNCING -> R.string.home_syncing
        HomeSyncState.SYNCED -> R.string.home_synced
        HomeSyncState.RETRY -> R.string.home_sync_retry
        HomeSyncState.SETUP -> R.string.home_sync_setup
    })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(
            onClick = if (state == HomeSyncState.SETUP) onSetup else onSync,
            modifier = Modifier.size(48.dp).semantics { contentDescription = label },
            enabled = !busy && !synced,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = c.primary.copy(alpha = c.tintAlpha), contentColor = c.primary,
                disabledContainerColor = (if (synced) c.green else c.muted).copy(alpha = c.tintAlpha),
                disabledContentColor = if (synced) c.green else c.muted,
            ),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = c.muted, strokeWidth = 2.dp)
            else Icon(when (state) {
                HomeSyncState.SYNCED -> Icons.Outlined.Check
                HomeSyncState.RETRY -> Icons.Outlined.SyncProblem
                HomeSyncState.SETUP -> Icons.Outlined.CloudOff
                else -> Icons.Outlined.Sync
            }, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        if (synced) IconButton(onClick = onForce, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.home_force_sync), tint = c.primary)
        }
    }
}
