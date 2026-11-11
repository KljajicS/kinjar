package app.kinjar.data.auth

import app.kinjar.BuildConfig
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class AppCheckStatus { VERIFIED, NOT_CONFIGURED, DEBUG_BUILD, COOLDOWN, TIMEOUT, NETWORK, THROTTLED, INTEGRITY, FIREBASE, FAILED, CONFIGURATION, HTTP, PLAY_SERVICES, NUMBER_FORMAT, INVALID_STATE, INVALID_ARGUMENT, SECURITY }
enum class AppCheckStage { INITIALIZATION, TOKEN_REQUEST, PLAY_INTEGRITY, CONFIGURATION, COMPLETE, NOT_STARTED }
enum class AppCheckErrorType { INTEGRITY, PLAY_SERVICES, FIREBASE, NETWORK, THROTTLED, NUMBER_FORMAT, INVALID_STATE, INVALID_ARGUMENT, SECURITY, RUNTIME, EXCEPTION, CONFIGURATION }

internal class AppCheckConfigurationException : Exception()
internal class AppCheckStepException(val stage: AppCheckStage, cause: Exception) : Exception(null, cause)

internal suspend fun <T> appCheckStep(stage: AppCheckStage, block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    throw AppCheckStepException(stage, error)
}

data class AppCheckResult(
    val status: AppCheckStatus,
    val integrityCode: Int? = null,
    val httpCode: Int? = null,
    val playServicesCode: Int? = null,
    val stage: AppCheckStage = when (status) {
        AppCheckStatus.VERIFIED -> AppCheckStage.COMPLETE
        AppCheckStatus.NOT_CONFIGURED, AppCheckStatus.DEBUG_BUILD, AppCheckStatus.COOLDOWN -> AppCheckStage.NOT_STARTED
        else -> AppCheckStage.TOKEN_REQUEST
    },
    val errorTypes: List<AppCheckErrorType> = emptyList(),
) {
    val code: String get() = when {
        status == AppCheckStatus.INTEGRITY && integrityCode != null -> "INTEGRITY_$integrityCode"
        status == AppCheckStatus.HTTP && httpCode != null -> "HTTP_$httpCode"
        status == AppCheckStatus.PLAY_SERVICES && playServicesCode != null -> "PLAY_SERVICES_$playServicesCode"
        else -> status.name
    }
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
            val stage = causes.filterIsInstance<AppCheckStepException>().firstOrNull()?.stage ?: AppCheckStage.TOKEN_REQUEST
            val types = causes.filterNot { it is AppCheckStepException }.map(::errorType)
            if (causes.any { it is AppCheckConfigurationException }) {
                return AppCheckResult(AppCheckStatus.CONFIGURATION, stage = AppCheckStage.CONFIGURATION, errorTypes = types)
            }
            causes.forEach {
                when (it) {
                    is IntegrityServiceException -> return AppCheckResult(AppCheckStatus.INTEGRITY, it.errorCode, stage = AppCheckStage.PLAY_INTEGRITY, errorTypes = types)
                    is StandardIntegrityException -> return AppCheckResult(AppCheckStatus.INTEGRITY, it.errorCode, stage = AppCheckStage.PLAY_INTEGRITY, errorTypes = types)
                }
            }
            causes.filterIsInstance<ApiException>().firstOrNull()?.let {
                return AppCheckResult(AppCheckStatus.PLAY_SERVICES, playServicesCode = it.statusCode, stage = stage, errorTypes = types)
            }
            causes.filterIsInstance<FirebaseException>().forEach {
                // The SDK exposes HTTP status only in this prefix; never retain the response body.
                val code = Regex("^Error returned from API\\. code: ([45][0-9]{2}) body: ").find(it.message.orEmpty().take(64))
                    ?.groupValues?.get(1)?.toInt()
                if (code != null) return AppCheckResult(AppCheckStatus.HTTP, httpCode = code, stage = stage, errorTypes = types)
            }
            val status = when {
                causes.any { it is FirebaseTooManyRequestsException || (it is FirebaseException && it.message == "Too many attempts.") } -> AppCheckStatus.THROTTLED
                causes.any { it is FirebaseNetworkException || it is IOException } -> AppCheckStatus.NETWORK
                causes.any { it is NumberFormatException } -> AppCheckStatus.NUMBER_FORMAT
                causes.any { it is SecurityException } -> AppCheckStatus.SECURITY
                causes.any { it is IllegalStateException } -> AppCheckStatus.INVALID_STATE
                causes.any { it is IllegalArgumentException } -> AppCheckStatus.INVALID_ARGUMENT
                causes.any { it is FirebaseException } -> AppCheckStatus.FIREBASE
                else -> AppCheckStatus.FAILED
            }
            return AppCheckResult(status, stage = stage, errorTypes = types)
        }

        private fun errorType(error: Throwable): AppCheckErrorType = when (error) {
            is AppCheckConfigurationException -> AppCheckErrorType.CONFIGURATION
            is IntegrityServiceException, is StandardIntegrityException -> AppCheckErrorType.INTEGRITY
            is ApiException -> AppCheckErrorType.PLAY_SERVICES
            is FirebaseTooManyRequestsException -> AppCheckErrorType.THROTTLED
            is FirebaseNetworkException, is IOException -> AppCheckErrorType.NETWORK
            is FirebaseException -> AppCheckErrorType.FIREBASE
            is NumberFormatException -> AppCheckErrorType.NUMBER_FORMAT
            is SecurityException -> AppCheckErrorType.SECURITY
            is IllegalStateException -> AppCheckErrorType.INVALID_STATE
            is IllegalArgumentException -> AppCheckErrorType.INVALID_ARGUMENT
            is RuntimeException -> AppCheckErrorType.RUNTIME
            else -> AppCheckErrorType.EXCEPTION
        }
    }
}
