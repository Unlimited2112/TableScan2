package by.tablescan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/** Ячейка таблицы (с учётом объединения) в координатах выровненного изображения. */
data class CellBox(
    val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int,
    val left: Int, val top: Int, val right: Int, val bottom: Int
)

data class Grid(val rows: Int, val cols: Int, val cells: List<CellBox>)

class Detection(
    val skew: Double,
    val grid: Grid?,
    private val bin: BooleanArray?,
    private val w: Int,
    private val h: Int
) {
    /** Есть ли в ячейке «чернила» (чтобы не гонять OCR по пустым ячейкам). */
    fun hasInk(c: CellBox, inset: Int = 3): Boolean {
        val b = bin ?: return true
        val x0 = (c.left + inset).coerceAtLeast(0)
        val x1 = (c.right - inset).coerceAtMost(w - 1)
        val y0 = (c.top + inset).coerceAtLeast(0)
        val y1 = (c.bottom - inset).coerceAtMost(h - 1)
        if (x1 <= x0 || y1 <= y0) return false
        var n = 0
        for (y in y0..y1) for (x in x0..x1) if (b[y * w + x]) n++
        return n >= 6 && n >= (x1 - x0 + 1) * (y1 - y0 + 1) * 0.003
    }
}

/**
 * Поиск сетки таблицы по линиям: адаптивная бинаризация → маски длинных
 * горизонтальных/вертикальных отрезков → выравнивание наклона → проекции.
 */
object TableDetector {

    private class Gray(val w: Int, val h: Int, val px: IntArray)

    private fun toGray(bmp: Bitmap): Gray {
        val w = bmp.width
        val h = bmp.height
        val p = IntArray(w * h)
        bmp.getPixels(p, 0, w, 0, 0, w, h)
        for (i in p.indices) {
            val c = p[i]
            p[i] = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
        }
        return Gray(w, h, p)
    }

    /** true = тёмный пиксель (пиксель темнее локального среднего на c). */
    private fun binarize(g: Gray, win: Int = 31, c: Int = 12): BooleanArray {
        val w = g.w
        val h = g.h
        val w1 = w + 1
        val integ = LongArray(w1 * (h + 1))
        for (y in 0 until h) {
            var s = 0L
            for (x in 0 until w) {
                s += g.px[y * w + x]
                integ[(y + 1) * w1 + x + 1] = integ[y * w1 + x + 1] + s
            }
        }
        val r = win / 2
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                val sum = integ[(y1 + 1) * w1 + x1 + 1] - integ[y0 * w1 + x1 + 1] -
                        integ[(y1 + 1) * w1 + x0] + integ[y0 * w1 + x0]
                val cnt = ((y1 - y0 + 1) * (x1 - x0 + 1)).toLong()
                out[y * w + x] = g.px[y * w + x].toLong() * cnt < sum - c * cnt
            }
        }
        return out
    }

    private fun horizontalMask(b: BooleanArray, w: Int, h: Int, minLen: Int): BooleanArray {
        val m = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var x = 0
            while (x < w) {
                if (!b[row + x]) { x++; continue }
                var end = x
                var gap = 0
                var k = x
                while (k < w) {
                    if (b[row + k]) { end = k; gap = 0 } else { gap++; if (gap > 2) break }
                    k++
                }
                if (end - x + 1 >= minLen) for (i in x..end) m[row + i] = true
                x = end + 1
            }
        }
        return m
    }

    private fun verticalMask(b: BooleanArray, w: Int, h: Int, minLen: Int): BooleanArray {
        val m = BooleanArray(w * h)
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!b[y * w + x]) { y++; continue }
                var end = y
                var gap = 0
                var k = y
                while (k < h) {
                    if (b[k * w + x]) { end = k; gap = 0 } else { gap++; if (gap > 2) break }
                    k++
                }
                if (end - y + 1 >= minLen) for (i in y..end) m[i * w + x] = true
                y = end + 1
            }
        }
        return m
    }

    /** Угол наклона (градусы), при котором горизонтальные линии дают самую «острую» проекцию. */
    private fun estimateSkew(hm: BooleanArray, w: Int, h: Int): Double {
        var n = 0
        for (y in 0 until h) { var x = 0; while (x < w) { if (hm[y * w + x]) n++; x += 3 } }
        if (n < 100) return 0.0
        val px = IntArray(n)
        val py = IntArray(n)
        var i = 0
        for (y in 0 until h) { var x = 0; while (x < w) { if (hm[y * w + x]) { px[i] = x; py[i] = y; i++ }; x += 3 } }
        val off = (w * 0.1).toInt() + 2
        val prof = IntArray(h + 2 * off)
        var bestA = 0.0
        var bestS = -1L
        var step = 0
        while (step <= 100) {
            val a = -5.0 + step * 0.1
            java.util.Arrays.fill(prof, 0)
            val t = tan(Math.toRadians(a))
            for (j in 0 until n) {
                val yy = (py[j] - px[j] * t).roundToInt() + off
                if (yy >= 0 && yy < prof.size) prof[yy]++
            }
            var s = 0L
            for (v in prof) s += v.toLong() * v
            if (s > bestS) { bestS = s; bestA = a }
            step++
        }
        return bestA
    }

    /** Поворот вокруг центра (по часовой стрелке при deg > 0), углы заливаются белым. */
    fun rotate(src: Bitmap, deg: Double): Bitmap {
        if (abs(deg) < 0.05) return src
        val m = Matrix()
        m.setRotate(deg.toFloat(), src.width / 2f, src.height / 2f)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun peaks(p: IntArray, thr: Int, merge: Int): List<Int> {
        if (thr <= 0) return emptyList()
        val res = ArrayList<Int>()
        var i = 0
        while (i < p.size) {
            if (p[i] < thr) { i++; continue }
            var j = i
            var last = i
            var ws = 0L
            var wt = 0L
            while (j < p.size && j - last <= merge) {
                if (p[j] >= thr) { last = j; ws += j.toLong() * p[j]; wt += p[j] }
                j++
            }
            res.add((ws / max(1L, wt)).toInt())
            i = last + 1
        }
        return res
    }

    private fun largestCluster(lines: List<Int>, maxGap: Int): List<Int> {
        if (lines.isEmpty()) return lines
        var best: List<Int> = listOf(lines[0])
        var cur = arrayListOf(lines[0])
        for (k in 1 until lines.size) {
            if (lines[k] - lines[k - 1] <= maxGap) cur.add(lines[k])
            else { if (cur.size > best.size) best = cur; cur = arrayListOf(lines[k]) }
        }
        if (cur.size > best.size) best = cur
        return best
    }

    fun detect(input: Bitmap): Detection {
        var g = toGray(input)
        var bin = binarize(g)
        val minH = max(30, g.w / 25)
        val skew = estimateSkew(horizontalMask(bin, g.w, g.h, minH), g.w, g.h)
        if (abs(skew) >= 0.05) {
            g = toGray(rotate(input, -skew))
            bin = binarize(g)
        }
        val w = g.w
        val h = g.h
        val none = Detection(skew, null, null, w, h)

        // --- горизонтальные линии ---
        val hm = horizontalMask(bin, w, h, minH)
        val hProf = IntArray(h) { y -> var c = 0; for (x in 0 until w) if (hm[y * w + x]) c++; c }
        val yBest = hProf.indices.maxByOrNull { hProf[it] } ?: return none
        if (hProf[yBest] < w / 5) return none
        var xL = 0
        while (xL < w - 1 && !hm[yBest * w + xL]) xL++
        var xR = w - 1
        while (xR > 0 && !hm[yBest * w + xR]) xR--
        val tw = xR - xL + 1
        if (tw < w / 5) return none
        val rowCov = IntArray(h) { y -> var c = 0; for (x in xL..xR) if (hm[y * w + x]) c++; c }
        val hLines = largestCluster(peaks(rowCov, (tw * 0.45).toInt(), 4), h / 6)
        if (hLines.size < 2) return none

        // --- вертикальные линии (только в пределах таблицы) ---
        val yT = hLines.first()
        val yB = hLines.last()
        val th = yB - yT + 1
        val gaps = (1 until hLines.size).map { hLines[it] - hLines[it - 1] }.sorted()
        val medGap = gaps[gaps.size / 2]
        val vm = verticalMask(bin, w, h, max(15, (medGap * 1.2).toInt()))
        val xa = max(0, xL - 8)
        val xb = min(w - 1, xR + 8)
        val colCov = IntArray(w) { x ->
            if (x < xa || x > xb) 0 else { var c = 0; for (y in yT..yB) if (vm[y * w + x]) c++; c }
        }
        val vLines = peaks(colCov, (th * 0.4).toInt(), 4)
        if (vLines.size < 2) return none

        // --- ячейки и объединения ---
        fun vSep(x: Int, ya: Int, yb: Int): Boolean {
            val y0 = ya + 3
            val y1 = yb - 3
            if (y1 <= y0) return true
            var hit = 0
            for (y in y0..y1) {
                var f = false
                for (dx in -2..2) { val xx = x + dx; if (xx in 0 until w && bin[y * w + xx]) { f = true; break } }
                if (f) hit++
            }
            return hit >= (y1 - y0 + 1) * 0.8
        }

        fun hSep(y: Int, xa0: Int, xb0: Int): Boolean {
            val x0 = xa0 + 3
            val x1 = xb0 - 3
            if (x1 <= x0) return true
            var hit = 0
            for (x in x0..x1) {
                var f = false
                for (dy in -2..2) { val yy = y + dy; if (yy in 0 until h && bin[yy * w + x]) { f = true; break } }
                if (f) hit++
            }
            return hit >= (x1 - x0 + 1) * 0.8
        }

        val nr = hLines.size - 1
        val nc = vLines.size - 1
        val parent = IntArray(nr * nc) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[max(ra, rb)] = min(ra, rb) }

        for (r in 0 until nr) for (c in 1 until nc)
            if (!vSep(vLines[c], hLines[r], hLines[r + 1])) union(r * nc + c - 1, r * nc + c)
        for (r in 1 until nr) for (c in 0 until nc)
            if (!hSep(hLines[r], vLines[c], vLines[c + 1])) union((r - 1) * nc + c, r * nc + c)

        val groups = LinkedHashMap<Int, IntArray>()
        for (r in 0 until nr) for (c in 0 until nc) {
            val bb = groups.getOrPut(find(r * nc + c)) { intArrayOf(r, c, r, c) }
            bb[0] = min(bb[0], r); bb[1] = min(bb[1], c); bb[2] = max(bb[2], r); bb[3] = max(bb[3], c)
        }
        val cells = groups.values.map { bb ->
            CellBox(
                bb[0], bb[1], bb[2] - bb[0] + 1, bb[3] - bb[1] + 1,
                vLines[bb[1]], hLines[bb[0]], vLines[bb[3] + 1], hLines[bb[2] + 1]
            )
        }.sortedWith(compareBy({ it.row }, { it.col }))

        return Detection(skew, Grid(nr, nc, cells), bin, w, h)
    }
}
