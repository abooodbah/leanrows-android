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

    // Hiding columns, as desktop LeanRows 0.6.0 does: the view changes, the titles do not.

    private fun threeColumns() = columns().apply { fit(listOf(row("a", "a much longer value", "ccc"))) }

    @Test fun aHiddenColumnLeavesTheViewButKeepsItsTitle() {
        val columns = threeColumns()
        val total = columns.totalWidth
        assertTrue(columns.hide(1))
        assertTrue(columns.isHidden(1))
        assertEquals(listOf("Column 1", "Column 2", "Column 3"), columns.titles)
        assertEquals(listOf(0, 2), columns.shown)
        assertEquals(1, columns.hiddenCount)
        assertEquals(total - 200f, columns.totalWidth, 0f)
        // Column 3 now starts where Column 2 did.
        assertEquals(90f, columns.left(2), 0f)
        assertEquals(2, columns.columnAt(95f))
        assertEquals(-1, columns.columnAt(185f))
    }

    @Test fun showAllBringsEveryColumnBackAtItsWidth() {
        val columns = threeColumns()
        val widths = columns.widths.copyOf()
        columns.hide(0)
        columns.hide(2)
        assertEquals(2, columns.hiddenCount)
        assertTrue(columns.showAll())
        assertEquals(0, columns.hiddenCount)
        assertEquals(listOf(0, 1, 2), columns.shown)
        assertArrayEquals(widths, columns.widths, 0f)
        assertEquals(1, columns.columnAt(95f))
        assertFalse("nothing to show", columns.showAll())
    }

    @Test fun theLastShownColumnCannotBeHidden() {
        val columns = threeColumns()
        assertTrue(columns.hide(0))
        assertTrue(columns.hide(1))
        assertFalse(columns.canHide(2))
        assertFalse(columns.hide(2))
        assertEquals(listOf(2), columns.shown)
        assertFalse("already hidden", columns.hide(0))
        assertFalse("not a column", columns.hide(3))
        assertFalse("not a column", columns.hide(-1))
    }

    @Test fun aPreviewColumnHasNothingToHide() {
        val columns = columns(preview = true)
        columns.fit(listOf(row("timestamp_ms=1 level=INFO")))
        assertFalse(columns.canHide(0))
        assertFalse(columns.hide(0))
    }

    @Test fun columnsThatLaterRowsAddAreShown() {
        val columns = columns()
        columns.fit(listOf(row("a", "b")))
        columns.hide(0)
        columns.fit(listOf(row("a", "b", "a third column")))
        assertEquals(listOf(1, 2), columns.shown)
        assertTrue(columns.isHidden(0))
    }

    @Test fun zoomScalesHiddenColumnsToo() {
        val columns = threeColumns()
        columns.hide(1)
        columns.zoomTo(2.0)
        columns.showAll()
        assertArrayEquals(floatArrayOf(180f, 400f, 180f), columns.widths, 0f)
    }

    // Zoom, as in desktop LeanRows 0.5.1: each column keeps the width it was sized at.

    @Test fun zoomingInAndOutLeavesTheWidthsAsTheyWere() {
        val columns = threeColumns()
        val widths = columns.widths.copyOf()
        val rowNumbers = columns.rowNumberWidth
        repeat(20) {
            for (level in Zoom.LEVELS) columns.zoomTo(level)
            for (level in Zoom.LEVELS.reversed()) columns.zoomTo(level)
            columns.zoomTo(1.0)
        }
        assertArrayEquals(widths, columns.widths, 0f)
        assertEquals(rowNumbers, columns.rowNumberWidth, 0f)
    }

    @Test fun eachZoomLevelGivesTheSameWidthsHoweverItIsReached() {
        val columns = threeColumns()
        columns.zoomTo(1.5)
        val direct = columns.widths.copyOf()
        for (level in listOf(3.0, 0.5, 1.1, 2.5, 0.67, 1.25)) columns.zoomTo(level)
        columns.zoomTo(1.5)
        assertArrayEquals(direct, columns.widths, 0f)
        assertArrayEquals(floatArrayOf(135f, 300f, 135f), direct, 0.001f)
    }

    @Test fun columnsMadeWhileZoomedStartAtThatZoom() {
        // Sized at 200%: padding and limits are already doubled, as TableStyle makes them.
        val columns = Columns(false, { it.length * 20f }, padding = 10f, minimum = 100f, maximum = 600f, zoom = 2.0)
        columns.fit(listOf(row("a", "a much longer value", "ccc")))
        assertArrayEquals(floatArrayOf(180f, 400f, 180f), columns.widths, 0f)
        columns.zoomTo(1.0)
        assertArrayEquals(floatArrayOf(90f, 200f, 90f), columns.widths, 0f)
        columns.zoomTo(2.0)
        assertArrayEquals(floatArrayOf(180f, 400f, 180f), columns.widths, 0f)
    }

    @Test fun aColumnThatLaterRowsAddWhileZoomedKeepsItsWidthAtTheFirstZoom() {
        val columns = columns()
        columns.fit(listOf(row("a")))
        columns.zoomTo(2.0)
        // A later row adds Column 2 at 200%: 80 for its title (this measure does not zoom) and 20 of padding.
        columns.fit(listOf(row("a", "b")))
        assertEquals(100f, columns.widths[1], 0f)
        columns.zoomTo(1.0)
        assertEquals(50f, columns.widths[1], 0f)
        assertEquals(90f, columns.widths[0], 0f)
    }
}
