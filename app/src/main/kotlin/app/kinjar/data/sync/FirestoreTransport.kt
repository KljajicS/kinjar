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

    suspend fun initialize(uid: String, fid: String, did: String, history: FamilyHistory) {
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
                    "digest" to digest, "lastChunk" to "", "sealed" to false))
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
            require(f.getString("activeDataset") == did)
            if (f.getString("status") != "ACTIVE") {
                require(state.long("chunks") == chunks.size.toLong() && state.long("chunkBytes") == bytes.size.toLong())
                tx.update(ref, "sealed", true)
                tx.update(root, mapOf("status" to "ACTIVE", "action" to "activate"))
            }
        }.await()
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
                tx.set(refs[empty], mapOf("uid" to uid, "installId" to installId))
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
