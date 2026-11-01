package by.tablescan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

class TableResult(
    val cells: List<MutableList<String>>,
    val merges: List<XlsxWriter.Merge>,
    val gridFound: Boolean
) {
    val rows get() = cells.size
    val cols get() = cells.maxOfOrNull { it.size } ?: 0
}

object Recognizer {

    private const val DETECT_WIDTH = 1200

    fun run(ctx: Context, src: Bitmap, onProgress: (Int, Int) -> Unit): TableResult {
        // Рабочее разрешение: мелкие фото увеличиваем — Tesseract лучше читает крупный текст
        val work = ImageUtils.scaleToWidth(src, src.width.coerceIn(2000, 2400))
        val small = ImageUtils.scaleToWidth(work, DETECT_WIDTH)
        val det = TableDetector.detect(small)
        val full = TableDetector.rotate(work, -det.skew)
        val k = full.width.toFloat() / small.width

        OcrEngine(ctx).use { ocr ->
            val grid = det.grid
            if (grid == null) {
                onProgress(0, 1)
                val rows = ocr.pageRows(full).map { r -> r.map { clean(it) }.toMutableList() }
                onProgress(1, 1)
                return TableResult(rows, emptyList(), false)
            }
            val cells = List(grid.rows) { MutableList(grid.cols) { "" } }
            val merges = ArrayList<XlsxWriter.Merge>()
            val inset = (3 * k).toInt() + 3
            grid.cells.forEachIndexed { i, cb ->
                if (cb.rowSpan > 1 || cb.colSpan > 1) merges.add(XlsxWriter.Merge(cb.row, cb.col, cb.rowSpan, cb.colSpan))
                if (det.hasInk(cb)) {
                    val l = (cb.left * k).toInt() + inset
                    val t = (cb.top * k).toInt() + inset
                    val r = (cb.right * k).toInt() - inset
                    val b = (cb.bottom * k).toInt() - inset
                    if (r - l > 6 && b - t > 6) cells[cb.row][cb.col] = clean(ocr.cell(crop(full, l, t, r, b)))
                }
                onProgress(i + 1, grid.cells.size)
            }
            return TableResult(cells, merges, true)
        }
    }

    private fun crop(src: Bitmap, l: Int, t: Int, r: Int, b: Int): Bitmap {
        val x0 = l.coerceIn(0, src.width - 2)
        val y0 = t.coerceIn(0, src.height - 2)
        val x1 = r.coerceIn(x0 + 1, src.width)
        val y1 = b.coerceIn(y0 + 1, src.height)
        val part = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
        val pad = 12
        val out = Bitmap.createBitmap(part.width + 2 * pad, part.height + 2 * pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(part, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    private val junk = charArrayOf('|', '_', '[', ']', '{', '}', '—', '~', ' ', '\'', '"', '`', '‘', '’', '“', '”', '¦')
    private val digitLike = mapOf('O' to '0', 'o' to '0', 'О' to '0', 'о' to '0', 'З' to '3', 'з' to '3', 'l' to '1', 'I' to '1', 'S' to '5', 'б' to '6')

    /** Чистка результата OCR: переносы, мусор от линий сетки, буквы вместо цифр в числах. */
    fun clean(s: String): String {
        var t = s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().trim(*junk)
        if (t.none { it.isLetterOrDigit() }) return ""
        val digits = t.count { it.isDigit() }
        val letters = t.count { it.isLetter() }
        if (digits > 0 && digits >= (digits + letters) * 0.7 && t.all { it.isDigit() || it in digitLike || it in ".,% -" }) {
            t = t.map { digitLike[it] ?: it }.joinToString("").replace(" ", "")
        }
        return t
    }
}
