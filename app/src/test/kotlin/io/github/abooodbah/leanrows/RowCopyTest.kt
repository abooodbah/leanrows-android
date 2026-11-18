package io.github.abooodbah.leanrows

import org.junit.Assert.assertEquals
import org.junit.Test

/** Copy row, as desktop LeanRows copies a row with Ctrl+C: the row number, then each cell, separated by tabs. */
class RowCopyTest {
    @Test fun theRowNumberComesFirstThenEachCell() {
        assertEquals("2\t2026-01-01T00:00:00Z\tST001\tHarbor Point", RowCopy.text("2", listOf("2026-01-01T00:00:00Z", "ST001", "Harbor Point"), 3))
    }

    @Test fun aShortRowKeepsAPlaceForEachColumn() {
        assertEquals("3\talpha\t\t", RowCopy.text("3", listOf("alpha"), 3))
    }

    @Test fun cellsAreCopiedAsTheyAre() {
        // The cell text as shown: a line break in a quoted field is shown, and copied, as \r\n.
        assertEquals("5\t4\tdelta\tquoted\\r\\nmultiline", RowCopy.text("5", listOf("4", "delta", "quoted\\r\\nmultiline"), 3))
    }

    @Test fun aRowWithMoreCellsThanTitlesKeepsThemAll() {
        assertEquals("3\t2\tbeta\textra\tcolumn", RowCopy.text("3", listOf("2", "beta", "extra", "column"), 3))
    }

    @Test fun aPreviewRowIsItsNumberAndItsText() {
        assertEquals("1\t{\"row_id\":0}", RowCopy.text("1", listOf("{\"row_id\":0}"), 1))
    }
}
