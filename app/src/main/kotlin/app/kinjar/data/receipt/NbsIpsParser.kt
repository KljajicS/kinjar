package app.kinjar.data.receipt

import app.kinjar.domain.Currency
import app.kinjar.domain.Money

data class PaymentSlip(
    val recipient: String,
    val account: String,
    val amount: Money,
    val paymentCode: String,
    val purpose: String?,
    val reference: String?,
    val payer: String?,
) {
    val merchant: String get() = recipient.lineSequence().first().trim()
    val note: String get() = listOfNotNull(purpose, reference?.let { "${it.take(2)} ${it.drop(2)}" }).joinToString("\n")
}

class UnsupportedPaymentSlip : IllegalArgumentException("Unsupported payment slip")

object NbsIpsParser {
    fun parse(raw: String): PaymentSlip {
        require(raw.length in 1..1024 && raw.toByteArray(Charsets.UTF_8).size <= 1024)
        val fields = mutableMapOf<String, String>()
        raw.split('|').forEach { field ->
            val split = field.indexOf(':')
            require(split > 0 && split < field.lastIndex)
            val tag = field.substring(0, split)
            val value = field.substring(split + 1)
            require(fields.put(tag, value) == null)
        }
        require(fields.keys.containsAll(setOf("K", "V", "C", "R", "N", "I", "SF")))
        if (fields["K"] != "PR" || fields["V"] != "01" || fields["C"] != "1" ||
            fields.keys.any { it !in setOf("K", "V", "C", "R", "N", "I", "SF", "P", "S", "RO") }) {
            throw UnsupportedPaymentSlip()
        }
        fun text(tag: String, max: Int, maxLines: Int): String? = fields[tag]?.also { value ->
            require(value.length <= max && value.isNotBlank())
            require(value.none { it.isISOControl() && it != '\n' && it != '\r' })
            val lines = value.split(Regex("\r\n|\n"))
            require(lines.size <= maxLines && lines.all { it.isNotBlank() && '\r' !in it })
        }
        val recipient = requireNotNull(text("N", 70, 3))
        val payer = text("P", 70, 3)
        val purpose = text("S", 35, 1)
        val account = fields.getValue("R").also { require(it.matches(Regex("[0-9]{18}"))) }
        val paymentCode = fields.getValue("SF").also { require(it.matches(Regex("[12][0-9]{2}"))) }
        val amountText = fields.getValue("I")
        if (!amountText.startsWith("RSD")) throw UnsupportedPaymentSlip()
        require(amountText.length in 5..18 && amountText.matches(Regex("RSD[0-9]+,[0-9]{0,2}")))
        val amount = Money.of(amountText.removePrefix("RSD").replace(',', '.'), Currency.RSD)
        require(!amount.isZero && !amount.isNegative)
        val reference = fields["RO"]?.also {
            require(it.length in 3..25 && it.matches(Regex("[0-9]{2}[A-Za-z0-9-]+")))
            if (it.startsWith("97")) require(it.drop(2).all { digit -> digit in '0'..'9' })
        }
        return PaymentSlip(recipient, account, amount, paymentCode, purpose, reference, payer)
    }
}
