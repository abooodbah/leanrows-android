package io.github.abooodbah.leanrows

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Debug
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens real files through a content provider in another package, the way
 * file managers and mail apps share them. The files are the desktop app's own
 * test files, or are written by the desktop's fixture script.
 */
@RunWith(AndroidJUnit4::class)
class TableTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aCsvSharedByAnotherAppShowsItsRows() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        assertEquals(1441L, viewer.onActivity { it.tableForTest?.rowCount })
        assertTrue(viewer.onActivity { it.statusForTest }, viewer.onActivity { it.statusForTest }.startsWith("1,441 rows · CSV"))
        assertEquals(
            "Row 1: timestamp_utc, station_id, station_name, latitude, longitude, temperature_c, humidity_pct, wind_kph, pressure_hpa, condition",
            viewer.rowText(0),
        )
        assertEquals(
            "Row 2: 2026-01-01T00:00:00Z, ST001, Harbor Point, 47.61, -122.33, 5.7, 26, 15.6, 1021.9, rain",
            viewer.rowText(1),
        )
        assertEquals((1..10).map { "Column $it" }, viewer.onActivity { it.stateForTest?.columns?.titles })
    }

    @Test
    fun aHundredThousandRowsScrollToTheLastOne() = Viewer.view("file/generated/large-multiline.csv").use { viewer ->
        viewer.waitForScan()
        assertEquals(100_001L, viewer.onActivity { it.tableForTest?.rowCount })
        viewer.onActivity { it.listForTest.scrollToPosition(100_000) }
        val last = viewer.rowText(100_000)
        val expected = viewer.onActivity { it.tableForTest!!.rows(100_000, 1).single() }
        assertEquals("Row 100001: " + expected.cells.joinToString(", "), last)
        assertTrue(last, last.startsWith("Row 100001: 99999, "))
    }

    @Test
    fun jsonLinesShowInOnePreviewColumn() = Viewer.view("file/generated/large.jsonl").use { viewer ->
        val first = viewer.rowText(0)
        assertTrue(first, first.startsWith("Row 1: {\"row_id\":0,"))
        viewer.waitForScan()
        assertEquals(100_000L, viewer.onActivity { it.tableForTest?.rowCount })
        assertEquals(listOf("Preview"), viewer.onActivity { it.stateForTest?.columns?.titles })
    }

    @Test
    fun rotatingKeepsTheFileAndThePlace() = Viewer.view("file/generated/large.log").use { viewer ->
        viewer.waitForScan()
        viewer.onActivity { it.listForTest.scrollToPosition(5_000) }
        viewer.rowText(5_000)
        val before = viewer.firstVisible()
        val activity = viewer.onActivity { it }
        viewer.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        instrumentation.waitForIdleSync()
        viewer.waitFor("landscape") { it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        viewer.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        instrumentation.waitForIdleSync()
        viewer.waitFor("portrait") { it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        assertTrue("the same activity", viewer.onActivity { it } === activity)
        val after = viewer.firstVisible()
        assertTrue("first row before $before, after $after", kotlin.math.abs(after - before) <= 2)
    }

    @Test
    fun findJumpsToTheNextMatchingRow() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.onActivity {
            it.findViewById<Button>(R.id.find).performClick()
            it.findViewById<EditText>(R.id.find_text).setText("desert flats")
            it.findViewById<Button>(R.id.find_next).performClick()
        }
        viewer.waitFor("the first match") { it.stateForTest?.highlightedRow == 4L }
        assertEquals("“desert flats” in row 5", viewer.onActivity { it.statusForTest })
        viewer.onActivity { it.findViewById<Button>(R.id.find_next).performClick() }
        viewer.waitFor("the second match") { it.stateForTest?.highlightedRow == 10L }
        viewer.onActivity { it.findViewById<Button>(R.id.find_previous).performClick() }
        viewer.waitFor("back to the first match") { it.stateForTest?.highlightedRow == 4L }
        assertTrue(viewer.firstVisible() <= 4 && viewer.lastVisible() >= 4)

        viewer.onActivity {
            it.findViewById<CheckBox>(R.id.match_case).isChecked = true
            it.findViewById<Button>(R.id.find_next).performClick()
        }
        viewer.waitFor("no exact match") { it.statusForTest.startsWith("No row contains") }
    }

    @Test
    fun goToRowScrollsThere() = Viewer.view("file/generated/large.log").use { viewer ->
        viewer.waitForScan()
        viewer.onActivity { it.findViewById<Button>(R.id.go_to).performClick() }
        viewer.waitFor("the dialog") { it.dialogForTest?.isShowing == true }
        viewer.onActivity {
            val dialog = it.dialogForTest!!
            val field = findEditText(dialog)
            field.setText("50000")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        val row = viewer.rowText(49_999)
        assertTrue(row, row.startsWith("Row 50000: timestamp_ms=1704117199000 level=ERROR row_id=49999 "))
    }

    @Test
    fun aTappedCellOpensWholeAndCanBeCopied() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(1)
        val (x, y) = viewer.onActivity { activity ->
            val row = activity.listForTest.findViewHolderForAdapterPosition(1)!!.itemView
            val columns = activity.stateForTest!!.columns
            val location = IntArray(2).also { row.getLocationOnScreen(it) }
            // The middle of Column 3, the station name.
            val x = location[0] + columns.rowNumberWidth + columns.widths[0] + columns.widths[1] + columns.widths[2] / 2
            x to (location[1] + row.height / 2f)
        }
        viewer.tap(x, y)
        viewer.waitFor("the cell") { it.dialogForTest?.isShowing == true }
        viewer.onActivity { it.dialogForTest!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        val copied = viewer.onActivity {
            it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals("Harbor Point", copied)
    }

    @Test
    fun aSidewaysDragMovesTheColumnsButNotTheRowNumbers() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(3)
        val (y, right) = viewer.onActivity { activity ->
            val location = IntArray(2).also { activity.listForTest.getLocationOnScreen(it) }
            (location[1] + activity.listForTest.height / 2f) to (location[0] + activity.listForTest.width - 40f)
        }
        viewer.drag(right, y, right - 500f, y)
        val offset = viewer.onActivity { it.stateForTest!!.offsetX }
        assertTrue("panned by $offset", offset > 300f)
        // The row views stay put; only the cells right of the row number move.
        assertEquals(0, viewer.onActivity { it.listForTest.findViewHolderForAdapterPosition(3)!!.itemView.left })
    }

    @Test
    fun invalidUtf8IsShownAsByteEscapes() = Viewer.view("file/desktop/inline-invalid-utf8.log").use { viewer ->
        viewer.waitForScan()
        assertEquals("Row 2: bad\\xFFtail", viewer.rowText(1))
    }

    @Test
    fun anUnterminatedQuoteIsReported() = Viewer.view("file/generated/malformed.csv").use { viewer ->
        viewer.waitForScan()
        assertEquals(6L, viewer.onActivity { it.tableForTest?.rowCount })
        assertTrue(viewer.onActivity { it.statusForTest }.contains("ends inside a quoted field"))
        assertEquals("Row 6: 5, epsilon, unterminated", viewer.rowText(5))
    }

    @Test
    fun aSixtyFourMegabyteRecordIsCutShortAndNotLoaded() = Viewer.view("file/generated/giant-record.csv").use { viewer ->
        viewer.waitForScan()
        assertEquals(2L, viewer.onActivity { it.tableForTest?.rowCount })
        val row = viewer.onActivity { it.tableForTest!!.rows(1, 1).single() }
        assertEquals("1", row.cells[0])
        assertTrue(row.cells[1].endsWith(" ...") && row.cells[1].length <= 1024)
        viewer.rowText(1)
        Runtime.getRuntime().gc()
        val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertTrue("Java heap in use: $heap bytes", heap < 48L * 1024 * 1024)
        val pss = Debug.getPss() * 1024
        assertTrue("PSS: $pss bytes", pss < 160L * 1024 * 1024)
    }

    @Test
    fun aStreamWithoutASizeIsCopiedThenShown() = Viewer.view("pipe/generated/large.log").use { viewer ->
        viewer.waitForScan()
        assertEquals(100_000L, viewer.onActivity { it.tableForTest?.rowCount })
        assertEquals("large.log", viewer.onActivity { it.findViewById<android.widget.TextView>(R.id.title).text.toString() })
        // The copy was unlinked as soon as it was open.
        assertEquals(emptyList<String>(), File(context.cacheDir, "streams").list()?.toList().orEmpty())
    }

    @Test
    fun anEmptyFileSaysSo() = Viewer.view("file/desktop/document-smoke-empty.log").use { viewer ->
        viewer.waitForScan()
        assertTrue(viewer.onActivity { it.statusForTest }.startsWith("The file is empty"))
    }

    @Test
    fun sharedTextIsShownAsRows() = Viewer.launch(
        Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_TEXT, "name,count\nalpha,1\nbeta,2\n"),
    ).use { viewer ->
        viewer.waitForScan()
        assertEquals("Row 3: beta, 2", viewer.rowText(2))
    }

    @Test
    fun aSharedFileStreamOpens() = Viewer.launch(
        Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, Viewer.uri("file/desktop/document-smoke.csv"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    ).use { viewer ->
        viewer.waitForScan()
        assertEquals("Row 1: alpha, quoted, value, line1\\nline2, ", viewer.rowText(0))
    }

    @Test
    fun fileUrisAreRefused() = Viewer.launch(
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/Download/rows.csv"), "text/csv"),
    ).use { viewer ->
        viewer.waitFor("a message") { it.messageForTest != null }
        assertNull(viewer.onActivity { it.tableForTest })
    }

    @Test
    fun theOpenButtonUsesTheSystemPicker() = Viewer.launch(Intent(Intent.ACTION_MAIN)).use { viewer ->
        assertNotNull(viewer.onActivity { it.messageForTest })
        val picked = Intent().setData(Viewer.uri("file/desktop/document-smoke.log"))
        // Stands in for the system picker: answers ACTION_OPEN_DOCUMENT with a file.
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                    Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, picked)
                } else {
                    null
                }
        }
        instrumentation.addMonitor(monitor)
        try {
            viewer.onActivity { it.findViewById<Button>(R.id.message_open).performClick() }
            viewer.waitForScan()
            assertEquals("Row 2: second row", viewer.rowText(1))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun theAppCannotOpenANetworkConnection() {
        // The test runs in the app's process, under its user ID. Without the
        // INTERNET permission the system refuses to create the socket at all.
        try {
            Socket().use { it.connect(InetSocketAddress("10.0.2.2", 80), 2_000) }
            fail("a socket connected")
        } catch (refused: java.io.IOException) {
            // EACCES before Android 14, EPERM from 14 on: either way the
            // system refused to create the socket.
            val text = refused.toString()
            assertTrue(text, listOf("EACCES", "EPERM").any { it in text })
        }
    }

    private fun findEditText(dialog: AlertDialog): EditText {
        fun search(view: android.view.View): EditText? = when (view) {
            is EditText -> view
            is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { search(view.getChildAt(it)) }
            else -> null
        }
        return search(dialog.window!!.decorView) ?: error("no field in the dialog")
    }
}
