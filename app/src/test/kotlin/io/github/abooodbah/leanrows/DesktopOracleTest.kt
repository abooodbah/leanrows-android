package io.github.abooodbah.leanrows

import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Compares what this port shows with what the desktop engine shows for the
 * same files. The expected output in resources/oracle was written by the
 * desktop's own leanrows-win32 document engine; tools/oracle/README.md has
 * the command.
 */
class DesktopOracleTest {
    private val desktop = File("src/sharedTest/fixtures/desktop")

    @Test fun desktopFixtures() {
        for (file in desktop.listFiles()!!.sortedBy { it.name }) check(file.name, file.readBytes())
    }

    @Test fun desktopTestInputs() {
        // The same bytes the desktop engine tests build in code.
        check("inline-giant-line.log", ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() } + '\n'.code.toByte())
        check("inline-20000-rows.txt", "row\n".repeat(20_000).toByteArray())
    }

    @Test fun generatedLargeFiles() {
        for (name in DesktopFixtures.NAMES) check(name, DesktopFixtures.bytes(name))
    }

    private fun check(name: String, bytes: ByteArray) {
        val lines = javaClass.getResourceAsStream("/oracle/$name.jsonl.gz")!!
            .let { GZIPInputStream(it).bufferedReader().readLines() }
        val summary = JSONObject(lines.first())
        val table = Table(ByteArraySource(bytes), Format.infer(name, null, bytes.copyOf(minOf(bytes.size, 4096))))
        table.scan()
        assertEquals("$name rows", summary.getLong("rows"), table.rowCount)
        assertEquals("$name diagnostic", !summary.isNull("diagnostic"), table.unterminatedQuote)

        var index = 1
        while (index < lines.size) {
            val window = JSONObject(lines[index++])
            val first = window.getLong("window")
            val count = window.getInt("count")
            val rows = table.rows(first, Table.WINDOW)
            assertEquals("$name window $first size", count, rows.size)
            for (row in rows) {
                val expected = JSONObject(lines[index++])
                assertEquals("$name row number", expected.getLong("row") + 1, row.number)
                val cells = expected.getJSONArray("cells")
                val actual = listOf(row.number.toString()) + row.cells
                assertEquals(
                    "$name row ${row.number}",
                    (0 until cells.length()).map { cells.getString(it) },
                    actual,
                )
            }
        }
    }
}
