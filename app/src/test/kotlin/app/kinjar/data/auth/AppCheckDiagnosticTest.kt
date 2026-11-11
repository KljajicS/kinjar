package app.kinjar.data.auth

import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
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
            IllegalStateException(secret) to "FAILED",
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
}
