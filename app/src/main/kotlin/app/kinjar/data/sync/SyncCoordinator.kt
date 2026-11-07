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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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

enum class SyncPhase { LOCAL, SIGNED_OUT, READY, RUNNING, REPLACEMENT_REQUIRED, RESTORING, CLEANUP_REQUIRED, REMOVED, CAPACITY, UPDATE_REQUIRED, ERROR }
data class SyncStatus(val phase: SyncPhase = SyncPhase.LOCAL, val pending: Int = 0, val lastSuccess: Instant? = null)

@Serializable
private data class Enrollment(val memberId: String, val localDigest: String, val history: FamilyHistory)

@Serializable
private data class FamilyRestore(val enrollment: Enrollment, val sourceGeneration: Long)

@Serializable
private data class FamilySwitch(val sourceFamily: String, val slot: String, val memberId: String, val installId: String, val localDigest: String)

@Singleton
class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val services: FirebaseServices,
    private val families: FamilyRepository,
    private val transport: FirestoreTransport,
    private val db: KinjarDb,
    private val store: EventStore,
    private val scheduler: SyncScheduler,
) {
    private val install = deviceId(context)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(SyncStatus())
    val status = mutableStatus.asStateFlow()
    private val mutableObservedFamily = MutableStateFlow<FamilyInfo?>(null)
    /** Family metadata last confirmed by synchronization for this installation's session; null when none applies. */
    val observedFamily = mutableObservedFamily.asStateFlow()
    val configured get() = services.configured
    private fun user(): String = requireNotNull(services.auth.currentUser?.takeIf { it.isEmailVerified }?.uid)
    private fun checkUser(uid: String) { check(user() == uid) { "Google account changed" } }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
        }.conflate().map { uid ->
            val session = store.read { db.syncSessionQueries.current().executeAsOneOrNull() }
            uid to session?.let { Triple(it.uid, it.family_id, it.dataset_id) }
        }.distinctUntilChanged().flatMapLatest { (uid, _) ->
            if (uid == null) return@flatMapLatest flowOf(Unit)
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
            }
        }.conflate().flowOn(Dispatchers.IO).collect {
            try { synchronize() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
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
        val journal = store.read { db.restoreJournalQueries.current().executeAsOneOrNull() }
        require(journal == null || journal.uid == uid) { "Sign in to the account that started recovery" }
        require(journal == null || journal.phase == "REPLACE") { "Finish the pending restore first" }
        val localDigest = store.read { HistoryCodec.digest(HistoryCodec.encode(store.snapshot())) }
        val membership = families.membership()
        checkUser(uid)
        val family = if (membership == null || membership.removed) {
            requireNotNull(invitation) { "No family membership" }
            families.accept(invitation, newId())
        } else families.info(membership.familyId)
        require(family.status == "ACTIVE") { "Family is not ready" }
        val member = requireNotNull(family.members[uid])
        val history = downloadHistory(uid, family)
        store.read {
            db.restoreJournalQueries.save(uid, family.id, "", family.activeDataset, family.generation.toString(), "REPLACE",
                Json.encodeToString(Enrollment(member, localDigest, history)).toByteArray())
        }
        mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.REPLACEMENT_REQUIRED)
    }

    /**
     * Joins [invitation] from this installation's personal workspace. The workspace is archived, not deleted:
     * its cloud history stays readable to its owner through [archivedHistory], and the local copy is retained
     * for export. Local edits are frozen from the durable journal until the family copy is activated.
     */
    suspend fun joinFromPersonal(invitation: FamilyInvitation) = action {
        val uid = user()
        val pending = store.read { db.restoreJournalQueries.current().executeAsOneOrNull() }
        if (pending == null) {
            val session = store.read { db.syncSessionQueries.current().executeAsOneOrNull() }
            require(session != null && session.uid == uid && session.install_id == install && session.status == "ACTIVE") { "No personal workspace on this phone" }
            require(invitation.familyId != session.family_id) { "Already a member" }
            try { syncLocked(uid) } catch (_: SyncCapacityReached) { }
            val source = mutableObservedFamily.value
            require(source != null && source.id == session.family_id && source.isPersonal(uid) && source.status == "ACTIVE") { "Only a personal workspace can join a family" }
            store.read {
                require(db.restoreJournalQueries.current().executeAsOneOrNull() == null) { "Recovery already pending" }
                val current = db.syncSessionQueries.current().executeAsOneOrNull()
                require(current != null && current.uid == uid && current.family_id == source.id && current.install_id == install
                    && current.status == "ACTIVE") { "Family session changed" }
                val data = FamilySwitch(source.id, invitation.slot, newId(), install, HistoryCodec.digest(HistoryCodec.encode(store.snapshot())))
                db.restoreJournalQueries.save(uid, invitation.familyId, "", "", "", "SWITCH", Json.encodeToString(data).toByteArray())
            }
        } else require(pending.uid == uid && pending.phase == "SWITCH" && pending.family_id == invitation.familyId) { "Another recovery is pending" }
        switchLocked(uid)
    }

    private suspend fun switchLocked(uid: String) {
        val journal = store.read { requireNotNull(db.restoreJournalQueries.current().executeAsOneOrNull()) }
        require(journal.uid == uid && journal.phase == "SWITCH")
        val data = Json.decodeFromString<FamilySwitch>(journal.manifest.toString(Charsets.UTF_8))
        require(data.installId == install) { "Installation must recover its session" }
        if (store.read { HistoryCodec.digest(HistoryCodec.encode(store.snapshot())) } != data.localDigest) throw LocalHistoryChanged()
        mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.RUNNING)
        val membership = families.membership()
        checkUser(uid)
        val joined = membership != null && !membership.removed && membership.familyId == journal.family_id && membership.memberId == data.memberId
        if (!joined) try {
            if (membership == null || membership.removed || membership.familyId != data.sourceFamily) throw FamilySwitchRejected()
            families.joinFromPersonal(data.sourceFamily, journal.family_id, data.slot, data.memberId)
        } catch (e: Exception) {
            // Nothing was committed, so the personal workspace stays active and local editing resumes.
            if (e is FamilySwitchRejected || e is SyncCapacityReached) store.read {
                if (db.restoreJournalQueries.current().executeAsOneOrNull()?.phase == "SWITCH") db.restoreJournalQueries.deleteAll()
            }
            throw e
        }
        checkUser(uid)
        val family = families.info(journal.family_id)
        checkUser(uid)
        require(family.status == "ACTIVE" && family.members[uid] == data.memberId) { "Family is not ready" }
        val history = downloadHistory(uid, family)
        checkUser(uid)
        store.activate(uid, family.id, family.activeDataset, data.memberId, family.generation, history, data.localDigest, preserveDraft = true)
        syncAfterReplacement(uid)
    }

    /** Validated cloud history of an archived workspace owned by the signed-in account, for export only. */
    suspend fun archivedHistory(familyId: String): FamilyHistory = withContext(Dispatchers.IO) {
        val uid = user()
        val archive = families.info(familyId)
        checkUser(uid)
        require(archive.status == "ARCHIVED" && archive.owner == uid) { "Workspace is not an archive of this account" }
        downloadHistory(uid, archive).also { checkUser(uid) }
    }

    private suspend fun downloadHistory(uid: String, family: FamilyInfo): FamilyHistory {
        val baseline = transport.downloadBaseline(uid, family.id, family.activeDataset)
        val operations = baseline.operations.toMutableList()
        val quarantine = baseline.quarantine.toMutableList()
        val ids = baseline.operations.associateBy { OperationCodec.decode(Base64.getDecoder().decode(it)).id }.toMutableMap()
        var cursor: CloudCursor? = null
        do {
            val page = transport.download(uid, family.id, family.activeDataset, cursor)
            quarantine += page.quarantine
            page.operations.forEach { bytes ->
                val op = OperationCodec.decode(bytes)
                val encoded = Base64.getEncoder().encodeToString(bytes)
                val prior = ids[op.id]
                if (prior == null) { ids[op.id] = encoded; operations += encoded }
                else if (prior != encoded) quarantine += QuarantinedOperation.from(bytes, "IDENTITY_MISMATCH")
            }
            cursor = page.cursor
        } while (page.hasMore)
        checkUser(uid)
        val latest = families.info(family.id)
        require(latest.activeDataset == family.activeDataset && latest.generation == family.generation && latest.status == family.status) { "Family changed during download" }
        val history = store.prepareCloudHistory(baseline.copy(operations = operations, quarantine = quarantine))
        HistoryCodec.encode(history)
        store.validateHistory(history)
        return history
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
        syncAfterReplacement(uid)
    }

    fun cancelReplacement() = store.read {
        val journal = db.restoreJournalQueries.current().executeAsOneOrNull()
        if (journal?.phase == "REPLACE") db.restoreJournalQueries.deleteAll()
    }

    suspend fun abandonRecovery() = action {
        val journal = store.read { db.restoreJournalQueries.current().executeAsOneOrNull() }
        if (journal != null) store.abandonRecovery(journal.uid)
        mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.REPLACEMENT_REQUIRED)
    }

    suspend fun synchronize(reconcile: Boolean = false) = action {
        if (!configured) { mutableStatus.value = SyncStatus(); mutableObservedFamily.value = null; return@action }
        if (services.auth.currentUser == null) {
            mutableObservedFamily.value = null
            mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.SIGNED_OUT)
            return@action
        }
        syncLocked(user(), reconcile)
    }

    suspend fun restoreFamily(history: FamilyHistory, expectedLocalDigest: String) = action {
        val uid = user()
        store.validateHistory(history)
        require(HistoryCodec.encode(history).size <= FirestoreTransport.MAX_HISTORY_BYTES) { "Backup exceeds cloud capacity" }
        val session = store.read { requireNotNull(db.syncSessionQueries.current().executeAsOneOrNull()) }
        require(session.uid == uid)
        val family = families.info(session.family_id)
        require(uid in family.admins && family.status == "ACTIVE" && family.activeDataset == session.dataset_id && family.generation == session.generation)
        if (family.otherDataset.isNotEmpty()) transport.cleanup(uid, family.id)
        checkUser(uid)
        store.read {
            require(db.restoreJournalQueries.current().executeAsOneOrNull() == null) { "Recovery already pending" }
            if (HistoryCodec.digest(HistoryCodec.encode(store.snapshot())) != expectedLocalDigest) throw LocalHistoryChanged()
            val data = FamilyRestore(Enrollment(session.member_id, expectedLocalDigest, history), session.generation)
            db.restoreJournalQueries.save(uid, family.id, session.dataset_id, newId(), newId(), "RESTORE", Json.encodeToString(data).toByteArray())
        }
        restoreLocked(uid)
    }

    suspend fun resumeRestore() = action { restoreLocked(user()) }

    private suspend fun restoreLocked(uid: String) {
        val journal = store.read { requireNotNull(db.restoreJournalQueries.current().executeAsOneOrNull()) }
        require(journal.uid == uid && journal.phase == "RESTORE")
        val data = Json.decodeFromString<FamilyRestore>(journal.manifest.toString(Charsets.UTF_8))
        mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.RESTORING)
        val current = families.info(journal.family_id)
        require(uid in current.admins && current.members[uid] == data.enrollment.memberId)
        if (current.status != "ACTIVE" || current.activeDataset != journal.target_dataset) {
            transport.beginRestore(uid, journal.family_id, journal.source_dataset, data.sourceGeneration, journal.target_dataset, journal.token)
            transport.initialize(uid, journal.family_id, journal.target_dataset, data.enrollment.history, journal.token)
        } else {
            val restored = transport.downloadBaseline(uid, journal.family_id, journal.target_dataset)
            require(HistoryCodec.digest(HistoryCodec.encode(restored)) == HistoryCodec.digest(HistoryCodec.encode(data.enrollment.history)))
        }
        val family = families.info(journal.family_id)
        checkUser(uid)
        require(family.status == "ACTIVE" && family.activeDataset == journal.target_dataset)
        store.activate(uid, family.id, family.activeDataset, data.enrollment.memberId, family.generation,
            data.enrollment.history, data.enrollment.localDigest, preserveDraft = true)
        syncAfterReplacement(uid)
    }

    private suspend fun syncAfterReplacement(uid: String) {
        try { syncLocked(uid) }
        catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.ERROR)
        }
    }

    suspend fun cancelRestore(familyId: String) = action {
        val uid = user()
        val family = families.info(familyId)
        require(uid in family.admins)
        if (family.status == "RESTORING") transport.cancelExpiredRestore(uid, familyId)
        val journal = store.read { db.restoreJournalQueries.current().executeAsOneOrNull() }
        if (journal?.phase == "RESTORE" && journal.uid == uid && journal.family_id == familyId) {
            val current = families.info(familyId)
            require(current.status == "ACTIVE" && current.activeDataset == journal.source_dataset) { "Restore already activated; recover restored data" }
            store.read { db.restoreJournalQueries.deleteAll() }
        }
        syncLocked(uid)
        cleanupLocked(uid, familyId)
    }

    suspend fun cleanupFamily(familyId: String) = action { cleanupLocked(user(), familyId) }

    suspend fun deleteFamily(familyId: String) = action {
        val uid = user()
        families.beginDeletion(familyId)
        checkUser(uid)
        store.clearRemovedFamily(uid, familyId)
        transport.deleteFamily(uid, familyId)
        mutableObservedFamily.value = null
        mutableStatus.value = SyncStatus(SyncPhase.REMOVED)
    }

    private suspend fun cleanupLocked(uid: String, familyId: String) {
        mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.CLEANUP_REQUIRED)
        transport.cleanup(uid, familyId)
        mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.READY)
    }

    private suspend fun syncLocked(uid: String, reconcile: Boolean = false) {
        val switching = store.read { db.restoreJournalQueries.current().executeAsOneOrNull() }
        if (switching?.uid == uid && switching.phase == "SWITCH") {
            mutableObservedFamily.value = null
            switchLocked(uid)
            return
        }
        val session = store.read { db.syncSessionQueries.current().executeAsOneOrNull() }
        if (session == null || session.uid != uid || session.install_id != install) {
            mutableObservedFamily.value = null
            val membership = families.membership()
            checkUser(uid)
            mutableStatus.value = mutableStatus.value.copy(phase = if (membership == null || membership.removed) SyncPhase.LOCAL else SyncPhase.REPLACEMENT_REQUIRED)
            return
        }
        val membership = families.membership()
        checkUser(uid)
        if (membership?.familyId == session.family_id && membership.removed) {
            mutableObservedFamily.value = null
            store.clearRemovedFamily(uid, session.family_id)
            mutableStatus.value = SyncStatus(SyncPhase.REMOVED)
            return
        }
        if (membership != null && !membership.removed && membership.familyId != session.family_id) {
            // The account moved to another family from a different phone; this copy waits for explicit replacement.
            mutableObservedFamily.value = null
            mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.REPLACEMENT_REQUIRED)
            return
        }
        require(membership != null && membership.familyId == session.family_id && !membership.removed) { "Family membership changed" }
        val family = families.info(session.family_id)
        checkUser(uid)
        mutableObservedFamily.value = family
        val journal = store.read { db.restoreJournalQueries.current().executeAsOneOrNull() }
        if (journal?.uid == uid && journal.phase == "RESTORE") {
            restoreLocked(uid)
            return
        }
        if (family.activeDataset != session.dataset_id || family.status != "ACTIVE") {
            mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.REPLACEMENT_REQUIRED)
            return
        }
        if (family.generation != session.generation) store.read {
            db.syncSessionQueries.save(uid, family.id, session.dataset_id, session.member_id, install, family.generation, "ACTIVE")
        }
        scheduler.schedule()
        val saved = if (reconcile) null else store.read { db.syncCheckpointQueries.byDataset(session.dataset_id).executeAsOneOrNull() }
        var cursor = saved?.let { CloudCursor(it.seconds, it.nanos.toInt(), it.document_id) }
        var overlap = true
        do {
            val page = transport.download(uid, family.id, session.dataset_id, cursor, overlap)
            checkUser(uid)
            cursor = page.cursor
            cursor?.let { store.receive(page.operations, uid, family.id, session.dataset_id, it.seconds, it.nanos.toLong(), it.id, page.quarantine) }
            overlap = false
        } while (page.hasMore)
        require(family.members[uid] == session.member_id)
        if (store.read { db.memberQueries.all().executeAsList().none { it.id == session.member_id } }) {
            store.append(MemberCreated(Member(session.member_id, services.auth.currentUser?.displayName?.take(80) ?: "Member", uid)))
        }
        val pending = store.read { db.syncOutboxQueries.pending(family.id, session.dataset_id).executeAsList() }
        val slot = if (pending.isEmpty()) null else transport.register(uid, family.id, install)
        mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.RUNNING, pending = pending.size)
        pending.forEach { operation ->
            transport.upload(uid, family.id, session.dataset_id, requireNotNull(slot), operation.payload)
            checkUser(uid)
            store.read { db.syncOutboxQueries.acknowledge(operation.id, family.id, session.dataset_id) }
        }
        mutableStatus.value = SyncStatus(SyncPhase.READY, lastSuccess = Instant.now())
        if (family.otherDataset.isNotEmpty() && uid in family.admins) {
            try { cleanupLocked(uid, family.id) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                mutableStatus.value = mutableStatus.value.copy(phase = SyncPhase.CLEANUP_REQUIRED)
            }
        }
    }

    private suspend fun action(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { block() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                mutableStatus.value = mutableStatus.value.copy(phase = when (e) {
                    is SyncCapacityReached -> SyncPhase.CAPACITY
                    is UnsupportedSyncVersion -> SyncPhase.UPDATE_REQUIRED
                    else -> SyncPhase.ERROR
                })
                throw e
            }
        }
    }
}
