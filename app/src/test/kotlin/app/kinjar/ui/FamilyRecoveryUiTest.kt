package app.kinjar.ui

import android.net.Uri
import app.kinjar.data.*
import app.kinjar.data.auth.FirebaseAuthRepository
import app.kinjar.data.auth.GoogleAccount
import app.kinjar.data.backup.BackupFiles
import app.kinjar.data.sync.*
import app.kinjar.domain.*
import app.kinjar.ui.sync.FamilyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        doReturn(GoogleAccount("user", "user@example.test", "User")).`when`(auth).current()
        doReturn(MutableStateFlow(SyncStatus())).`when`(sync).status
        doReturn(MutableStateFlow<FamilyInfo?>(null)).`when`(sync).observedFamily
    }
    @After fun close() { Dispatchers.resetMain() }

    private suspend fun viewModel(): FamilyViewModel {
        doReturn(emptyList<FamilyInvitation>()).`when`(families).invitations()
        return FamilyViewModel(auth, families, sync, backups, db, store, repo).also { it.awaitIdle() }
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
}
