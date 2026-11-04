package app.kinjar.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Base64

@Serializable
data class QuarantinedOperation(val payload: String, val reason: String) {
    init { require(reason in setOf("INVALID_OPERATION", "IDENTITY_MISMATCH")) }
    fun bytes(): ByteArray = Base64.getDecoder().decode(payload).also { require(it.size in 1..OperationCodec.MAX_BYTES) }
    companion object {
        fun from(bytes: ByteArray, reason: String = "INVALID_OPERATION") = QuarantinedOperation(Base64.getEncoder().encodeToString(bytes), reason)
    }
}

@Serializable
data class FamilyHistory(val version: Int = 1, val baseline: List<OperationEvent>, val operations: List<String>, val quarantine: List<QuarantinedOperation> = emptyList()) {
    init { require(version == 1 && baseline.size <= 100_000 && operations.size <= 100_000 && quarantine.size <= 100_000) }
    fun decodedOperations(): List<SyncOperation> = operations.map { OperationCodec.decode(Base64.getDecoder().decode(it)) }
}

object HistoryCodec {
    const val MAX_BYTES = 64 * 1024 * 1024
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }
    fun encode(history: FamilyHistory): ByteArray = json.encodeToString(history).toByteArray(Charsets.UTF_8).also {
        require(it.size <= MAX_BYTES) { "Family history capacity reached" }
    }
    fun decode(bytes: ByteArray): FamilyHistory {
        require(bytes.size in 1..MAX_BYTES)
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        if (json.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.intOrNull != 1) throw UnsupportedSyncVersion()
        return json.decodeFromString<FamilyHistory>(text)
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
