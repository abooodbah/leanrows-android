package io.github.abooodbah.leanrows

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Debug
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sorting as in desktop LeanRows 0.4.0, through the screen: tap a column
 * title, tap it again, long-press it for numbers, tap Row to go back,
 * Cancel. Files arrive through the test provider as content:// URIs.
 */
@RunWith(AndroidJUnit4::class)
class SortTableTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** The weather file's rows, split at commas (it has no quoted fields), from row 0. */
    private val weather: List<List<String>> by lazy {
        context.contentResolver.openInputStream(Viewer.uri("file/desktop/weather-stations.csv"))!!.use { input ->
            input.readBytes().toString(Charsets.UTF_8).trimEnd('\n').split("\n").map { it.trimEnd('\r').split(",") }
        }
    }

    private fun bytesOrder(a: String, b: String): Int {
        val x = a.toByteArray()
        val y = b.toByteArray()
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toInt() and 0xFF).compareTo(y[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }

    private fun Viewer.headerPoint(column: Int): Pair<Float, Float> = onActivity { activity ->
        val header = activity.findViewById<View>(R.id.header)
        val location = IntArray(2).also { header.getLocationOnScreen(it) }
        val state = activity.stateForTest!!
        val columns = state.columns
        val x = if (column < 0) {
            columns.rowNumberWidth / 2
        } else {
            columns.rowNumberWidth + (0 until column).sumOf { columns.widths[it].toDouble() }.toFloat() +
                columns.widths[column] / 2 - state.offsetX
        }
        (location[0] + x) to (location[1] + header.height / 2f)
    }

    private fun Viewer.tapHeader(column: Int) {
        val (x, y) = headerPoint(column)
        tap(x, y)
    }

    private fun Viewer.longPressHeader(column: Int) {
        val (x, y) = headerPoint(column)
        val activity = onActivity { it }
        val start = SystemClock.uptimeMillis()
        Focus.touch(activity, MotionEvent.ACTION_DOWN, start, start, x, y)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() * 2L)
        Focus.touch(activity, MotionEvent.ACTION_UP, start, SystemClock.uptimeMillis(), x, y)
        waitFor("the column menu") { it.popupForTest != null }
    }

    private fun Viewer.menu(id: Int) = onActivity { it.popupForTest!!.menu.performIdentifierAction(id, 0) }

    private fun Viewer.waitForSort(timeoutMs: Long = 60_000) =
        waitFor("the sort to finish", timeoutMs) { it.sortedForTest != null && !it.sortingForTest }

    /** "Row N" of the row shown at [position]. */
    private fun Viewer.numberAt(position: Int): Int = rowText(position).substringAfter("Row ").substringBefore(":").toInt()

    @Test
    fun tappingATitleSortsTheWholeFileAndTappingAgainReverses() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(0)
        viewer.tapHeader(2)
        viewer.waitForSort()
        val ascending = weather.indices.sortedWith { a, b -> bytesOrder(weather[a][2], weather[b][2]).let { if (it != 0) it else a.compareTo(b) } }
        // Each row keeps its own number from the file.
        assertEquals("Row ${ascending[0] + 1}: " + weather[ascending[0]].joinToString(", "), viewer.rowText(0))
        assertEquals(ascending[1] + 1, viewer.numberAt(1))
        assertEquals(1441, viewer.onActivity { it.listForTest.adapter!!.itemCount })
        assertTrue(viewer.onActivity { it.statusForTest }, viewer.onActivity { it.statusForTest }.contains("sorted by Column 3, text, ascending"))

        viewer.tapHeader(2)
        viewer.waitFor("descending") { it.statusForTest.contains("Column 3, text, descending") && !it.sortingForTest }
        // Lower-case "station_name" comes after every capitalised name.
        assertEquals(1, viewer.numberAt(0))
        val descending = weather.indices.sortedWith { a, b -> bytesOrder(weather[b][2], weather[a][2]).let { if (it != 0) it else a.compareTo(b) } }
        assertEquals(descending[1] + 1, viewer.numberAt(1))
    }

    @Test
    fun aLongPressOffersNumericOrderAndTappingRowRestoresTheFile() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(0)
        viewer.longPressHeader(5)
        viewer.menu(R.id.sort_numbers_ascending)
        viewer.waitForSort()
        val temperatures = weather.drop(1).map { it[5].toDouble() }
        val coldest = temperatures.indices.minWith(compareBy<Int> { temperatures[it] }.thenBy { it })
        assertEquals(coldest + 2, viewer.numberAt(0))
        // Text, here the title row, comes after every number.
        viewer.onActivity { it.listForTest.scrollToPosition(1440) }
        assertEquals(1, viewer.numberAt(1440))

        viewer.tapHeader(-1)
        viewer.waitFor("the file's own order") { it.sortedForTest == null }
        viewer.onActivity { it.listForTest.scrollToPosition(0) }
        assertTrue(viewer.rowText(0).startsWith("Row 1: timestamp_utc, station_id"))
        assertTrue(viewer.onActivity { it.statusForTest }.startsWith("1,441 rows · CSV"))
    }

    @Test
    fun findAndGoToRowUseTheFilesOwnNumbersInASortedView() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.tapHeader(2)
        viewer.waitForSort()
        viewer.onActivity {
            it.findViewById<Button>(R.id.find).performClick()
            it.findViewById<EditText>(R.id.find_text).setText("desert flats")
            it.findViewById<Button>(R.id.find_next).performClick()
        }
        viewer.waitFor("the first match") { it.statusForTest.startsWith("“desert flats” in row 5") }
        val highlighted = viewer.onActivity { it.stateForTest!!.highlightedRow }.toInt()
        assertEquals(5, viewer.numberAt(highlighted))

        viewer.onActivity { it.goToRowForTest(50) }
        viewer.waitFor("row 50") { it.stateForTest!!.highlightedRow != highlighted.toLong() }
        assertEquals(50, viewer.numberAt(viewer.onActivity { it.stateForTest!!.highlightedRow }.toInt()))
    }

    @Test
    fun theMoreMenuSortsByAChosenColumnAndRestores() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.onActivity { it.findViewById<Button>(R.id.more).performClick() }
        viewer.waitFor("the More menu") { it.popupForTest != null }
        viewer.menu(R.id.sort_by)
        viewer.waitFor("the column list") { it.dialogForTest?.isShowing == true }
        viewer.onActivity { it.dialogForTest!!.listView.performItemClick(null, 1, 1) }
        viewer.waitForSort()
        assertTrue(viewer.onActivity { it.statusForTest }.contains("sorted by Column 2, text, ascending"))
        viewer.onActivity { it.findViewById<Button>(R.id.more).performClick() }
        viewer.waitFor("the More menu") { it.popupForTest != null }
        viewer.menu(R.id.restore_order)
        viewer.waitFor("the file's own order") { it.sortedForTest == null }
        assertEquals(1, viewer.numberAt(0))
    }

    @Test
    fun cancelStopsASortAndKeepsTheViewYouHad() = Viewer.view("file/sort/shuffled-1000000.csv").use { viewer ->
        viewer.waitForScan(120_000)
        viewer.rowText(0)
        viewer.tapHeader(0)
        viewer.waitFor("the sort to start") { it.sortingForTest }
        viewer.onActivity { it.findViewById<Button>(R.id.sort_cancel).performClick() }
        viewer.waitFor("the cancel") { !it.sortingForTest }
        assertNull(viewer.onActivity { it.sortedForTest })
        assertTrue(viewer.onActivity { it.statusForTest }, viewer.onActivity { it.statusForTest }.contains("cancelled"))
        assertTrue(viewer.rowText(0).startsWith("Row 1: id, name, value"))
        assertEquals(emptyList<String>(), File(context.cacheDir, "sort").list()?.toList().orEmpty())
    }

    @Test
    fun aMillionRowsSortInBoundedMemoryAndStaySortedWhenRotated() = Viewer.view("file/sort/shuffled-1000000.csv").use { viewer ->
        viewer.waitForScan(120_000)
        viewer.longPressHeader(0)
        viewer.menu(R.id.sort_numbers_descending)
        viewer.waitForSort(240_000)
        // id = row * 7919 + 13 mod 1,000,000: find the data row that holds 999,999.
        val rows = 1_000_000L
        val top = (0 until rows).first { (it * 7919 + 13) % rows == rows - 1 }
        assertEquals("Row ${top + 2}: 999999, name-999, ${(999_999L * 31) % 100_000 / 100.0}", viewer.rowText(0))
        Runtime.getRuntime().gc()
        val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertTrue("Java heap in use: $heap bytes", heap < 48L * 1024 * 1024)
        assertTrue("PSS: ${Debug.getPss()} KB", Debug.getPss() < 160L * 1024)
        assertEquals(emptyList<String>(), File(context.cacheDir, "sort").list()?.toList().orEmpty())

        viewer.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        viewer.waitFor("landscape") { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        viewer.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        viewer.waitFor("portrait") { it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        viewer.onActivity { it.listForTest.scrollToPosition(0) }
        assertEquals(top + 2, viewer.numberAt(0).toLong())
        assertTrue(viewer.onActivity { it.statusForTest }.contains("numbers, descending"))
    }

    @Test
    fun aSortThatWouldNotFitIsRefusedBeforeItStarts() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.onActivity { it.freeSpaceForTest = 1024L * 1024 }
        viewer.tapHeader(2)
        viewer.waitFor("the refusal") { it.statusForTest.contains("Not enough free space to sort") }
        assertTrue(!viewer.onActivity { it.sortingForTest })
        assertNull(viewer.onActivity { it.sortedForTest })
    }

    @Test
    fun aWorkbookIsNotSortedAndSaysSo() = Viewer.view("file/xlsx/features.xlsx").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(0)
        viewer.tapHeader(0)
        viewer.waitFor("the message") { it.statusForTest == "Sorting does not work on Excel workbooks yet." }
        assertNull(viewer.onActivity { it.sortedForTest })
        assertEquals(1, viewer.numberAt(0))
    }

    @Test
    fun aFileThatEndsInsideAQuoteStillSorts() = Viewer.view("file/generated/malformed.csv").use { viewer ->
        viewer.waitForScan()
        viewer.tapHeader(1)
        viewer.waitForSort()
        assertEquals(6, viewer.onActivity { it.listForTest.adapter!!.itemCount })
        val numbers = (0 until 6).map { viewer.numberAt(it) }.sorted()
        assertEquals((1..6).toList(), numbers)
    }
}
