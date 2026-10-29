package io.github.abooodbah.leanrows

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * .xlsx files are turned into CSV, sheet by sheet, and then shown by the same
 * row engine as any CSV. The expected rows were written from the raw XML by
 * tools/xlsx-fixtures/oracle.py, a separate implementation of the rules in
 * docs/XLSX.md. The files themselves were written by LibreOffice, XlsxWriter
 * and openpyxl (tools/xlsx-fixtures/make.py).
 */
class XlsxTest {
    private val fixtures = File("src/sharedTest/fixtures/xlsx")
    private val expected = File(fixtures, "expected")

    @Test fun everySheetOfEveryFileMatchesTheOracle() {
        val listing = JSONObject(File(expected, "sheets.json").readText())
        var sheetsChecked = 0
        for (name in listing.keys()) {
            val names = listing.getJSONArray(name).let { array -> List(array.length()) { array.getString(it) } }
            XlsxBook.open(File(fixtures, name)).use { book ->
                assertEquals("$name sheets", names, book.sheets.map { it.name })
                for (index in names.indices) {
                    val csv = ByteArrayOutputStream()
                    val rows = book.writeCsv(index, csv)
                    val want = File(expected, "$name.$index.jsonl").readLines().map { line ->
                        JSONArray(line).let { array -> List(array.length()) { array.getString(it) } }
                    }
                    assertEquals("$name ${names[index]} rows written", want.size.toLong(), rows)
                    val table = Table(ByteArraySource(csv.toByteArray()), Format.CSV)
                    assertTrue(table.scan())
                    assertEquals("$name ${names[index]} rows shown", want.size.toLong(), table.rowCount)
                    val got = table.rows(0, want.size)
                    for (row in want.indices) {
                        assertEquals("$name ${names[index]} row ${row + 1}", trimmed(want[row].map(::shown)), trimmed(got[row].cells))
                    }
                    sheetsChecked++
                }
            }
        }
        assertTrue("sheets checked: $sheetsChecked", sheetsChecked >= 9)
    }

    @Test fun hiddenSheetsAreNotOffered() {
        XlsxBook.open(File(fixtures, "features.xlsx")).use { book ->
            assertEquals(listOf("Readings", "Notes"), book.sheets.map { it.name })
        }
    }

    @Test fun columnsAreNamedWithTheSheetsLetters() {
        val cases = mapOf(0 to "A", 1 to "B", 25 to "Z", 26 to "AA", 51 to "AZ", 52 to "BA", 701 to "ZZ", 702 to "AAA", 16_383 to "XFD")
        for ((index, letters) in cases) assertEquals("column $index", letters, XlsxBook.columnName(index))
    }

    @Test fun aDoctypeIsRefused() {
        val error = refusal { XlsxBook.open(File(fixtures, "hostile/doctype.xlsx")).use { it.writeCsv(0, OutputStream.nullOutputStream()) } }
        assertTrue(error, error.contains("DOCTYPE"))
    }

    @Test fun aTruncatedFileIsRefused() {
        val error = refusal { XlsxBook.open(File(fixtures, "hostile/truncated.xlsx")).use { it.writeCsv(0, OutputStream.nullOutputStream()) } }
        assertTrue(error, error.contains("not a complete .xlsx file"))
    }

    @Test fun aZipBombIsStoppedByCountingNotByItsHeader() {
        val written = CountingStream()
        val started = System.nanoTime()
        val error = refusal { XlsxBook.open(File(fixtures, "hostile/zip-bomb.xlsx")).use { it.writeCsv(0, written) } }
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(error, error.contains("compressed more than"))
        // The limit is 200 times the compressed size once past 1 MiB, so the
        // reader stops long before the 1.5 GiB the entry would inflate to.
        assertTrue("stopped after $seconds s", seconds < 10)
        assertTrue("wrote ${written.count} bytes", written.count < 64L * 1024 * 1024)
    }

    @Test fun anEntryOverTheSizeLimitIsRefused() {
        val limits = XlsxLimits(entryBytes = 64L * 1024)
        val error = refusal { XlsxBook.open(File(fixtures, "large-weather.xlsx"), limits).use { it.writeCsv(0, OutputStream.nullOutputStream()) } }
        assertTrue(error, error.contains("larger than"))
    }

    @Test fun moreRowsThanASheetCanHoldAreRefused() {
        val limits = XlsxLimits(rows = 1_000)
        val error = refusal { XlsxBook.open(File(fixtures, "large-weather.xlsx"), limits).use { it.writeCsv(0, OutputStream.nullOutputStream()) } }
        assertTrue(error, error.contains("more than 1,000 rows"))
    }

    @Test fun xlsxFilesAreRecognisedByNameTypeOrContent() {
        val zip = File(fixtures, "features.xlsx").readBytes().copyOf(4096)
        val text = "a,b\n1,2\n".toByteArray()
        assertTrue(XlsxBook.recognises("Book1.xlsx", null, zip))
        assertTrue(XlsxBook.recognises("download", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", zip))
        assertTrue(XlsxBook.recognises("download", "application/octet-stream", zip))
        assertFalse(XlsxBook.recognises("rows.csv", "text/csv", text))
        // A zip that is not a workbook, such as a .docx, is not taken for one.
        val docx = File("src/sharedTest/fixtures/desktop/document-smoke.csv").readBytes()
        assertFalse(XlsxBook.recognises("notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx))
    }

    private fun trimmed(cells: List<String>): List<String> = cells.dropLastWhile { it.isEmpty() }

    /** How the table shows a cell's text: line breaks and tabs as \n, \r and \t, as for CSV. */
    private fun shown(text: String): String = text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun refusal(block: () -> Unit): String {
        try {
            block()
        } catch (error: XlsxException) {
            return error.message.orEmpty()
        }
        fail("expected an XlsxException")
        return ""
    }

    private class CountingStream : OutputStream() {
        var count = 0L
        override fun write(b: Int) { count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len }
    }
}
