package app.kinjar.data.receipt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.Receipt
import app.kinjar.domain.ReceiptItem
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

data class FiscalHeader(
    val issuer: String, val invoiceNumber: String, val merchant: String,
    val date: LocalDate, val total: Money, val token: String,
)

class UnsupportedReceipt : IllegalArgumentException("Unsupported receipt")

object FiscalReceiptParser {
    fun header(html: String): FiscalHeader {
        val doc = Jsoup.parse(html)
        fun field(id: String) = requireNotNull(doc.getElementById(id)).ownText().trim().also {
            require(it.isNotBlank() && it.length <= 500)
        }
        require(field("invoiceStatusLabel") in setOf("Рачун је проверен", "Invoice is verified"))
        if (field("invoiceCounterExtensionLabel") !in setOf("ПП", "NS")) throw UnsupportedReceipt()
        val scripts = doc.select("script:not([src])").joinToString("\n") { it.data() }
        require(Regex("viewModel\\.IsNormalSale\\(true\\)").containsMatchIn(scripts))
        fun argument(name: String): String = requireNotNull(
            Regex("viewModel\\.$name\\('([A-Za-z0-9-]+)'\\)").find(scripts)
        ).groupValues[1]
        val invoice = field("invoiceNumberLabel")
        require(invoice.matches(Regex("[A-Z0-9]{8}-[A-Z0-9]{8}-[0-9]{1,20}")))
        require(argument("InvoiceNumber") == invoice)
        val token = argument("Token")
        require(token.matches(Regex("[a-fA-F0-9-]{36}")))
        val issuer = field("tinLabel").also { require(it.matches(Regex("[0-9]{9}"))) }
        val totalText = field("totalAmountLabel")
        val serbian = doc.selectFirst("html")?.attr("lang")?.startsWith("sr") == true
        val pattern = if (serbian) "(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+),[0-9]{2}" else "(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)\\.[0-9]{2}"
        require(totalText.matches(Regex(pattern)))
        val total = if (serbian) totalText.replace(".", "").replace(',', '.') else totalText.replace(",", "")
        val datePattern = if (serbian) "d.M.uuuu. H:mm:ss" else "M/d/uuuu h:mm:ss a"
        val date = LocalDateTime.parse(field("sdcDateTimeLabel"), DateTimeFormatter.ofPattern(datePattern, Locale.US)
            .withResolverStyle(ResolverStyle.STRICT)).toLocalDate()
        val money = Money.of(BigDecimal(total).setScale(2, RoundingMode.UNNECESSARY), Currency.RSD)
        require(!money.isZero && !money.isNegative)
        val shop = field("shopFullNameLabel")
        val registeredShop = Regex("[0-9]{7}-(.+)").matchEntire(shop)?.groupValues?.get(1)
        val name = registeredShop ?: shop
        val codeOnly = name.matches(Regex("[A-Z]{1,4}[0-9]{2,6}"))
        val business = doc.select("pre").mapNotNull { pre ->
            val lines = pre.wholeText().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            // The journal header binds the business name to this issuer and registered shop.
            lines.take(4).windowed(3).firstOrNull { it[0] == issuer && it[2] == shop }
                ?.get(1)?.takeIf { it.length <= 500 && it != shop && it != issuer }
        }.firstOrNull()
        val merchant = if (registeredShop != null && codeOnly) business.orEmpty() else name
        return FiscalHeader(issuer, invoice, merchant, date, money, token)
    }

    fun receipt(url: String, header: FiscalHeader, specifications: String): Receipt {
        val root = Json.parseToJsonElement(specifications).jsonObject
        require(root["success"]?.jsonPrimitive?.booleanOrNull == true)
        val rows = requireNotNull(root["items"]).jsonArray
        require(rows.size in 1..1000)
        val items = rows.map { row ->
            val obj = row.jsonObject
            fun decimal(key: String): BigDecimal {
                val value = requireNotNull(obj[key]).jsonPrimitive.content
                require(value.length <= 30 && value.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
                return BigDecimal(value)
            }
            val name = requireNotNull(obj["name"]).jsonPrimitive.content
            require(name.isNotBlank() && name.length <= 500)
            val quantity = decimal("quantity").also { require(it.signum() > 0) }
            ReceiptItem(name, quantity, decimal("unitPrice"), decimal("total"))
        }
        require(items.fold(BigDecimal.ZERO) { sum, item -> sum + item.total }.compareTo(header.total.amount) == 0)
        return Receipt(url, header.issuer, header.invoiceNumber, header.merchant, header.date, header.total, items)
    }
}
