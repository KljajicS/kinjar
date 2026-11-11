package app.kinjar.data.auth

import app.kinjar.BuildConfig
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class AppCheckStatus { VERIFIED, NOT_CONFIGURED, DEBUG_BUILD, COOLDOWN, TIMEOUT, NETWORK, THROTTLED, INTEGRITY, FIREBASE, FAILED }

data class AppCheckResult(val status: AppCheckStatus, val integrityCode: Int? = null) {
    val code: String get() = if (status == AppCheckStatus.INTEGRITY && integrityCode != null) "INTEGRITY_$integrityCode" else status.name
}

@Singleton
class AppCheckDiagnostic internal constructor(
    private val configured: Boolean,
    private val debug: Boolean,
    private val request: suspend () -> Unit,
    private val elapsedMillis: () -> Long,
) {
    @Inject constructor(services: FirebaseServices) : this(
        services.configured, BuildConfig.DEBUG, services::verifyAppCheck, { System.nanoTime() / 1_000_000 },
    )

    private val mutex = Mutex()
    private var lastAttempt: Long? = null

    suspend fun check(): AppCheckResult {
        if (!configured) return AppCheckResult(AppCheckStatus.NOT_CONFIGURED)
        if (debug) return AppCheckResult(AppCheckStatus.DEBUG_BUILD)
        if (!mutex.tryLock()) return AppCheckResult(AppCheckStatus.COOLDOWN)
        try {
            val now = elapsedMillis()
            if (lastAttempt?.let { now - it < 60_000 } == true) return AppCheckResult(AppCheckStatus.COOLDOWN)
            lastAttempt = now
            return withTimeoutOrNull(30_000) {
                try {
                    request()
                    AppCheckResult(AppCheckStatus.VERIFIED)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    classify(error)
                }
            } ?: AppCheckResult(AppCheckStatus.TIMEOUT)
        } finally {
            mutex.unlock()
        }
    }

    internal companion object {
        fun classify(error: Throwable): AppCheckResult {
            val causes = generateSequence(error) { it.cause }.take(8).toList()
            causes.forEach {
                when (it) {
                    is IntegrityServiceException -> return AppCheckResult(AppCheckStatus.INTEGRITY, it.errorCode)
                    is StandardIntegrityException -> return AppCheckResult(AppCheckStatus.INTEGRITY, it.errorCode)
                }
            }
            val status = when {
                causes.any { it is FirebaseTooManyRequestsException } -> AppCheckStatus.THROTTLED
                causes.any { it is FirebaseNetworkException || it is IOException } -> AppCheckStatus.NETWORK
                causes.any { it is FirebaseException } -> AppCheckStatus.FIREBASE
                else -> AppCheckStatus.FAILED
            }
            return AppCheckResult(status)
        }
    }
}
