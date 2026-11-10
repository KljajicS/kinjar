package app.kinjar.data.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GitHubUpdateSource internal constructor(
    private val cacheDir: File,
    client: OkHttpClient,
    private val testOrigin: HttpUrl?,
) : UpdateSource {
    constructor(cacheDir: File, client: OkHttpClient = defaultClient()) : this(cacheDir, client, null)

    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(true)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .callTimeout(90, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .apply { interceptors().clear(); networkInterceptors().clear() }
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val downloadMutex = Mutex()

    init {
        require(testOrigin == null || (testOrigin.scheme == "http" && testOrigin.host in setOf("localhost", "127.0.0.1", "::1")
            && testOrigin.username.isEmpty() && testOrigin.password.isEmpty()))
    }

    override suspend fun latest(): UpdateManifest = request(METADATA_URL.toHttpUrl()) { response ->
        val body = response.body ?: throw IOException("Missing update metadata")
        require(body.contentLength() <= MAX_METADATA_BYTES) { "Update metadata too large" }
        val source = body.source()
        require(!source.request(MAX_METADATA_BYTES + 1)) { "Update metadata too large" }
        json.decodeFromString<UpdateManifest>(source.readUtf8()).also(::validate)
    }

    override suspend fun download(release: UpdateManifest, onProgress: (Long) -> Unit): File = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            validate(release)
            currentCoroutineContext().ensureActive()
            check(cacheDir.isDirectory || cacheDir.mkdirs()) { "Update cache unavailable" }
            cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) }?.forEach { it.delete() }
            val partial = File.createTempFile(FILE_PREFIX, ".part", cacheDir)
            val ready = File(cacheDir, "$FILE_PREFIX${release.versionCode}.apk")
            try {
                request(release.apkUrl.toHttpUrl()) { response ->
                    val body = response.body ?: throw IOException("Missing update APK")
                    require(body.contentLength() == -1L || body.contentLength() == release.sizeBytes) { "Update size mismatch" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    onProgress(bytes)
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                bytes += count
                                require(bytes <= release.sizeBytes) { "Update size mismatch" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                onProgress(bytes)
                            }
                        }
                    }
                    require(bytes == release.sizeBytes) { "Update size mismatch" }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    require(actual.equals(release.sha256, ignoreCase = true)) { "Update checksum mismatch" }
                }
                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(ready)) throw IOException("Cannot retain verified update")
                ready
            } finally {
                partial.delete()
            }
        }
    }

    private suspend fun <T> request(initial: HttpUrl, consume: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            var url = initial
            repeat(MAX_REDIRECTS + 1) { redirects ->
                validateDestination(url)
                currentCoroutineContext().ensureActive()
                val transport = testOrigin?.newBuilder()?.encodedPath(url.encodedPath)?.encodedQuery(url.encodedQuery)?.build() ?: url
                val call = client.newCall(Request.Builder().url(transport).header("Accept-Encoding", "identity").build())
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { response ->
                        if (response.code in REDIRECT_CODES) {
                            require(redirects < MAX_REDIRECTS) { "Too many update redirects" }
                            val location = response.header("Location") ?: throw IOException("Missing redirect location")
                            url = url.resolve(location) ?: throw IOException("Invalid update redirect")
                        } else {
                            if (response.code != 200) throw IOException("Update request failed")
                            return@coroutineScope consume(response)
                        }
                    }
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw error
                } finally {
                    cancellation.cancel()
                }
            }
            throw IOException("Too many update redirects")
        }
    }

    companion object {
        private const val METADATA_URL = "https://github.com/KljajicS/kinjar/releases/latest/download/update.json"
        private const val MAX_METADATA_BYTES = 16L * 1024
        private const val MAX_APK_BYTES = 256L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val FILE_PREFIX = "kinjar-update-"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val RELEASE_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

        private fun defaultClient(): OkHttpClient = OkHttpClient()

        private fun validateDestination(url: HttpUrl) {
            require(url.scheme == "https" && url.port == 443 && url.host in RELEASE_HOSTS
                && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "Invalid update destination" }
        }

        private fun validate(release: UpdateManifest) {
            require(release.schemaVersion == 1 && release.versionCode > 0 && release.minSdk >= 26) { "Unsupported update metadata" }
            require(release.versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid update version" }
            require(release.sizeBytes in 1..MAX_APK_BYTES && release.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid update integrity metadata" }
            require(release.apkUrl == "https://github.com/KljajicS/kinjar/releases/download/v${release.versionName}/kinjar-release.apk") { "Invalid update URL" }
        }
    }
}
