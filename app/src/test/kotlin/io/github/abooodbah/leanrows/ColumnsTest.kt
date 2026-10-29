package io.github.abooodbah.leanrows

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColumnsTest {
    // Ten pixels per character, five pixels of padding on each side.
    private fun columns(preview: Boolean = false) =
        Columns(preview, { it.length * 10f }, padding = 5f, minimum = 50f, maximum = 300f)

    private fun row(vararg cells: String) = Row(1, cells.toList())

    @Test fun delimitedFilesGetNumberedColumnsSizedToTheirText() {
        val columns = columns()
        assertTrue(columns.fit(listOf(row("a", "a much longer value"), row("bb"))))
        assertEquals(listOf("Column 1", "Column 2"), columns.titles)
        // "Column 1" is wider than "a" or "bb"; the long value sets column 2.
        assertArrayEquals(floatArrayOf(90f, 200f), columns.widths, 0f)
    }

    @Test fun widthsStayBetweenTheLimits() {
        val columns = columns()
        columns.fit(listOf(row("x".repeat(500))))
        assertArrayEquals(floatArrayOf(300f), columns.widths, 0f)
    }

    @Test fun laterRowsAddColumnsButDoNotMoveTheOthers() {
        val columns = columns()
        columns.fit(listOf(row("a", "b")))
        val before = columns.widths.copyOf()
        assertFalse(columns.fit(listOf(row("a much longer value", "b"))))
        assertArrayEquals(before, columns.widths, 0f)
        assertTrue(columns.fit(listOf(row("a", "b", "a third column"))))
        assertEquals(listOf("Column 1", "Column 2", "Column 3"), columns.titles)
        assertEquals(before[0], columns.widths[0], 0f)
        assertEquals(150f, columns.widths[2], 0f)
    }

    @Test fun lineFormatsHaveOnePreviewColumn() {
        val columns = columns(preview = true)
        columns.fit(listOf(row("timestamp_ms=1 level=INFO")))
        assertEquals(listOf("Preview"), columns.titles)
        assertEquals(1, columns.widths.size)
    }

    @Test fun theRowNumberColumnGrowsWithTheRowCount() {
        val columns = columns()
        val start = columns.rowNumberWidth
        assertFalse(columns.fitRowNumbers(1_000))
        assertTrue(columns.fitRowNumbers(10_000_000))
        assertTrue(columns.rowNumberWidth > start)
    }

    @Test fun tapsMapToColumns() {
        val columns = columns()
        columns.fit(listOf(row("a", "a much longer value")))
        assertEquals(0, columns.columnAt(0f))
        assertEquals(1, columns.columnAt(95f))
        assertEquals(-1, columns.columnAt(500f))
    }
}
