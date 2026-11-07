package app.kinjar.data.sync

import app.kinjar.data.auth.FirebaseServices
import app.kinjar.domain.newId
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class FamilyMembership(val familyId: String, val memberId: String, val removed: Boolean)
data class FamilyInfo(
    val id: String, val owner: String, val admins: List<String>, val members: Map<String, String>,
    val activeDataset: String, val generation: Long, val status: String,
    val otherDataset: String = "",
) {
    fun isPersonal(uid: String?): Boolean = uid != null && owner == uid && members.keys == setOf(uid)
}
data class FamilyInvitation(val familyId: String, val slot: String, val expiresAt: Instant, val inviterEmail: String)
data class FamilyDevice(val slot: String, val uid: String, val installId: String, val name: String)

/** The personal workspace was not archived and the invitation was not accepted; retrying the same switch cannot succeed. */
class FamilySwitchRejected : IllegalStateException("Invitation or personal workspace is no longer eligible")

@Singleton
class FamilyRepository @Inject constructor(private val services: FirebaseServices) {
    private val db get() = services.firestore
    private fun uid(): String = requireNotNull(services.auth.currentUser?.takeIf { it.isEmailVerified }?.uid)
    private fun family(id: String) = db.collection("families").document(id)

    suspend fun membership(): FamilyMembership? {
        val document = db.collection("users").document(uid()).get(Source.SERVER).await()
        if (!document.exists()) return null
        return FamilyMembership(requireNotNull(document.getString("familyId")), requireNotNull(document.getString("memberId")), document.getString("status") == "REMOVED")
    }

    suspend fun info(familyId: String): FamilyInfo = decode(family(familyId).get(Source.SERVER).await())

    suspend fun create(memberId: String, id: String, dataset: String): FamilyInfo {
        val user = uid()
        val created = db.runTransaction { transaction ->
            check(uid() == user)
            val userRef = db.collection("users").document(user)
            val existing = transaction.get(userRef)
            if (existing.getString("familyId") == id && existing.getString("status") == "ACTIVE") return@runTransaction true
            check(!existing.exists() || existing.getString("status") == "REMOVED") { "Account already belongs to a family" }
            val slots = (0..9).map { db.collection("slots").document(it.toString()) }
            val slot = slots.zip(slots.map { transaction.get(it) }).firstOrNull { !it.second.exists() }?.first
                ?: return@runTransaction false
            val data = mapOf("owner" to user, "admins" to listOf(user), "members" to mapOf(user to memberId),
                "slot" to slot.id, "activeDataset" to dataset, "otherDataset" to "", "generation" to 1L,
                "status" to "INITIALIZING", "action" to "create", "target" to "", "inviteSlot" to "")
            transaction.set(slot, mapOf("familyId" to id, "owner" to user))
            transaction.set(family(id), data)
            transaction.set(userRef, mapOf("familyId" to id, "memberId" to memberId, "status" to "ACTIVE"))
            true
        }.await()
        if (!created) throw SyncCapacityReached()
        return info(id)
    }

    /** Owned archived workspaces; archives keep their admission slot, so at most ten exist. */
    suspend fun archived(): List<FamilyInfo> = db.collection("families").whereEqualTo("owner", uid())
        .whereEqualTo("status", "ARCHIVED").limit(10).get(Source.SERVER).await().documents.map(::decode)

    /**
     * Archives the caller's personal workspace [sourceFamilyId] and accepts the invitation in [slot] of
     * [targetFamilyId] in one commit. Idempotent: a commit whose response was lost is recognized by [memberId].
     * Throws [FamilySwitchRejected] only when nothing was committed and retrying cannot succeed.
     */
    suspend fun joinFromPersonal(sourceFamilyId: String, targetFamilyId: String, slot: String, memberId: String) {
        val user = uid()
        val email = services.auth.currentUser?.email
        val outcome = try {
            db.runTransaction { tx ->
                check(uid() == user)
                val userRef = db.collection("users").document(user)
                val account = tx.get(userRef)
                if (account.getString("familyId") == targetFamilyId && account.getString("memberId") == memberId
                    && account.getString("status") == "ACTIVE") return@runTransaction SwitchOutcome.COMMITTED
                val sourceRef = family(sourceFamilyId)
                val targetRef = family(targetFamilyId)
                val inviteRef = targetRef.collection("invites").document(slot)
                val sourceDocument = tx.get(sourceRef)
                val targetDocument = tx.get(targetRef)
                val invite = tx.get(inviteRef)
                val source = runCatching { decode(sourceDocument) }.getOrNull()
                val target = runCatching { decode(targetDocument) }.getOrNull()
                val created = invite.getTimestamp("createdAt")?.seconds ?: 0L
                if (account.getString("familyId") != sourceFamilyId || account.getString("status") != "ACTIVE"
                    || source == null || !source.isPersonal(user) || source.admins != listOf(user) || source.status != "ACTIVE"
                    || target == null || target.status != "ACTIVE" || user in target.members || sourceFamilyId == targetFamilyId
                    || invite.getString("email") != email || invite.getString("status") != "PENDING"
                    || created + 6 * 86400 <= Instant.now().epochSecond) return@runTransaction SwitchOutcome.REJECTED
                if (target.members.size >= 6) return@runTransaction SwitchOutcome.FULL
                tx.update(sourceRef, mapOf("status" to "ARCHIVED", "action" to "archive", "target" to targetFamilyId, "inviteSlot" to ""))
                tx.update(targetRef, mapOf("members" to (target.members + (user to memberId)), "action" to "join", "target" to user, "inviteSlot" to slot))
                tx.update(inviteRef, mapOf("status" to "ACCEPTED", "uid" to user))
                tx.set(userRef, mapOf("familyId" to targetFamilyId, "memberId" to memberId, "status" to "ACTIVE"))
                SwitchOutcome.COMMITTED
            }.await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) throw FamilySwitchRejected()
            throw e
        }
        when (outcome) {
            SwitchOutcome.REJECTED -> throw FamilySwitchRejected()
            SwitchOutcome.FULL -> throw SyncCapacityReached()
            SwitchOutcome.COMMITTED -> Unit
        }
    }

    private enum class SwitchOutcome { COMMITTED, REJECTED, FULL }

    suspend fun invitations(): List<FamilyInvitation> {
        val email = requireNotNull(services.auth.currentUser?.email)
        return db.collectionGroup("invites").whereEqualTo("email", email).whereEqualTo("status", "PENDING")
            .get(Source.SERVER).await().documents.mapNotNull { document ->
                val expires = document.getTimestamp("createdAt")?.toDate()?.toInstant()?.plusSeconds(6 * 86400) ?: return@mapNotNull null
                FamilyInvitation(requireNotNull(document.getString("familyId")), document.id, expires, requireNotNull(document.getString("inviterEmail")))
            }
    }

    suspend fun invite(familyId: String, email: String) {
        val user = uid()
        val normalized = email.trim().lowercase(Locale.ROOT)
        require(normalized.length in 3..254 && '@' in normalized)
        db.runTransaction { transaction ->
            val root = family(familyId)
            val info = decode(transaction.get(root))
            check(user in info.admins && info.status == "ACTIVE")
            val slots = (0..5).map { root.collection("invites").document(it.toString()) }
            val saved = slots.map { transaction.get(it) }
            if (saved.any { it.getString("email") == normalized && it.getString("status") == "PENDING"
                    && (it.getTimestamp("createdAt")?.seconds ?: 0) + 6 * 86400 > Instant.now().epochSecond }) return@runTransaction
            val target = slots.zip(saved).firstOrNull { !it.second.exists() }?.first ?: error("Invitation capacity reached")
            transaction.set(target, mapOf("familyId" to familyId, "email" to normalized, "inviter" to user,
                "inviterEmail" to requireNotNull(services.auth.currentUser?.email),
                "createdAt" to FieldValue.serverTimestamp(), "status" to "PENDING", "uid" to ""))
        }.await()
    }

    suspend fun accept(invitation: FamilyInvitation, memberId: String): FamilyInfo {
        val user = uid()
        db.runTransaction { transaction ->
            val root = family(invitation.familyId)
            val current = decode(transaction.get(root))
            val ref = root.collection("invites").document(invitation.slot)
            val saved = transaction.get(ref)
            val userRef = db.collection("users").document(user)
            val existing = transaction.get(userRef)
            check(!existing.exists() || existing.getString("status") == "REMOVED")
            check(current.status == "ACTIVE" && current.members.size < 6)
            check(saved.getString("email") == services.auth.currentUser?.email && saved.getString("status") == "PENDING")
            transaction.update(root, mapOf("members" to (current.members + (user to memberId)), "action" to "join", "target" to user, "inviteSlot" to invitation.slot))
            transaction.update(ref, mapOf("status" to "ACCEPTED", "uid" to user))
            transaction.set(userRef, mapOf("familyId" to invitation.familyId, "memberId" to memberId, "status" to "ACTIVE"))
        }.await()
        return info(invitation.familyId)
    }

    suspend fun setAdministrator(familyId: String, targetUid: String, administrator: Boolean) {
        val user = uid()
        db.runTransaction { transaction ->
            val root = family(familyId)
            val current = decode(transaction.get(root))
            check(current.owner == user && targetUid in current.members)
            check(administrator || targetUid != current.owner)
            val admins = if (administrator) (current.admins + targetUid).distinct() else current.admins - targetUid
            transaction.update(root, mapOf("admins" to admins, "action" to "roles"))
        }.await()
    }

    suspend fun remove(familyId: String, targetUid: String) {
        val user = uid()
        db.runTransaction { transaction ->
            val root = family(familyId)
            val current = decode(transaction.get(root))
            check(user in current.admins && targetUid != current.owner && targetUid in current.members)
            check(targetUid !in current.admins || user == current.owner)
            val installs = (0..5).map { root.collection("installs").document(it.toString()) }
            val removed = installs.filter { transaction.get(it).getString("uid") == targetUid }
            transaction.update(root, mapOf("members" to (current.members - targetUid), "admins" to (current.admins - targetUid), "action" to "remove", "target" to targetUid))
            transaction.update(db.collection("users").document(targetUid), "status", "REMOVED")
            removed.forEach(transaction::delete)
        }.await()
    }

    suspend fun beginDeletion(familyId: String) {
        val user = uid()
        db.runTransaction { tx ->
            check(uid() == user)
            val root = family(familyId)
            val current = decode(tx.get(root))
            require(current.owner == user)
            if (current.status != "DELETING") {
                tx.update(root, mapOf("status" to "DELETING", "action" to "delete", "deletionPhase" to 0L))
                current.members.keys.forEach { tx.update(db.collection("users").document(it), "status", "REMOVED") }
            }
        }.await()
    }

    suspend fun transferOwnership(familyId: String, targetUid: String) {
        val user = uid()
        db.runTransaction { tx ->
            check(uid() == user)
            val root = family(familyId)
            val document = tx.get(root)
            val current = decode(document)
            require(current.owner == user && targetUid in current.admins && current.status == "ACTIVE")
            tx.update(root, mapOf("owner" to targetUid, "action" to "transfer"))
            tx.update(db.collection("slots").document(requireNotNull(document.getString("slot"))), "owner", targetUid)
        }.await()
    }

    suspend fun cancelInvitations(familyId: String) {
        val user = uid()
        for (slot in 0..5) {
            val ref = family(familyId).collection("invites").document(slot.toString())
            db.runTransaction { tx ->
                check(uid() == user)
                val invite = tx.get(ref)
                if (invite.getString("status") == "PENDING") tx.update(ref, "status", "CANCELLED")
            }.await()
            db.runTransaction { tx ->
                check(uid() == user)
                val invite = tx.get(ref)
                if (invite.exists() && invite.getString("status") != "PENDING") tx.delete(ref)
            }.await()
        }
    }

    suspend fun devices(familyId: String): List<FamilyDevice> = family(familyId).collection("installs").get(Source.SERVER).await().documents.map {
        FamilyDevice(it.id, requireNotNull(it.getString("uid")), requireNotNull(it.getString("installId")), it.getString("name") ?: "Android")
    }

    suspend fun removeDevice(familyId: String, slot: String) {
        val user = uid()
        require(slot in (0..5).map(Int::toString))
        val ref = family(familyId).collection("installs").document(slot)
        db.runTransaction { tx -> check(uid() == user); if (tx.get(ref).exists()) tx.delete(ref) }.await()
    }

    private fun decode(document: DocumentSnapshot): FamilyInfo {
        require(document.exists()) { "Family unavailable" }
        val rawMembers = document.get("members") as? Map<*, *> ?: error("Invalid family membership")
        val members = rawMembers.entries.associate { requireNotNull(it.key as? String) to requireNotNull(it.value as? String) }
        val admins = (document.get("admins") as? List<*>)?.map { requireNotNull(it as? String) } ?: error("Invalid administrators")
        return FamilyInfo(document.id, requireNotNull(document.getString("owner")), admins, members,
            requireNotNull(document.getString("activeDataset")), requireNotNull(document.getLong("generation")), requireNotNull(document.getString("status")),
            document.getString("otherDataset") ?: "")
    }
}
