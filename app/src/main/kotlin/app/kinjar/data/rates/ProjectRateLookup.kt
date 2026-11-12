package app.kinjar.data.rates

import app.kinjar.domain.Currency
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class ProjectRateQuote(val date: LocalDate, val rates: Map<Currency, BigDecimal>, val source: String)

fun interface ProjectRateLookup {
    suspend fun fetch(date: LocalDate): ProjectRateQuote
}

class NbsHistoricalClient internal constructor(
    private val endpoint: String,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Europe/Belgrade")) },
    private val lookup: (String) -> RateLookup = { NbsClient(it) },
) : ProjectRateLookup {
    constructor() : this("https://webappcenter.nbs.rs/ExchangeRateWebApp/ExchangeRate/IndexByDate")

    private val mutex = Mutex()
    private val cache = linkedMapOf<LocalDate, ProjectRateQuote>()

    override suspend fun fetch(date: LocalDate): ProjectRateQuote = mutex.withLock {
        val now = today()
        require(date.year >= 1900 && !date.isAfter(now)) { "Historical rate unavailable for expense date" }
        cache[date]?.let { return@withLock it }
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("Date", date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu.")))
            .addQueryParameter("ExchangeRateListTypeID", "3")
            .addQueryParameter("isSearchExecuted", "true")
            .build()
        val result = lookup(url.toString()).fetch()
        require(!result.date.isAfter(date) && ChronoUnit.DAYS.between(result.date, date) <= 14) {
            "Historical rate unavailable for expense date"
        }
        val quote = ProjectRateQuote(result.date, result.rates, "NBS middle rate")
        // Before the morning publication a lookup for today returns the previous list, which is not final.
        if (date.isBefore(now) || result.date == date) {
            if (cache.size >= 200) cache.remove(cache.keys.first())
            cache[date] = quote
        }
        quote
    }
}
