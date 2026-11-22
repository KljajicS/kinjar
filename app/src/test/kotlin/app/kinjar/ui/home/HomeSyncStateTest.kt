package app.kinjar.ui.home

import app.kinjar.data.sync.SyncPhase
import app.kinjar.data.sync.SyncStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSyncStateTest {
    private val ready = SyncStatus(SyncPhase.READY, lastSuccess = Instant.EPOCH)

    @Test fun `successful sync is disabled until local changes arrive`() {
        assertEquals(HomeSyncState.SYNCED, homeSyncState(ready, false, false))
        assertEquals(HomeSyncState.SYNC, homeSyncState(ready, false, true))
        assertEquals(HomeSyncState.SYNC, homeSyncState(ready.copy(lastSuccess = null), false, false))
    }

    @Test fun `busy takes precedence over success and errors`() {
        SyncPhase.entries.forEach { phase ->
            assertEquals(HomeSyncState.SYNCING, homeSyncState(ready.copy(phase = phase), true, false))
        }
        assertEquals(HomeSyncState.SYNCING, homeSyncState(ready.copy(phase = SyncPhase.RESTORING), false, false))
    }

    @Test fun `errors offer retry and unavailable sessions offer setup`() {
        assertEquals(HomeSyncState.RETRY, homeSyncState(ready.copy(phase = SyncPhase.ERROR), false, false))
        listOf(SyncPhase.LOCAL, SyncPhase.SIGNED_OUT, SyncPhase.REPLACEMENT_REQUIRED, SyncPhase.REMOVED,
            SyncPhase.CAPACITY, SyncPhase.UPDATE_REQUIRED, SyncPhase.CLEANUP_REQUIRED).forEach { phase ->
            assertEquals(HomeSyncState.SETUP, homeSyncState(ready.copy(phase = phase), false, false))
        }
    }
}
