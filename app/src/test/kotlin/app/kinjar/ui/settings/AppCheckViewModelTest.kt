package app.kinjar.ui.settings

import androidx.lifecycle.viewModelScope
import app.kinjar.data.FixedClock
import app.kinjar.data.auth.AppCheckDiagnostic
import app.kinjar.data.auth.AppCheckStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class AppCheckViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-11-11T14:00:00Z"), ZoneOffset.UTC)
    private val models = mutableListOf<AppCheckViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    @Test fun `opening settings makes no request and repeated taps keep a single check`() = runTest(dispatcher) {
        var requests = 0
        val pending = CompletableDeferred<Unit>()
        val diagnostic = AppCheckDiagnostic(true, false, { requests++; pending.await() }, { testScheduler.currentTime })
        val vm = AppCheckViewModel(diagnostic, clock).also { models += it }
        runCurrent()
        assertEquals(0, requests)
        vm.check()
        vm.check()
        assertTrue(vm.state.value.busy)
        runCurrent()
        assertEquals(1, requests)
        pending.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.busy)
        assertEquals(AppCheckStatus.VERIFIED, vm.state.value.result?.status)
        assertEquals(clock.now(), vm.state.value.checkedAt)
    }

    @Test fun `cooldown preserves useful failure and later retry replaces it`() = runTest(dispatcher) {
        var requests = 0
        val diagnostic = AppCheckDiagnostic(true, false, { if (++requests == 1) throw IOException("private details") }, { testScheduler.currentTime })
        val vm = AppCheckViewModel(diagnostic, clock).also { models += it }
        vm.check()
        runCurrent()
        val original = vm.state.value
        assertEquals(AppCheckStatus.NETWORK, original.result?.status)
        clock.instant = clock.instant.plusSeconds(1)
        vm.check()
        runCurrent()
        assertTrue(vm.state.value.cooldown)
        assertEquals(original.result, vm.state.value.result)
        assertEquals(original.checkedAt, vm.state.value.checkedAt)
        advanceTimeBy(60_000)
        vm.check()
        runCurrent()
        assertFalse(vm.state.value.cooldown)
        assertEquals(AppCheckStatus.VERIFIED, vm.state.value.result?.status)
        assertEquals(clock.now(), vm.state.value.checkedAt)
    }
}
