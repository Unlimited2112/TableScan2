package by.tablescan

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File

/** Обёртка над Tesseract. Языковые модели лежат в assets/tessdata. */
class OcrEngine(ctx: Context, lang: String = LANG) : Closeable {

    companion object { const val LANG = "rus" }

    private val api = TessBaseAPI()

    init {
        val root = File(ctx.filesDir, "tesseract")
        val td = File(root, "tessdata")
        td.mkdirs()
        for (l in lang.split("+")) {
            val f = File(td, "$l.traineddata")
            if (!f.exists() || f.length() == 0L) {
                ctx.assets.open("tessdata/$l.traineddata").use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
            }
        }
        if (!api.init(root.absolutePath, lang)) {
            api.recycle()
            throw IllegalStateException("Не удалось инициализировать Tesseract")
        }
        api.setVariable("preserve_interword_spaces", "1")
    }

    /** Распознать содержимое одной ячейки. */
    fun cell(bmp: Bitmap): String {
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        api.setImage(bmp)
        return api.getUTF8Text() ?: ""
    }

    /** Запасной режим без сетки: слова группируются в строки и «колонки» по промежуткам. */
    fun pageRows(bmp: Bitmap): List<List<String>> {
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        api.setImage(bmp)
        api.getUTF8Text()
        class Wd(val t: String, val l: Int, val tp: Int, val r: Int, val b: Int) {
            val cy get() = (tp + b) / 2
            val hgt get() = b - tp
        }
        val words = ArrayList<Wd>()
        val iter = api.getResultIterator() ?: return emptyList()
        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
        iter.begin()
        do {
            val t = iter.getUTF8Text(level)
            val bb = iter.getBoundingBox(level)
            if (!t.isNullOrBlank() && bb != null && bb.size >= 4) words.add(Wd(t.trim(), bb[0], bb[1], bb[2], bb[3]))
        } while (iter.next(level))
        iter.delete()
        if (words.isEmpty()) return emptyList()

        val medH = words.map { w -> w.hgt }.sorted()[words.size / 2].coerceAtLeast(8)
        val rows = ArrayList<ArrayList<Wd>>()
        for (w in words.sortedBy { it.cy }) {
            val last = rows.lastOrNull()
            if (last != null && abs(last.map { it.cy }.average() - w.cy) < medH * 0.6) last.add(w)
            else rows.add(arrayListOf(w))
        }
        return rows.map { row ->
            val sorted = row.sortedBy { it.l }
            val cells = ArrayList<String>()
            var cur = StringBuilder(sorted[0].t)
            for (k in 1 until sorted.size) {
                if (sorted[k].l - sorted[k - 1].r > medH * 1.2) { cells.add(cur.toString()); cur = StringBuilder(sorted[k].t) }
                else cur.append(' ').append(sorted[k].t)
            }
            cells.add(cur.toString())
            cells
        }
    }

    private fun abs(d: Double) = if (d < 0) -d else d

    override fun close() {
        api.recycle()
    }
}
