package app.kinjar.data.sync

import android.content.Context
import app.kinjar.data.EventStore
import app.kinjar.data.auth.FirebaseServices
import app.kinjar.data.db.KinjarDb
import app.kinjar.data.deviceId
import app.kinjar.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncPhase { LOCAL, SIGNED_OUT, READY, RUNNING, REPLACEMENT_REQUIRED, REMOVED, CAPACITY, ERROR }
data class SyncStatus(val phase: SyncPhase = SyncPhase.LOCAL, val pending: Int = 0, val lastSuccess: Instant? = null)

@Serializable
private data class Enrollment(val memberId: String, val localDigest: String, val history: FamilyHistory)

@Singleton
class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val services: FirebaseServices,
    private val families: FamilyRepository,
    private val transport: FirestoreTransport,
    private val db: KinjarDb,
    private val store: EventStore,
) {
    private val install = deviceId(context)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(SyncStatus())
    val status = mutableStatus.asStateFlow()
    val configured get() = services.configured
    private fun user(): String = requireNotNull(services.auth.currentUser?.takeIf { it.isEmailVerified }?.uid)
    private fun checkUser(uid: String) { check(user() == uid) { "Google account changed" } }

    suspend fun foreground() {
        if (!configured) return
        callbackFlow {
            val listener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
            val sessions = db.syncSessionQueries.current()
            val changed = object : app.cash.sqldelight.Query.Listener {
                override fun queryResultsChanged() { trySend(services.auth.currentUser?.uid) }
            }
            sessions.addListener(changed)
            services.auth.addAuthStateListener(listener)
            awaitClose { services.auth.removeAuthStateListener(listener); sessions.removeListener(changed) }
        }.conflate().collectLatest { uid ->
            if (uid == null) return@collectLatest
            callbackFlow {
                val listeners = mutableListOf<com.google.firebase.firestore.ListenerRegistration>()
                listeners += services.firestore.collection("users").document(uid).addSnapshotListener { _, _ -> trySend(Unit) }
                val session = store.read { db.syncSessionQueries.current().executeAsOneOrNull() }
                if (session?.uid == uid) {
                    val root = services.firestore.collection("families").document(session.family_id)
                    listeners += root.addSnapshotListener { _, _ -> trySend(Unit) }
                    listeners += root.collection("datasets").document(session.dataset_id).addSnapshotListener { _, _ -> trySend(Unit) }
                }
                val changed = object : app.cash.sqldelight.Query.Listener { override fun queryResultsChanged() { trySend(Unit) } }
                val pending = db.syncOutboxQueries.pending(session?.family_id ?: "", session?.dataset_id ?: "")
                pending.addListener(changed)
                trySend(Unit)
                awaitClose { listeners.forEach { it.remove() }; pending.removeListener(changed) }
            }.conflate().collect {
                try { synchronize() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            }
        }
    }

    suspend fun createFamily(memberId: String) = action {
        val uid = user()
        var journal = store.read { db.restoreJournalQueries.current().executeAsOneOrNull() }
        if (journal == null) {
            val history = store.snapshot()
            store.validateHistory(history)
            val enrollment = Enrollment(memberId, HistoryCodec.digest(HistoryCodec.encode(history)), history)
            store.read {
                require(HistoryCodec.digest(HistoryCodec.encode(store.snapshot())) == enrollment.localDigest) { "Local data changed" }
                db.restoreJournalQueries.save(uid, newId(), "", newId(), newId(), "CREATE", Json.encodeToString(enrollment).toByteArray())
            }
            journal = store.read { db.restoreJournalQueries.current().executeAsOne() }
        }
        require(journal.uid == uid && journal.phase == "CREATE") { "Another recovery is pending" }
        val enrollment = Json.decodeFromString<Enrollment>(journal.manifest.toString(Charsets.UTF_8))
        families.create(enrollment.memberId, journal.family_id, journal.target_dataset)
        checkUser(uid)
        transport.initialize(uid, journal.family_id, journal.target_dataset, enrollment.history)
        val family = families.info(journal.family_id)
        checkUser(uid)
        store.activate(uid, family.id, family.activeDataset, enrollment.memberId, family.generation,
            enrollment.history, enrollment.localDigest, preserveDraft = false)
        syncLocked(uid)
    }

    suspend fun stageRecovery(invitation: FamilyInvitation? = null) = action {
        val uid = user()
        val localDigest = store.read { HistoryCodec.digest(HistoryCodec.encode(store.snapshot())) }
        val membership = families.membership()
        checkUser(uid)
        val family = if (membership == null || membership.removed) {
            requireNotNull(invitation) { "No family membership" }
            families.accept(invitation, newId())
        } else families.info(membership.familyId)
        require(family.status == "ACTIVE") { "Family is not ready" }
        val member = requireNotNull(family.members[uid])
        val baseline = transport.downloadBaseline(uid, family.id, family.activeDataset)
        val operations = baseline.operations.toMutableList()
        val ids = baseline.decodedOperations().associateBy { it.id }.toMutableMap()
        var cursor: CloudCursor? = null
        do {
            val page = transport.download(uid, family.id, family.activeDataset, cursor)
            page.operations.forEach { bytes ->
                val op = OperationCodec.decode(bytes)
                require(op.id !in ids) { "Cloud operation repeats bootstrap identity" }
                ids[op.id] = op
                operations += Base64.getEncoder().encodeToString(bytes)
            }
            cursor = page.cursor
        } while (page.hasMore)
        checkUser(uid)
        val latest = families.info(family.id)
        require(latest.activeDataset == family.activeDataset && latest.generation == family.generation && latest.status == "ACTIVE") { "Family changed during download" }
        val history = baseline.copy(operations = operations)
        HistoryCodec.encode(history)
        store.validateHistory(history)
        store.read {
            db.restoreJournalQueries.save(uid, family.id, "", family.activeDataset, family.generation.toString(), "REPLACE",
                Json.encodeToString(Enrollment(member, localDigest, history)).toByteArray())
        }
        mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.REPLACEMENT_REQUIRED)
    }

    suspend fun confirmReplacement() = action {
        val uid = user()
        val journal = store.read { requireNotNull(db.restoreJournalQueries.current().executeAsOneOrNull()) }
        require(journal.uid == uid && journal.phase == "REPLACE")
        val family = families.info(journal.family_id)
        checkUser(uid)
        require(family.status == "ACTIVE" && family.activeDataset == journal.target_dataset && family.generation.toString() == journal.token)
        val enrollment = Json.decodeFromString<Enrollment>(journal.manifest.toString(Charsets.UTF_8))
        require(family.members[uid] == enrollment.memberId)
        store.activate(uid, family.id, family.activeDataset, enrollment.memberId, family.generation,
            enrollment.history, enrollment.localDigest, preserveDraft = true)
        if (store.read { db.memberQueries.all().executeAsList().none { it.id == enrollment.memberId } }) {
            store.append(MemberCreated(Member(enrollment.memberId, services.auth.currentUser?.displayName?.take(80) ?: "Member", install)))
        }
        syncLocked(uid)
    }

    fun cancelReplacement() = store.read {
        val journal = db.restoreJournalQueries.current().executeAsOneOrNull()
        if (journal?.phase == "REPLACE") db.restoreJournalQueries.deleteAll()
    }

    suspend fun synchronize() = action {
        if (!configured) { mutableStatus.value = SyncStatus(); return@action }
        if (services.auth.currentUser == null) {
            mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.SIGNED_OUT)
            return@action
        }
        syncLocked(user())
    }

    private suspend fun syncLocked(uid: String) {
        val session = store.read { db.syncSessionQueries.current().executeAsOneOrNull() }
        if (session == null || session.uid != uid || session.install_id != install) {
            mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.REPLACEMENT_REQUIRED)
            return
        }
        val membership = families.membership()
        checkUser(uid)
        if (membership?.familyId == session.family_id && membership.removed) {
            store.clearRemovedFamily(uid, session.family_id)
            mutableStatus.value = SyncStatus(SyncPhase.REMOVED)
            return
        }
        require(membership != null && membership.familyId == session.family_id && !membership.removed) { "Family membership changed" }
        val family = families.info(session.family_id)
        checkUser(uid)
        if (family.activeDataset != session.dataset_id || family.generation != session.generation || family.status != "ACTIVE") {
            mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.REPLACEMENT_REQUIRED)
            return
        }
        val slot = transport.register(uid, family.id, install)
        SyncWorker.schedule(context)
        val saved = store.read { db.syncCheckpointQueries.byDataset(session.dataset_id).executeAsOneOrNull() }
        var cursor = saved?.let { CloudCursor(it.seconds, it.nanos.toInt(), it.document_id) }
        var overlap = true
        do {
            val page = transport.download(uid, family.id, session.dataset_id, cursor, overlap)
            checkUser(uid)
            cursor = page.cursor
            cursor?.let { store.receive(page.operations, uid, family.id, session.dataset_id, it.seconds, it.nanos.toLong(), it.id) }
            overlap = false
        } while (page.hasMore)
        val pending = store.read { db.syncOutboxQueries.pending(family.id, session.dataset_id).executeAsList() }
        mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.RUNNING, pending = pending.size)
        pending.forEach { operation ->
            transport.upload(uid, family.id, session.dataset_id, slot, operation.payload)
            checkUser(uid)
            store.read { db.syncOutboxQueries.acknowledge(operation.id, family.id, session.dataset_id) }
        }
        mutableStatus.value = SyncStatus(SyncPhase.READY, lastSuccess = Instant.now())
    }

    private suspend fun action(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { block() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                mutableStatus.value = mutableStatus.value.copy(phase = if (e is SyncCapacityReached) SyncPhase.CAPACITY else SyncPhase.ERROR)
                throw e
            }
        }
    }
}
