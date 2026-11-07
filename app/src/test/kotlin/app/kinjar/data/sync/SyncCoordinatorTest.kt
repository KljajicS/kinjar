package app.kinjar.data.sync

import android.content.Context
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kinjar.data.*
import app.kinjar.data.auth.FirebaseServices
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File
import java.time.Instant
import java.time.ZoneId

class SyncCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private lateinit var db: KinjarDb
    private lateinit var store: EventStore
    private lateinit var install: String
    private val context = mock(Context::class.java)
    private val services = mock(FirebaseServices::class.java)
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val families = mock(FamilyRepository::class.java)
    private val transport = mock(FirestoreTransport::class.java)
    private val scheduler = mock(SyncScheduler::class.java)
    private var family = FamilyInfo("family", "user", listOf("user"), mapOf("user" to "member"), "dataset", 1, "ACTIVE")
    private var target = FamilyInfo("target", "owner", listOf("owner"), mapOf("owner" to "m-owner"), "shared", 3, "ACTIVE")
    private var membership: FamilyMembership? = FamilyMembership("family", "member", false)
    private val invitation = FamilyInvitation("target", "0", Instant.parse("2026-11-10T10:00:00Z"), "owner@example.com")
    private fun coordinator() = SyncCoordinator(context, services, families, transport, db, store, scheduler)
    private fun digest() = HistoryCodec.digest(HistoryCodec.encode(store.snapshot()))
    private fun enroll() = store.activate("user", "family", "dataset", "member", 1, store.snapshot(), digest(), false)
    private fun anyHistory() = any(FamilyHistory::class.java) ?: FamilyHistory(baseline = emptyList(), operations = emptyList())
    private fun anyBytes() = any(ByteArray::class.java) ?: byteArrayOf()

    @Before fun prepare(): Unit = runBlocking {
        KinjarDb.Schema.create(driver)
        db = KinjarDb(driver)
        install = InstallationIdentity.load(File(temporary.root, "install-id"))
        store = EventStore(db, install, FixedClock(Instant.parse("2026-11-04T10:00:00Z"), ZoneId.of("UTC")), Projector(db))
        store.appendAll(listOf(MemberCreated(Member("member", "Test", "user")), AccountCreated(Account("account", "Cash", Currency.RSD))))
        doReturn(temporary.root).`when`(context).noBackupFilesDir
        doReturn(true).`when`(services).configured
        doReturn(auth).`when`(services).auth
        doReturn(user).`when`(auth).currentUser
        doReturn(true).`when`(user).isEmailVerified
        doReturn("user").`when`(user).uid
        doAnswer { membership }.`when`(families).membership()
        doAnswer { call -> if (call.getArgument<String>(0) == "target") target else family }.`when`(families).info(anyString())
        doReturn("0").`when`(transport).register(anyString(), anyString(), anyString())
        doReturn(CloudPage(emptyList(), null, false)).`when`(transport).download(anyString(), anyString(), anyString(), nullable(CloudCursor::class.java), anyBoolean())
    }

    @After fun close() = driver.close()

    @Test fun `failed initial creation can be abandoned offline without losing local finances`() = runTest {
        doThrow(IllegalStateException("Public capacity reached")).`when`(families).create(anyString(), anyString(), anyString())
        val sync = coordinator()
        assertTrue(runCatching { sync.createFamily("member") }.isFailure)
        assertEquals("CREATE", db.restoreJournalQueries.current().executeAsOne().phase)
        assertTrue(runCatching { store.append(AccountCreated(Account("blocked", "Blocked", Currency.RSD))) }.isFailure)
        doReturn(null).`when`(auth).currentUser
        coordinator().abandonRecovery()
        store.append(AccountCreated(Account("later", "Later", Currency.RSD)))
        assertEquals(2, db.accountQueries.active().executeAsList().size)
        assertNull(db.syncSessionQueries.current().executeAsOneOrNull())
    }

    @Test fun `confirmed removal clears pending restore before journal pause`() = runTest {
        enroll()
        db.restoreJournalQueries.save("user", "family", "dataset", "new", "token", "RESTORE", byteArrayOf(1))
        doReturn(FamilyMembership("family", "member", true)).`when`(families).membership()
        val sync = coordinator()
        sync.synchronize()
        assertEquals(SyncPhase.REMOVED, sync.status.value.phase)
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        assertEquals(0L, db.eventQueries.count().executeAsOne())
        verifyNoInteractions(transport)
    }

    @Test fun `copied installation does not read or publish cloud data`() = runTest {
        enroll()
        db.syncSessionQueries.save("user", "family", "dataset", "member", "copied", 1, "ACTIVE")
        val sync = coordinator()
        sync.synchronize()
        assertEquals(SyncPhase.REPLACEMENT_REQUIRED, sync.status.value.phase)
        assertEquals(1, db.accountQueries.active().executeAsList().size)
        verifyNoInteractions(transport)
    }

    @Test fun `account switch during download leaves local state and checkpoint untouched`() = runTest {
        enroll()
        val before = store.snapshot()
        doAnswer {
            doReturn(null).`when`(auth).currentUser
            CloudPage(emptyList(), CloudCursor(100, 0, "other:1"), false)
        }.`when`(transport).download(anyString(), anyString(), anyString(), nullable(CloudCursor::class.java), anyBoolean())
        assertTrue(runCatching { coordinator().synchronize() }.isFailure)
        assertEquals(before, store.snapshot())
        assertNull(db.syncCheckpointQueries.byDataset("dataset").executeAsOneOrNull())
    }

    @Test fun `ambiguous upload retries exact bytes after coordinator recreation`() = runTest {
        enroll()
        store.append(AccountCreated(Account("new", "New", Currency.RSD)))
        val attempts = mutableListOf<ByteArray>()
        doAnswer { call ->
            attempts += call.getArgument<ByteArray>(4).copyOf()
            if (attempts.size == 1) throw IllegalStateException("Response lost after commit")
            Unit
        }.`when`(transport).upload(anyString(), anyString(), anyString(), anyString(), anyBytes())
        assertTrue(runCatching { coordinator().synchronize() }.isFailure)
        assertEquals(1, db.syncOutboxQueries.pending("family", "dataset").executeAsList().size)
        coordinator().synchronize()
        assertEquals(2, attempts.size)
        assertArrayEquals(attempts[0], attempts[1])
        assertTrue(db.syncOutboxQueries.pending("family", "dataset").executeAsList().isEmpty())
    }

    @Test fun `restore resumes after cloud activation response is lost`() = runTest {
        enroll()
        val replacement = store.snapshot().copy(baseline = store.snapshot().baseline.filterNot { it.event is AccountCreated })
        var failOnce = true
        doAnswer { call ->
            family = family.copy(activeDataset = call.getArgument(4), generation = 2)
            Unit
        }.`when`(transport).beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
        doAnswer {
            if (failOnce) { failOnce = false; throw IllegalStateException("Response lost after activation") }
            Unit
        }.`when`(transport).initialize(anyString(), anyString(), anyString(), anyHistory(), anyString())
        assertTrue(runCatching { coordinator().restoreFamily(replacement, digest()) }.isFailure)
        assertEquals("RESTORE", db.restoreJournalQueries.current().executeAsOne().phase)
        assertEquals(1, db.accountQueries.active().executeAsList().size)
        doReturn(replacement).`when`(transport).downloadBaseline("user", "family", family.activeDataset, true)
        coordinator().synchronize()
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        assertTrue(db.accountQueries.active().executeAsList().isEmpty())
        assertEquals(1, db.syncDraftQueries.all("user").executeAsList().size)
        assertEquals(family.activeDataset, db.syncSessionQueries.current().executeAsOne().dataset_id)
        verify(transport, times(1)).initialize(anyString(), anyString(), anyString(), anyHistory(), anyString())
    }

    @Test fun `download failure after replacement does not report restore failure or leave a journal`() = runTest {
        enroll()
        val backup = store.snapshot()
        doAnswer { call ->
            family = family.copy(activeDataset = call.getArgument(4), generation = 2, otherDataset = "dataset")
            Unit
        }.`when`(transport).beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
        doThrow(IllegalStateException("Connection lost")).`when`(transport)
            .download(anyString(), anyString(), anyString(), nullable(CloudCursor::class.java), anyBoolean())
        val sync = coordinator()
        sync.restoreFamily(backup, digest())
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        assertEquals(family.activeDataset, db.syncSessionQueries.current().executeAsOne().dataset_id)
        assertEquals(backup, store.snapshot())
        assertEquals(SyncPhase.ERROR, sync.status.value.phase)
    }

    @Test fun `failed cleanup remains background maintenance after successful restore`() = runTest {
        enroll()
        doAnswer { call ->
            family = family.copy(activeDataset = call.getArgument(4), generation = 2, otherDataset = "dataset")
            Unit
        }.`when`(transport).beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
        doThrow(IllegalStateException("Cleanup unavailable")).`when`(transport).cleanup("user", "family")
        val sync = coordinator()
        sync.restoreFamily(store.snapshot(), digest())
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        assertEquals(SyncPhase.CLEANUP_REQUIRED, sync.status.value.phase)
        assertEquals(family.activeDataset, db.syncSessionQueries.current().executeAsOne().dataset_id)
        assertEquals(1, db.syncDraftQueries.all("user").executeAsList().size)
        doAnswer { family = family.copy(otherDataset = ""); Unit }.`when`(transport).cleanup("user", "family")
        sync.synchronize()
        assertEquals(SyncPhase.READY, sync.status.value.phase)
        assertEquals(1, db.syncDraftQueries.all("user").executeAsList().size)
    }

    @Test fun `pending cleanup failure cannot start a second restore journal`() = runTest {
        enroll()
        family = family.copy(otherDataset = "retired")
        doThrow(IllegalStateException("Cleanup unavailable")).`when`(transport).cleanup("user", "family")
        assertTrue(runCatching { coordinator().restoreFamily(store.snapshot(), digest()) }.isFailure)
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        verify(transport, never()).beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
    }

    @Test fun `ordinary member cannot start family restore`() = runTest {
        enroll()
        family = family.copy(owner = "owner", admins = listOf("owner"))
        val before = store.snapshot()
        assertTrue(runCatching { coordinator().restoreFamily(before, digest()) }.isFailure)
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        assertEquals(before, store.snapshot())
        verifyNoInteractions(transport)
    }

    @Test fun `stale preview never starts cloud replacement`() = runTest {
        enroll()
        val backup = store.snapshot()
        val confirmed = digest()
        store.append(AccountCreated(Account("later", "Later", Currency.RSD)))
        val failure = runCatching { coordinator().restoreFamily(backup, confirmed) }.exceptionOrNull()
        assertTrue(failure is LocalHistoryChanged)
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        assertEquals(2, db.accountQueries.active().executeAsList().size)
        verifyNoInteractions(transport)
    }

    @Test fun `idle synchronization does not reserve device slots`() = runTest {
        enroll()
        coordinator().synchronize()
        verify(transport, never()).register(anyString(), anyString(), anyString())
        assertTrue(db.syncOutboxQueries.pending("family", "dataset").executeAsList().isEmpty())
    }

    @Test fun `recovery cannot overwrite another account or an unfinished restore journal`() = runTest {
        enroll()
        for (uid in listOf("other-user", "user")) {
            db.restoreJournalQueries.save(uid, "family", "dataset", "pending", "token", "RESTORE", byteArrayOf(1))
            assertTrue(runCatching { coordinator().stageRecovery() }.isFailure)
            assertEquals(uid, db.restoreJournalQueries.current().executeAsOne().uid)
            assertEquals("RESTORE", db.restoreJournalQueries.current().executeAsOne().phase)
        }
        verifyNoInteractions(transport)
    }

    @Test fun `freshly confirmed recovery replaces stale staging without losing the newer local copy`() = runTest {
        enroll()
        val backup = store.snapshot()
        doReturn(backup).`when`(transport).downloadBaseline("user", "family", "dataset", true)
        val sync = coordinator()
        sync.stageRecovery()
        store.append(AccountCreated(Account("later", "Later", Currency.RSD)))
        assertTrue(runCatching { sync.confirmReplacement() }.exceptionOrNull() is LocalHistoryChanged)
        sync.stageRecovery()
        sync.confirmReplacement()
        assertEquals(listOf("account"), db.accountQueries.active().executeAsList().map { it.id })
        val retained = HistoryCodec.decode(db.syncDraftQueries.all("user").executeAsList().single().payload)
        assertTrue(retained.decodedOperations().any { op -> op.events.any { (it.event as? AccountCreated)?.account?.id == "later" } })
    }

    @Test fun `manual reconciliation reads before saved cursor without duplicating data`() = runTest {
        enroll()
        db.syncCheckpointQueries.save("dataset", 100, 0, "old:1")
        val before = store.snapshot()
        coordinator().synchronize(reconcile = true)
        verify(transport).download("user", "family", "dataset", null, true)
        assertEquals(before, store.snapshot())
    }

    private fun targetHistory() = store.snapshot().let { it.copy(baseline = it.baseline.filterNot { record -> record.event is AccountCreated }) }
    private fun journal() = db.restoreJournalQueries.current().executeAsOneOrNull()
    private fun sessionFamily() = db.syncSessionQueries.current().executeAsOne().family_id
    private fun accounts() = db.accountQueries.active().executeAsList().size

    private suspend fun commitSwitch(after: () -> Unit = {}) = doAnswer { call ->
        assertEquals(listOf("family", "target", "0"), (0..2).map { call.getArgument<String>(it) })
        val memberId = call.getArgument<String>(3)
        membership = FamilyMembership("target", memberId, false)
        target = target.copy(members = target.members + ("user" to memberId))
        family = family.copy(status = "ARCHIVED")
        after()
        Unit
    }.`when`(families).joinFromPersonal(anyString(), anyString(), anyString(), anyString())

    @Test fun `personal workspace joins invited family keeping its cloud archive and local copy`() = runTest {
        enroll()
        doReturn(targetHistory()).`when`(transport).downloadBaseline("user", "target", "shared", true)
        store.append(AccountCreated(Account("personal", "Personal", Currency.RSD)))
        val uploadedTo = mutableListOf<String>()
        doAnswer { call -> uploadedTo += call.getArgument<String>(1); Unit }
            .`when`(transport).upload(anyString(), anyString(), anyString(), anyString(), anyBytes())
        commitSwitch()
        val sync = coordinator()
        sync.joinFromPersonal(invitation)
        assertNull(journal())
        assertEquals("target", sessionFamily())
        assertEquals("shared", db.syncSessionQueries.current().executeAsOne().dataset_id)
        assertEquals(0, accounts())
        val draft = db.syncDraftQueries.all("user").executeAsList().single()
        assertEquals("family", draft.family_id)
        assertTrue(HistoryCodec.decode(draft.payload).decodedOperations().any { op -> op.events.any { (it.event as? AccountCreated)?.account?.id == "personal" } })
        assertEquals(listOf("family", "target"), uploadedTo)
        verify(families, times(1)).joinFromPersonal(anyString(), anyString(), anyString(), anyString())
        verify(families, never()).beginDeletion(anyString())
        verify(transport, never()).deleteFamily(anyString(), anyString())
        assertEquals("target", sync.observedFamily.value?.id)
    }

    @Test fun `shared workspace cannot leave through an archive`() = runTest {
        enroll()
        family = family.copy(members = mapOf("user" to "member", "other" to "other-member"))
        assertTrue(runCatching { coordinator().joinFromPersonal(invitation) }.isFailure)
        assertNull(journal())
        assertEquals(1, accounts())
        verify(families, never()).joinFromPersonal(anyString(), anyString(), anyString(), anyString())
    }

    @Test fun `committed switch whose response was lost resumes after restart without a second commit`() = runTest {
        enroll()
        doReturn(targetHistory()).`when`(transport).downloadBaseline("user", "target", "shared", true)
        commitSwitch { throw IllegalStateException("Response lost after commit") }
        assertTrue(runCatching { coordinator().joinFromPersonal(invitation) }.isFailure)
        assertEquals("SWITCH", journal()?.phase)
        assertEquals("family", sessionFamily())
        assertEquals(1, accounts())
        assertTrue(runCatching { store.append(AccountCreated(Account("blocked", "Blocked", Currency.RSD))) }.isFailure)
        assertEquals("Finish family setup or restore before editing finances", runCatching { store.discardConflictingChange("held") }.exceptionOrNull()?.message)
        coordinator().synchronize()
        assertNull(journal())
        assertEquals("target", sessionFamily())
        assertEquals("family", db.syncDraftQueries.all("user").executeAsList().single().family_id)
        verify(families, times(1)).joinFromPersonal(anyString(), anyString(), anyString(), anyString())
    }

    @Test fun `rejected or full invitation leaves the personal workspace active and editable`() = runTest {
        enroll()
        for ((failure, phase) in listOf(FamilySwitchRejected() to SyncPhase.ERROR, SyncCapacityReached() to SyncPhase.CAPACITY)) {
            doThrow(failure).`when`(families).joinFromPersonal(anyString(), anyString(), anyString(), anyString())
            val sync = coordinator()
            assertTrue(failure.javaClass.isInstance(runCatching { sync.joinFromPersonal(invitation) }.exceptionOrNull()))
            assertEquals(phase, sync.status.value.phase)
            assertNull(journal())
            assertEquals("family", sessionFamily())
        }
        store.append(AccountCreated(Account("later", "Later", Currency.RSD)))
        assertEquals(2, accounts())
    }

    @Test fun `stale personal switch journal cannot commit the cloud handoff`() = runTest {
        enroll()
        doThrow(IllegalStateException("Connection interrupted")).`when`(families).joinFromPersonal(anyString(), anyString(), anyString(), anyString())
        assertTrue(runCatching { coordinator().joinFromPersonal(invitation) }.isFailure)
        val pending = requireNotNull(journal())
        val stale = pending.manifest.toString(Charsets.UTF_8).replace(digest(), "stale")
        db.restoreJournalQueries.save(pending.uid, pending.family_id, pending.source_dataset, pending.target_dataset,
            pending.token, pending.phase, stale.toByteArray())
        assertTrue(runCatching { coordinator().synchronize() }.exceptionOrNull() is LocalHistoryChanged)
        verify(families, times(1)).joinFromPersonal(anyString(), anyString(), anyString(), anyString())
        assertEquals("family", sessionFamily())
        assertEquals(1, accounts())
        assertEquals("SWITCH", journal()?.phase)
    }

    @Test fun `family change during download keeps the journal and local data until a safe retry`() = runTest {
        enroll()
        doReturn(targetHistory()).`when`(transport).downloadBaseline("user", "target", "shared", true)
        commitSwitch()
        var changed = false
        doAnswer { call ->
            if (call.getArgument<String>(1) == "target" && !changed) { changed = true; target = target.copy(generation = target.generation + 1) }
            CloudPage(emptyList(), null, false)
        }.`when`(transport).download(anyString(), anyString(), anyString(), nullable(CloudCursor::class.java), anyBoolean())
        assertTrue(runCatching { coordinator().joinFromPersonal(invitation) }.isFailure)
        assertEquals("SWITCH", journal()?.phase)
        assertEquals("family", sessionFamily())
        assertEquals(1, accounts())
        coordinator().synchronize()
        assertNull(journal())
        assertEquals("target", sessionFamily())
        assertEquals(4L, db.syncSessionQueries.current().executeAsOne().generation)
        verify(families, times(1)).joinFromPersonal(anyString(), anyString(), anyString(), anyString())
    }

    @Test fun `another account never resumes or replaces a pending switch`() = runTest {
        enroll()
        commitSwitch { throw IllegalStateException("Response lost after commit") }
        assertTrue(runCatching { coordinator().joinFromPersonal(invitation) }.isFailure)
        doReturn("other").`when`(user).uid
        val sync = coordinator()
        sync.synchronize()
        assertTrue(runCatching { sync.joinFromPersonal(invitation) }.isFailure)
        assertEquals("user", journal()?.uid)
        assertEquals("SWITCH", journal()?.phase)
        assertEquals("family", sessionFamily())
        assertEquals(1, accounts())
        verify(families, times(1)).joinFromPersonal(anyString(), anyString(), anyString(), anyString())
        verify(transport, never()).downloadBaseline(anyString(), anyString(), anyString(), anyBoolean())
    }

    @Test fun `copy left on a personal workspace after the account joined elsewhere waits for confirmed replacement`() = runTest {
        enroll()
        membership = FamilyMembership("target", "joined", false)
        target = target.copy(members = target.members + ("user" to "joined"))
        val sync = coordinator()
        sync.synchronize()
        assertEquals(SyncPhase.REPLACEMENT_REQUIRED, sync.status.value.phase)
        assertNull(sync.observedFamily.value)
        assertEquals(1, accounts())
        verifyNoInteractions(transport)
        doReturn(targetHistory()).`when`(transport).downloadBaseline("user", "target", "shared", true)
        sync.stageRecovery()
        sync.confirmReplacement()
        assertEquals("target", sessionFamily())
        assertEquals(0, accounts())
        assertEquals("family", db.syncDraftQueries.all("user").executeAsList().single().family_id)
    }

    @Test fun `observed family follows synchronization and clears when signed out`() = runTest {
        enroll()
        val sync = coordinator()
        sync.synchronize()
        assertEquals(family, sync.observedFamily.value)
        doReturn(null).`when`(auth).currentUser
        sync.synchronize()
        assertNull(sync.observedFamily.value)
    }

    @Test fun `foreground auth listener clears stale status after sign out`() = runTest {
        enroll()
        val sync = coordinator()
        sync.synchronize()
        doReturn(null).`when`(auth).currentUser
        doAnswer { call ->
            call.getArgument<FirebaseAuth.AuthStateListener>(0).onAuthStateChanged(auth)
            null
        }.`when`(auth).addAuthStateListener(any(FirebaseAuth.AuthStateListener::class.java))
        val foreground = backgroundScope.launch { sync.foreground() }
        sync.status.first { it.phase == SyncPhase.SIGNED_OUT }
        assertNull(sync.observedFamily.value)
        foreground.cancel()
    }

    @Test fun `archived workspace history stays exportable to its owner without local changes`() = runTest {
        enroll()
        family = family.copy(status = "ARCHIVED")
        doReturn(store.snapshot()).`when`(transport).downloadBaseline("user", "family", "dataset", true)
        val before = store.snapshot()
        val history = coordinator().archivedHistory("family")
        assertTrue(history.baseline.any { (it.event as? AccountCreated)?.account?.id == "account" })
        assertEquals(before, store.snapshot())
        family = family.copy(owner = "other")
        assertTrue(runCatching { coordinator().archivedHistory("family") }.isFailure)
    }
}
