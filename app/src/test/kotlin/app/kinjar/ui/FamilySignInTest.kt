package app.kinjar.ui

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

@OptIn(ExperimentalCoroutinesApi::class)
class FamilySignInTest {
    private val dispatcher = StandardTestDispatcher()
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-07T10:00:00Z"), ZoneId.of("UTC"))
    private val store = EventStore(db, "install", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "install")
    private val auth = mock(FirebaseAuthRepository::class.java)
    private val families = mock(FamilyRepository::class.java)
    private val sync = mock(SyncCoordinator::class.java)
    private val cloud = FamilyInfo("family", "user", listOf("user"), mapOf("user" to "member"), "dataset", 1, "ACTIVE")

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        doReturn(GoogleAccount("user", "user@example.test", "Google Name")).`when`(auth).current()
        doReturn(MutableStateFlow(SyncStatus())).`when`(sync).status
        doReturn(MutableStateFlow<FamilyInfo?>(null)).`when`(sync).observedFamily
    }
    @After fun close() { Dispatchers.resetMain() }
    private suspend fun viewModel(): FamilyViewModel {
        doReturn(emptyList<FamilyInvitation>()).`when`(families).invitations()
        doReturn(emptyList<FamilyDevice>()).`when`(families).devices(anyString())
        return FamilyViewModel(auth, families, sync, mock(BackupFiles::class.java), db, store, repo).also {
            it.writes.state.first { state -> !state.busy }
        }
    }

    private suspend fun FamilyViewModel.awaitIdle() { writes.state.first { !it.busy } }

    @Test fun `new Google account chooses accounts before saving personal cloud`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.continueSignIn()
        vm.awaitIdle()
        advanceUntilIdle()
        assertTrue(vm.state.value.needsCloudSetup)
        verify(sync, never()).createFamily(anyString())
        assertTrue(db.memberQueries.all().executeAsList().isEmpty())
        vm.createWithAccounts(listOf(Account("cash", "Cash", Currency.RSD)))
        vm.awaitIdle()
        advanceUntilIdle()
        assertEquals("Google Name", repo.currentMember()!!.displayName)
        assertEquals("cash", db.accountQueries.active().executeAsList().single().id)
        verify(sync).createFamily(repo.currentMember()!!.id)
        assertTrue(vm.state.value.completedEnrollment)
    }

    @Test fun `existing local profile uploads on explicit Google sign in`() = runTest(dispatcher) {
        repo.setUp("Local Name", listOf(Account("cash", "Cash", Currency.RSD)))
        val member = repo.currentMember()!!.id
        val vm = viewModel()
        vm.continueSignIn()
        vm.awaitIdle()
        advanceUntilIdle()
        verify(sync).createFamily(member)
        assertEquals("Local Name", repo.currentMember()!!.displayName)
        assertTrue(vm.state.value.completedEnrollment)
    }

    @Test fun `existing cloud with local finances requires replacement confirmation`() = runTest(dispatcher) {
        repo.setUp("Local", listOf(Account("cash", "Cash", Currency.RSD)))
        doReturn(FamilyMembership("family", "member", false)).`when`(families).membership()
        doReturn(cloud).`when`(families).info("family")
        val vm = viewModel()
        vm.continueSignIn()
        vm.awaitIdle()
        advanceUntilIdle()
        verify(sync, never()).createFamily(anyString())
        verify(sync, never()).confirmReplacement()
        assertFalse(vm.state.value.needsCloudSetup)
        assertFalse(vm.state.value.completedEnrollment)
        assertEquals("cash", db.accountQueries.active().executeAsList().single().id)
    }

    @Test fun `fresh phone restores existing cloud without creating local accounts`() = runTest(dispatcher) {
        doReturn(FamilyMembership("family", "member", false)).`when`(families).membership()
        doReturn(cloud).`when`(families).info("family")
        val vm = viewModel()
        vm.continueSignIn()
        vm.awaitIdle()
        advanceUntilIdle()
        verify(sync).stageRecovery(null)
        verify(sync).confirmReplacement()
        verify(sync, never()).createFamily(anyString())
        assertTrue(vm.state.value.completedEnrollment)
    }

    @Test fun `signing in another account never uploads old family finances automatically`() = runTest(dispatcher) {
        repo.setUp("Previous", listOf(Account("cash", "Cash", Currency.RSD)))
        val history = store.snapshot()
        store.activate("previous-user", "previous-family", "previous-dataset", repo.currentMember()!!.id, 1,
            history, HistoryCodec.digest(HistoryCodec.encode(history)), false)
        doReturn(MutableStateFlow(SyncStatus(SyncPhase.READY))).`when`(sync).status
        val vm = viewModel()
        vm.continueSignIn()
        vm.awaitIdle()
        advanceUntilIdle()
        verify(sync, never()).createFamily(anyString())
        verify(sync, never()).confirmReplacement()
        assertFalse(vm.state.value.completedEnrollment)
        assertEquals(history, store.snapshot())
    }
}
