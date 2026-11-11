package app.kinjar.data.auth

import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AppCheckDiagnosticTest {
    @Test fun `unconfigured and debug builds never request attestation`() = runTest {
        val request: suspend () -> Unit = { error("must not request a token") }
        assertEquals(AppCheckStatus.NOT_CONFIGURED, AppCheckDiagnostic(false, false, request, { 0 }).check().status)
        assertEquals(AppCheckStatus.DEBUG_BUILD, AppCheckDiagnostic(true, true, request, { 0 }).check().status)
    }

    @Test fun `manual requests are limited and become available after one minute`() = runTest {
        var requests = 0
        val diagnostic = AppCheckDiagnostic(true, false, { requests++ }, { testScheduler.currentTime })
        assertEquals(0, requests)
        assertEquals(AppCheckStatus.VERIFIED, diagnostic.check().status)
        repeat(10) { assertEquals(AppCheckStatus.COOLDOWN, diagnostic.check().status) }
        advanceTimeBy(59_999)
        assertEquals(AppCheckStatus.COOLDOWN, diagnostic.check().status)
        advanceTimeBy(1)
        assertEquals(AppCheckStatus.VERIFIED, diagnostic.check().status)
        assertEquals(2, requests)
    }

    @Test fun `concurrent callers cannot start duplicate requests`() = runTest {
        val pending = CompletableDeferred<Unit>()
        var requests = 0
        val diagnostic = AppCheckDiagnostic(true, false, { requests++; pending.await() }, { 0 })
        val first = async { diagnostic.check() }
        runCurrent()
        assertEquals(AppCheckStatus.COOLDOWN, diagnostic.check().status)
        pending.complete(Unit)
        assertEquals(AppCheckStatus.VERIFIED, first.await().status)
        assertEquals(1, requests)
    }

    @Test fun `hung requests time out and a later manual retry can recover`() = runTest {
        var requests = 0
        val diagnostic = AppCheckDiagnostic(true, false, { if (++requests == 1) awaitCancellation() }, { testScheduler.currentTime })
        val first = async { diagnostic.check() }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(AppCheckStatus.TIMEOUT, first.await().status)
        assertEquals(AppCheckStatus.COOLDOWN, diagnostic.check().status)
        advanceTimeBy(30_000)
        assertEquals(AppCheckStatus.VERIFIED, diagnostic.check().status)
    }

    @Test fun `cancellation propagates and releases the request gate`() = runTest {
        var cancelled = false
        var requests = 0
        val diagnostic = AppCheckDiagnostic(true, false, {
            if (++requests == 1) {
                try { awaitCancellation() } catch (error: CancellationException) { cancelled = true; throw error }
            }
        }, { testScheduler.currentTime })
        val first = async { diagnostic.check() }
        runCurrent()
        first.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(first.isCancelled)
        advanceTimeBy(60_000)
        assertEquals(AppCheckStatus.VERIFIED, diagnostic.check().status)
    }

    @Test fun `private exception text never becomes a diagnostic code`() {
        val secret = "private-token private-email private-response-body"
        val errors = listOf(
            IOException(secret) to "NETWORK",
            FirebaseTooManyRequestsException(secret) to "THROTTLED",
            FirebaseException(secret) to "FIREBASE",
            IllegalStateException(secret) to "INVALID_STATE",
        )
        errors.forEach { (error, code) ->
            assertEquals(code, AppCheckDiagnostic.classify(RuntimeException(secret, error)).code)
        }
        val classic = mock(IntegrityServiceException::class.java)
        `when`(classic.errorCode).thenReturn(-12)
        `when`(classic.message).thenReturn(secret)
        assertEquals("INTEGRITY_-12", AppCheckDiagnostic.classify(FirebaseException(secret, classic)).code)
        val standard = mock(StandardIntegrityException::class.java)
        `when`(standard.errorCode).thenReturn(-18)
        assertEquals("INTEGRITY_-18", AppCheckDiagnostic.classify(standard).code)
    }

    @Test fun `cyclic exception causes are bounded`() {
        val first = RuntimeException("private one")
        val second = RuntimeException("private two", first)
        first.initCause(second)
        assertEquals("FAILED", AppCheckDiagnostic.classify(first).code)
    }

    @Test fun `missing SDK project number becomes a safe numeric configuration clue`() {
        val failure = runCatching { java.lang.Long.parseLong(null) }.exceptionOrNull()!!
        val result = AppCheckDiagnostic.classify(AppCheckStepException(AppCheckStage.TOKEN_REQUEST, failure as Exception))
        assertEquals("NUMBER_FORMAT", result.code)
        assertEquals(AppCheckStage.TOKEN_REQUEST, result.stage)
        assertEquals(listOf(AppCheckErrorType.NUMBER_FORMAT), result.errorTypes)
    }

    @Test fun `details preserve known steps without exposing exception text or arbitrary class names`() = runTest {
        val secret = "private-token private-account"
        val failure = runCatching {
            appCheckStep(AppCheckStage.INITIALIZATION) { throw IllegalStateException(secret) }
        }.exceptionOrNull()!!
        val result = AppCheckDiagnostic.classify(failure)
        assertEquals(AppCheckStage.INITIALIZATION, result.stage)
        assertEquals(listOf(AppCheckErrorType.INVALID_STATE), result.errorTypes)
        assertFalse(result.toString().contains(secret))
        val unknown = object : Exception(secret) {}
        assertEquals(listOf(AppCheckErrorType.EXCEPTION), AppCheckDiagnostic.classify(unknown).errorTypes)
        assertEquals(AppCheckStage.CONFIGURATION, AppCheckDiagnostic.classify(AppCheckConfigurationException()).stage)
    }

    @Test fun `HTTP diagnosis extracts only bounded SDK status and discards private body`() {
        val result = AppCheckDiagnostic.classify(FirebaseException("Error returned from API. code: 403 body: secret token and account"))
        assertEquals("HTTP_403", result.code)
        assertEquals(403, result.httpCode)
        assertFalse(result.toString().contains("secret"))
        for (message in listOf(
            "secret Error returned from API. code: 403 body: private",
            "Error returned from API. code: 4030 body: private",
            "Error returned from API. code: 200 body: private",
            "Error returned from API. code: token body: private",
        )) assertEquals("FIREBASE", AppCheckDiagnostic.classify(FirebaseException(message)).code)
        assertEquals("FAILED", AppCheckDiagnostic.classify(Exception("Error returned from API. code: 403 body: private")).code)
        assertEquals("THROTTLED", AppCheckDiagnostic.classify(FirebaseException("Too many attempts.")).code)
    }

    @Test fun `Play services errors keep only numeric status`() {
        val failure = mock(ApiException::class.java)
        `when`(failure.statusCode).thenReturn(17)
        `when`(failure.message).thenReturn("private account")
        val result = AppCheckDiagnostic.classify(failure)
        assertEquals("PLAY_SERVICES_17", result.code)
        assertEquals(listOf(AppCheckErrorType.PLAY_SERVICES), result.errorTypes)
    }

    @Test fun `step tracking preserves coroutine cancellation`() = runTest {
        val failure = runCatching { appCheckStep(AppCheckStage.TOKEN_REQUEST) { throw CancellationException() } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
