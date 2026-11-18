package io.github.abooodbah.leanrows

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Copy row in the cell dialog: the row number and every cell, separated by
 * tabs, as desktop LeanRows copies a row. Files arrive through the test
 * provider as content:// URIs.
 */
@RunWith(AndroidJUnit4::class)
class CopyRowTest {
    /** Taps the middle of [column] in the row shown at [position] and waits for the cell dialog. */
    private fun Viewer.tapCell(position: Int, column: Int) {
        rowText(position)
        val (x, y) = onActivity { activity ->
            val row = activity.listForTest.findViewHolderForAdapterPosition(position)!!.itemView
            val state = activity.stateForTest!!
            val columns = state.columns
            val location = IntArray(2).also { row.getLocationOnScreen(it) }
            val x = location[0] + columns.rowNumberWidth + (0 until column).sumOf { columns.widths[it].toDouble() }.toFloat() +
                minOf(columns.widths[column], row.width - columns.rowNumberWidth) / 2 - state.offsetX
            x to (location[1] + row.height / 2f)
        }
        tap(x, y)
        waitFor("the cell") { it.dialogForTest?.isShowing == true }
    }

    private fun Viewer.copyRow(): String? {
        val button = onActivity { it.dialogForTest!!.getButton(AlertDialog.BUTTON_NEUTRAL) }
        assertNotNull("the cell dialog has a Copy row button", button)
        assertEquals("Copy row", onActivity { button.text.toString() })
        onActivity { button.performClick() }
        Focus.ensure()
        return onActivity { it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
    }

    @Test
    fun aRowIsCopiedWithItsNumberAndTabs() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.tapCell(1, 2)
        assertEquals(
            "2\t2026-01-01T00:00:00Z\tST001\tHarbor Point\t47.61\t-122.33\t5.7\t26\t15.6\t1021.9\train",
            viewer.copyRow(),
        )
    }

    @Test
    fun aRowFarDownAHundredThousandRowsIsCopiedAfterScrollingAndRotating() = Viewer.view("file/generated/large-multiline.csv").use { viewer ->
        viewer.waitForScan()
        viewer.onActivity { it.listForTest.scrollToPosition(100_000) }
        viewer.rowText(100_000)
        viewer.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        viewer.waitFor("landscape") { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        viewer.onActivity { it.listForTest.scrollToPosition(100_000) }
        viewer.tapCell(100_000, 0)
        val expected = viewer.onActivity { it.tableForTest!!.rows(100_000, 1).single() }
        assertEquals("100001\t" + expected.cells.joinToString("\t"), viewer.copyRow())
        viewer.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test
    fun aFileThatEndsInsideAQuoteCopiesItsRows() = Viewer.view("file/generated/malformed.csv").use { viewer ->
        viewer.waitForScan()
        // Row 3 has a fourth field; row 6 runs to the end of the file.
        viewer.tapCell(2, 0)
        assertEquals("3\t2\tbeta\textra\tcolumn", viewer.copyRow())
        viewer.onActivity { it.dialogForTest!!.dismiss() }
        viewer.tapCell(5, 0)
        assertEquals("6\t5\tepsilon\tunterminated\t", viewer.copyRow())
    }

    @Test
    fun aJsonLinesRowIsItsNumberAndItsText() = Viewer.view("file/generated/large.jsonl").use { viewer ->
        viewer.tapCell(0, 0)
        val copied = viewer.copyRow().orEmpty()
        assertTrue(copied, copied.startsWith("1\t{\"row_id\":0,"))
        assertEquals(1, copied.count { it == '\t' })
    }

    @Test
    fun aWorkbookRowIsCopiedFromItsSheet() = Viewer.view("file/xlsx/weather-stations.xlsx").use { viewer ->
        viewer.waitForScan()
        viewer.tapCell(1, 0)
        assertEquals(
            "2\t2026-01-01T00:00:00Z\tST001\tHarbor Point\t47.61\t-122.33\t5.7\t26\t15.6\t1021.9\train",
            viewer.copyRow(),
        )
    }
}
