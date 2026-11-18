package io.github.abooodbah.leanrows

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hiding columns from the view, as in desktop LeanRows 0.6.0. A long press
 * on a column title keeps its sort orders and adds Hide column and Show all
 * columns; the More menu has Hide column… and Show all columns. Files arrive
 * through the test provider as content:// URIs.
 */
@RunWith(AndroidJUnit4::class)
class HideColumnsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val weatherRow2 = "2026-01-01T00:00:00Z, ST001, Harbor Point, 47.61, -122.33, 5.7, 26, 15.6, 1021.9, rain"

    /** The middle of [column]'s title on screen, panning the table so it is in view. */
    private fun Viewer.headerPoint(column: Int): Pair<Float, Float> {
        waitFor("the columns") { (it.stateForTest?.columns?.widths?.size ?: 0) > column }
        return onActivity { activity ->
            val state = activity.stateForTest!!
            val columns = state.columns
            val header = activity.findViewById<View>(R.id.header)
            val left = columns.left(column)
            if (left < state.offsetX || left - state.offsetX + columns.widths[column] > state.viewportWidth - columns.rowNumberWidth) {
                state.offsetX = left
                state.clampOffset()
                header.invalidate()
            }
            val location = IntArray(2).also { header.getLocationOnScreen(it) }
            val x = columns.rowNumberWidth + left + minOf(columns.widths[column], header.width - columns.rowNumberWidth) / 2 - state.offsetX
            (location[0] + x) to (location[1] + header.height / 2f)
        }
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

    private fun Viewer.tapHeader(column: Int) {
        val (x, y) = headerPoint(column)
        tap(x, y)
    }

    private fun Viewer.openMore() {
        onActivity { it.findViewById<Button>(R.id.more).performClick() }
        waitFor("the More menu") { it.popupForTest != null }
    }

    private fun Viewer.item(id: Int) = onActivity { it.popupForTest!!.menu.findItem(id) }

    private fun Viewer.menu(id: Int) {
        val item = item(id)
        assertNotNull("menu item ${context.resources.getResourceEntryName(id)}", item)
        assertTrue("menu item ${item.title} is enabled", item.isEnabled)
        onActivity { it.popupForTest!!.menu.performIdentifierAction(id, 0) }
    }

    /** Long-presses [column]'s title and chooses Hide column. */
    private fun Viewer.hide(column: Int) {
        longPressHeader(column)
        menu(R.id.hide_column)
        waitFor("${column + 1} hidden") { it.stateForTest!!.columns.isHidden(column) }
    }

    private fun Viewer.shownTitles(): List<String> = onActivity { activity ->
        val columns = activity.stateForTest!!.columns
        columns.shown.map { columns.titles[it] }
    }

    private fun Viewer.dialogItems(): List<String> = onActivity { activity ->
        val list = activity.dialogForTest!!.listView
        (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
    }

    private fun Viewer.chooseDialogItem(index: Int) = onActivity { activity ->
        val list = activity.dialogForTest!!.listView
        list.performItemClick(list.getChildAt(index) ?: list, index, list.adapter.getItemId(index))
    }

    private fun Viewer.status() = onActivity { it.statusForTest }

    /** The status line says [note] on a line of its own, under the file's line. */
    private fun Viewer.assertNote(note: String) {
        val lines = status().lines()
        assertTrue(status(), lines.size > 1 && note in lines.drop(1))
    }

    private fun Viewer.waitForSort() = waitFor("the sort to finish", 60_000) { it.sortedForTest != null && !it.sortingForTest }

    /** "Row N" of the row shown at [position]. */
    private fun Viewer.numberAt(position: Int): Int = rowText(position).substringAfter("Row ").substringBefore(":").toInt()

    private fun sha256(path: String): String = context.contentResolver.openInputStream(Viewer.uri(path))!!.use { input ->
        MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun aLongPressKeepsTheSortOrdersAndHidesAColumn() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        assertEquals("Row 2: $weatherRow2", viewer.rowText(1))
        viewer.longPressHeader(2)
        for (id in listOf(R.id.sort_text_ascending, R.id.sort_text_descending, R.id.sort_numbers_ascending, R.id.sort_numbers_descending)) {
            assertNotNull("the long press still offers the sort orders", viewer.item(id))
        }
        assertFalse("nothing is hidden yet", viewer.item(R.id.show_all_columns).isEnabled)
        viewer.menu(R.id.hide_column)
        viewer.waitFor("Column 3 hidden") { it.stateForTest!!.columns.isHidden(2) }
        viewer.assertNote("Hid Column 3 from the view. The file is unchanged.")
        assertEquals("1,441 rows · CSV · 1 column hidden", viewer.status().lines().first())
        assertEquals((1..10).map { "Column $it" }, viewer.onActivity { it.stateForTest!!.columns.titles })
        assertEquals((1..10).filter { it != 3 }.map { "Column $it" }, viewer.shownTitles())
        // The row's description, which TalkBack reads, leaves the hidden cell out.
        viewer.waitFor("the rows drawn again") { !(it.listForTest.findViewHolderForAdapterPosition(1)?.itemView?.contentDescription ?: "").contains("Harbor Point") }
        assertEquals("Row 2: 2026-01-01T00:00:00Z, ST001, 47.61, -122.33, 5.7, 26, 15.6, 1021.9, rain", viewer.rowText(1))

        // A tap where Column 3 was now sorts by Column 4: titles map to the file's columns.
        viewer.tapHeader(3)
        viewer.waitForSort()
        assertTrue(viewer.status(), viewer.status().contains("sorted by Column 4, text, ascending"))
        assertTrue(viewer.status(), viewer.status().contains("1 column hidden"))

        viewer.longPressHeader(0)
        viewer.menu(R.id.show_all_columns)
        viewer.waitFor("every column") { it.stateForTest!!.columns.hiddenCount == 0 }
        viewer.assertNote("Showing all 10 columns.")
        assertFalse(viewer.status(), viewer.status().contains("hidden"))
        assertEquals((1..10).map { "Column $it" }, viewer.shownTitles())
    }

    /** The text a view draws, with the x of each piece. */
    private class RecordingCanvas : Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) {
        val texts = mutableListOf<Pair<String, Float>>()

        override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            texts += text.subSequence(start, end).toString() to x
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += text to x
        }
    }

    private fun Viewer.drawn(view: () -> View): List<Pair<String, Float>> = onActivity {
        RecordingCanvas().also { canvas -> view().draw(canvas) }.texts.filter { (text, _) -> text.isNotEmpty() }
    }

    @Test
    fun hiddenColumnsAreNotDrawnAndTheOthersCloseUp() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(1)
        viewer.hide(0)
        viewer.waitFor("the rows drawn again") { (it.listForTest.findViewHolderForAdapterPosition(1)?.itemView?.contentDescription ?: "").startsWith("Row 2: ST001") }
        val (rowNumbers, padding) = viewer.onActivity { it.stateForTest!!.columns.rowNumberWidth to it.stateForTest!!.style.padding }
        val row = viewer.drawn { viewer.onActivity { it.listForTest.findViewHolderForAdapterPosition(1)!!.itemView } }
        assertFalse(row.toString(), row.any { it.first.startsWith("2026-01-01") })
        // Column 2 now starts where Column 1 did.
        assertEquals("ST001", row.first().first)
        assertEquals(rowNumbers + padding, row.first().second, 0.5f)
        val titles = viewer.drawn { viewer.onActivity { it.findViewById<View>(R.id.header) } }
        assertFalse(titles.toString(), titles.any { it.first == "Column 1" })
        assertEquals("Column 2", titles.first().first)
        assertEquals(rowNumbers + padding, titles.first().second, 0.5f)
    }

    @Test
    fun theMoreMenuHidesAChosenColumnAndShowsAllAgain() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(0)
        viewer.openMore()
        assertFalse(viewer.item(R.id.show_all_columns).isEnabled)
        viewer.menu(R.id.hide_column)
        viewer.waitFor("the column list") { it.dialogForTest?.isShowing == true }
        assertEquals((1..10).map { "Column $it" }, viewer.dialogItems())
        viewer.chooseDialogItem(0)
        viewer.waitFor("Column 1 hidden") { it.stateForTest!!.columns.isHidden(0) }
        viewer.assertNote("Hid Column 1 from the view. The file is unchanged.")

        // The list offers only the columns still shown.
        viewer.openMore()
        viewer.menu(R.id.hide_column)
        viewer.waitFor("the column list") { it.dialogForTest?.isShowing == true }
        assertEquals((2..10).map { "Column $it" }, viewer.dialogItems())
        viewer.chooseDialogItem(8)
        viewer.waitFor("Column 10 hidden") { it.stateForTest!!.columns.isHidden(9) }

        viewer.openMore()
        viewer.menu(R.id.show_all_columns)
        viewer.waitFor("every column") { it.stateForTest!!.columns.hiddenCount == 0 }
        viewer.assertNote("Showing all 10 columns.")
        assertEquals("Row 2: $weatherRow2", viewer.rowText(1))
    }

    @Test
    fun aSortedColumnStaysSortedWhenHiddenAndTheStatusSaysSo() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.tapHeader(2)
        viewer.waitForSort()
        val first = viewer.numberAt(0)
        val second = viewer.numberAt(1)
        viewer.hide(2)
        viewer.assertNote("Hid Column 3 from the view. The file is unchanged. Rows stay sorted by it.")
        assertTrue(viewer.status(), viewer.status().lines().first().endsWith("sorted by Column 3 (hidden), text, ascending · 1 column hidden"))
        assertEquals(first, viewer.numberAt(0))
        assertEquals(second, viewer.numberAt(1))
        assertTrue(viewer.onActivity { it.sortedForTest != null })
        // The next status line marks the sorted column as hidden.
        viewer.onActivity { it.listForTest.scrollToPosition(1440) }
        viewer.openMore()
        viewer.menu(R.id.sort_text_descending)
        viewer.waitFor("descending") { it.statusForTest.contains("sorted by Column 3 (hidden), text, descending") && !it.sortingForTest }
        assertTrue(viewer.status(), viewer.status().contains("1 column hidden"))
    }

    @Test
    fun findStillSearchesHiddenColumnsAndSaysSo() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(0)
        // Column 3 holds the station names.
        viewer.hide(2)
        viewer.onActivity {
            it.findViewById<Button>(R.id.find).performClick()
            it.findViewById<EditText>(R.id.find_text).setText("desert flats")
            it.findViewById<Button>(R.id.find_next).performClick()
        }
        viewer.waitFor("the match") { it.stateForTest?.highlightedRow == 4L }
        assertEquals("“desert flats” in row 5 · hidden columns are searched too", viewer.status())
        viewer.onActivity {
            it.findViewById<EditText>(R.id.find_text).setText("no such station")
            it.findViewById<Button>(R.id.find_next).performClick()
        }
        viewer.waitFor("no match") { it.statusForTest.startsWith("No row contains") }
        assertEquals("No row contains “no such station”. Hidden columns are searched too.", viewer.status())
    }

    @Test
    fun copiesLeaveHiddenColumnsOut() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(1)
        viewer.hide(0)
        viewer.hide(2)
        val (x, y) = viewer.onActivity { activity ->
            val row = activity.listForTest.findViewHolderForAdapterPosition(1)!!.itemView
            val columns = activity.stateForTest!!.columns
            val location = IntArray(2).also { row.getLocationOnScreen(it) }
            // Column 2, which is now the first column on screen.
            (location[0] + columns.rowNumberWidth + columns.left(1) + columns.widths[1] / 2 - activity.stateForTest!!.offsetX) to
                (location[1] + row.height / 2f)
        }
        viewer.tap(x, y)
        viewer.waitFor("the cell") { it.dialogForTest?.isShowing == true }
        val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
        assertEquals("Row 2, Column 2", viewer.onActivity { it.dialogForTest!!.findViewById<android.widget.TextView>(titleId).text.toString() })
        viewer.onActivity { it.dialogForTest!!.getButton(AlertDialog.BUTTON_NEUTRAL).performClick() }
        Focus.ensure()
        val copied = viewer.onActivity { it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
        assertEquals("2\tST001\t47.61\t-122.33\t5.7\t26\t15.6\t1021.9\train", copied)
    }

    @Test
    fun theLastShownColumnCannotBeHidden() = Viewer.view("file/generated/malformed.csv").use { viewer ->
        // A file that ends inside a quote, with a short and a long row.
        viewer.waitForScan()
        viewer.rowText(0)
        viewer.waitFor("four columns") { it.stateForTest!!.columns.titles.size == 4 }
        viewer.hide(0)
        viewer.hide(1)
        viewer.hide(2)
        viewer.longPressHeader(3)
        assertFalse("the last shown column", viewer.item(R.id.hide_column).isEnabled)
        viewer.onActivity { it.popupForTest!!.dismiss() }
        viewer.openMore()
        assertFalse(viewer.item(R.id.hide_column).isEnabled)
        assertTrue(viewer.item(R.id.show_all_columns).isEnabled)
        viewer.onActivity { it.popupForTest!!.dismiss() }
        // Row 3 has a fourth field; row 6 has three.
        viewer.waitFor("the rows drawn again") { (it.listForTest.findViewHolderForAdapterPosition(2)?.itemView?.contentDescription ?: "") == "Row 3: column" }
        assertEquals("Row 6: ", viewer.onActivity { it.listForTest.findViewHolderForAdapterPosition(5)!!.itemView.contentDescription.toString() })
        assertEquals(listOf("Column 4"), viewer.shownTitles())
        assertTrue(viewer.status(), viewer.status().contains("3 columns hidden"))
        assertTrue(viewer.status(), viewer.status().contains("The file ends inside a quoted field"))
    }

    @Test
    fun aJsonLinesFileHasOnePreviewColumnAndNothingToHide() = Viewer.view("file/generated/large.jsonl").use { viewer ->
        viewer.rowText(0)
        viewer.longPressHeader(0)
        assertFalse(viewer.item(R.id.hide_column).isEnabled)
        assertFalse(viewer.item(R.id.show_all_columns).isEnabled)
    }

    @Test
    fun aHundredThousandRowsKeepTheirHiddenColumnsWhenScrolledAndRotated() = Viewer.view("file/generated/large-multiline.csv").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(0)
        val total = viewer.onActivity { it.stateForTest!!.columns.totalWidth }
        viewer.hide(1)
        val activity = viewer.onActivity { it }
        viewer.onActivity { it.listForTest.scrollToPosition(100_000) }
        val expected = viewer.onActivity { it.tableForTest!!.rows(100_000, 1).single() }
        assertEquals("Row 100001: " + expected.cells.filterIndexed { index, _ -> index != 1 }.joinToString(", "), viewer.rowText(100_000))
        viewer.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        viewer.waitFor("landscape") { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        assertTrue("the same activity", viewer.onActivity { it } === activity)
        assertTrue(viewer.onActivity { it.stateForTest!!.columns.isHidden(1) })
        // Sideways panning covers the shown columns only.
        viewer.onActivity { it.stateForTest!!.offsetX = Float.MAX_VALUE; it.stateForTest!!.clampOffset() }
        val state = viewer.onActivity { it.stateForTest!! }
        assertEquals(maxOf(0f, total - viewer.onActivity { it.stateForTest!!.columns.widths[1] } - state.viewportWidth), state.offsetX, 1f)
        viewer.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test
    fun aWorkbookSheetHidesColumnsByLetterAndAnotherSheetShowsThemAll() = Viewer.view("file/xlsx/features.xlsx").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(0)
        viewer.hide(1)
        viewer.assertNote("Hid B from the view. The file is unchanged.")
        viewer.waitFor("the rows drawn again") { !(it.listForTest.findViewHolderForAdapterPosition(1)?.itemView?.contentDescription ?: "").contains("12.5") }
        assertEquals("Row 2: Harbor Point, 2026-01-01, 2026-01-01 06:30:15, 06:30:00, TRUE, 0.125, 1234.5, First line\\nsecond line", viewer.rowText(1))
        assertEquals(listOf("A") + ('C'..'L').map { it.toString() }, viewer.shownTitles())
        viewer.onActivity { it.findViewById<Button>(R.id.sheet).performClick() }
        viewer.waitFor("the sheet list") { it.dialogForTest?.isShowing == true }
        viewer.chooseDialogItem(1)
        viewer.waitFor("the Notes sheet") { it.statusForTest.contains("sheet Notes") && it.tableForTest?.scanned == true }
        assertEquals(0, viewer.onActivity { it.stateForTest!!.columns.hiddenCount })
    }

    @Test
    fun nothingIsSavedAndTheFileIsNotChanged() {
        val before = sha256("file/desktop/weather-stations.csv")
        Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
            viewer.waitForScan()
            viewer.rowText(0)
            viewer.hide(2)
            viewer.hide(4)
        }
        Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
            viewer.waitForScan()
            assertEquals("Row 2: $weatherRow2", viewer.rowText(1))
            assertEquals(0, viewer.onActivity { it.stateForTest!!.columns.hiddenCount })
            assertTrue(viewer.status(), viewer.status().startsWith("1,441 rows · CSV"))
            assertFalse(viewer.status(), viewer.status().contains("hidden"))
        }
        assertEquals(before, sha256("file/desktop/weather-stations.csv"))
    }
}
