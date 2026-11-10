package app.kinjar.ui.update

import androidx.lifecycle.viewModelScope
import app.kinjar.data.update.UpdateManifest
import app.kinjar.data.update.UpdateSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val environment = FakeEnvironment()
    private val source = FakeSource()
    private val models = mutableListOf<UpdateViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun model() = UpdateViewModel(source, environment).also { models += it }

    @Test fun `creation never checks and explicit checks are not throttled`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        assertEquals(0, source.checks)
        assertFalse(vm.state.value.visible)
        vm.check()
        runCurrent()
        assertEquals(1, source.checks)
        vm.check()
        runCurrent()
        assertEquals(2, source.checks)
        val recreated = model()
        runCurrent()
        assertEquals(2, source.checks)
        recreated.check()
        runCurrent()
        assertEquals(3, source.checks)
    }

    @Test fun `repeated request and opening settings share pending check and receive its failure`() = runTest(dispatcher) {
        val waiting = CompletableDeferred<UpdateManifest>()
        source.lookup = { waiting.await() }
        val vm = model()
        vm.check()
        runCurrent()
        vm.check()
        vm.show()
        assertTrue(vm.state.value.visible)
        waiting.completeExceptionally(IOException())
        runCurrent()
        assertEquals(1, source.checks)
        assertEquals(UpdateNotice.CHECK_FAILED, vm.state.value.notice)
        assertFalse(vm.state.value.checking)
    }

    @Test fun `check failures are visible and allow retry`() = runTest(dispatcher) {
        source.lookup = { throw IOException() }
        val vm = model()
        vm.check()
        runCurrent()
        assertTrue(vm.state.value.visible)
        assertEquals(UpdateNotice.CHECK_FAILED, vm.state.value.notice)
        source.lookup = { release }
        vm.check()
        runCurrent()
        assertEquals(release, vm.state.value.release)
        assertNull(vm.state.value.notice)
    }

    @Test fun `closing pending check cancels it and allows retry`() = runTest(dispatcher) {
        source.lookup = { awaitCancellation() }
        val vm = model()
        vm.check()
        runCurrent()
        assertTrue(vm.state.value.checking)
        vm.dismiss()
        runCurrent()
        assertFalse(vm.state.value.visible)
        assertFalse(vm.state.value.checking)
        source.lookup = { release }
        vm.check()
        runCurrent()
        assertEquals(2, source.checks)
        assertEquals(release, vm.state.value.release)
    }

    @Test fun `dismissed update stays hidden until requested again`() = runTest(dispatcher) {
        val vm = model()
        vm.check()
        runCurrent()
        assertTrue(vm.state.value.visible)
        vm.dismiss()
        vm.onBackground()
        runCurrent()
        assertFalse(vm.state.value.visible)
        assertEquals(1, source.checks)
        vm.show()
        runCurrent()
        assertTrue(vm.state.value.visible)
        assertEquals(1, source.checks)
    }

    @Test fun `older current and incompatible releases never become install candidates`() = runTest(dispatcher) {
        val vm = model()
        for (code in listOf(12L, 13L)) {
            source.lookup = { release.copy(versionCode = code) }
            vm.check()
            runCurrent()
            assertNull(vm.state.value.release)
            assertEquals(UpdateNotice.CURRENT, vm.state.value.notice)
        }
        source.lookup = { release.copy(minSdk = 37) }
        vm.check()
        runCurrent()
        assertNull(vm.state.value.release)
        assertEquals(UpdateNotice.INCOMPATIBLE, vm.state.value.notice)
        vm.download()
        runCurrent()
        assertEquals(0, source.downloads)
    }

    @Test fun `download is verified again before installer handoff and tampering refuses install`() = runTest(dispatcher) {
        val vm = model()
        vm.check()
        runCurrent()
        vm.download()
        runCurrent()
        assertTrue(vm.state.value.ready)
        assertEquals(1, environment.verifications)
        environment.verification = { throw IllegalArgumentException() }
        vm.install()
        runCurrent()
        assertEquals(2, environment.verifications)
        assertFalse(vm.state.value.ready)
        assertNull(vm.consumeHandoff())
        assertEquals(UpdateNotice.VERIFY_FAILED, vm.state.value.notice)
    }

    @Test fun `settings return never triggers installation and handoff is consumed once`() = runTest(dispatcher) {
        val vm = model()
        vm.check()
        runCurrent()
        vm.download()
        runCurrent()
        vm.install()
        runCurrent()
        assertEquals(source.file, vm.consumeHandoff())
        assertNull(vm.consumeHandoff())
        vm.permissionReturned(false)
        assertEquals(UpdateNotice.PERMISSION_REQUIRED, vm.state.value.notice)
        vm.permissionReturned(true)
        assertNull(vm.state.value.handoff)
        assertTrue(vm.state.value.ready)
        vm.install()
        runCurrent()
        assertEquals(source.file, vm.consumeHandoff())
    }

    @Test fun `background cancels pending download and cannot hand off installer`() = runTest(dispatcher) {
        source.transfer = { awaitCancellation() }
        val vm = model()
        vm.check()
        runCurrent()
        vm.download()
        runCurrent()
        assertTrue(vm.state.value.downloading)
        vm.onBackground()
        runCurrent()
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.ready)
        assertNull(vm.state.value.handoff)
        assertNull(vm.state.value.notice)
    }

    @Test fun `background during verification cancels pending installer handoff`() = runTest(dispatcher) {
        val vm = model()
        vm.check()
        runCurrent()
        vm.download()
        runCurrent()
        environment.verification = { awaitCancellation() }
        vm.install()
        runCurrent()
        vm.onBackground()
        runCurrent()
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.handoff)
    }

    @Test fun `new model never resumes installation from downloaded state`() = runTest(dispatcher) {
        val original = model()
        original.check()
        runCurrent()
        original.download()
        runCurrent()
        assertTrue(original.state.value.ready)
        val recreated = model()
        recreated.permissionReturned(true)
        recreated.check()
        runCurrent()
        recreated.install()
        runCurrent()
        assertFalse(recreated.state.value.ready)
        assertNull(recreated.state.value.handoff)
    }

    @Test fun `debug environment does not request metadata or downloads`() = runTest(dispatcher) {
        environment.enabled = false
        val vm = model()
        vm.check()
        vm.check()
        vm.show()
        vm.download()
        vm.install()
        runCurrent()
        assertEquals(0, source.checks)
        assertEquals(0, source.downloads)
        assertFalse(vm.state.value.visible)
    }

    private class FakeEnvironment : UpdateEnvironment {
        override var enabled = true
        override val versionCode = 13L
        override val versionName = "0.3.10"
        override val sdk = 36
        var verifications = 0
        var verification: suspend () -> Unit = { }
        override suspend fun verify(file: File, release: UpdateManifest) {
            verifications++
            verification()
        }
    }

    private class FakeSource : UpdateSource {
        var checks = 0
        var downloads = 0
        val file = File("update.apk")
        var lookup: suspend () -> UpdateManifest = { release }
        var transfer: suspend () -> File = { file }
        override suspend fun latest(): UpdateManifest { checks++; return lookup() }
        override suspend fun download(release: UpdateManifest, onProgress: (Long) -> Unit): File {
            downloads++
            onProgress(release.sizeBytes)
            return transfer()
        }
    }

    companion object {
        private val release = UpdateManifest(1, 14, "0.3.11", 26,
            "https://github.com/KljajicS/kinjar/releases/download/v0.3.11/kinjar-release.apk", 1234, "a".repeat(64))
    }
}
