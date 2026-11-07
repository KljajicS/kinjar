package app.kinjar.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.kinjar.data.*
import app.kinjar.data.auth.FirebaseAuthRepository
import app.kinjar.data.auth.GoogleAccount
import app.kinjar.data.backup.BackupFiles
import app.kinjar.data.backup.OpenStoredBackup
import app.kinjar.data.sync.*
import app.kinjar.domain.*
import app.kinjar.ui.sync.FamilyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-07T10:00:00Z"), ZoneId.of("UTC"))
    private val store = EventStore(db, "install", clock, Projector(db))
    private val spied = spy(store)
    private val repo = BudgetRepository(db, store, clock, "install")
    private val auth = mock(FirebaseAuthRepository::class.java)
    private val families = mock(FamilyRepository::class.java)
    private val sync = mock(SyncCoordinator::class.java)
    private val backups = mock(BackupFiles::class.java)
    private val owner = ViewModelStore()
    private val uri = mock(Uri::class.java)
    private val password = "four long random words".toCharArray()

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        doReturn(GoogleAccount("user", "user@example.test", "User")).`when`(auth).current()
        doReturn(MutableStateFlow(SyncStatus())).`when`(sync).status
        doReturn(MutableStateFlow<FamilyInfo?>(null)).`when`(sync).observedFamily
        runBlocking { repo.setUp("Local", listOf(Account("cash", "Cash", Currency.RSD))) }
    }
    @After fun close() { Dispatchers.resetMain() }

    private suspend fun viewModel(): FamilyViewModel {
        doReturn(emptyList<FamilyInvitation>()).`when`(families).invitations()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FamilyViewModel(auth, families, sync, backups, db, spied, repo) as T
        }
        return ViewModelProvider(owner, factory)[FamilyViewModel::class.java].also { it.awaitIdle() }
    }
    private suspend fun FamilyViewModel.awaitIdle() { writes.state.first { !it.busy } }
    private fun spools() = store.read { db.historySpoolQueries.unreferenced().executeAsList() }

    private fun TestScope.settle(done: () -> Boolean) {
        repeat(500) {
            advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("Background work did not finish")
    }

    private suspend fun open(vm: FamilyViewModel): StoredHistory {
        val history = store.captureHistory()
        doReturn(OpenStoredBackup(Instant.EPOCH, history)).`when`(backups).read(uri, password)
        vm.inspectBackup(uri, password)
        return history
    }

    @Test fun `export cancelled after a finished capture frees the captured history`() = runTest(dispatcher) {
        val vm = viewModel()
        val captured = AtomicBoolean()
        doAnswer { call -> call.callRealMethod().also { vm.viewModelScope.cancel(); captured.set(true) } }.`when`(spied).captureHistory()
        vm.prepareExport(password, null)
        vm.export(uri)
        settle { captured.get() && spools().isEmpty() && !vm.writes.state.value.busy }
        assertTrue(password.all { it == '\u0000' })
        verifyNoInteractions(backups)
    }

    @Test fun `opening a backup cancelled during validation discards its history`() = runTest(dispatcher) {
        val vm = viewModel()
        val validated = AtomicBoolean()
        val history = store.captureHistory()
        doAnswer { call -> call.callRealMethod().also { vm.viewModelScope.cancel(); validated.set(true) } }.`when`(spied).validateHistory(history)
        doReturn(OpenStoredBackup(Instant.EPOCH, history)).`when`(backups).read(uri, password)
        vm.inspectBackup(uri, password)
        settle { validated.get() && spools().isEmpty() && !vm.writes.state.value.busy }
        assertNull(vm.state.value.backupDate)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun `clearing the screen deletes an opened backup that was never restored`() = runTest(dispatcher) {
        val vm = viewModel()
        val history = open(vm)
        settle { vm.state.value.backupDate != null && !vm.writes.state.value.busy }
        assertEquals(listOf(history.id), spools())
        owner.clear()
        settle { spools().isEmpty() }
    }

    @Test fun `clearing during a restore keeps the backup its journal took`() = runTest(dispatcher) {
        db.syncSessionQueries.save("user", "family", "dataset", "member", "install", 1, "ACTIVE")
        val vm = viewModel()
        val digest = store.currentDigest()
        val history = open(vm)
        settle { vm.state.value.backupDate != null && !vm.writes.state.value.busy }
        doAnswer {
            owner.clear()
            store.saveJournal("user", "family", "dataset", "target", "token", "RESTORE", "{}".toByteArray(), history)
        }.`when`(sync).restoreFamily(history, digest)
        vm.confirmBackupRestore()
        settle { !vm.writes.state.value.busy }
        assertEquals(history.id, db.restoreJournalQueries.journalHistory().executeAsOne().history_id)
        assertNotNull(db.historySpoolQueries.info(history.id).executeAsOneOrNull())
        assertTrue(spools().isEmpty())
    }
}
