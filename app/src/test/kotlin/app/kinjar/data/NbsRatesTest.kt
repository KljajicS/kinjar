package app.kinjar.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
import app.kinjar.data.rates.*
import app.kinjar.domain.Currency
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class NbsRatesTest {
    private val fixture = javaClass.getResource("/nbs-middle-2026-10-15.html")!!.readText()

    @Test fun `captured public list parses by currency code and normalizes units`() {
        val parsed = NbsParser.parse(fixture)
        assertEquals(LocalDate.of(2026, 10, 15), parsed.date)
        assertEquals(BigDecimal("117.3931"), parsed.rates[Currency.EUR])
        assertEquals(BigDecimal("104.6564"), parsed.rates[Currency.USD])
        val doc = Jsoup.parse(fixture)
        val cells = doc.select("tr").first { it.select("td").firstOrNull()?.text() == "EUR" }.select("td")
        cells[3].text("100")
        cells[4].text("11739,31")
        assertEquals(parsed, NbsParser.parse(doc.html()))
    }

    @Test fun `live list dates without leading zeros parse`() {
        assertEquals(LocalDate.of(2026, 11, 6), NbsParser.parse(fixture.replace("15.10.2026.", "6.11.2026.")).date)
        assertEquals(LocalDate.of(2027, 1, 5), NbsParser.parse(fixture.replace("15.10.2026.", "5.1.2027.")).date)
        assertEquals(LocalDate.of(2026, 11, 6), NbsParser.parse(fixture.replace("15.10.2026.", "06.11.2026.")).date)
    }

    @Test fun `malformed incomplete duplicate and invalid lists are rejected`() {
        val bad = listOf(
            fixture.replace("15.10.2026.", "31.02.2026."),
            fixture.replace("15.10.2026.", "31.2.2026."),
            fixture.replace("15.10.2026.", "115.10.2026."),
            fixture.replace("<td>USD</td>", "<td>XXX</td>"),
            fixture.replace("117,3931", "0"),
            fixture.replace("117,3931", "-117,3931"),
            fixture.replace("117,3931", "1000001"),
            fixture.replace("<td>978</td>", "<td>840</td>"),
            Jsoup.parse(fixture).also { d -> d.select("tbody").first()!!.appendChild(d.select("tbody tr").first()!!.clone()) }.html(),
            Jsoup.parse(fixture).also { d -> d.select("tbody tr").first()!!.select("td")[3].text("0") }.html(),
            "<html>maintenance</html>",
        )
        bad.forEachIndexed { i, html -> assertTrue("case $i", runCatching { NbsParser.parse(html) }.isFailure) }
    }

    @Test fun `client accepts fixture and rejects redirects errors and oversized responses`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = NbsClient(server.url("/").toString())
            server.enqueue(MockResponse().setBody(fixture))
            assertEquals(NbsParser.parse(fixture), client.fetch())
            for (response in listOf(
                MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")),
                MockResponse().setResponseCode(500),
                MockResponse().setBody("x".repeat(256 * 1024 + 1)),
                MockResponse().setBody("maintenance"),
            )) {
                server.enqueue(response)
                assertTrue(runCatching { client.fetch() }.isFailure)
            }
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun `refresh boundary uses Belgrade including DST and failure cooldown`() {
        val before = Instant.parse("2026-10-15T05:59:59Z")
        val boundary = before.plusSeconds(1)
        val yesterday = Instant.parse("2026-10-14T06:00:00Z")
        assertFalse(RateRefresh.due(before, yesterday, yesterday))
        assertTrue(RateRefresh.due(boundary, yesterday, yesterday))
        assertFalse(RateRefresh.due(boundary, yesterday, boundary.minusSeconds(3599)))
        assertTrue(RateRefresh.due(boundary, yesterday, boundary.minusSeconds(3600)))
        assertFalse(RateRefresh.due(boundary, boundary, null))
        assertTrue(RateRefresh.due(Instant.parse("2026-10-25T07:00:00Z"), Instant.parse("2026-10-24T06:00:00Z"), null))
    }

    @Test fun `refresh is single flight with offline retention cooldown and explicit retry`() = runTest {
        val clock = FixedClock(Instant.parse("2026-10-15T10:00:00Z"), ZoneId.of("Pacific/Honolulu"))
        val db = testDb()
        val repo = RateRepository(db, EventStore(db, "local", clock, Projector(db)), clock)
        val list = NbsParser.parse(fixture)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var fail = false
        val attempts = object : RateAttempts { override var lastAttempt: Instant? = null }
        val refresh = RateRefresh(repo, RateLookup {
            calls++
            entered.complete(Unit)
            release.await()
            if (fail) error("offline")
            list
        }, attempts, clock)
        val first = async(Dispatchers.Default) { refresh.refresh(true) }
        entered.await()
        refresh.refresh(true)
        assertEquals(1, calls)
        release.complete(Unit)
        first.await()
        val cached = repo.snapshots.first()
        assertEquals(2, cached.cached.size)
        clock.instant = clock.instant.plusSeconds(86400)
        fail = true
        refresh.refresh()
        assertTrue(refresh.state.value.failed)
        refresh.refresh()
        assertEquals(2, calls)
        refresh.refresh(true)
        assertEquals(3, calls)
        assertEquals(cached, repo.snapshots.first())
    }

    @Test fun `interrupted refresh keeps cache and does not start failure cooldown`() = runTest {
        val clock = FixedClock(Instant.parse("2026-10-15T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
        val db = testDb()
        val repo = RateRepository(db, EventStore(db, "local", clock, Projector(db)), clock)
        val list = NbsParser.parse(fixture)
        repo.cache(list.rates, list.date)
        val cached = repo.snapshots.first()
        val entered = CompletableDeferred<Unit>()
        val attempts = object : RateAttempts { override var lastAttempt: Instant? = null }
        val refresh = RateRefresh(repo, RateLookup { entered.complete(Unit); CompletableDeferred<NbsRateList>().await() }, attempts, clock)
        val running = async(Dispatchers.Default) { refresh.refresh(true) }
        entered.await()
        running.cancel()
        running.join()
        assertNull(attempts.lastAttempt)
        assertEquals(RateRefreshState(), refresh.state.value)
        assertEquals(cached, repo.snapshots.first())
    }
}
