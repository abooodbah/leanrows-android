package io.github.abooodbah.leanrows

import org.junit.Assert.assertEquals
import org.junit.Test

class TableWindowTest {
    @Test
    fun aWindowThatEndsOnAReadBoundaryHasNoExtraRow() {
        // 64-byte lines, so row 1023 ends exactly where the first 64 KiB read ends.
        val line = "x".repeat(63) + "\n"
        val table = Table(ByteArraySource(line.repeat(2048).toByteArray()), Format.LOG)
        val rows = table.rows(1024L - Table.WINDOW, Table.WINDOW)
        assertEquals(Table.WINDOW, rows.size)
        assertEquals(1024L, rows.last().number)
    }

    @Test
    fun windowsReadFromACheckpointMatchWindowsReadFromTheStart() {
        val text = (0 until 5000).joinToString("") { "$it,\"line\n$it\",end\r\n" }
        val table = Table(ByteArraySource(text.toByteArray()), Format.CSV)
        val before = table.rows(4321, 3).map { it.cells }
        table.scan()
        assertEquals(before, table.rows(4321, 3).map { it.cells })
        assertEquals(listOf("4321", "line\\n4321", "end"), before.first())
    }
}
