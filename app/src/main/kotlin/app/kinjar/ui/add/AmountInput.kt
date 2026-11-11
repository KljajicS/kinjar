package app.kinjar.ui.add

import java.math.BigDecimal

data class AmountInput(val text: String = "") {
    val value: BigDecimal?
        get() = text.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    fun edit(input: String): AmountInput {
        val normalized = input.replace(',', '.')
        if (normalized.length > MAX || !normalized.matches(Regex("[0-9]*(\\.[0-9]{0,2})?"))) return this
        return copy(text = normalized)
    }

    fun press(key: Char): AmountInput = when {
        key == '<' -> copy(text = text.dropLast(1))
        text.length >= MAX -> this
        key == '.' -> if ('.' in text) this else copy(text = text.ifEmpty { "0" } + ".")
        key.isDigit() -> {
            val decimals = text.substringAfter('.', "")
            when {
                '.' in text && decimals.length >= 2 -> this
                text == "0" -> copy(text = key.toString())
                else -> copy(text = text + key)
            }
        }
        else -> this
    }

    private companion object { const val MAX = 12 }
}
