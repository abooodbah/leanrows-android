package io.github.abooodbah.leanrows

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Zoom as in desktop LeanRows 0.5.1: a step changes only the rows and their
 * titles, in one pass over the rows on screen, and column widths come back
 * exactly when zooming in and out. Files arrive through the test provider as
 * content:// URIs.
 */
@RunWith(AndroidJUnit4::class)
class ZoomTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before @After fun defaults() {
        context.getSharedPreferences(SavedSettings.FILE, 0).edit().clear().commit()
    }

    private fun Viewer.openSettings() {
        onActivity { it.findViewById<View>(R.id.settings).performClick() }
        waitFor("the settings sheet") { it.settingsForTest?.isShowing == true }
    }

    private fun Viewer.press(id: Int, percent: Int) {
        onActivity { it.settingsForTest!!.findViewById<View>(id).performClick() }
        waitFor("$percent%") { it.settingsForTest!!.findViewById<TextView>(R.id.zoom_value).text == "$percent%" }
        waitForRowsLaidOut()
    }

    /** Every row on screen and the titles have the zoom's height. */
    private fun Viewer.waitForRowsLaidOut() = waitFor("rows at the new height") { activity ->
        val list = activity.listForTest
        val style = activity.stateForTest!!.style
        !list.isLayoutRequested && list.childCount > 0 &&
            (0 until list.childCount).all { list.getChildAt(it).height == style.rowHeight } &&
            activity.findViewById<View>(R.id.header).height == style.headerHeight
    }

    private fun Viewer.widths() = onActivity { it.stateForTest!!.columns.widths.copyOf() }

    @Test
    fun aZoomStepRebindsTheRowsOnScreenInsteadOfMakingNewOnes() = Viewer.view("file/generated/large-multiline.csv").use { viewer ->
        viewer.waitForScan()
        viewer.onActivity { it.listForTest.scrollToPosition(50_000) }
        viewer.rowText(50_000)
        val first = viewer.firstVisible()
        val made = viewer.onActivity { it.rowViewsMadeForTest }
        viewer.openSettings()
        viewer.press(R.id.zoom_in, 110)
        viewer.press(R.id.zoom_in, 125)
        // Taller rows: fewer fit on screen, so every one is a row view that was already there.
        assertEquals(made, viewer.onActivity { it.rowViewsMadeForTest })
        assertEquals(first, viewer.firstVisible())
        assertTrue(viewer.rowText(first + 1), viewer.rowText(first + 1).startsWith("Row ${first + 2}: "))

        viewer.press(R.id.zoom_reset, 100)
        viewer.press(R.id.zoom_out, 90)
        // Shorter rows: only the rows that now also fit are new.
        val shown = viewer.onActivity { it.listForTest.childCount }
        assertTrue("${viewer.onActivity { it.rowViewsMadeForTest }} row views for $shown rows on screen", viewer.onActivity { it.rowViewsMadeForTest } <= maxOf(made, shown) + 2)
        assertEquals(first, viewer.firstVisible())

        viewer.onActivity { it.settingsForTest!!.dismiss() }
        viewer.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        viewer.waitFor("landscape") { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        viewer.waitForRowsLaidOut()
        viewer.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test
    fun widthsComeBackExactlyAfterZoomingInAndOut() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(0)
        val widths = viewer.widths()
        val rowNumbers = viewer.onActivity { it.stateForTest!!.columns.rowNumberWidth }
        viewer.openSettings()
        repeat(2) {
            for (percent in listOf(110, 125, 150, 175, 200, 250, 300)) viewer.press(R.id.zoom_in, percent)
            for (percent in listOf(250, 200, 175, 150, 125, 110, 100, 90, 80, 75, 67, 50)) viewer.press(R.id.zoom_out, percent)
            viewer.press(R.id.zoom_reset, 100)
        }
        assertArrayEquals(widths, viewer.widths(), 0f)
        assertEquals(rowNumbers, viewer.onActivity { it.stateForTest!!.columns.rowNumberWidth }, 0f)
    }

    @Test
    fun aFileThatEndsInsideAQuoteZoomsAndComesBack() = Viewer.view("file/generated/malformed.csv").use { viewer ->
        viewer.waitForScan()
        val before = viewer.rowText(5)
        val widths = viewer.widths()
        viewer.openSettings()
        viewer.press(R.id.zoom_in, 110)
        viewer.press(R.id.zoom_in, 125)
        assertEquals(before, viewer.rowText(5))
        viewer.press(R.id.zoom_reset, 100)
        assertArrayEquals(widths, viewer.widths(), 0f)
        assertTrue(viewer.onActivity { it.statusForTest }.contains("The file ends inside a quoted field"))
    }

    @Test
    fun aFileOpenedWhileZoomedGetsColumnsSizedForThatZoom() {
        val atDefault = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
            viewer.waitForScan()
            viewer.rowText(0)
            viewer.widths()
        }
        SavedSettings(context).zoom = 2.0
        Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
            viewer.waitForScan()
            viewer.rowText(0)
            assertEquals(2.0, viewer.onActivity { it.zoomForTest }, 0.0)
            val zoomed = viewer.widths()
            for (index in atDefault.indices) {
                assertEquals("Column ${index + 1}", atDefault[index] * 2, zoomed[index], atDefault[index] * 0.04f)
            }
            // Back at 100%, the columns are about as wide as when the file was opened at 100%.
            viewer.openSettings()
            viewer.press(R.id.zoom_reset, 100)
            val reset = viewer.widths()
            for (index in atDefault.indices) {
                assertEquals("Column ${index + 1} at 100%", atDefault[index], reset[index], atDefault[index] * 0.04f)
            }
        }
    }
}
