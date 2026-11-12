package app.kinjar.data

import app.kinjar.data.rates.NbsHistoricalClient
import app.kinjar.data.rates.NbsParser
import app.kinjar.data.rates.NbsRateList
import app.kinjar.data.rates.RateLookup
import app.kinjar.domain.Currency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ProjectRateLookupTest {
    private val fixture = javaClass.getResource("/nbs-middle-2026-10-15.html")!!.readText()
    private val friday = LocalDate.of(2026, 11, 6)
    private val sunday = LocalDate.of(2026, 11, 8)
    private val today = LocalDate.of(2026, 11, 12)

    private fun page(date: String) = fixture.replace("15.10.2026.", date).replace("117,3931", "117,6089")
    private fun list(date: LocalDate) = NbsParser.parse(page("${date.dayOfMonth}.${date.monthValue}.${date.year}."))

    private fun client(now: LocalDate = today, urls: MutableList<String> = mutableListOf(), respond: suspend (Int) -> NbsRateList) =
        NbsHistoricalClient("https://nbs.test/IndexByDate", { now }, { url ->
            urls += url
            val call = urls.size
            RateLookup { respond(call) }
        })

    @Test fun `weekend request sends official query and returns effective Friday list`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(page("6.11.2026.")))
            val lookup = NbsHistoricalClient(server.url("/ExchangeRateWebApp/ExchangeRate/IndexByDate").toString(), { today })
            val quote = lookup.fetch(sunday)
            val request = server.takeRequest().requestUrl!!
            assertEquals("/ExchangeRateWebApp/ExchangeRate/IndexByDate", request.encodedPath)
            assertEquals("08.11.2026.", request.queryParameter("Date"))
            assertEquals("3", request.queryParameter("ExchangeRateListTypeID"))
            assertEquals("true", request.queryParameter("isSearchExecuted"))
            assertEquals(friday, quote.date)
            assertEquals(BigDecimal("117.6089"), quote.rates[Currency.EUR])
            assertEquals("NBS middle rate", quote.source)
            assertEquals(quote, lookup.fetch(sunday))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `holiday request accepts previous business day list`() = runTest {
        val holiday = LocalDate.of(2026, 11, 11)
        assertEquals(LocalDate.of(2026, 11, 10), client { list(LocalDate.of(2026, 11, 10)) }.fetch(holiday).date)
    }

    @Test fun `future request is rejected without network`() = runTest {
        val urls = mutableListOf<String>()
        val lookup = client(urls = urls) { list(today) }
        assertTrue(runCatching { lookup.fetch(today.plusDays(1)) }.isFailure)
        assertTrue(runCatching { lookup.fetch(LocalDate.of(1899, 12, 31)) }.isFailure)
        assertEquals(emptyList<String>(), urls)
    }

    @Test fun `response dated after request or wildly stale is rejected and not cached`() = runTest {
        val urls = mutableListOf<String>()
        val lookup = client(urls = urls) { call ->
            when (call) {
                1 -> list(sunday.plusDays(1))
                2 -> list(sunday.minusDays(15))
                3 -> list(sunday.minusDays(14))
                else -> error("unexpected")
            }
        }
        assertTrue(runCatching { lookup.fetch(sunday) }.isFailure)
        assertTrue(runCatching { lookup.fetch(sunday) }.isFailure)
        assertEquals(sunday.minusDays(14), lookup.fetch(sunday).date)
        assertEquals(sunday.minusDays(14), lookup.fetch(sunday).date)
        assertEquals(3, urls.size)
    }

    @Test fun `failures are never cached and dates cache independently`() = runTest {
        val urls = mutableListOf<String>()
        val lookup = client(urls = urls) { call -> if (call == 1) error("offline") else list(friday) }
        assertTrue(runCatching { lookup.fetch(sunday) }.isFailure)
        assertEquals(friday, lookup.fetch(sunday).date)
        assertEquals(friday, lookup.fetch(sunday).date)
        assertEquals(friday, lookup.fetch(friday).date)
        assertEquals(3, urls.size)
        assertTrue(urls[2].contains("Date=06.11.2026."))
    }

    @Test fun `unparseable official page fails`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("<html><h6>maintenance</h6></html>"))
            val lookup = NbsHistoricalClient(server.url("/").toString(), { today })
            assertTrue(runCatching { lookup.fetch(sunday) }.isFailure)
        }
    }

    @Test fun `today provisional previous list is not cached but published list is`() = runTest {
        val urls = mutableListOf<String>()
        val lookup = client(urls = urls) { call -> if (call == 1) list(today.minusDays(1)) else list(today) }
        assertEquals(today.minusDays(1), lookup.fetch(today).date)
        assertEquals(today, lookup.fetch(today).date)
        assertEquals(today, lookup.fetch(today).date)
        assertEquals(2, urls.size)
    }

    @Test fun `cancelled lookup releases lock and caches nothing`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val urls = mutableListOf<String>()
        val lookup = client(urls = urls) { call ->
            if (call == 1) {
                entered.complete(Unit)
                CompletableDeferred<NbsRateList>().await()
            }
            list(friday)
        }
        val running = async(Dispatchers.Default) { lookup.fetch(sunday) }
        entered.await()
        running.cancel()
        running.join()
        assertEquals(friday, lookup.fetch(sunday).date)
        assertEquals(2, urls.size)
    }
}
