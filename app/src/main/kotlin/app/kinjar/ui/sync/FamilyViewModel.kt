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
)

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
    private var opened: OpenBackup? = null
    private var restoreDigest: String? = null

    init {
        refresh()
        viewModelScope.launch { sync.status.collect { withContext(Dispatchers.IO) { loadLocal() } } }
    }

    fun refresh() = run { load() }
    fun signIn(activity: Activity) = run { auth.signIn(activity); load() }
    fun signOut(activity: Activity) = run { auth.signOut(activity); load() }
    fun synchronize() = run { sync.synchronize(reconcile = true); load() }
    fun create() = run {
        val account = requireNotNull(auth.current())
        val member = repository.currentMember() ?: Member(newId(), account.displayName.ifBlank { account.email.substringBefore('@') }, account.uid)
            .also { store.append(MemberCreated(it)) }
        sync.createFamily(member.id)
        load()
    }
    fun stage(invitation: FamilyInvitation? = null) = run { sync.stageRecovery(invitation); load() }
    fun confirmReplacement() = run { sync.confirmReplacement(); load() }
    fun cancelReplacement() = run { sync.cancelReplacement(); loadLocal() }
    fun abandonRecovery() = run { sync.abandonRecovery(); loadLocal() }
    fun invite(email: String) = run { families.invite(requireFamily(), email); load() }
    fun cancelInvitations() = run { families.cancelInvitations(requireFamily()); load() }
    fun administrator(uid: String, value: Boolean) = run { families.setAdministrator(requireFamily(), uid, value); load() }
    fun removeMember(uid: String) = run { families.remove(requireFamily(), uid); load() }
    fun transfer(uid: String) = run { families.transferOwnership(requireFamily(), uid); load() }
    fun removeDevice(slot: String) = run { families.removeDevice(requireFamily(), slot); load() }
    fun deleteFamily() = run { sync.deleteFamily(requireFamily()); mutableState.update { it.copy(family = null) }; load() }
    fun resumeRestore() = run { sync.resumeRestore(); load() }
    fun cancelRestore() = run { sync.cancelRestore(requireFamily()); load() }
    fun cleanup() = run { sync.cleanupFamily(requireFamily()); load() }
    fun discard(id: String) = run { store.discardConflictingChange(id); loadLocal(); sync.synchronize(); load() }

    fun prepareExport(password: CharArray, copy: String?) {
        exportPassword?.fill('\u0000')
        exportPassword = password
        exportCopy = copy
    }

    fun export(uri: Uri?) {
        val password = exportPassword
        exportPassword = null
        if (uri == null) { password?.fill('\u0000'); return }
        val copy = exportCopy
        run {
            try {
                requireNotNull(password) { "Enter the backup passphrase again" }
                val history = withContext(Dispatchers.IO) {
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

    fun inspectBackup(uri: Uri, password: CharArray) = run {
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
        if (store.read { db.syncSessionQueries.current().executeAsOneOrNull() } == null) withContext(Dispatchers.IO) { store.restoreLocal(backup.history, digest) }
        else sync.restoreFamily(backup.history, digest)
        cancelBackupRestore()
        load()
    }

    private fun requireFamily(): String = requireNotNull(mutableState.value.family).id
    private fun run(action: suspend () -> Unit) = writes.run(finish = false, action = action)

    private suspend fun load() {
        withContext(Dispatchers.IO) { loadLocal() }
        val account = auth.current()
        if (account == null) {
            mutableState.update { it.copy(account = null, family = null, invitations = emptyList(), devices = emptyList()) }
            return
        }
        mutableState.update { it.copy(account = account) }
        val membership = families.membership()
        val family = if (membership == null) null else if (membership.removed) {
            try { families.info(membership.familyId).takeIf { it.owner == account.uid && it.status == "DELETING" } }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
        } else families.info(membership.familyId)
        val invites = if (family == null) families.invitations() else emptyList()
        val devices = if (family?.status == "ACTIVE") families.devices(family.id) else emptyList()
        check(auth.current()?.uid == account.uid)
        mutableState.update { it.copy(account = account, family = family, invitations = invites, devices = devices) }
    }

    private fun loadLocal() = store.read {
        val operations = db.syncOperationQueries.all().executeAsList().associate { it.id to OperationCodec.decode(it.payload) }
        val conflicts = db.syncConflictQueries.all().executeAsList().mapNotNull { row -> operations[row.operation_id]?.let { ConflictItem(it, row.reason) } }
        val session = db.syncSessionQueries.current().executeAsOneOrNull()
        val members = db.memberQueries.all().executeAsList()
        val uid = auth.current()?.uid ?: "local"
        val copies = db.syncDraftQueries.all(uid).executeAsList().map { RetainedCopy(it.id, Instant.ofEpochMilli(it.created_at)) }
        mutableState.update { it.copy(names = members.associate { m -> m.id to m.display_name }, conflicts = conflicts, copies = copies,
            quarantined = db.syncQuarantineQueries.all().executeAsList().size,
            journalPhase = db.restoreJournalQueries.current().executeAsOneOrNull()?.phase,
            enrolled = session != null, hasLocalMember = members.isNotEmpty()) }
    }

    override fun onCleared() {
        exportPassword?.fill('\u0000')
        exportPassword = null
        opened = null
    }
}
