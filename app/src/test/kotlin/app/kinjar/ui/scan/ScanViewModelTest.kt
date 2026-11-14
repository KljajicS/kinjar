package app.kinjar.ui.scan

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import app.kinjar.R
import app.kinjar.data.*
import app.kinjar.data.receipt.ReceiptLookup
import app.kinjar.domain.*
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-10-15T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val repo = BudgetRepository(db, EventStore(db, "one", clock, Projector(db)), clock, "one")
    private val url = "https://suf.purs.gov.rs/v/?vl=test"
    private val receipt = Receipt(url, "123456789", "ABCDEFGH-IJKLMNOP-1", "Shop", LocalDate.of(2026, 10, 15),
        Money.of("100", Currency.RSD), listOf(ReceiptItem("Food", BigDecimal.ONE, BigDecimal("100"), BigDecimal("100"))))
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    private val ips = "K:PR|V:01|C:1|R:200000000000000001|N:ЈП Пример|I:RSD3018,58|SF:221|RO:00000138"

    @Test fun `IPS scans offline without fetching or saving and repeated frames preserve review`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = ScanViewModel(ReceiptLookup { error("IPS must not fetch") }, repo, saved)
        vm.scanned(ips)
        assertEquals(ScanStage.REVIEW, vm.state.value.stage)
        assertEquals("ЈП Пример", vm.state.value.paymentSlip?.merchant)
        assertNull(vm.state.value.receipt)
        vm.scanned(url)
        vm.scanned(ips.replace("3018,58", "9999,00"))
        assertEquals("3018.58", vm.state.value.paymentSlip?.amount?.amount?.toPlainString())
        assertEquals(setOf("paymentSlipPending"), saved.keys())
        assertEquals(0L, db.eventQueries.count().executeAsOne())
        vm.reset()
        assertNull(vm.state.value.paymentSlip)
        assertTrue(saved.keys().isEmpty())
        vm.scanned(ips)
        assertEquals(ScanStage.REVIEW, vm.state.value.stage)
    }

    @Test fun `IPS recreation requires rescan instead of restoring partial payment details`() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf("paymentSlipPending" to true))
        val vm = ScanViewModel(ReceiptLookup { error("No lookup") }, repo, saved)
        assertEquals(ScanStage.ERROR, vm.state.value.stage)
        assertEquals(R.string.scan_ips_rescan, vm.state.value.error)
        assertFalse(vm.state.value.canRetry)
        assertNull(vm.state.value.paymentSlip)
        vm.retry()
        assertEquals(ScanStage.ERROR, vm.state.value.stage)
        vm.reset()
        vm.scanned(ips)
        assertEquals(ScanStage.REVIEW, vm.state.value.stage)
    }

    @Test fun `invalid or unsupported IPS shows local errors and can scan another code`() = runTest(dispatcher) {
        val vm = ScanViewModel(ReceiptLookup { receipt }, repo, SavedStateHandle())
        vm.scanned(ips.replace("3018,58", "bad"))
        assertEquals(R.string.scan_ips_invalid, vm.state.value.error)
        assertFalse(vm.state.value.canRetry)
        vm.reset()
        vm.scanned(ips.replace("V:01", "V:02"))
        assertEquals(R.string.scan_ips_unsupported, vm.state.value.error)
        vm.reset()
        vm.scanned(url)
        vm.state.first { it.stage == ScanStage.REVIEW }
        assertEquals(receipt, vm.state.value.receipt)
        assertNull(vm.state.value.paymentSlip)
    }

    @Test fun `invalid QR never fetches and repeated frames make only one request`() = runTest(dispatcher) {
        var requests = 0
        val gate = CompletableDeferred<Receipt>()
        val vm = ScanViewModel(ReceiptLookup { requests++; gate.await() }, repo, SavedStateHandle())
        vm.scanned("https://evil.test")
        assertEquals(R.string.scan_invalid_qr, vm.state.value.error)
        assertEquals(0, requests)
        vm.reset()
        vm.scanned(url)
        vm.scanned(url)
        runCurrent()
        assertEquals(1, requests)
        gate.complete(receipt)
        vm.state.first { it.stage == ScanStage.REVIEW }
        assertEquals(receipt, vm.state.value.receipt)
        assertEquals(0L, db.eventQueries.count().executeAsOne())
    }

    @Test fun `network error retains URL for retry and process restoration`() = runTest(dispatcher) {
        var fail = true
        val saved = SavedStateHandle()
        val lookup = ReceiptLookup { if (fail) throw IOException(); receipt }
        val vm = ScanViewModel(lookup, repo, saved)
        vm.scanned(url)
        vm.state.first { it.stage == ScanStage.ERROR }
        assertTrue(vm.state.value.canRetry)
        assertEquals(url, saved.get<String>("fiscalUrl"))
        val restored = ScanViewModel(lookup, repo, SavedStateHandle(mapOf("fiscalUrl" to saved.get<String>("fiscalUrl"))))
        assertEquals(ScanStage.ERROR, restored.state.value.stage)
        fail = false
        restored.retry()
        restored.state.first { it.stage == ScanStage.REVIEW }
        assertEquals(0L, db.eventQueries.count().executeAsOne())
    }

    @Test fun `leaving cancels lookup and reset drops URL and stale result`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Receipt>()
        val saved = SavedStateHandle()
        val vm = ScanViewModel(ReceiptLookup { gate.await() }, repo, saved)
        vm.scanned(url)
        runCurrent()
        vm.leave()
        assertTrue(vm.state.value.canRetry)
        vm.reset()
        gate.complete(receipt)
        runCurrent()
        assertEquals(ScanStage.CAPTURING, vm.state.value.stage)
        assertNull(saved.get<String>("fiscalUrl"))
        assertNull(vm.state.value.receipt)
    }

    @Test fun `active receipt is rejected but deleted expense can be scanned again`() = runTest(dispatcher) {
        repo.setUp("Family", listOf(Account("cash", "Cash", Currency.RSD, AccountKind.SPENDING)))
        repo.addReceipt(Transaction(receipt.transactionId, TransactionType.EXPENSE, receipt.total, "cash", null,
            null, receipt.date, "", receipt.merchant, "member", TransactionSource.RECEIPT), receipt)
        val vm = ScanViewModel(ReceiptLookup { receipt }, repo, SavedStateHandle())
        vm.scanned(url)
        vm.state.first { it.stage == ScanStage.ERROR }
        assertEquals(R.string.scan_duplicate, vm.state.value.error)
        assertFalse(vm.state.value.canRetry)
        repo.delete(receipt.transactionId)
        vm.reset()
        vm.scanned(url)
        vm.state.first { it.stage == ScanStage.REVIEW }
        assertEquals(receipt, vm.state.value.receipt)
        assertNull(repo.transaction(receipt.transactionId))
    }
}
