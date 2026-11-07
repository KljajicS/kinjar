package app.kinjar.data.rates

import org.jsoup.Jsoup
import app.kinjar.domain.Currency
import app.kinjar.domain.parseRate
import app.kinjar.domain.validRate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class NbsRateList(val date: LocalDate, val rates: Map<Currency, BigDecimal>)

object NbsParser {
    fun parse(html: String): NbsRateList {
        val document = Jsoup.parse(html)
        val dates = document.select("h6").flatMap { Regex("\\b\\d{1,2}\\.\\d{1,2}\\.\\d{4}\\.").findAll(it.text()).map { match -> match.value }.toList() }
        require(dates.size == 1)
        val date = LocalDate.parse(dates.single(), DateTimeFormatter.ofPattern("d.M.uuuu.").withResolverStyle(ResolverStyle.STRICT))
        val rates = mutableMapOf<Currency, BigDecimal>()
        for (row in document.select("table tbody tr")) {
            val cells = row.select("td").map { it.text().trim() }
            val currency = when (cells.firstOrNull()) { "EUR" -> Currency.EUR; "USD" -> Currency.USD; else -> continue }
            require(cells.size == 5 && currency !in rates)
            require(cells[1] == if (currency == Currency.EUR) "978" else "840")
            val unit = cells[3].toIntOrNull()
            require(unit in setOf(1, 10, 100, 1000))
            require(Regex("[0-9]{1,7}(,[0-9]{1,8})?").matches(cells[4]))
            val published = requireNotNull(parseRate(cells[4].replace(',', '.')))
            val rate = published.divide(BigDecimal(unit!!)).stripTrailingZeros()
            require(validRate(rate))
            rates[currency] = rate
        }
        require(rates.keys == setOf(Currency.EUR, Currency.USD))
        return NbsRateList(date, rates.toMap())
    }
}
