package io.github.abooodbah.leanrows

import android.app.AlertDialog
import android.view.View
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Excel workbooks open like any row file: the first visible sheet is shown
 * with the sheet's own column letters, and a Sheet button switches between
 * the visible sheets. The files were written by LibreOffice, XlsxWriter and
 * openpyxl (tools/xlsx-fixtures/make.py).
 */
@RunWith(AndroidJUnit4::class)
class XlsxTableTest {

    @Test
    fun aWorkbookSharedByAnotherAppShowsItsFirstSheet() = Viewer.view("file/xlsx/features.xlsx").use { viewer ->
        viewer.waitForScan()
        assertEquals(7L, viewer.onActivity { it.tableForTest?.rowCount })
        val status = viewer.onActivity { it.statusForTest }
        assertTrue(status, status.startsWith("7 rows · Excel, sheet Readings"))
        assertEquals(
            "Row 1: Station, Reading, Taken on, Taken at, Time, Checked, Ratio, Cost, Note",
            viewer.rowText(0),
        )
        assertEquals(
            "Row 2: Harbor Point, 12.5, 2026-01-01, 2026-01-01 06:30:15, 06:30:00, TRUE, 0.125, 1234.5, First line\\nsecond line",
            viewer.rowText(1),
        )
        assertEquals(
            "Row 3: Ridge Field, -3, 2024-02-29, , , FALSE, #DIV/0!, 25, Harbor Point north",
            viewer.rowText(2),
        )
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"), viewer.onActivity { it.stateForTest?.columns?.titles })
    }

    @Test
    fun theSheetButtonSwitchesToAnotherVisibleSheet() = Viewer.view("file/xlsx/features.xlsx").use { viewer ->
        viewer.waitForScan()
        val button = viewer.onActivity { it.findViewById<Button>(R.id.sheet) }
        assertEquals(View.VISIBLE, viewer.onActivity { button.visibility })
        viewer.onActivity { button.performClick() }
        viewer.waitFor("the sheet list") { it.dialogForTest?.isShowing == true }
        val names = viewer.onActivity { activity ->
            val list = activity.dialogForTest!!.listView
            (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
        }
        // "Lookup" is hidden in the workbook and is not offered.
        assertEquals(listOf("Readings", "Notes"), names)
        viewer.onActivity { activity ->
            val list = activity.dialogForTest!!.listView
            list.performItemClick(list.getChildAt(1) ?: list, 1, list.adapter.getItemId(1))
        }
        viewer.waitFor("the Notes sheet") { it.statusForTest.contains("sheet Notes") && it.tableForTest?.scanned == true }
        assertEquals("Row 1: Plain, bold and plain again", viewer.rowText(0))
        assertEquals("Row 4: Unicode: Zürich, 東京, مرحبا, 🙂", viewer.rowText(3))
    }

    @Test
    fun aOneSheetWorkbookHasNoSheetButton() = Viewer.view("file/xlsx/weather-stations.xlsx").use { viewer ->
        viewer.waitForScan()
        assertEquals(1441L, viewer.onActivity { it.tableForTest?.rowCount })
        assertEquals(View.GONE, viewer.onActivity { it.findViewById<Button>(R.id.sheet).visibility })
        assertEquals(
            "Row 2: 2026-01-01T00:00:00Z, ST001, Harbor Point, 47.61, -122.33, 5.7, 26, 15.6, 1021.9, rain",
            viewer.rowText(1),
        )
    }

    @Test
    fun aHundredThousandRowWorkbookScrollsToItsLastRow() = Viewer.view("file/xlsx/large-weather.xlsx").use { viewer ->
        viewer.waitForScan(timeoutMs = 120_000)
        assertEquals(100_001L, viewer.onActivity { it.tableForTest?.rowCount })
        viewer.onActivity { it.listForTest.scrollToPosition(100_000) }
        val last = viewer.rowText(100_000)
        // Data row 100,000 is the desktop's reading number 100,000 mod 1,440.
        assertTrue(last, last.startsWith("Row 100001: 2026-01-"))
    }

    @Test
    fun aWorkbookStreamWithoutASizeOpens() = Viewer.view("pipe/xlsx/openpyxl-stations.xlsx").use { viewer ->
        viewer.waitForScan()
        assertEquals(25L, viewer.onActivity { it.tableForTest?.rowCount })
        assertTrue(viewer.rowText(0), viewer.rowText(0).endsWith(", Installed, Active"))
        assertTrue(viewer.rowText(1), viewer.rowText(1).endsWith(", 2019-05-17, TRUE"))
    }

    @Test
    fun aZipBombIsRefusedWithAMessage() = Viewer.view("file/xlsx-hostile/zip-bomb.xlsx").use { viewer ->
        viewer.waitFor("a message", 60_000) { it.messageForTest != null }
        val message = viewer.onActivity { it.messageForTest }!!
        assertTrue(message, message.contains("compressed more than"))
        assertNull(viewer.onActivity { it.tableForTest })
    }

    @Test
    fun aWorkbookWithADoctypeIsRefusedWithAMessage() = Viewer.view("file/xlsx-hostile/doctype.xlsx").use { viewer ->
        viewer.waitFor("a message") { it.messageForTest != null }
        assertTrue(viewer.onActivity { it.messageForTest }!!, viewer.onActivity { it.messageForTest }!!.contains("DOCTYPE"))
    }

    @Test
    fun aTruncatedWorkbookIsRefusedWithAMessage() = Viewer.view("file/xlsx-hostile/truncated.xlsx").use { viewer ->
        viewer.waitFor("a message") { it.messageForTest != null }
        assertTrue(viewer.onActivity { it.messageForTest }!!, viewer.onActivity { it.messageForTest }!!.contains("not a complete .xlsx file"))
    }

    @Suppress("unused")
    private fun AlertDialog.items(): Int = listView.adapter.count
}
