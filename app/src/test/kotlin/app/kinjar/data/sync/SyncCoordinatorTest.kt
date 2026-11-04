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
        doReturn(FamilyMembership("family", "member", false)).`when`(families).membership()
        doAnswer { family }.`when`(families).info(anyString())
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
        coordinator().resumeRestore()
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        assertTrue(db.accountQueries.active().executeAsList().isEmpty())
        assertEquals(1, db.syncDraftQueries.all("user").executeAsList().size)
        assertEquals(family.activeDataset, db.syncSessionQueries.current().executeAsOne().dataset_id)
    }

    @Test fun `idle synchronization does not reserve device slots`() = runTest {
        enroll()
        coordinator().synchronize()
        verify(transport, never()).register(anyString(), anyString(), anyString())
        assertTrue(db.syncOutboxQueries.pending("family", "dataset").executeAsList().isEmpty())
    }

    @Test fun `manual reconciliation reads before saved cursor without duplicating data`() = runTest {
        enroll()
        db.syncCheckpointQueries.save("dataset", 100, 0, "old:1")
        val before = store.snapshot()
        coordinator().synchronize(reconcile = true)
        verify(transport).download("user", "family", "dataset", null, true)
        assertEquals(before, store.snapshot())
    }
}
