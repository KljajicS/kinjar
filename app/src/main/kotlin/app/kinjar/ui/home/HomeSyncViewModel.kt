package app.kinjar.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.kinjar.data.db.KinjarDb
import app.kinjar.data.sync.SyncCoordinator
import app.kinjar.data.sync.SyncPhase
import app.kinjar.data.sync.SyncStatus
import app.kinjar.ui.WriteOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class HomeSyncState { SYNC, SYNCING, SYNCED, RETRY, SETUP }

internal fun homeSyncState(status: SyncStatus, busy: Boolean, pending: Boolean): HomeSyncState = when {
    busy || status.phase in setOf(SyncPhase.RUNNING, SyncPhase.RESTORING) -> HomeSyncState.SYNCING
    status.phase == SyncPhase.ERROR -> HomeSyncState.RETRY
    status.phase != SyncPhase.READY -> HomeSyncState.SETUP
    status.lastSuccess != null && !pending -> HomeSyncState.SYNCED
    else -> HomeSyncState.SYNC
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeSyncViewModel @Inject constructor(private val sync: SyncCoordinator, db: KinjarDb) : ViewModel() {
    private val writes = WriteOperation(viewModelScope)
    private val pending = db.syncSessionQueries.current().asFlow().mapToList(Dispatchers.IO).flatMapLatest { sessions ->
        val session = sessions.firstOrNull() ?: return@flatMapLatest flowOf(true)
        db.syncOutboxQueries.pending(session.family_id, session.dataset_id).asFlow().mapToList(Dispatchers.IO)
            .map { it.isNotEmpty() }
    }.catch { emit(true) }
    val state = combine(sync.status, sync.busy, pending, writes.state) { status, busy, pending, write ->
        homeSyncState(status, busy || write.busy, pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeSyncState.SYNCING)

    fun synchronize(force: Boolean = false) {
        if (sync.busy.value || state.value == HomeSyncState.SYNCING) return
        if (state.value == HomeSyncState.SETUP || (state.value == HomeSyncState.SYNCED && !force)) return
        writes.run(finish = false) { sync.synchronize(reconcile = force) }
    }
}
