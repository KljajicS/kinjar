package app.kinjar.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GitHubUpdateSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var directory: File
    private lateinit var source: GitHubUpdateSource
    private val apk = "verified test APK".toByteArray()

    @Before fun start() {
        server.start()
        directory = temporary.newFolder("updates")
        source = GitHubUpdateSource(directory, OkHttpClient(), server.url("/"))
    }

    @After fun stop() { server.shutdown() }

    private fun release(bytes: ByteArray = apk) = UpdateManifest(
        1, 14, "0.3.11", 26,
        "https://github.com/KljajicS/kinjar/releases/download/v0.3.11/kinjar-release.apk",
        bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )

    private fun metadata(release: UpdateManifest = release()) = MockResponse().setBody(Json.encodeToString(release))

    @Test fun `valid metadata and streamed APK produce verified private file`() = runBlocking {
        val unrelated = File(directory, "unrelated.txt").apply { writeText("keep") }
        File(directory, "kinjar-update-old.part").writeText("interrupted")
        server.enqueue(metadata())
        server.enqueue(MockResponse().setChunkedBody(String(apk), 3))
        val manifest = source.latest()
        val progress = mutableListOf<Long>()
        val result = source.download(manifest, progress::add)
        assertEquals(directory.canonicalFile, result.canonicalFile.parentFile)
        assertArrayEquals(apk, result.readBytes())
        assertEquals(listOf(0L, apk.size.toLong()), listOf(progress.first(), progress.last()))
        assertTrue(progress.zipWithNext().all { (before, after) -> after >= before })
        assertTrue(unrelated.exists())
        assertEquals(listOf(result.name), directory.listFiles()!!.filter { it.name.startsWith("kinjar-update-") }.map { it.name })
        assertEquals("/KljajicS/kinjar/releases/latest/download/update.json", server.takeRequest().path)
        assertEquals("/KljajicS/kinjar/releases/download/v0.3.11/kinjar-release.apk", server.takeRequest().path)
    }

    @Test fun `invalid metadata is rejected before download request`() {
        val valid = release()
        val invalid = listOf(
            valid.copy(schemaVersion = 2), valid.copy(versionCode = 0), valid.copy(versionName = "0.3.11-beta"),
            valid.copy(minSdk = 25), valid.copy(sizeBytes = 0), valid.copy(sizeBytes = 256L * 1024 * 1024 + 1),
            valid.copy(sha256 = "bad"), valid.copy(apkUrl = "https://example.com/kinjar-release.apk"),
            valid.copy(apkUrl = valid.apkUrl + "?token=secret"), valid.copy(apkUrl = valid.apkUrl + "#fragment"),
            valid.copy(apkUrl = valid.apkUrl.replace("github.com", "user@github.com")),
            valid.copy(apkUrl = valid.apkUrl.replace("v0.3.11", "v0.3.12")),
        )
        for (manifest in invalid) {
            server.enqueue(metadata(manifest))
            assertThrows(Exception::class.java) { runBlocking { source.latest() } }
            val requests = server.requestCount
            assertThrows(Exception::class.java) { runBlocking { source.download(manifest) {} } }
            assertEquals(requests, server.requestCount)
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `malformed and oversized metadata fail without accepting release`() {
        for (body in listOf("{", "{}", " ".repeat(16 * 1024 + 1))) {
            server.enqueue(MockResponse().setChunkedBody(body, 4096))
            assertThrows(Exception::class.java) { runBlocking { source.latest() } }
        }
    }

    @Test fun `HTTP errors and partial content never produce ready APK`() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(Exception::class.java) { runBlocking { source.latest() } }
        for (code in listOf(404, 206)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody(String(apk)))
            assertThrows(Exception::class.java) { runBlocking { source.download(release()) {} } }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun `truncated oversized and corrupt downloads leave no partial APK`() {
        val responses = listOf(
            MockResponse().setChunkedBody("short", 2),
            MockResponse().setChunkedBody(String(apk) + "extra", 3),
            MockResponse().setBody("x".repeat(apk.size)),
            MockResponse().setBody("short").setHeader("Content-Length", apk.size).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END),
        )
        for (response in responses) {
            server.enqueue(response)
            assertThrows(Exception::class.java) { runBlocking { source.download(release()) {} } }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun `redirects preserve GitHub HTTPS policy before test transport mapping`() {
        val destinations = listOf(
            "http://github.com/file", "https://evil.example/file", "https://github.com.evil.example/file",
            "https://user:password@github.com/file", "https://github.com:444/file", "https://github.com/file#fragment",
        )
        for (destination in destinations) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination))
            assertThrows(Exception::class.java) { runBlocking { source.latest() } }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun `signed GitHub asset redirects work and redirect loops are bounded`() = runBlocking {
        for (host in listOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://$host/asset?signature=example"))
            server.enqueue(metadata())
            assertEquals(release(), source.latest())
        }
        val before = server.requestCount
        repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop")) }
        assertThrows(Exception::class.java) { runBlocking { source.latest() } }
        assertEquals(before + 6, server.requestCount)
    }

    @Test fun `cancellation closes stalled request removes partial and releases next download`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val download = launch { source.download(release()) {} }
        withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        withTimeout(5_000) { download.cancelAndJoin() }
        assertTrue(directory.listFiles()!!.isEmpty())
        server.enqueue(MockResponse().setBody(String(apk)))
        val result = withTimeout(5_000) { source.download(release()) {} }
        assertArrayEquals(apk, result.readBytes())
        assertFalse(directory.listFiles()!!.any { it.extension == "part" })
    }

    @Test fun `cancellation after writing bytes removes partial APK`() {
        server.enqueue(MockResponse().setChunkedBody(String(apk), 3))
        assertThrows(CancellationException::class.java) {
            runBlocking { source.download(release()) { if (it > 0) throw CancellationException("Cancelled") } }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
