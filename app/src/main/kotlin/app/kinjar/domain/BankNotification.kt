package app.kinjar.domain

import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

enum class BankApp(val packageName: String) {
    RAIFFEISEN("rs.Raiffeisen.mobile"),
    YETTEL("rs.yettelbank.app"),
}

data class ReportedBankBalance(val amount: BigDecimal, val currency: Currency, val available: Boolean)

data class BankNotification(
    val direction: TransactionType?,
    val money: Money?,
    val counterparty: String?,
    val date: LocalDate?,
    val transactionTime: LocalDateTime?,
    val balances: List<ReportedBankBalance>,
    val cashWithdrawal: Boolean = false,
)

fun Transaction.bankMovement(accountId: String): Money? = when {
    this.accountId == accountId -> if (type == TransactionType.INCOME || type == TransactionType.LOAN_RECEIPT) money else -money
    toAccountId == accountId -> received ?: money
    else -> null
}

object BankNotificationParser {
    private val datePattern = Regex("""\b(\d{2}\.\d{2}\.\d{4})(?:\s+(\d{2}:\d{2}:\d{2}))?""")
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    private const val amount = """([+-]?[\d.,]+(?:[ \u00a0]\d{3})*)\s+(RSD|EUR|USD)\b"""
    private val amountLine = Regex("""(?i)\biznos:\s*$amount""")
    private val quotedAmount = Regex(amount)
    private val namedCounterparty = Regex("""(?im)^\s*(?:mesto|platilac):\s*([^\r\n]+)""")
    private val yettelPurchase = Regex("""(?is)^\s*karticom\s+[^,]+,\s*iznos:\s*$amount,\s*\d{2}\.\d{2}\.\d{4},\s*(.+)""")
    private val cardBalance = Regex("""(?im)^\s*stanje:\s*([+-]?[\d.,]+(?:[ \u00a0]\d{3})*)\s*$""")
    private val available = Regex("""(?i)trenutno raspolo[zž]ivo:\s*""")
    private val sensitive = Regex("""\b(otp|3ds|lozinka|pin|jednokratn\w*|verifikacion\w*|autorizacion\w*)\b|potvrdite transakciju""")
    private val transactionCue = Regex("""\b(iznos|uplata|isplata|odliv|priliv|prenos|transfer|bankomat|atm|transakcij\w*|kartic\w*)\b""")
    private val cash = Regex("""\b(bankomat\w*|atm|podizanje gotovine)\b""")
    private val marks = Regex("\\p{M}")

    fun shouldCapture(title: String, body: String): Boolean {
        val text = plain("$title $body")
        return !sensitive.containsMatchIn(text) && transactionCue.containsMatchIn(text) &&
            (amountLine.containsMatchIn(body) || datePattern.containsMatchIn(body) || cash.containsMatchIn(text))
    }

    fun parse(bank: BankApp, title: String, body: String): BankNotification {
        val text = plain(body)
        val cardUse = bank == BankApp.RAIFFEISEN && text.trimStart().startsWith("koriscenje kartice ")
        val incoming = bank == BankApp.RAIFFEISEN && text.trimStart().startsWith("imate uplatu po racunu ")
        val purchase = if (bank == BankApp.YETTEL && plain(title).trim() == "transakcija je izvrsena") yettelPurchase.find(body) else null
        val amountMatch = amountLine.find(body)
        val dateMatch = datePattern.find(body)
        val date = dateMatch?.let { runCatching { LocalDate.parse(it.groupValues[1], dateFormat) }.getOrNull() }
        val time = dateMatch?.groups?.get(2)?.value?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val money = amountMatch?.let {
            decimal(it.groupValues[1], commaDecimal = purchase == null, scale = 2)?.takeIf { it.signum() > 0 }
                ?.let { value -> Money.of(value, Currency.valueOf(it.groupValues[2].uppercase(Locale.ROOT))) }
        }
        val balances = when {
            cardUse && money != null -> listOfNotNull(cardBalance.find(body)?.groupValues?.get(1)
                ?.let { decimal(it, commaDecimal = true, scale = 12) }
                ?.let { ReportedBankBalance(it, money.currency, available = false) })
            bank == BankApp.YETTEL -> available.find(body)?.let { label ->
                quotedAmount.findAll(body.substring(label.range.last + 1)).mapNotNull {
                    decimal(it.groupValues[1], commaDecimal = true, scale = 12)?.let { value ->
                        ReportedBankBalance(value, Currency.valueOf(it.groupValues[2]), available = true)
                    }
                }.toList()
            }.orEmpty()
            else -> emptyList()
        }
        val withdrawal = cash.containsMatchIn(text)
        val counterparty = namedCounterparty.find(body)?.groupValues?.get(1)?.trim()
            ?: purchase?.groupValues?.get(3)?.let { available.split(it, limit = 2).first().trim().trimEnd('.').trim() }
        return BankNotification(
            when {
                withdrawal -> TransactionType.TRANSFER
                incoming -> TransactionType.INCOME
                cardUse || purchase != null -> TransactionType.EXPENSE
                else -> null
            },
            money, counterparty?.takeIf(String::isNotBlank), date, if (date != null && time != null) date.atTime(time) else null,
            balances, withdrawal,
        )
    }

    private fun decimal(raw: String, commaDecimal: Boolean, scale: Int): BigDecimal? {
        val mark = if (commaDecimal) ',' else '.'
        val group = if (commaDecimal) '.' else ','
        val pattern = Regex("""[+-]?(?:\d+|\d{1,3}(?:[\Q$group\E \u00a0]\d{3})+)(?:\Q$mark\E\d{1,$scale})?""")
        if (raw.length > 40 || !pattern.matches(raw)) return null
        return raw.filterNot { it == group || it == ' ' || it == '\u00a0' }.replace(mark, '.').toBigDecimalOrNull()
    }

    private fun plain(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(marks, "").lowercase(Locale.ROOT)
}
