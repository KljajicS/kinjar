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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
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
    private fun current() = store.currentDigest()
    private fun source(history: FamilyHistory) = legacyHistorySource(history)
    private fun staged(history: FamilyHistory) = store.stageHistory(source(history))
    private fun records(history: StoredHistory) = store.historySource(history.id).records().toList()
    private fun anySource() = any(HistorySource::class.java) ?: source(FamilyHistory(baseline = emptyList(), operations = emptyList()))
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
        doReturn(true).`when`(families).admitted()
        doAnswer { call -> if (call.getArgument<String>(0) == "target") target else family }.`when`(families).info(anyString())
        doReturn("0").`when`(transport).register(anyString(), anyString(), anyString())
        doReturn(true).`when`(transport).cleanup(anyString(), anyString(), anyInt())
        doReturn(CloudPage(emptyList(), null, false)).`when`(transport).download(anyString(), anyString(), anyString(), nullable(CloudCursor::class.java), anyBoolean())
    }

    @After fun close() = driver.close()

    @Test fun `leaving retains local history and pending writes only after server removal`() = runTest {
        enroll()
        store.append(AccountCreated(Account("pending", "Pending", Currency.RSD)))
        val before = store.snapshot()
        doAnswer {
            assertEquals(before, store.snapshot())
            membership = membership!!.copy(removed = true)
            Unit
        }.`when`(families).leave("family", "user")
        val sync = coordinator()
        sync.leaveFamily("family")
        verify(families).leave("family", "user")
        assertEquals(before.source().records().toList(), drafts().single())
        assertNull(db.syncSessionQueries.current().executeAsOneOrNull())
        assertEquals(0L, db.eventQueries.count().executeAsOne())
        assertEquals(0, accounts())
        assertTrue(db.syncOutboxQueries.pending("family", "dataset").executeAsList().isEmpty())
        assertEquals(SyncPhase.REMOVED, sync.status.value.phase)
        assertNull(sync.observedFamily.value)
        verifyNoInteractions(transport)
    }

    @Test fun `failed leave preserves finances and a lost response recovers through sync`() = runTest {
        enroll()
        store.append(AccountCreated(Account("pending", "Pending", Currency.RSD)))
        val before = store.snapshot()
        doThrow(IllegalStateException("Offline")).`when`(families).leave("family", "user")
        val sync = coordinator()
        assertTrue(runCatching { sync.leaveFamily("family") }.isFailure)
        assertEquals(before, store.snapshot())
        assertEquals("family", sessionFamily())
        assertTrue(drafts().isEmpty())
        membership = membership!!.copy(removed = true)
        coordinator().synchronize()
        assertNull(db.syncSessionQueries.current().executeAsOneOrNull())
        assertEquals(0, accounts())
        assertEquals(before.source().records().toList(), drafts().single())
        verifyNoInteractions(transport)
    }

    @Test fun `authoritative removal detaches stale family and repeated restart keeps one copy`() = runTest {
        enroll()
        store.append(AccountCreated(Account("pending", "Pending", Currency.RSD)))
        val before = store.snapshot()
        membership = FamilyMembership("target", "other-member", true)
        coordinator().synchronize()
        coordinator().synchronize()
        assertNull(db.syncSessionQueries.current().executeAsOneOrNull())
        assertEquals(before.source().records().toList(), drafts().single())
        verifyNoInteractions(transport)
    }

    @Test fun `leave rejects foreign local account and preserves account switch after remote commit`() = runTest {
        enroll()
        val before = store.snapshot()
        doReturn("other-user").`when`(user).uid
        assertTrue(runCatching { coordinator().leaveFamily("family") }.isFailure)
        verify(families, never()).leave(anyString(), anyString())
        doReturn("user").`when`(user).uid
        doAnswer { doReturn("other-user").`when`(user).uid; Unit }.`when`(families).leave("family", "user")
        assertTrue(runCatching { coordinator().leaveFamily("family") }.isFailure)
        assertEquals(before, store.snapshot())
        assertEquals("family", sessionFamily())
        assertTrue(drafts().isEmpty())
    }

    @Test fun `no session leave preserves unrelated standalone budget`() = runTest {
        val before = store.snapshot()
        coordinator().leaveFamily("family")
        verify(families).leave("family", "user")
        assertEquals(before, store.snapshot())
        assertTrue(drafts().isEmpty())
        verifyNoInteractions(transport)
    }

    @Test fun `phone reconnecting after another phone creates family retains old edits on replacement`() = runTest {
        enroll()
        store.append(AccountCreated(Account("pending", "Pending", Currency.RSD)))
        val before = store.snapshot()
        target = target.copy(members = target.members + ("user" to "new-member"))
        membership = FamilyMembership("target", "new-member", false)
        val sync = coordinator()
        sync.synchronize()
        assertEquals(SyncPhase.REPLACEMENT_REQUIRED, sync.status.value.phase)
        assertEquals(before, store.snapshot())
        verifyNoInteractions(transport)
        val fresh = FamilyHistory(baseline = before.baseline.filter { it.event is MemberCreated }
            .map { it.copy(event = MemberCreated(Member("new-member", "New", "user"))) }, operations = emptyList())
        doReturn(source(fresh)).`when`(transport).downloadBaseline("user", "target", "shared", true)
        sync.stageRecovery()
        coordinator().confirmReplacement()
        assertEquals("target", sessionFamily())
        assertEquals(before.source().records().toList(), drafts().single())
        assertTrue(db.txQueries.all().executeAsList().isEmpty())
        assertEquals(0, accounts())
        verify(transport, never()).upload(anyString(), anyString(), anyString(), anyString(), anyBytes())
    }

    @Test fun `unapproved workspace creation leaves local editing available without a journal`() = runTest {
        doReturn(false).`when`(families).admitted()
        val before = store.snapshot()
        assertTrue(runCatching { coordinator().createFamily("member") }.exceptionOrNull() is WorkspaceAdmissionRequired)
        assertNull(journal())
        assertEquals(before, store.snapshot())
        verify(families, never()).create(anyString(), anyString(), anyString())
        verifyNoInteractions(transport)
        store.append(AccountCreated(Account("later", "Later", Currency.RSD)))
    }

    @Test fun `creation claims the installation before exposing bootstrap history`() = runTest {
        doThrow(InstallationClaimed()).`when`(transport).register(anyString(), anyString(), anyString())
        val before = store.snapshot()
        assertTrue(runCatching { coordinator().createFamily("member") }.exceptionOrNull() is InstallationClaimed)
        verify(transport, never()).initialize(anyString(), anyString(), anyString(), anySource(), anyString())
        assertEquals("CREATE", journal()?.phase)
        assertEquals(before, store.snapshot())
    }

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
        }.`when`(transport).initialize(anyString(), anyString(), anyString(), anySource(), anyString())
        assertTrue(runCatching { coordinator().restoreFamily(staged(replacement), current()) }.isFailure)
        assertEquals("RESTORE", db.restoreJournalQueries.current().executeAsOne().phase)
        assertEquals(1, db.accountQueries.active().executeAsList().size)
        doReturn(source(replacement)).`when`(transport).downloadBaseline("user", "family", family.activeDataset, true)
        coordinator().synchronize()
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        assertTrue(db.accountQueries.active().executeAsList().isEmpty())
        assertEquals(1, db.syncDraftQueries.all("user").executeAsList().size)
        assertEquals(family.activeDataset, db.syncSessionQueries.current().executeAsOne().dataset_id)
        verify(transport, times(1)).initialize(anyString(), anyString(), anyString(), anySource(), anyString())
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
        sync.restoreFamily(staged(backup), current())
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
        sync.restoreFamily(staged(store.snapshot()), current())
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        assertEquals(SyncPhase.CLEANUP_REQUIRED, sync.status.value.phase)
        assertEquals(family.activeDataset, db.syncSessionQueries.current().executeAsOne().dataset_id)
        assertEquals(1, db.syncDraftQueries.all("user").executeAsList().size)
        doAnswer { family = family.copy(otherDataset = ""); true }.`when`(transport).cleanup("user", "family")
        sync.synchronize()
        assertEquals(SyncPhase.READY, sync.status.value.phase)
        assertEquals(1, db.syncDraftQueries.all("user").executeAsList().size)
    }

    @Test fun `unfinished cleanup releases the lock between batches and schedules its continuation`() = runTest {
        enroll()
        family = family.copy(otherDataset = "retired")
        var batches = 0
        doAnswer { if (++batches == 3) { family = family.copy(otherDataset = ""); true } else false }
            .`when`(transport).cleanup("user", "family")
        val sync = coordinator()
        sync.synchronize()
        assertEquals(1, batches)
        assertEquals(SyncPhase.CLEANUP_REQUIRED, sync.status.value.phase)
        verify(scheduler).continueCleanup()
        assertTrue(sync.continueCleanup())
        assertFalse(sync.continueCleanup())
        assertEquals(3, batches)
        assertEquals(SyncPhase.READY, sync.status.value.phase)
        assertFalse(sync.continueCleanup())
        assertEquals(3, batches)
    }

    @Test fun `background cleanup failures remain cleanup status and propagate for worker retry`() = runTest {
        enroll()
        family = family.copy(otherDataset = "retired")
        val failure = java.io.IOException("Offline")
        doAnswer { throw failure }.`when`(transport).cleanup("user", "family")
        val sync = coordinator()
        assertTrue(runCatching { sync.continueCleanup() }.exceptionOrNull() is java.io.IOException)
        assertEquals(SyncPhase.CLEANUP_REQUIRED, sync.status.value.phase)
        assertTrue(runCatching { sync.cleanupFamily("family") }.exceptionOrNull() is java.io.IOException)
        assertEquals(SyncPhase.CLEANUP_REQUIRED, sync.status.value.phase)
        assertEquals("dataset", db.syncSessionQueries.current().executeAsOne().dataset_id)
    }

    @Test fun `restore waits for unfinished cleanup without writing a journal`() = runTest {
        enroll()
        family = family.copy(otherDataset = "retired")
        doReturn(false).`when`(transport).cleanup("user", "family")
        val sync = coordinator()
        assertTrue(runCatching { sync.restoreFamily(staged(store.snapshot()), current()) }.exceptionOrNull() is CloudCleanupPending)
        assertEquals(SyncPhase.CLEANUP_REQUIRED, sync.status.value.phase)
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        verify(scheduler).continueCleanup()
        verify(transport, never()).beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
    }

    @Test fun `pending cleanup failure cannot start a second restore journal`() = runTest {
        enroll()
        family = family.copy(otherDataset = "retired")
        doThrow(IllegalStateException("Cleanup unavailable")).`when`(transport).cleanup("user", "family")
        assertTrue(runCatching { coordinator().restoreFamily(staged(store.snapshot()), current()) }.isFailure)
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        verify(transport, never()).beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
    }

    @Test fun `ordinary member cannot start family restore`() = runTest {
        enroll()
        family = family.copy(owner = "owner", admins = listOf("owner"))
        val before = store.snapshot()
        assertTrue(runCatching { coordinator().restoreFamily(staged(before), current()) }.isFailure)
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        assertEquals(before, store.snapshot())
        verifyNoInteractions(transport)
    }

    @Test fun `stale preview never starts cloud replacement`() = runTest {
        enroll()
        val backup = staged(store.snapshot())
        val confirmed = current()
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
        doReturn(source(backup)).`when`(transport).downloadBaseline("user", "family", "dataset", true)
        val sync = coordinator()
        sync.stageRecovery()
        store.append(AccountCreated(Account("later", "Later", Currency.RSD)))
        assertTrue(runCatching { sync.confirmReplacement() }.exceptionOrNull() is LocalHistoryChanged)
        sync.stageRecovery()
        sync.confirmReplacement()
        assertEquals(listOf("account"), db.accountQueries.active().executeAsList().map { it.id })
        val retained = requireNotNull(store.draftHistory("user", db.syncDraftQueries.draftInfo("user").executeAsList().single().id))
        assertTrue(records(retained).filterIsInstance<HistoryRecord.Operation>().any { record ->
            OperationCodec.decode(record.bytes()).events.any { (it.event as? AccountCreated)?.account?.id == "later" }
        })
    }

    @Test fun `manual reconciliation reads before saved cursor without duplicating data`() = runTest {
        enroll()
        db.syncCheckpointQueries.save("dataset", 100, 0, "old:1")
        val before = store.snapshot()
        coordinator().synchronize(reconcile = true)
        verify(transport).download("user", "family", "dataset", null, true)
        assertEquals(before, store.snapshot())
    }

    @Test fun `recovery pages past a thousand operations and resumes from a small journal referencing staged history`() = runTest {
        val before = store.snapshot()
        val operations = (1L..1_201L).map { seq ->
            val tx = Transaction("large-$seq", TransactionType.EXPENSE, Money.of("1", Currency.RSD), "account", null, null,
                java.time.LocalDate.of(2026, 11, 4), "n".repeat(1500), "", "member", TransactionSource.MANUAL)
            OperationCodec.encode(SyncOperation(familyId = "family", datasetId = "dataset", authorUid = "peer", installId = "peer", sequence = seq,
                parents = emptySet(), resources = mapOf("transaction:${tx.id}" to ResourceAccess.WRITE, "account:account" to ResourceAccess.READ),
                events = listOf(OperationEvent("peer-event-$seq", "peer", seq, seq, TransactionAdded(tx)))))
        }
        doReturn(source(before)).`when`(transport).downloadBaseline("user", "family", "dataset", true)
        var pages = 0
        doAnswer { call ->
            val offset = call.getArgument<CloudCursor?>(3)?.id?.toInt() ?: 0
            val page = operations.drop(offset).take(100)
            val end = offset + page.size
            pages++
            CloudPage(page, CloudCursor(end.toLong(), 0, end.toString()), end < operations.size)
        }.`when`(transport).download(anyString(), anyString(), anyString(), nullable(CloudCursor::class.java), anyBoolean())

        coordinator().stageRecovery()
        assertEquals(13, pages)
        assertEquals(before, store.snapshot())
        assertTrue(requireNotNull(db.restoreManifest()).size < 64 * 1024)
        doReturn(CloudPage(emptyList(), null, false)).`when`(transport)
            .download(anyString(), anyString(), anyString(), nullable(CloudCursor::class.java), anyBoolean())
        coordinator().confirmReplacement()

        assertNull(db.restoreJournalQueries.journalInfo().executeAsOneOrNull())
        assertEquals(1_201, store.snapshot().operations.size)
        assertEquals(1_201, db.txQueries.all().executeAsList().size)
        assertEquals(operations.map(HistoryCodec::digest), db.syncOperationQueries.all().executeAsList().map { HistoryCodec.digest(it.payload) })
        assertEquals("dataset", db.syncSessionQueries.current().executeAsOne().dataset_id)
        assertTrue(db.syncConflictQueries.all().executeAsList().isEmpty())
    }

    @Test fun `replacement registers this installation even with no pending changes`() = runTest {
        doReturn(source(store.snapshot())).`when`(transport).downloadBaseline("user", "family", "dataset", true)
        val sync = coordinator()
        sync.stageRecovery()
        sync.confirmReplacement()
        verify(transport).register("user", "family", install)
        verify(transport, never()).upload(anyString(), anyString(), anyString(), anyString(), anyBytes())
        assertEquals(SyncPhase.READY, sync.status.value.phase)
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
        doReturn(source(targetHistory())).`when`(transport).downloadBaseline("user", "target", "shared", true)
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
        assertTrue(records(requireNotNull(store.draftHistory("user", draft.id))).filterIsInstance<HistoryRecord.Operation>().any { record ->
            OperationCodec.decode(record.bytes()).events.any { (it.event as? AccountCreated)?.account?.id == "personal" }
        })
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
        doReturn(source(targetHistory())).`when`(transport).downloadBaseline("user", "target", "shared", true)
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
        val stale = pending.manifest.toString(Charsets.UTF_8).replace(current(), "stale")
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
        doReturn(source(targetHistory())).`when`(transport).downloadBaseline("user", "target", "shared", true)
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
        doReturn(source(targetHistory())).`when`(transport).downloadBaseline("user", "target", "shared", true)
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
        doReturn(source(store.snapshot())).`when`(transport).downloadBaseline("user", "family", "dataset", true)
        val before = store.snapshot()
        val history = coordinator().archivedHistory("family")
        assertTrue(records(history).filterIsInstance<HistoryRecord.Baseline>().any { (it.event.event as? AccountCreated)?.account?.id == "account" })
        assertEquals(before, store.snapshot())
        family = family.copy(owner = "other")
        assertTrue(runCatching { coordinator().archivedHistory("family") }.isFailure)
    }

    @Test fun `cancelled archive export releases history completed before dispatcher return`() = runTest {
        family = family.copy(status = "ARCHIVED")
        val archive = source(store.snapshot())
        val job = Job()
        doAnswer { job.cancel(); archive }.`when`(transport).downloadBaseline("user", "family", "dataset", true)
        val failure = runCatching { withContext(job) { coordinator().archivedHistory("family") } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(db.historySpoolQueries.unreferenced().executeAsList().isEmpty())
        assertEquals(1, accounts())
    }

    private fun legacyEnrollment(history: FamilyHistory) =
        """{"memberId":"member","localDigest":"${digest()}","history":${HistoryCodec.encode(history).toString(Charsets.UTF_8)}}""".toByteArray()

    @Test fun `interrupted bootstrap resumes uploading the same staged history after restart`() = runTest {
        val uploads = mutableListOf<List<HistoryRecord>>()
        doAnswer { call ->
            uploads += call.getArgument<HistorySource>(3).records().toList()
            if (uploads.size == 1) throw IllegalStateException("Connection lost after a chunk")
            Unit
        }.`when`(transport).initialize(anyString(), anyString(), anyString(), anySource(), anyString())
        assertTrue(runCatching { coordinator().createFamily("member") }.isFailure)
        assertEquals("CREATE", journal()?.phase)
        assertTrue(requireNotNull(db.restoreManifest()).size < 64 * 1024)
        assertTrue(runCatching { store.append(AccountCreated(Account("blocked", "Blocked", Currency.RSD))) }.isFailure)
        val stagedId = requireNotNull(db.restoreJournalQueries.journalHistory().executeAsOne().history_id)
        store = EventStore(db, install, FixedClock(Instant.parse("2026-11-04T10:00:00Z"), ZoneId.of("UTC")), Projector(db))
        store.discardHistory(store.beginHistory())
        assertEquals(stagedId, store.history(stagedId).id)
        coordinator().createFamily("member")
        assertNull(journal())
        assertEquals(2, uploads.size)
        assertEquals(uploads[0], uploads[1])
        assertTrue(uploads[0].filterIsInstance<HistoryRecord.Baseline>().any { (it.event.event as? AccountCreated)?.account?.id == "account" })
        assertEquals(1, accounts())
    }

    @Test fun `create journal written before staging converts its inline history once`() = runTest {
        db.restoreJournalQueries.save("user", "family", "", "dataset", "token", "CREATE", legacyEnrollment(store.snapshot()))
        var attempts = 0
        doAnswer { if (++attempts < 3) throw IllegalStateException("Connection lost"); Unit }
            .`when`(transport).initialize(anyString(), anyString(), anyString(), anySource(), anyString())
        assertTrue(runCatching { coordinator().createFamily("member") }.isFailure)
        val converted = requireNotNull(db.restoreManifest())
        assertFalse(converted.toString(Charsets.UTF_8).contains("\"history\""))
        assertTrue(runCatching { coordinator().createFamily("member") }.isFailure)
        assertArrayEquals(converted, db.restoreManifest())
        coordinator().createFamily("member")
        assertNull(journal())
        assertEquals("dataset", db.syncSessionQueries.current().executeAsOne().dataset_id)
        assertEquals(1, accounts())
    }

    @Test fun `replacement journal written before staging still activates with its old digest`() = runTest {
        enroll()
        db.restoreJournalQueries.save("user", "family", "", "dataset", "1", "REPLACE", legacyEnrollment(targetHistory()))
        coordinator().confirmReplacement()
        assertNull(journal())
        assertEquals(0, accounts())
        assertEquals(1, db.syncDraftQueries.draftInfo("user").executeAsList().size)
    }

    @Test fun `restore journal written before staging resumes after the cloud already switched`() = runTest {
        enroll()
        val replacement = targetHistory()
        val legacy = """{"enrollment":${legacyEnrollment(replacement).toString(Charsets.UTF_8)},"sourceGeneration":1}"""
        db.restoreJournalQueries.save("user", "family", "dataset", "restored", "token", "RESTORE", legacy.toByteArray())
        family = family.copy(activeDataset = "restored", generation = 2)
        doReturn(source(replacement)).`when`(transport).downloadBaseline("user", "family", "restored", true)
        coordinator().synchronize()
        assertNull(journal())
        assertEquals("restored", db.syncSessionQueries.current().executeAsOne().dataset_id)
        assertEquals(0, accounts())
        verify(transport, never()).initialize(anyString(), anyString(), anyString(), anySource(), anyString())
    }

    @Test fun `cloud baseline differing from the restore journal is never activated`() = runTest {
        enroll()
        val backup = staged(targetHistory())
        doAnswer { call -> family = family.copy(activeDataset = call.getArgument(4), generation = 2); Unit }
            .`when`(transport).beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
        doThrow(IllegalStateException("Response lost after activation")).`when`(transport)
            .initialize(anyString(), anyString(), anyString(), anySource(), anyString())
        assertTrue(runCatching { coordinator().restoreFamily(backup, current()) }.isFailure)
        doReturn(source(store.snapshot())).`when`(transport).downloadBaseline("user", "family", family.activeDataset, true)
        assertTrue(runCatching { coordinator().synchronize() }.isFailure)
        assertEquals("RESTORE", journal()?.phase)
        assertEquals("dataset", db.syncSessionQueries.current().executeAsOne().dataset_id)
        assertEquals(1, accounts())
    }

    @Test fun `interrupted cloud download keeps local data and the pending replacement unchanged`() = runTest {
        enroll()
        doReturn(source(targetHistory())).`when`(transport).downloadBaseline("user", "family", "dataset", true)
        coordinator().stageRecovery()
        val pending = requireNotNull(db.restoreManifest())
        val before = store.snapshot()
        var pages = 0
        doAnswer {
            if (++pages == 2) throw IllegalStateException("Connection lost")
            CloudPage(emptyList(), CloudCursor(1, 0, "page"), true)
        }.`when`(transport).download(anyString(), anyString(), anyString(), nullable(CloudCursor::class.java), anyBoolean())
        assertTrue(runCatching { coordinator().stageRecovery() }.isFailure)
        assertArrayEquals(pending, db.restoreManifest())
        assertEquals(before, store.snapshot())
        assertEquals(1, accounts())
        doReturn(CloudPage(emptyList(), null, false)).`when`(transport)
            .download(anyString(), anyString(), anyString(), nullable(CloudCursor::class.java), anyBoolean())
        coordinator().confirmReplacement()
        assertEquals(0, accounts())
    }

    private fun accountIds() = db.accountQueries.active().executeAsList().map { it.id }.toSet()
    private fun pendingCount() = db.syncOutboxQueries.pending("family", "dataset").executeAsList().size
    private fun drafts() = db.syncDraftQueries.draftInfo("user").executeAsList().map { records(requireNotNull(store.draftHistory("user", it.id))) }
    private fun holdsPending(records: List<HistoryRecord>) = records.filterIsInstance<HistoryRecord.Operation>().any { record ->
        OperationCodec.decode(record.bytes()).events.any { (it.event as? AccountCreated)?.account?.id == "pending" }
    }

    /** A restore journal whose cloud restore never began, with one local edit still waiting for upload. */
    private suspend fun interruptedRestore(): FamilyHistory {
        enroll()
        val backup = targetHistory()
        store.append(AccountCreated(Account("pending", "Pending", Currency.RSD)))
        doThrow(IllegalStateException("Connection lost")).`when`(transport)
            .beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
        assertTrue(runCatching { coordinator().restoreFamily(staged(backup), current()) }.isFailure)
        assertEquals("RESTORE", journal()?.phase)
        return backup
    }

    @Test fun `admin demoted before cloud activation keeps local edits and the backup`() = runTest {
        val backup = interruptedRestore()
        family = family.copy(admins = listOf("owner"))
        val sync = coordinator()
        assertTrue(runCatching { sync.resumeRestore() }.exceptionOrNull() is RestoreSuperseded)
        assertNull(journal())
        assertEquals(listOf(backup.source().records().toList()), drafts())
        assertEquals(setOf("account", "pending"), accountIds())
        assertEquals("dataset", db.syncSessionQueries.current().executeAsOne().dataset_id)
        assertEquals(SyncPhase.READY, sync.status.value.phase)
        verify(transport, times(1)).beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
        verify(transport, never()).initialize(anyString(), anyString(), anyString(), anySource(), anyString())
        verify(transport, never()).cancelExpiredRestore(anyString(), anyString())
        store.append(AccountCreated(Account("later", "Later", Currency.RSD)))
        assertEquals(setOf("account", "pending", "later"), accountIds())
    }

    @Test fun `admin demoted after lost cloud activation still adopts the restored dataset`() = runTest {
        enroll()
        val backup = targetHistory()
        store.append(AccountCreated(Account("pending", "Pending", Currency.RSD)))
        doAnswer { call -> family = family.copy(activeDataset = call.getArgument(4), generation = 2, otherDataset = "dataset"); Unit }
            .`when`(transport).beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
        doThrow(IllegalStateException("Response lost after activation")).`when`(transport)
            .initialize(anyString(), anyString(), anyString(), anySource(), anyString())
        assertTrue(runCatching { coordinator().restoreFamily(staged(backup), current()) }.isFailure)
        family = family.copy(admins = listOf("owner"))
        doReturn(source(backup)).`when`(transport).downloadBaseline("user", "family", family.activeDataset, true)
        coordinator().synchronize()
        assertNull(journal())
        assertEquals(family.activeDataset, db.syncSessionQueries.current().executeAsOne().dataset_id)
        assertTrue(accountIds().isEmpty())
        assertTrue(holdsPending(drafts().single()))
        verify(transport, times(1)).beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
        verify(transport, never()).cleanup(anyString(), anyString(), anyInt())
    }

    @Test fun `restore superseded by a third dataset keeps both histories and offers replacement`() = runTest {
        val backup = interruptedRestore()
        family = family.copy(activeDataset = "third", generation = 2, otherDataset = "dataset")
        val sync = coordinator()
        sync.synchronize()
        assertNull(journal())
        assertEquals(SyncPhase.REPLACEMENT_REQUIRED, sync.status.value.phase)
        assertEquals(listOf(backup.source().records().toList()), drafts())
        assertEquals(setOf("account", "pending"), accountIds())
        assertEquals(1, pendingCount())
        verify(transport, never()).upload(anyString(), anyString(), anyString(), anyString(), anyBytes())
        verify(transport, times(1)).beginRestore(anyString(), anyString(), anyString(), anyLong(), anyString(), anyString())
        doReturn(source(backup)).`when`(transport).downloadBaseline("user", "family", "third", true)
        sync.stageRecovery()
        sync.confirmReplacement()
        assertEquals("third", db.syncSessionQueries.current().executeAsOne().dataset_id)
        val retained = drafts()
        assertEquals(2, retained.size)
        assertTrue(backup.source().records().toList() in retained)
        assertTrue(retained.any(::holdsPending))
    }

    @Test fun `cancelling an obsolete restore never cancels another admin restore`() = runTest {
        val backup = interruptedRestore()
        family = family.copy(status = "RESTORING", otherDataset = "theirs", generation = 2)
        val sync = coordinator()
        sync.cancelRestore("family")
        verify(transport, never()).cancelExpiredRestore(anyString(), anyString())
        assertNull(journal())
        assertEquals(listOf(backup.source().records().toList()), drafts())
        assertEquals(setOf("account", "pending"), accountIds())
        assertEquals(1, pendingCount())
        assertEquals(SyncPhase.REPLACEMENT_REQUIRED, sync.status.value.phase)
    }

    @Test fun `rejoined member identity waits for replacement keeping pending edits`() = runTest {
        enroll()
        val cloud = targetHistory()
        store.append(AccountCreated(Account("pending", "Pending", Currency.RSD)))
        membership = FamilyMembership("family", "rejoined", false)
        family = family.copy(members = mapOf("user" to "rejoined"))
        val sync = coordinator()
        sync.synchronize()
        assertEquals(SyncPhase.REPLACEMENT_REQUIRED, sync.status.value.phase)
        assertEquals(1, pendingCount())
        verify(transport, never()).download(anyString(), anyString(), anyString(), nullable(CloudCursor::class.java), anyBoolean())
        verify(transport, never()).upload(anyString(), anyString(), anyString(), anyString(), anyBytes())
        doReturn(source(cloud)).`when`(transport).downloadBaseline("user", "family", "dataset", true)
        sync.stageRecovery()
        sync.confirmReplacement()
        assertEquals("rejoined", db.syncSessionQueries.current().executeAsOne().member_id)
        assertTrue(holdsPending(drafts().single()))
        assertEquals(SyncPhase.READY, sync.status.value.phase)
    }
}
