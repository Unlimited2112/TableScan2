package by.tablescan

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Минимальный генератор .xlsx (Office Open XML) без внешних библиотек. */
object XlsxWriter {

    data class Merge(val r: Int, val c: Int, val rs: Int, val cs: Int)

    private const val S_TEXT = 1
    private const val S_INT = 2
    private const val S_DEC = 3
    private const val S_PCT = 4

    private val numRe = Regex("^-?\\d+(?:[.,]\\d+)?$")
    private val pctRe = Regex("^(-?\\d+(?:[.,]\\d+)?)\\s?%$")

    fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun cellXml(ref: String, raw: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return "<c r=\"$ref\" s=\"$S_TEXT\"/>"
        val compact = v.replace(" ", "").replace("\u00A0", "")
        pctRe.matchEntire(compact)?.let { m ->
            val d = m.groupValues[1].replace(',', '.').toDoubleOrNull()
            if (d != null) return "<c r=\"$ref\" s=\"$S_PCT\"><v>${d / 100.0}</v></c>"
        }
        val leadingZero = compact.length > 1 && compact.startsWith("0") && !compact.startsWith("0,") && !compact.startsWith("0.")
        if (numRe.matches(compact) && !leadingZero && compact.length <= 15) {
            val d = compact.replace(',', '.').toDoubleOrNull()
            if (d != null) {
                val style = if (compact.contains(',') || compact.contains('.')) S_DEC else S_INT
                return "<c r=\"$ref\" s=\"$style\"><v>$d</v></c>"
            }
        }
        return "<c r=\"$ref\" s=\"$S_TEXT\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>"
    }

    fun write(out: OutputStream, rows: List<List<String>>, merges: List<Merge>, sheetName: String = "Таблица") {
        val cols = rows.maxOfOrNull { it.size } ?: 0
        val zip = ZipOutputStream(out)
        fun put(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        val main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

        put("[Content_Types].xml", head +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "</Types>")
        put("_rels/.rels", head +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$rel/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>")
        put("xl/workbook.xml", head +
            "<workbook xmlns=\"$main\" xmlns:r=\"$rel\"><sheets>" +
            "<sheet name=\"${esc(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/>" +
            "</sheets></workbook>")
        put("xl/_rels/workbook.xml.rels", head +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$rel/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$rel/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>")
        val border = "borderId=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>"
        put("xl/styles.xml", head +
            "<styleSheet xmlns=\"$main\">" +
            "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
            "<border><left style=\"thin\"><color auto=\"1\"/></left><right style=\"thin\"><color auto=\"1\"/></right>" +
            "<top style=\"thin\"><color auto=\"1\"/></top><bottom style=\"thin\"><color auto=\"1\"/></bottom><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"5\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" $border" +
            "<xf numFmtId=\"1\" fontId=\"0\" fillId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" $border" +
            "<xf numFmtId=\"4\" fontId=\"0\" fillId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" $border" +
            "<xf numFmtId=\"9\" fontId=\"0\" fillId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" $border" +
            "</cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>")

        val sb = StringBuilder(head)
        sb.append("<worksheet xmlns=\"$main\">")
        if (cols > 0) {
            sb.append("<cols>")
            for (c in 0 until cols) {
                val len = rows.maxOf { r -> r.getOrNull(c)?.length ?: 0 }
                val width = (len * 1.1 + 2).coerceIn(6.0, 50.0)
                sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${"%.1f".format(java.util.Locale.US, width)}\" customWidth=\"1\"/>")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        rows.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 1}\">")
            for (c in 0 until cols) sb.append(cellXml(colName(c) + (r + 1), row.getOrElse(c) { "" }))
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        val valid = merges.filter { it.rs * it.cs > 1 }
        if (valid.isNotEmpty()) {
            sb.append("<mergeCells count=\"${valid.size}\">")
            for (m in valid) {
                val a = colName(m.c) + (m.r + 1)
                val b = colName(m.c + m.cs - 1) + (m.r + m.rs)
                sb.append("<mergeCell ref=\"$a:$b\"/>")
            }
            sb.append("</mergeCells>")
        }
        sb.append("</worksheet>")
        put("xl/worksheets/sheet1.xml", sb.toString())
        zip.finish()
        zip.flush()
    }
}
