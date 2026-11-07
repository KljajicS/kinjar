package app.kinjar.data

import app.kinjar.data.db.DraftInfo
import app.kinjar.data.db.JournalInfo
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SyncHistoryStorageTest {
    private val db = testDb()
    private fun bytes(size: Int) = Random(size).nextBytes(size)

    @Test fun `baseline larger than a cursor window is reassembled byte for byte`() {
        val payload = bytes(2 * 1024 * 1024 + 12_345)
        db.syncBaselineQueries.save(payload)
        assertArrayEquals(payload, db.syncBaselinePayload())
        assertTrue(db.hasSyncBaseline())
    }

    @Test fun `chunk boundaries keep every byte`() {
        for (size in listOf(0, 1, HISTORY_CHUNK_BYTES - 1, HISTORY_CHUNK_BYTES, HISTORY_CHUNK_BYTES + 1, 2 * HISTORY_CHUNK_BYTES)) {
            val payload = bytes(size)
            db.syncBaselineQueries.save(payload)
            assertArrayEquals("size $size", payload, db.syncBaselinePayload())
        }
    }

    @Test fun `missing rows read as null`() {
        assertNull(db.syncBaselinePayload())
        assertFalse(db.hasSyncBaseline())
        assertNull(db.restoreManifest())
        db.syncDraftQueries.save("copy", "user", "family", 1, byteArrayOf(1))
        assertNull(db.syncDraftPayload("other", "copy"))
        assertNull(db.syncDraftPayload("user", "missing"))
    }

    @Test fun `large draft and manifest are read by owner and id`() {
        val draft = bytes(3 * 1024 * 1024)
        val manifest = bytes(2 * 1024 * 1024 + 1)
        db.syncDraftQueries.save("copy", "user", "family", 1, draft)
        db.syncDraftQueries.save("other", "user", "family", 2, byteArrayOf(9))
        db.restoreJournalQueries.save("user", "family", "source", "target", "token", "RESTORE", manifest)
        assertArrayEquals(draft, db.syncDraftPayload("user", "copy"))
        assertArrayEquals(manifest, db.restoreManifest())
        assertEquals(listOf("other", "copy"), db.syncDraftQueries.draftInfo("user").executeAsList().map { it.id })
        assertEquals("RESTORE", db.restoreJournalQueries.journalInfo().executeAsOne().phase)
    }

    @Test fun `metadata queries carry no payload`() {
        for (type in listOf(DraftInfo::class.java, JournalInfo::class.java))
            assertTrue(type.name, type.declaredFields.none { it.type == ByteArray::class.java })
    }
}
