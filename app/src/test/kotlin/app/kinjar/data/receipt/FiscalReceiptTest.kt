package app.kinjar.data.receipt

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class FiscalReceiptTest {
    private fun fixture(name: String) = javaClass.getResource("/receipt/$name")!!.readText()
    private val html get() = fixture("sale.html")
    private val items get() = fixture("items.json")

    @Test fun `url validation preserves opaque query and rejects other destinations`() {
        val raw = "https://suf.purs.gov.rs/v/?vl=abc%2Bdef%2Fghi%3D"
        assertEquals(raw, FiscalUrl.parse(raw).toString())
        listOf(
            "http://suf.purs.gov.rs/v/?vl=a", "https://suf.purs.gov.rs.evil.test/v/?vl=a",
            "https://evil.test/v/?vl=a", "https://x@suf.purs.gov.rs/v/?vl=a",
            "https://suf.purs.gov.rs:444/v/?vl=a", "https://suf.purs.gov.rs/v/?vl=a#x",
            "https://suf.purs.gov.rs/v/?vl=a&vl=b", "https://suf.purs.gov.rs/v/?vl=",
            "https://suf.purs.gov.rs/other?vl=a", "https://suf.purs.gov.rs/v/?vl=%xx",
            "https://suf.purs.gov.rs/v/?vl=a&x=1",
        ).forEach { assertTrue(it, runCatching { FiscalUrl.parse(it) }.isFailure) }
    }

    @Test fun `observed html and specifications yield exact receipt fields`() {
        val receipt = FiscalReceiptParser.receipt("https://suf.purs.gov.rs/v/?vl=test", FiscalReceiptParser.header(html), items)
        assertEquals("123456789", receipt.issuer)
        assertEquals("Пример Čair", receipt.merchant)
        assertEquals("1500.00", receipt.total.amount.toPlainString())
        assertEquals(LocalDate.of(2023, 1, 3), receipt.date)
        assertEquals(3, receipt.items.size)
        assertEquals(receipt.id, receipt.copy(fiscalUrl = "another-url").id)
        assertNotEquals(receipt.id, receipt.copy(invoiceNumber = "ABCDEFGH-IJKLMNOP-124").id)
    }

    @Test fun `registered shop identifier falls back to business in matching journal header`() {
        val coded = html.replace("Пример Čair", "1345054-J072")
        assertEquals("Пример продавница", FiscalReceiptParser.header(coded).merchant)
        val receipt = FiscalReceiptParser.receipt("test", FiscalReceiptParser.header(coded), items)
        assertEquals("Пример продавница", receipt.merchant)
    }

    @Test fun `registered shop prefix is removed but meaningful numeric names survive`() {
        listOf("1234567-Пример Čair" to "Пример Čair", "1234567-7-Eleven" to "7-Eleven",
            "7-Eleven" to "7-Eleven", "123 Store" to "123 Store", "1234567-365" to "365").forEach { (shop, expected) ->
            assertEquals(expected, FiscalReceiptParser.header(html.replace("Пример Čair", shop)).merchant)
        }
    }

    @Test fun `unresolved coded shop stays blank for editing instead of borrowing an unrelated name`() {
        val coded = html.replace("Пример Čair", "1345054-J072")
        val withoutJournal = coded.substringBefore("<pre>") + "</body></html>"
        assertEquals("", FiscalReceiptParser.header(withoutJournal).merchant)
        assertEquals("", FiscalReceiptParser.header(coded.replace("               123456789", "               999999999")).merchant)
        assertEquals("", FiscalReceiptParser.header(coded.replace("               1345054-J072", "               Another shop")).merchant)
        val unrelatedHeader = coded.replace("               123456789", "               999999999")
        val fakeItem = unrelatedHeader.replace("</pre>", "123456789\nNot the merchant\n1345054-J072\n</pre>")
        assertEquals("", FiscalReceiptParser.header(fakeItem).merchant)
    }

    @Test fun `invalid or unsupported page fails closed`() {
        listOf(
            html.replace("ПП", "ПР"), html.replace("true", "false"),
            html.replace("Рачун је проверен", "Рачун није проверен"),
            html.replace("1.500,00", "0,00"), html.replace("1.500,00", "1.500,001"),
            html.replace("3.1.2023.", "31.2.2023."), html.replace("123456789", ""),
        ).forEach { assertTrue(runCatching { FiscalReceiptParser.header(it) }.isFailure) }
    }

    @Test fun `incomplete mismatched and invalid items cannot silently save`() {
        listOf(items.replace("true", "false"), items.replace("650", "651"),
            items.replace("\"quantity\":1", "\"quantity\":0"), "{\"success\":true,\"items\":[]}",
            items.replace("\"name\":\"Sok\"", "\"name\":\"\""),
        ).forEach { assertTrue(runCatching { FiscalReceiptParser.receipt("test", FiscalReceiptParser.header(html), it) }.isFailure) }
        val weighted = "{\"success\":true,\"items\":[{\"name\":\"Voće\",\"quantity\":0.75,\"unitPrice\":2000,\"total\":1500}]}"
        assertEquals("0.75", FiscalReceiptParser.receipt("test", FiscalReceiptParser.header(html), weighted).items.single().quantity.toPlainString())
    }

    @Test fun `http redirects and oversized bodies are rejected`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = FiscalReceiptClient()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
            assertTrue(runCatching { client.read(Request.Builder().url(server.url("/")).build()) }.exceptionOrNull() is IOException)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)))
            assertTrue(runCatching { client.read(Request.Builder().url(server.url("/")).build()) }.exceptionOrNull() is IOException)
        }
    }

    @Test fun `cancellation stops pending request and timeout is surfaced`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val http = FiscalReceiptClient.defaultClient().newBuilder().callTimeout(100, TimeUnit.MILLISECONDS).build()
            val client = FiscalReceiptClient(http)
            server.enqueue(MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setBody("late"))
            assertTrue(runCatching { client.read(Request.Builder().url(server.url("/")).build()) }.exceptionOrNull() is IOException)
            server.enqueue(MockResponse().setBodyDelay(1, TimeUnit.SECONDS).setBody("late"))
            val cancellableClient = FiscalReceiptClient()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                cancellableClient.read(Request.Builder().url(server.url("/")).build())
            }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
    }
}
