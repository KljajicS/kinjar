package app.kinjar.ui.projects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.io.File

internal fun reportPageLines(bottoms: List<Int>, pageHeight: Int): List<IntRange> {
    require(pageHeight > 0)
    val pages = mutableListOf<IntRange>()
    var start = 0
    var top = 0
    for (line in bottoms.indices) {
        if (bottoms[line] - top > pageHeight && line > start) {
            pages += start until line
            start = line
            top = bottoms[line - 1]
        }
    }
    if (start < bottoms.size) pages += start..bottoms.lastIndex
    return pages
}

internal fun writeProjectReportImages(report: String, directory: File, correctionLabel: String): List<File> {
    check(directory.isDirectory || directory.mkdirs())
    val accent = Color.rgb(64, 70, 180)
    val correctionInk = Color.rgb(139, 78, 20)
    val lines = report.lines()
    val title = lines.first()
    val date = lines[1]
    val body = lines.drop(2).joinToString("\n").trim()
    val text = SpannableString(body)
    val panels = mutableListOf<Pair<IntRange, Int>>()
    var offset = 0
    body.lines().forEach { line ->
        if (line.isNotBlank() && !line.startsWith("• ")) {
            val correction = line.startsWith("$correctionLabel:")
            text.setSpan(StyleSpan(Typeface.BOLD), offset, offset + line.length, 0)
            text.setSpan(ForegroundColorSpan(if (correction) correctionInk else accent), offset, offset + line.length, 0)
            panels += (offset until offset + line.length) to
                (if (correction) Color.rgb(255, 244, 225) else Color.rgb(239, 241, 255))
        }
        offset += line.length + 1
    }
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f }
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 888)
        .setLineSpacing(18f, 1f).setIncludePad(false).build()
    val pages = reportPageLines((0 until layout.lineCount).map(layout::getLineBottom), 1760)
    val files = mutableListOf<File>()
    try {
        pages.forEachIndexed { index, page ->
            val top = layout.getLineTop(page.first)
            val height = layout.getLineBottom(page.last) - top
            val bitmap = Bitmap.createBitmap(1080, height + if (pages.size == 1) 280 else 340, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val decoration = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
                canvas.drawRect(0f, 0f, 1080f, 10f, decoration)
                decoration.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                decoration.textSize = 58f
                canvas.drawText(title, 72f, 112f, decoration)
                decoration.typeface = Typeface.DEFAULT
                decoration.textSize = 30f
                decoration.textAlign = Paint.Align.RIGHT
                canvas.drawText(date, 1008f, 108f, decoration)
                decoration.textAlign = Paint.Align.LEFT
                decoration.color = Color.rgb(225, 228, 242)
                canvas.drawRect(72f, 151f, 1008f, 153f, decoration)
                canvas.save()
                canvas.clipRect(72, 188, 1008, 212 + height)
                canvas.translate(96f, 200f - top)
                panels.forEach { (range, color) ->
                    val first = layout.getLineForOffset(range.first)
                    val last = layout.getLineForOffset(range.last)
                    decoration.color = color
                    canvas.drawRoundRect(-24f, layout.getLineTop(first) - 12f, 912f,
                        layout.getLineBottom(last) + 12f, 16f, 16f, decoration)
                }
                layout.draw(canvas)
                canvas.restore()
                if (pages.size > 1) {
                    decoration.color = accent
                    decoration.textSize = 24f
                    canvas.drawText("${index + 1}/${pages.size}", 72f, height + 290f, decoration)
                }
                val file = File.createTempFile("project-report-", ".png", directory)
                files += file
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
        }
        return files
    } catch (error: Exception) {
        files.forEach { it.delete() }
        throw error
    }
}
