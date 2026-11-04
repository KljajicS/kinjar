package app.kinjar.data.sync

import app.kinjar.data.auth.FirebaseServices
import app.kinjar.domain.FamilyHistory
import app.kinjar.domain.HistoryCodec
import app.kinjar.domain.OperationCodec
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class CloudCursor(val seconds: Long, val nanos: Int, val id: String)
data class CloudPage(val operations: List<ByteArray>, val cursor: CloudCursor?, val hasMore: Boolean)

class SyncCapacityReached : IllegalStateException("Cloud capacity reached; changes remain on this phone")

@Singleton
class FirestoreTransport @Inject constructor(private val services: FirebaseServices) {
    private val db get() = services.firestore
    private fun family(id: String) = db.collection("families").document(id)
    private fun dataset(fid: String, did: String) = family(fid).collection("datasets").document(did)
    private fun checkUser(uid: String) { check(services.auth.currentUser?.uid == uid) { "Google account changed" } }

    suspend fun initialize(uid: String, fid: String, did: String, history: FamilyHistory, restoreToken: String = "") {
        checkUser(uid)
        val bytes = HistoryCodec.encode(history)
        if (bytes.size > MAX_HISTORY_BYTES) throw SyncCapacityReached()
        val chunks = (bytes.indices step CHUNK_BYTES).map { bytes.copyOfRange(it, minOf(it + CHUNK_BYTES, bytes.size)) }
        val digest = HistoryCodec.digest(bytes)
        val ref = dataset(fid, did)
        db.runTransaction { tx ->
            checkUser(uid)
            val saved = tx.get(ref)
            if (saved.exists()) {
                require(saved.getString("digest") == digest && saved.getLong("expectedBytes") == bytes.size.toLong()) { "Bootstrap history changed" }
            } else {
                tx.set(ref, mapOf("count" to 0L, "bytes" to 0L, "day" to day(), "dailyCount" to 0L, "lastOpId" to "",
                    "chunks" to 0L, "chunkBytes" to 0L, "expectedChunks" to chunks.size.toLong(), "expectedBytes" to bytes.size.toLong(),
                    "digest" to digest, "lastChunk" to "", "sealed" to false) +
                    if (restoreToken.isEmpty()) emptyMap() else mapOf("restoreToken" to restoreToken))
            }
        }.await()
        chunks.forEachIndexed { index, chunk ->
            db.runTransaction { tx ->
                checkUser(uid)
                val state = tx.get(ref)
                val target = ref.collection("chunks").document(index.toString())
                val existing = tx.get(target)
                if (existing.exists()) {
                    require(existing.getBlob("payload")?.toBytes()?.contentEquals(chunk) == true) { "Conflicting bootstrap chunk" }
                } else {
                    tx.set(target, mapOf("payload" to Blob.fromBytes(chunk)))
                    tx.update(ref, mapOf("chunks" to (state.long("chunks") + 1), "chunkBytes" to (state.long("chunkBytes") + chunk.size), "lastChunk" to index.toString()))
                }
            }.await()
        }
        require(HistoryCodec.digest(HistoryCodec.encode(downloadBaseline(uid, fid, did, requireSealed = false))) == digest)
        db.runTransaction { tx ->
            checkUser(uid)
            val root = family(fid)
            val f = tx.get(root)
            val state = tx.get(ref)
            if (f.getString("status") == "ACTIVE" && f.getString("activeDataset") == did) return@runTransaction
            if (restoreToken.isEmpty()) require(f.getString("activeDataset") == did && f.getString("status") == "INITIALIZING")
            else require(f.getString("status") == "RESTORING" && f.getString("otherDataset") == did && f.getString("restoreToken") == restoreToken)
            run {
                require(state.long("chunks") == chunks.size.toLong() && state.long("chunkBytes") == bytes.size.toLong())
                tx.update(ref, "sealed", true)
                if (restoreToken.isEmpty()) tx.update(root, mapOf("status" to "ACTIVE", "action" to "activate"))
                else tx.update(root, mapOf("status" to "ACTIVE", "action" to "restored", "activeDataset" to did,
                    "otherDataset" to requireNotNull(f.getString("activeDataset")), "restoreToken" to FieldValue.delete(),
                    "restoreUid" to FieldValue.delete(), "restoreExpires" to FieldValue.delete()))
            }
        }.await()
    }

    suspend fun beginRestore(uid: String, fid: String, source: String, generation: Long, target: String, token: String) {
        checkUser(uid)
        db.runTransaction { tx ->
            checkUser(uid)
            val ref = family(fid)
            val f = tx.get(ref)
            if (f.getString("activeDataset") == target && f.getString("status") == "ACTIVE") return@runTransaction
            if (f.getString("restoreToken") == token && f.getString("otherDataset") == target) return@runTransaction
            require(f.getString("activeDataset") == source && f.long("generation") == generation
                && f.getString("status") == "ACTIVE" && f.getString("otherDataset") == "") { "Family changed or cleanup is pending" }
            tx.update(ref, mapOf("status" to "RESTORING", "action" to "restore", "generation" to generation + 1,
                "otherDataset" to target, "restoreToken" to token, "restoreUid" to uid,
                "restoreExpires" to Timestamp(java.util.Date.from(Instant.now().plusSeconds(25 * 60)))))
        }.await()
    }

    suspend fun cancelExpiredRestore(uid: String, fid: String) {
        checkUser(uid)
        db.runTransaction { tx ->
            checkUser(uid)
            val ref = family(fid)
            val f = tx.get(ref)
            require(f.getString("status") == "RESTORING" && (f.getString("restoreUid") == uid || requireNotNull(f.getTimestamp("restoreExpires")).toDate().toInstant() <= Instant.now()))
            tx.update(ref, mapOf("status" to "ACTIVE", "action" to "cancelRestore", "restoreToken" to FieldValue.delete(),
                "restoreUid" to FieldValue.delete(), "restoreExpires" to FieldValue.delete()))
        }.await()
    }

    suspend fun cleanup(uid: String, fid: String) {
        checkUser(uid)
        val root = family(fid)
        val f = root.get(Source.SERVER).await()
        require(f.getString("status") == "ACTIVE")
        val other = requireNotNull(f.getString("otherDataset"))
        if (other.isEmpty()) return
        require(other != f.getString("activeDataset"))
        val stateRef = dataset(fid, other)
        deleteContents(uid, stateRef)
        db.runTransaction { tx ->
            checkUser(uid)
            val current = tx.get(root)
            val state = tx.get(stateRef)
            if (current.getString("otherDataset") == "") return@runTransaction
            require(current.getString("otherDataset") == other)
            if (state.exists()) {
                requireEmpty(state)
                tx.delete(stateRef)
            }
            tx.update(root, mapOf("otherDataset" to "", "action" to "cleaned"))
        }.await()
    }

    private suspend fun deleteContents(uid: String, stateRef: com.google.firebase.firestore.DocumentReference) {
        for (collection in listOf("operations", "chunks")) {
            while (true) {
                checkUser(uid)
                val documents = stateRef.collection(collection).limit(50).get(Source.SERVER).await().documents
                if (documents.isEmpty()) break
                documents.forEach { document ->
                    db.runTransaction { tx ->
                        checkUser(uid)
                        val content = tx.get(document.reference)
                        val state = tx.get(stateRef)
                        if (content.exists()) {
                            val size = requireNotNull(content.getBlob("payload")).toBytes().size
                            val counters = if (collection == "operations") mapOf("count" to state.long("count") - 1,
                                "bytes" to state.long("bytes") - size, "lastOpId" to document.id)
                            else mapOf("chunks" to state.long("chunks") - 1, "chunkBytes" to state.long("chunkBytes") - size, "lastChunk" to document.id)
                            tx.delete(document.reference)
                            tx.update(stateRef, counters)
                        }
                    }.await()
                }
            }
        }
    }

    suspend fun deleteFamily(uid: String, fid: String) {
        checkUser(uid)
        val root = family(fid)
        val family = root.get(Source.SERVER).await()
        require(family.getString("owner") == uid && family.getString("status") == "DELETING")
        val datasets = listOfNotNull(family.getString("activeDataset"), family.getString("otherDataset")?.takeIf { it.isNotEmpty() }).distinct()
        datasets.forEach { id ->
            val ref = dataset(fid, id)
            deleteContents(uid, ref)
            db.runTransaction { tx ->
                checkUser(uid)
                val state = tx.get(ref)
                if (state.exists()) { requireEmpty(state); tx.delete(ref) }
            }.await()
        }
        for ((index, collection) in listOf("invites", "installs").withIndex()) {
            val current = root.get(Source.SERVER).await()
            if ((current.getLong("deletionPhase") ?: 0L) > index) continue
            val refs = (0..5).map { root.collection(collection).document(it.toString()) }
            db.runTransaction { tx ->
                checkUser(uid)
                val f = tx.get(root)
                val saved = refs.map(tx::get)
                require(f.getLong("deletionPhase") == index.toLong())
                saved.filter { it.exists() }.forEach { tx.delete(it.reference) }
                tx.update(root, mapOf("deletionPhase" to index + 1L, "action" to "deleteSlots"))
            }.await()
        }
        db.runTransaction { tx ->
            checkUser(uid)
            val current = tx.get(root)
            require(current.getString("owner") == uid && current.getLong("deletionPhase") == 2L)
            tx.delete(db.collection("slots").document(requireNotNull(current.getString("slot"))))
            tx.delete(root)
        }.await()
    }

    private fun requireEmpty(state: DocumentSnapshot) {
        require(state.long("count") == 0L && state.long("bytes") == 0L && state.long("chunks") == 0L && state.long("chunkBytes") == 0L)
    }

    suspend fun downloadBaseline(uid: String, fid: String, did: String, requireSealed: Boolean = true): FamilyHistory {
        checkUser(uid)
        val ref = dataset(fid, did)
        val state = ref.get(Source.SERVER).await()
        require(!requireSealed || state.getBoolean("sealed") == true) { "Family initialization incomplete" }
        val count = state.long("expectedChunks").also { require(it in 1..20) }.toInt()
        val size = state.long("expectedBytes").also { require(it in 1..MAX_HISTORY_BYTES.toLong()) }.toInt()
        require(state.long("chunks") == count.toLong() && state.long("chunkBytes") == size.toLong())
        val output = ByteArrayOutputStream(size)
        repeat(count) { index ->
            checkUser(uid)
            val chunk = requireNotNull(ref.collection("chunks").document(index.toString()).get(Source.SERVER).await().getBlob("payload")).toBytes()
            require(chunk.size in 1..CHUNK_BYTES && output.size() + chunk.size <= size)
            output.write(chunk)
        }
        val bytes = output.toByteArray()
        require(bytes.size == size && HistoryCodec.digest(bytes) == state.getString("digest")) { "Incomplete or corrupt family history" }
        return HistoryCodec.decode(bytes)
    }

    suspend fun register(uid: String, fid: String, installId: String): String {
        checkUser(uid)
        return db.runTransaction { tx ->
            checkUser(uid)
            val refs = (0..5).map { family(fid).collection("installs").document(it.toString()) }
            val saved = refs.map(tx::get)
            val existing = saved.indexOfFirst { it.getString("installId") == installId && it.getString("uid") == uid }
            if (existing >= 0) refs[existing].id else {
                val empty = saved.indexOfFirst { !it.exists() }
                check(empty >= 0) { "Remove an old installation before connecting another phone" }
                tx.set(refs[empty], mapOf("uid" to uid, "installId" to installId, "name" to android.os.Build.MODEL.take(80), "createdAt" to FieldValue.serverTimestamp()))
                refs[empty].id
            }
        }.await()
    }

    suspend fun upload(uid: String, fid: String, did: String, slot: String, payload: ByteArray) {
        checkUser(uid)
        val op = OperationCodec.decode(payload)
        require(op.authorUid == uid && op.familyId == fid && op.datasetId == did)
        val ref = dataset(fid, did)
        db.runTransaction { tx ->
            checkUser(uid)
            val target = ref.collection("operations").document(op.id)
            val existing = tx.get(target)
            val state = tx.get(ref)
            if (existing.exists()) {
                require(existing.getBlob("payload")?.toBytes()?.contentEquals(payload) == true) { "Conflicting uploaded operation" }
            } else {
                val count = state.long("count") + 1
                val bytes = state.long("bytes") + payload.size
                val today = day()
                val daily = if (state.long("day") == today) state.long("dailyCount") + 1 else 1
                if (count > 1000 || bytes + state.long("chunkBytes") > MAX_HISTORY_BYTES || daily > 200) throw SyncCapacityReached()
                tx.set(target, mapOf("uid" to uid, "installId" to op.installId, "installSlot" to slot,
                    "sequence" to op.sequence, "payload" to Blob.fromBytes(payload), "createdAt" to FieldValue.serverTimestamp()))
                tx.update(ref, mapOf("count" to count, "bytes" to bytes, "day" to today, "dailyCount" to daily, "lastOpId" to op.id))
            }
        }.await()
    }

    suspend fun download(uid: String, fid: String, did: String, cursor: CloudCursor?, overlap: Boolean = false): CloudPage {
        checkUser(uid)
        var query: Query = dataset(fid, did).collection("operations").orderBy("createdAt").orderBy(FieldPath.documentId()).limit(100)
        if (cursor != null) {
            query = if (overlap) query.startAt(Timestamp((cursor.seconds - 60).coerceAtLeast(0), 0))
                else query.startAfter(Timestamp(cursor.seconds, cursor.nanos), cursor.id)
        }
        val documents = query.get(Source.SERVER).await().documents
        checkUser(uid)
        val operations = documents.map { doc ->
            val bytes = requireNotNull(doc.getBlob("payload")).toBytes()
            val op = OperationCodec.decode(bytes)
            require(op.id == doc.id && op.familyId == fid && op.datasetId == did && op.authorUid == doc.getString("uid")
                && op.installId == doc.getString("installId") && op.sequence == doc.getLong("sequence")) { "Operation identity mismatch" }
            bytes
        }
        val end = documents.lastOrNull()?.let {
            val at = requireNotNull(it.getTimestamp("createdAt"))
            CloudCursor(at.seconds, at.nanoseconds, it.id)
        }
        return CloudPage(operations, end ?: cursor, documents.size == 100)
    }

    private fun day(): Long = Math.floorDiv(Instant.now().toEpochMilli(), 86_400_000)
    private fun DocumentSnapshot.long(name: String): Long = requireNotNull(getLong(name)) { "Invalid dataset metadata" }

    companion object {
        const val MAX_HISTORY_BYTES = 10 * 1024 * 1024
        const val CHUNK_BYTES = 512 * 1024
    }
}
