package app.kinjar.data.sync

import android.content.Context
import app.kinjar.data.auth.FirebaseServices
import app.kinjar.domain.FamilyHistory
import app.kinjar.domain.HistoryCodec
import app.kinjar.domain.HistoryRecord
import app.kinjar.domain.HistorySource
import app.kinjar.domain.HistoryStream
import app.kinjar.domain.OperationCodec
import app.kinjar.domain.OperationEvent
import app.kinjar.domain.QuarantinedOperation
import app.kinjar.domain.SyncOperation
import app.kinjar.domain.UnsupportedSyncVersion
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class CloudCursor(val seconds: Long, val nanos: Int, val id: String)
data class CloudPage(val operations: List<ByteArray>, val cursor: CloudCursor?, val hasMore: Boolean, val quarantine: List<QuarantinedOperation> = emptyList())

class SyncCapacityReached : IllegalStateException("Cloud capacity reached; changes remain on this phone")

/** Another account in this family owns this installation identity, so its operation IDs cannot be used here. */
class InstallationClaimed : IllegalStateException("This phone is registered to another account in this family")

@Singleton
class FirestoreTransport @Inject constructor(
    private val services: FirebaseServices,
    @ApplicationContext private val context: Context,
) {
    private val db get() = services.firestore
    private fun family(id: String) = db.collection("families").document(id)
    private fun dataset(fid: String, did: String) = family(fid).collection("datasets").document(did)
    private fun checkUser(uid: String) { check(services.auth.currentUser?.uid == uid) { "Google account changed" } }

    /**
     * Uploads [history] as a [HISTORY_VERSION] stream in [CHUNK_BYTES] chunks, resuming after interruption in the version
     * the dataset was started with. The source is read twice, once for the manifest and once for upload, and both passes
     * must produce the same bytes.
     */
    suspend fun initialize(uid: String, fid: String, did: String, history: HistorySource, restoreToken: String = "") {
        checkUser(uid)
        val existing = dataset(fid, did).get(Source.SERVER).await()
        checkUser(uid)
        val version = if (existing.exists()) existing.getLong("historyVersion") ?: 1L else HISTORY_VERSION
        if (version > HISTORY_VERSION) throw UnsupportedSyncVersion()
        require(version in 1L..HISTORY_VERSION)
        val legacy = if (version == 1L) File.createTempFile("legacy-bootstrap", ".spool", context.cacheDir) else null
        try {
            if (legacy != null) legacy.outputStream().buffered().use { HistoryCodec.write(history, it) }
            initializeStream(uid, fid, did, restoreToken, version) {
                if (legacy == null) historyPieces(history, version.toInt()) else filePieces(legacy)
            }
        } finally { legacy?.delete() }
    }

    private suspend fun initializeStream(uid: String, fid: String, did: String, restoreToken: String, version: Long,
                                         stream: () -> Sequence<ByteArray>) {
        val manifest = StreamManifest.of(stream())
        val ref = dataset(fid, did)
        db.runTransaction { tx ->
            checkUser(uid)
            val saved = tx.get(ref)
            if (saved.exists()) {
                require((saved.getLong("historyVersion") ?: 1L) == version && saved.getString("digest") == manifest.digest
                    && saved.getLong("expectedBytes") == manifest.bytes) { "Bootstrap history changed" }
            } else {
                require(version == HISTORY_VERSION) { "Legacy bootstrap disappeared" }
                tx.set(ref, mapOf("count" to 0L, "bytes" to 0L, "day" to FieldValue.serverTimestamp(), "dailyCount" to 0L, "dailyBytes" to 0L,
                    "lastOpId" to "", "chunks" to 0L, "chunkBytes" to 0L, "expectedChunks" to manifest.chunks, "expectedBytes" to manifest.bytes,
                    "digest" to manifest.digest, "lastChunk" to "", "sealed" to false, "historyVersion" to HISTORY_VERSION) +
                    if (restoreToken.isEmpty()) emptyMap() else mapOf("restoreToken" to restoreToken))
            }
        }.await()
        val uploaded = StreamManifest.Builder()
        for (chunk in chunked(stream())) {
            val index = uploaded.chunks
            uploaded.add(chunk)
            require(uploaded.chunks <= manifest.chunks) { "Bootstrap history changed" }
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
        // Every stored chunk now equals this pass, so a matching digest proves the cloud holds the manifest's stream.
        require(uploaded.build() == manifest) { "Bootstrap history changed" }
        db.runTransaction { tx ->
            checkUser(uid)
            val root = family(fid)
            val f = tx.get(root)
            val state = tx.get(ref)
            if (f.getString("status") == "ACTIVE" && f.getString("activeDataset") == did) return@runTransaction
            if (restoreToken.isEmpty()) require(f.getString("activeDataset") == did && f.getString("status") == "INITIALIZING")
            else require(f.getString("status") == "RESTORING" && f.getString("otherDataset") == did && f.getString("restoreToken") == restoreToken)
            run {
                require(state.long("chunks") == manifest.chunks && state.long("chunkBytes") == manifest.bytes)
                tx.update(ref, "sealed", true)
                if (restoreToken.isEmpty()) tx.update(root, mapOf("status" to "ACTIVE", "action" to "activate"))
                else tx.update(root, mapOf("status" to "ACTIVE", "action" to "restored", "activeDataset" to did,
                    "otherDataset" to requireNotNull(f.getString("activeDataset")), "restoreToken" to FieldValue.delete(),
                    "restoreUid" to FieldValue.delete(), "restoreStartedAt" to FieldValue.delete()))
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
                "restoreStartedAt" to FieldValue.serverTimestamp()))
        }.await()
    }

    suspend fun cancelExpiredRestore(uid: String, fid: String) {
        checkUser(uid)
        db.runTransaction { tx ->
            checkUser(uid)
            val ref = family(fid)
            val f = tx.get(ref)
            require(f.getString("status") == "RESTORING")
            tx.update(ref, mapOf("status" to "ACTIVE", "action" to "cancelRestore", "restoreToken" to FieldValue.delete(),
                "restoreUid" to FieldValue.delete(), "restoreStartedAt" to FieldValue.delete()))
        }.await()
    }

    /**
     * Deletes at most [maxDeletes] retired operation or chunk documents, one counted transaction each.
     * Returns true once the retired dataset is gone, false while more deletion remains.
     */
    suspend fun cleanup(uid: String, fid: String, maxDeletes: Int = 32): Boolean {
        require(maxDeletes > 0)
        checkUser(uid)
        val root = family(fid)
        val f = root.get(Source.SERVER).await()
        require(f.getString("status") == "ACTIVE")
        val other = requireNotNull(f.getString("otherDataset"))
        if (other.isEmpty()) return true
        require(other != f.getString("activeDataset"))
        val stateRef = dataset(fid, other)
        if (!deleteContents(uid, stateRef, maxDeletes)) return false
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
        return true
    }

    /** True when no content remains; false after [limit] deletions, even if those emptied the dataset. */
    private suspend fun deleteContents(uid: String, stateRef: com.google.firebase.firestore.DocumentReference, limit: Int = Int.MAX_VALUE): Boolean {
        var remaining = limit
        for (collection in listOf("operations", "chunks")) {
            while (true) {
                if (remaining == 0) return false
                checkUser(uid)
                val documents = stateRef.collection(collection).limit(minOf(50, remaining).toLong()).get(Source.SERVER).await().documents
                if (documents.isEmpty()) break
                documents.forEach { document ->
                    remaining--
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
        return true
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
        val claims = root.collection("claims")
        while (true) {
            checkUser(uid)
            val documents = claims.limit(50).get(Source.SERVER).await().documents
            if (documents.isEmpty()) break
            val batch = db.batch()
            documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
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

    /**
     * Downloads the sealed baseline into a cache file and verifies its size and digest before any record is parsed.
     * The returned source reads that file and must be closed. Version 1 baselines were bounded by their release's
     * rules and are converted in memory.
     */
    suspend fun downloadBaseline(uid: String, fid: String, did: String, requireSealed: Boolean = true): HistorySource {
        checkUser(uid)
        val ref = dataset(fid, did)
        val state = ref.get(Source.SERVER).await()
        require(!requireSealed || state.getBoolean("sealed") == true) { "Family initialization incomplete" }
        val version = state.getLong("historyVersion") ?: 1L
        if (version > HISTORY_VERSION) throw UnsupportedSyncVersion()
        require(version >= 1L) { "Invalid dataset metadata" }
        val count = state.long("expectedChunks")
        val size = state.long("expectedBytes")
        if (version == 1L) require(count <= MAX_LEGACY_CHUNKS && size <= HistoryCodec.MAX_BYTES) { "Invalid dataset metadata" }
        require(state.long("chunks") == count && state.long("chunkBytes") == size)
        val digest = requireNotNull(state.getString("digest"))
        val file = File.createTempFile("baseline", ".spool", context.cacheDir)
        val spool = try { ChunkSpool(file, count, size, digest) } catch (e: Throwable) { file.delete(); throw e }
        try {
            for (index in 0 until count) {
                checkUser(uid)
                spool.write(requireNotNull(ref.collection("chunks").document(index.toString()).get(Source.SERVER).await().getBlob("payload")).toBytes())
            }
            spool.finish()
            if (version == 1L) return legacyHistorySource(HistoryCodec.decode(spool.file.readBytes())).also { spool.file.delete() }
            return SpooledHistory(spool.file)
        } catch (e: Throwable) {
            spool.discard()
            throw e
        }
    }

    suspend fun register(uid: String, fid: String, installId: String): String {
        checkUser(uid)
        val refs = (0..5).map { family(fid).collection("installs").document(it.toString()) }
        val claim = family(fid).collection("claims").document(installId)
        return db.runTransaction { tx ->
            checkUser(uid)
            val saved = refs.map(tx::get)
            val owner = tx.get(claim)
            if (owner.exists() && owner.getString("uid") != uid) throw InstallationClaimed()
            val existing = saved.indexOfFirst { it.getString("installId") == installId && it.getString("uid") == uid }
            val empty = saved.indexOfFirst { !it.exists() }
            check(existing >= 0 || empty >= 0) { "Remove an old installation before connecting another phone" }
            val slot = refs[if (existing >= 0) existing else empty].id
            if (!owner.exists()) tx.set(claim, mapOf("uid" to uid, "installSlot" to slot, "createdAt" to FieldValue.serverTimestamp()))
            if (existing >= 0) refs[existing].id else {
                tx.set(refs[empty], mapOf("uid" to uid, "installId" to installId, "name" to android.os.Build.MODEL.take(80), "createdAt" to FieldValue.serverTimestamp()))
                refs[empty].id
            }
        }.await()
    }

    suspend fun upload(uid: String, fid: String, did: String, slot: String, payload: ByteArray) {
        var dailyLimit = false
        val observeLimit: (Boolean) -> Unit = { dailyLimit = dailyLimit || it }
        try { uploadAttempt(uid, fid, did, slot, payload, false, observeLimit) }
        catch (e: FirebaseFirestoreException) {
            if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
            // Server rules decide the UTC day; retry the other counter value when the device clock disagrees.
            try { uploadAttempt(uid, fid, did, slot, payload, true, observeLimit) }
            catch (retry: FirebaseFirestoreException) {
                if (retry.code == FirebaseFirestoreException.Code.PERMISSION_DENIED && dailyLimit) throw SyncCapacityReached()
                throw retry
            }
        }
    }

    private suspend fun uploadAttempt(uid: String, fid: String, did: String, slot: String, payload: ByteArray, alternateDay: Boolean, observeLimit: (Boolean) -> Unit) {
        checkUser(uid)
        val op = OperationCodec.decode(payload)
        require(op.authorUid == uid && op.familyId == fid && op.datasetId == did)
        val cost = operationCost(op, payload.size)
        if (cost > MAX_DAILY_BYTES) throw SyncCapacityReached()
        val ref = dataset(fid, did)
        db.runTransaction { tx ->
            checkUser(uid)
            val target = ref.collection("operations").document(op.id)
            val existing = tx.get(target)
            val state = tx.get(ref)
            if (existing.exists()) {
                require(existing.getBlob("payload")?.toBytes()?.contentEquals(payload) == true) { "Conflicting uploaded operation" }
            } else {
                val count = Math.addExact(state.long("count"), 1)
                val bytes = Math.addExact(state.long("bytes"), payload.size.toLong())
                val sameDay = Math.floorDiv(requireNotNull(state.getTimestamp("day")).seconds, 86400) == Math.floorDiv(Instant.now().epochSecond, 86400)
                val daily = if (sameDay != alternateDay) state.long("dailyCount") + 1 else 1
                val dailyBytes = if (sameDay != alternateDay) Math.addExact(state.getLong("dailyBytes") ?: 0L, cost) else cost
                observeLimit(daily > MAX_DAILY_OPERATIONS || dailyBytes > MAX_DAILY_BYTES)
                tx.set(target, mapOf("uid" to uid, "installId" to op.installId, "installSlot" to slot,
                    "sequence" to op.sequence, "payload" to Blob.fromBytes(payload), "cost" to cost, "createdAt" to FieldValue.serverTimestamp()))
                tx.update(ref, mapOf("count" to count, "bytes" to bytes, "day" to FieldValue.serverTimestamp(), "dailyCount" to daily,
                    "dailyBytes" to dailyBytes, "lastOpId" to op.id))
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
        val quarantine = mutableListOf<QuarantinedOperation>()
        val operations = documents.mapNotNull { doc ->
            val bytes = requireNotNull(doc.getBlob("payload")).toBytes()
            try {
                val op = checkedOperation(bytes, doc.get("cost"))
                require(op.id == doc.id && op.familyId == fid && op.datasetId == did && op.authorUid == doc.getString("uid")
                    && op.installId == doc.getString("installId") && op.sequence == doc.getLong("sequence"))
                bytes
            } catch (e: UnsupportedSyncVersion) { throw e }
            catch (_: IllegalArgumentException) { quarantine += QuarantinedOperation.from(bytes, "IDENTITY_MISMATCH"); null }
        }
        val end = documents.lastOrNull()?.let {
            val at = requireNotNull(it.getTimestamp("createdAt"))
            CloudCursor(at.seconds, at.nanoseconds, it.id)
        }
        return CloudPage(operations, end ?: cursor, documents.size == 100, quarantine)
    }

    private fun DocumentSnapshot.long(name: String): Long = requireNotNull(getLong(name)) { "Invalid dataset metadata" }

    companion object {
        const val CHUNK_BYTES = 512 * 1024
        const val MAX_DAILY_OPERATIONS = 200
        const val MAX_DAILY_BYTES = 8L * 1024 * 1024
        const val REFERENCE_COST = 32L
        // Matches HistoryStream.VERSION; datasets started at version 1 or 2 finish and stay readable at their version.
        const val HISTORY_VERSION = 3L
        // Datasets written by version 1 apps were capped by the rules of that release.
        private const val MAX_LEGACY_CHUNKS = 128L

        /** Daily budget charged for [operation]; compact version 2 references still cost what they expand to. */
        fun operationCost(operation: SyncOperation, payloadSize: Int): Long =
            payloadSize + REFERENCE_COST * (operation.parents.size.toLong() + operation.rejected.size)

        /**
         * Decodes a downloaded payload only after its stored cost is plausible, within the reference count that cost paid for,
         * then requires the exact cost it was charged. Documents without a cost predate the budget and can only hold
         * version 1 operations, which carry at most [LEGACY_REFERENCES] references.
         */
        fun checkedOperation(payload: ByteArray, claimedCost: Any?): SyncOperation {
            require(claimedCost == null || (claimedCost is Long && claimedCost in payload.size..MAX_DAILY_BYTES)) { "Invalid operation cost" }
            val references = if (claimedCost == null) LEGACY_REFERENCES else ((claimedCost as Long) - payload.size) / REFERENCE_COST
            val operation = OperationCodec.decode(payload, references)
            require(if (claimedCost == null) operation.version == 1 else claimedCost == operationCost(operation, payload.size)) { "Operation cost mismatch" }
            return operation
        }

        private const val LEGACY_REFERENCES = 2_000L
    }
}

/** A verified baseline stream kept in a cache file; [close] deletes the file. */
class SpooledHistory internal constructor(private val file: File) : HistorySource, Closeable {
    override fun records(): Sequence<HistoryRecord> = sequence {
        file.inputStream().buffered().use { yieldAll(HistoryStream.read(it)) }
    }
    override fun close() { file.delete() }
}

internal fun legacyHistorySource(history: FamilyHistory): HistorySource = object : HistorySource {
    override fun records(): Sequence<HistoryRecord> = history.baseline.asSequence().map<OperationEvent, HistoryRecord> { HistoryRecord.Baseline(it) } +
        history.operations.asSequence().map { HistoryRecord.Operation.of(Base64.getDecoder().decode(it)) } +
        history.quarantine.asSequence().map { HistoryRecord.Quarantine(it) }
}

internal data class StreamManifest(val digest: String, val bytes: Long, val chunks: Long) {
    class Builder {
        private val sha = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
            private set
        var chunks = 0L
            private set
        fun add(chunk: ByteArray) {
            sha.update(chunk)
            bytes = Math.addExact(bytes, chunk.size.toLong())
            chunks++
        }
        fun build() = StreamManifest(sha.digest().joinToString("") { "%02x".format(it) }, bytes, chunks)
    }

    companion object {
        fun of(stream: Sequence<ByteArray>): StreamManifest =
            Builder().apply { chunked(stream).forEach(::add) }.build().also { require(it.bytes in 1..MAX_STREAM_BYTES) { "Invalid history stream" } }
    }
}

// Firestore rules compare stream sizes as integers that JavaScript clients must also represent exactly.
internal const val MAX_STREAM_BYTES = 9_007_199_254_740_991L

internal fun filePieces(file: File): Sequence<ByteArray> = sequence {
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(FirestoreTransport.CHUNK_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) yield(buffer.copyOf(count))
        }
    }
}

internal fun historyPieces(source: HistorySource, version: Int = HistoryStream.VERSION): Sequence<ByteArray> = sequence {
    val buffer = ByteArrayOutputStream()
    val writer = HistoryStream.Writer(buffer, version)
    yield(buffer.toByteArray())
    buffer.reset()
    for (record in source.records()) {
        writer.write(record)
        yield(buffer.toByteArray())
        buffer.reset()
    }
    writer.finish()
    yield(buffer.toByteArray())
}

/** Regroups stream pieces into full [FirestoreTransport.CHUNK_BYTES] chunks; only the last may be shorter. */
internal fun chunked(stream: Sequence<ByteArray>, size: Int = FirestoreTransport.CHUNK_BYTES): Sequence<ByteArray> = sequence {
    var buffer = ByteArray(size)
    var at = 0
    for (piece in stream) {
        var offset = 0
        while (offset < piece.size) {
            val count = minOf(size - at, piece.size - offset)
            piece.copyInto(buffer, at, offset, offset + count)
            at += count
            offset += count
            if (at == size) {
                yield(buffer)
                buffer = ByteArray(size)
                at = 0
            }
        }
    }
    if (at > 0) yield(buffer.copyOf(at))
}

/** Writes downloaded chunks to [file], rejecting anything beyond the manifest and verifying the digest at the end. */
internal class ChunkSpool(val file: File, private val expectedChunks: Long, private val expectedBytes: Long, private val digest: String) {
    init {
        require(expectedChunks >= 1 && expectedBytes in expectedChunks..MAX_STREAM_BYTES
            && (expectedBytes - 1) / FirestoreTransport.CHUNK_BYTES < expectedChunks) { "Invalid dataset metadata" }
    }

    private val output = file.outputStream().buffered()
    private val received = StreamManifest.Builder()

    fun write(chunk: ByteArray) {
        require(chunk.size in 1..FirestoreTransport.CHUNK_BYTES && received.chunks < expectedChunks
            && received.bytes + chunk.size <= expectedBytes) { "Incomplete or corrupt family history" }
        received.add(chunk)
        output.write(chunk)
    }

    fun finish() {
        output.close()
        require(received.build() == StreamManifest(digest, expectedBytes, expectedChunks)) { "Incomplete or corrupt family history" }
    }

    fun discard() {
        runCatching { output.close() }
        file.delete()
    }
}
