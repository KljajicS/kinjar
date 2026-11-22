package app.kinjar.ui.add

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.text.DecimalFormatSymbols
import java.util.Locale

class AmountVisualTransformation(locale: Locale) : VisualTransformation {
    private val symbols = DecimalFormatSymbols(locale)

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val integerEnd = raw.indexOf('.').let { if (it < 0) raw.length else it }
        val positions = IntArray(raw.length + 1)
        val formatted = buildString {
            if (raw.startsWith('.')) append('0')
            raw.forEachIndexed { index, char ->
                if (index > 0 && index < integerEnd && (integerEnd - index) % 3 == 0) append(symbols.groupingSeparator)
                positions[index] = length
                append(if (char == '.') symbols.decimalSeparator else char)
            }
            positions[raw.length] = length
            if (raw.isNotEmpty()) {
                if ('.' !in raw) append(symbols.decimalSeparator)
                val decimals = raw.substringAfter('.', "").length
                repeat((2 - decimals).coerceAtLeast(0)) { append('0') }
            }
        }
        return TransformedText(AnnotatedString(formatted), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = positions[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int): Int = positions.indexOfLast { it <= offset }.coerceAtLeast(0)
        })
    }
}
