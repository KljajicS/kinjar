package app.kinjar.ui

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.kinjar.R
import app.kinjar.data.*
import app.kinjar.data.auth.FirebaseAuthRepository
import app.kinjar.data.auth.GoogleAccount
import app.kinjar.data.backup.BackupFiles
import app.kinjar.data.sync.*
import app.kinjar.domain.*
import app.kinjar.ui.sync.FamilyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyRecoveryUiTest {
    private val dispatcher = StandardTestDispatcher()
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-07T10:00:00Z"), ZoneId.of("UTC"))
    private val store = EventStore(db, "install", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "install")
    private val auth = mock(FirebaseAuthRepository::class.java)
    private val families = mock(FamilyRepository::class.java)
    private val sync = mock(SyncCoordinator::class.java)
    private val backups = mock(BackupFiles::class.java)
    private val models = mutableListOf<FamilyViewModel>()

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        doReturn(GoogleAccount("user", "user@example.test", "User")).`when`(auth).current()
        doReturn(MutableStateFlow(SyncStatus())).`when`(sync).status
        doReturn(MutableStateFlow<FamilyInfo?>(null)).`when`(sync).observedFamily
    }
    @After fun close() {
        try {
            runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun viewModel(): FamilyViewModel {
        doReturn(emptyList<FamilyInvitation>()).`when`(families).invitations()
        return FamilyViewModel(auth, families, sync, backups, db, store, repo).also {
            models += it
            it.awaitIdle()
        }
    }
    private suspend fun FamilyViewModel.awaitIdle() { writes.state.first { !it.busy } }

    @Test fun `copy retained before sign in stays listed and exportable`() = runTest(dispatcher) {
        repo.setUp("Local", listOf(Account("cash", "Cash", Currency.RSD)))
        store.restoreLocal(store.captureHistory(), store.currentDigest())
        val local = db.syncDraftQueries.draftInfo("local").executeAsList().single().id
        db.syncDraftQueries.save("other-copy", "other", "other-family", 0, ByteArray(0))
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(listOf(local), vm.state.value.copies.map { it.id })

        val expected = requireNotNull(store.draftHistory("local", local))
        val uri = mock(Uri::class.java)
        val password = "secret".toCharArray()
        vm.prepareExport(password, local)
        vm.export(uri)
        vm.awaitIdle()
        assertTrue(vm.state.value.savedBackup)
        verify(backups).write(uri, expected, password)

        vm.prepareExport("secret".toCharArray(), "other-copy")
        vm.export(mock(Uri::class.java))
        vm.awaitIdle()
        assertFalse(vm.state.value.savedBackup)
        assertTrue(vm.writes.state.value.failed)
    }

    @Test fun `obsolete journal disappearing is not reported as a successful restore`() = runTest(dispatcher) {
        val status = MutableStateFlow(SyncStatus())
        doReturn(status).`when`(sync).status
        db.syncSessionQueries.save("user", "family", "old", "member", "install", 1, "ACTIVE")
        db.restoreJournalQueries.save("user", "family", "old", "target", "token", "RESTORE", "{}".toByteArray())
        val vm = viewModel()
        vm.state.first { it.journalPhase == "RESTORE" }
        store.read { db.restoreJournalQueries.deleteAll() }
        status.value = SyncStatus(SyncPhase.READY)
        vm.state.first { it.journalPhase == null }
        advanceUntilIdle()
        assertNotEquals(R.string.fbs_restored, vm.state.value.notice)
    }

    @Test fun `cancelling replacement runs store work off the caller thread`() = runTest(dispatcher) {
        val vm = viewModel()
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread>()
        doAnswer { worker.set(Thread.currentThread()) }.`when`(sync).cancelReplacement()
        vm.cancelReplacement()
        vm.awaitIdle()
        assertFalse(vm.writes.state.value.failed)
        assertNotEquals(caller, worker.get())
    }

    @Test fun `retained copies are deleted only for this account or this device`() = runTest(dispatcher) {
        repo.setUp("Local", listOf(Account("cash", "Cash", Currency.RSD)))
        store.restoreLocal(store.captureHistory(), store.currentDigest())
        val local = db.syncDraftQueries.draftInfo("local").executeAsList().single().id
        val mine = store.retainHistory(store.captureHistory(), "user", "family")
        db.syncDraftQueries.save("other-copy", "other", "other-family", 0, ByteArray(0))
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(setOf(local, mine), vm.state.value.copies.map { it.id }.toSet())

        vm.deleteCopy("other-copy")
        vm.awaitIdle()
        assertTrue(vm.writes.state.value.failed)
        assertEquals(listOf("other-copy"), db.syncDraftQueries.draftInfo("other").executeAsList().map { it.id })

        vm.deleteCopy(mine)
        vm.awaitIdle()
        assertEquals(listOf(local), vm.state.value.copies.map { it.id })
        vm.deleteCopy(local)
        vm.awaitIdle()
        assertFalse(vm.writes.state.value.failed)
        assertTrue(vm.state.value.copies.isEmpty())
        assertTrue(db.historySpoolQueries.unreferenced().executeAsList().isEmpty())
        assertEquals(listOf("other-copy"), db.syncDraftQueries.draftInfo("other").executeAsList().map { it.id })
    }

    @Test fun `cloud session matches only while this account keeps its member id`() = runTest(dispatcher) {
        db.syncSessionQueries.save("user", "family", "dataset", "member", "install", 1, "ACTIVE")
        val family = FamilyInfo("family", "user", listOf("user"), mapOf("user" to "rejoined"), "dataset", 1, "ACTIVE")
        doReturn(FamilyMembership("family", "rejoined", false)).`when`(families).membership()
        doReturn(family).`when`(families).info("family")
        doReturn(emptyList<FamilyDevice>()).`when`(families).devices("family")
        val vm = viewModel()
        advanceUntilIdle()
        assertFalse(vm.state.value.sessionMatchesCloud)

        doReturn(family.copy(members = mapOf("user" to "member"))).`when`(families).info("family")
        vm.refresh()
        vm.awaitIdle()
        assertTrue(vm.state.value.sessionMatchesCloud)
    }
}
