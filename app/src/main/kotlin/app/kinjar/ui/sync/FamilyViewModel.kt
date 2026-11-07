package app.kinjar.ui.sync

import android.app.Activity
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinjar.data.BudgetRepository
import app.kinjar.data.EventStore
import app.kinjar.data.auth.FirebaseAuthRepository
import app.kinjar.data.auth.GoogleAccount
import app.kinjar.data.backup.BackupFiles
import app.kinjar.data.backup.OpenBackup
import app.kinjar.data.db.KinjarDb
import app.kinjar.data.sync.*
import app.kinjar.domain.*
import app.kinjar.ui.WriteOperation
import app.kinjar.R
import kotlinx.coroutines.CancellationException
import com.google.firebase.firestore.FirebaseFirestoreException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

data class ConflictItem(val operation: SyncOperation, val reason: String)
data class RetainedCopy(val id: String, val createdAt: Instant)
data class FamilyScreenState(
    val account: GoogleAccount? = null,
    val family: FamilyInfo? = null,
    val invitations: List<FamilyInvitation> = emptyList(),
    val devices: List<FamilyDevice> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    val conflicts: List<ConflictItem> = emptyList(),
    val copies: List<RetainedCopy> = emptyList(),
    val journalPhase: String? = null,
    val enrolled: Boolean = false,
    val hasLocalMember: Boolean = false,
    val backupDate: Instant? = null,
    val savedBackup: Boolean = false,
    val quarantined: Int = 0,
    val notice: Int? = null,
    val failureText: Int = R.string.fbs_action_failed,
    val completedEnrollment: Boolean = false,
    val recoveryOtherAccount: Boolean = false,
    val needsCloudSetup: Boolean = false,
    val sessionMatchesAccount: Boolean = false,
    val sessionMatchesCloud: Boolean = false,
    val archives: List<FamilyInfo> = emptyList(),
    val archivesLoaded: Boolean = false,
) {
    val canRestore get() = !recoveryOtherAccount && journalPhase != "SWITCH" && (!enrolled || (family?.status == "ACTIVE" && account?.uid in family.admins))
}

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val auth: FirebaseAuthRepository,
    private val families: FamilyRepository,
    private val sync: SyncCoordinator,
    private val backups: BackupFiles,
    private val db: KinjarDb,
    private val store: EventStore,
    private val repository: BudgetRepository,
) : ViewModel() {
    val writes = WriteOperation(viewModelScope)
    val status = sync.status
    val configured get() = auth.configured
    private val mutableState = MutableStateFlow(FamilyScreenState())
    val state = mutableState.asStateFlow()
    private var exportPassword: CharArray? = null
    private var exportCopy: String? = null
    private var exportArchive: String? = null
    private var opened: OpenBackup? = null
    private var restoreDigest: String? = null

    init {
        refresh()
        viewModelScope.launch {
            var previousPhase: SyncPhase? = null
            sync.status.collect { status ->
                val wasRestoring = state.value.journalPhase == "RESTORE"
                withContext(Dispatchers.IO) { loadLocal() }
                if (wasRestoring && state.value.journalPhase == null && status.phase in setOf(SyncPhase.READY, SyncPhase.CLEANUP_REQUIRED)) {
                    cancelBackupRestore()
                    writes.clearFailure()
                    mutableState.update { it.copy(notice = R.string.fbs_restored) }
                }
                if (status.phase != previousPhase && status.phase in setOf(SyncPhase.REPLACEMENT_REQUIRED, SyncPhase.RESTORING, SyncPhase.REMOVED)) {
                    try { load() } catch (e: Exception) { if (e is CancellationException) throw e }
                }
                previousPhase = status.phase
            }
        }
        viewModelScope.launch {
            sync.observedFamily.collect { family ->
                if (family != null && auth.current()?.uid in family.members && state.value.family != family) {
                    try { load() } catch (e: Exception) { if (e is CancellationException) throw e }
                }
            }
        }
    }

    fun refresh() = run { load() }
    fun signIn(activity: Activity) = run { auth.signIn(activity); finishSignIn() }
    fun continueSignIn() = run { finishSignIn() }
    private suspend fun finishSignIn() {
        load()
        val state = state.value
        if (state.recoveryOtherAccount || state.journalPhase != null) return
        if (state.family == null && !state.enrolled) {
            if (state.hasLocalMember) createFamily()
            else mutableState.update { it.copy(needsCloudSetup = true) }
        } else if (state.family?.status == "ACTIVE" && !state.hasLocalMember) {
            sync.stageRecovery()
            sync.confirmReplacement()
            load()
            mutableState.update { it.copy(completedEnrollment = true) }
        } else if (state.sessionMatchesCloud && state.family?.status == "ACTIVE") {
            mutableState.update { it.copy(completedEnrollment = true) }
        }
    }
    fun signOut(activity: Activity) = run { auth.signOut(activity); load() }
    fun synchronize() = run { sync.synchronize(reconcile = true); load() }
    fun create() = run { createFamily() }
    fun createWithAccounts(accounts: List<Account>) = run { createFamily(accounts) }
    private suspend fun createFamily(accounts: List<Account>? = null) {
        val account = requireNotNull(auth.current())
        val membership = families.membership()
        check(auth.current()?.uid == account.uid) { "Google account changed" }
        require(membership == null || membership.removed || state.value.journalPhase == "CREATE") { "Cloud data already exists" }
        if (repository.currentMember() == null) {
            repository.setUp(account.displayName.ifBlank { account.email.substringBefore('@') }, accounts.orEmpty())
        }
        val member = requireNotNull(repository.currentMember())
        check(auth.current()?.uid == account.uid) { "Google account changed" }
        sync.createFamily(member.id)
        load()
        mutableState.update { it.copy(completedEnrollment = true, needsCloudSetup = false) }
    }
    fun stage(invitation: FamilyInvitation? = null) = run {
        if (invitation != null && (state.value.family?.isPersonal(auth.current()?.uid) == true || state.value.journalPhase == "SWITCH")) {
            sync.joinFromPersonal(invitation)
        } else {
            sync.stageRecovery(invitation)
            sync.confirmReplacement()
        }
        load()
        mutableState.update { it.copy(completedEnrollment = true) }
    }
    fun confirmReplacement() = run { sync.confirmReplacement(); load(); mutableState.update { it.copy(completedEnrollment = true) } }
    fun cancelReplacement() = run { sync.cancelReplacement(); loadLocal() }
    fun abandonRecovery() = run { sync.abandonRecovery(); loadLocal() }
    fun invite(email: String) = run { families.invite(requireFamily(), email); load(); mutableState.update { it.copy(notice = R.string.fbs_invitation_sent) } }
    fun cancelInvitations() = run { families.cancelInvitations(requireFamily()); load() }
    fun administrator(uid: String, value: Boolean) = run { families.setAdministrator(requireFamily(), uid, value); load() }
    fun removeMember(uid: String) = run { families.remove(requireFamily(), uid); load() }
    fun transfer(uid: String) = run { families.transferOwnership(requireFamily(), uid); load() }
    fun removeDevice(slot: String) = run { families.removeDevice(requireFamily(), slot); load() }
    fun deleteFamily() = run { sync.deleteFamily(requireFamily()); mutableState.update { it.copy(family = null) }; load() }
    fun resumeRestore() = run { sync.resumeRestore(); cancelBackupRestore(); load(); mutableState.update { it.copy(notice = R.string.fbs_restored) } }
    fun cancelRestore() = run { sync.cancelRestore(requireFamily()); cancelBackupRestore(); load(); mutableState.update { it.copy(notice = R.string.fbs_restore_cancelled) } }
    fun cleanup() = run { sync.cleanupFamily(requireFamily()); load() }
    fun discard(id: String) = run { store.discardConflictingChange(id); loadLocal(); sync.synchronize(); load() }

    fun loadArchives() = run {
        val uid = requireNotNull(auth.current()).uid
        val archives = families.archived()
        check(auth.current()?.uid == uid) { "Google account changed" }
        mutableState.update { it.copy(archives = archives, archivesLoaded = true) }
    }

    fun prepareExport(password: CharArray, copy: String?, archive: String? = null) {
        clearBackupFeedback()
        exportPassword?.fill('\u0000')
        exportPassword = password
        exportCopy = copy
        exportArchive = archive
    }

    fun export(uri: Uri?) {
        val password = exportPassword
        exportPassword = null
        if (uri == null) { password?.fill('\u0000'); return }
        val copy = exportCopy
        val archive = exportArchive
        run {
            try {
                requireNotNull(password) { "Enter the backup passphrase again" }
                val history = if (archive != null) sync.archivedHistory(archive) else withContext(Dispatchers.IO) {
                    if (copy == null) store.snapshot() else store.read {
                        val uid = auth.current()?.uid ?: "local"
                        HistoryCodec.decode(db.syncDraftQueries.all(uid).executeAsList().single { it.id == copy }.payload)
                    }
                }
                backups.write(uri, history, password)
                mutableState.update { it.copy(savedBackup = true) }
            } finally { password?.fill('\u0000') }
        }
    }

    fun inspectBackup(uri: Uri, password: CharArray) = run(R.string.fbs_backup_open_failed) {
        try {
            val backup = backups.read(uri, password)
            withContext(Dispatchers.IO) { store.validateHistory(backup.history) }
            opened = backup
            restoreDigest = withContext(Dispatchers.IO) { HistoryCodec.digest(HistoryCodec.encode(store.snapshot())) }
            mutableState.update { it.copy(backupDate = backup.createdAt) }
        } finally { password.fill('\u0000') }
    }

    fun cancelBackupRestore() { opened = null; restoreDigest = null; mutableState.update { it.copy(backupDate = null) } }
    fun confirmBackupRestore() = run {
        val backup = requireNotNull(opened)
        val digest = requireNotNull(restoreDigest)
        try {
            if (store.read { db.syncSessionQueries.current().executeAsOneOrNull() } == null) withContext(Dispatchers.IO) { store.restoreLocal(backup.history, digest) }
            else if (state.value.journalPhase == "RESTORE") sync.resumeRestore()
            else sync.restoreFamily(backup.history, digest)
        } catch (e: LocalHistoryChanged) {
            restoreDigest = HistoryCodec.digest(HistoryCodec.encode(store.snapshot()))
            throw e
        }
        cancelBackupRestore()
        load()
        mutableState.update { it.copy(notice = R.string.fbs_restored) }
    }

    fun clearBackupFeedback() {
        writes.clearFailure()
        mutableState.update { it.copy(savedBackup = false, notice = null) }
    }

    private fun requireFamily(): String = requireNotNull(mutableState.value.family).id
    private fun run(fallback: Int = R.string.fbs_action_failed, action: suspend () -> Unit) = writes.run(finish = false) {
        mutableState.update { it.copy(notice = null, savedBackup = false, failureText = fallback) }
        try { action() }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            val message = when (e) {
                is LocalHistoryChanged -> R.string.fbs_local_changed
                is SyncCapacityReached -> R.string.fbs_capacity
                is UnsupportedSyncVersion -> R.string.fbs_update
                is FirebaseFirestoreException -> when (e.code) {
                    FirebaseFirestoreException.Code.PERMISSION_DENIED -> R.string.fbs_permission_failed
                    FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> R.string.fbs_connection_failed
                    else -> fallback
                }
                is java.io.IOException -> R.string.fbs_file_failed
                else -> fallback
            }
            mutableState.update { it.copy(failureText = message) }
            withContext(Dispatchers.IO) { loadLocal() }
            throw e
        }
    }

    private suspend fun load() {
        withContext(Dispatchers.IO) { loadLocal() }
        val account = auth.current()
        if (account == null) {
            mutableState.update { it.copy(account = null, family = null, invitations = emptyList(), devices = emptyList(), archives = emptyList(), archivesLoaded = false) }
            return
        }
        mutableState.update { it.copy(account = account,
            archives = if (it.account?.uid == account.uid) it.archives else emptyList(),
            archivesLoaded = it.account?.uid == account.uid && it.archivesLoaded) }
        val membership = families.membership()
        val family = if (membership == null) null else if (membership.removed) {
            try { families.info(membership.familyId).takeIf { it.owner == account.uid && it.status == "DELETING" } }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
        } else families.info(membership.familyId)
        val invites = if (family == null || family.isPersonal(account.uid)) families.invitations() else emptyList()
        val devices = if (family?.status == "ACTIVE") families.devices(family.id) else emptyList()
        check(auth.current()?.uid == account.uid)
        val matchesCloud = withContext(Dispatchers.IO) { store.read {
            db.syncSessionQueries.current().executeAsOneOrNull()?.let {
                family != null && it.uid == account.uid && it.family_id == family.id && it.dataset_id == family.activeDataset && it.generation == family.generation
            } == true
        } }
        mutableState.update { it.copy(account = account, family = family, invitations = invites, devices = devices, sessionMatchesCloud = matchesCloud) }
    }

    private fun loadLocal() = store.read {
        val operations = db.syncOperationQueries.all().executeAsList().associate { it.id to OperationCodec.decode(it.payload) }
        val conflicts = db.syncConflictQueries.all().executeAsList().mapNotNull { row -> operations[row.operation_id]?.let { ConflictItem(it, row.reason) } }
        val session = db.syncSessionQueries.current().executeAsOneOrNull()
        val members = db.memberQueries.all().executeAsList()
        val uid = auth.current()?.uid ?: "local"
        val copies = db.syncDraftQueries.all(uid).executeAsList().map { RetainedCopy(it.id, Instant.ofEpochMilli(it.created_at)) }
        val journal = db.restoreJournalQueries.current().executeAsOneOrNull()
        mutableState.update { it.copy(names = members.associate { m -> m.id to m.display_name }, conflicts = conflicts, copies = copies,
            quarantined = db.syncQuarantineQueries.all().executeAsList().size,
            journalPhase = journal?.phase,
            recoveryOtherAccount = journal != null && journal.uid != uid,
            backupDate = if (journal?.phase == "RESTORE") null else it.backupDate,
            sessionMatchesAccount = session != null && session.uid == uid,
            enrolled = session != null, hasLocalMember = members.isNotEmpty()) }
    }

    override fun onCleared() {
        exportPassword?.fill('\u0000')
        exportPassword = null
        opened = null
    }
}
