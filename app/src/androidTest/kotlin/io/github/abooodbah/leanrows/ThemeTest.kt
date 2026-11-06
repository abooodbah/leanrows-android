package io.github.abooodbah.leanrows

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LeanMark's five themes, the theme button with its dot, and the settings
 * sheet with theme and zoom. Files arrive through the test provider as
 * content:// URIs. Each test starts from the defaults.
 */
@RunWith(AndroidJUnit4::class)
class ThemeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    @Before @After fun defaults() {
        context.getSharedPreferences(SavedSettings.FILE, 0).edit().clear().commit()
        // The system dark theme came with Android 10; on Android 8 the shell
        // command is not needed, and its UiAutomation connection can drop.
        if (Build.VERSION.SDK_INT >= 29) shell("cmd uimode night no")
    }

    private fun background(view: View) = (view.background as ColorDrawable).color

    private fun Viewer.assertShows(palette: Palette) = onActivity { activity ->
        assertEquals(palette, activity.paletteForTest)
        assertEquals(palette.surface, background(activity.findViewById(R.id.toolbar)))
        assertEquals(palette.canvas, background(activity.findViewById(R.id.table_frame)))
        assertEquals(palette.textPrimary, activity.findViewById<TextView>(R.id.title).currentTextColor)
        assertEquals(palette.textSecondary, activity.findViewById<TextView>(R.id.status).currentTextColor)
        assertEquals(palette.link, activity.findViewById<Button>(R.id.find).currentTextColor)
        val style = activity.stateForTest!!.style
        assertEquals(palette.textPrimary, style.text.color)
        assertEquals(palette.surface, style.surface.color)
        assertEquals(palette.border, style.rule.color)
    }

    @Test
    fun theThemeButtonCyclesTheFiveThemesInPlace() = Viewer.view("file/generated/large.log").use { viewer ->
        viewer.waitForScan()
        viewer.onActivity { it.listForTest.scrollToPosition(5_000) }
        viewer.rowText(5_000)
        val first = viewer.firstVisible()
        val activity = viewer.onActivity { it }
        val button = viewer.onActivity { it.findViewById<View>(R.id.theme) }
        assertEquals("Theme: System (Light). Activate to change theme.", button.contentDescription)
        assertEquals("Theme: System (Light)", button.tooltipText)
        viewer.assertShows(Palette.LIGHT)
        for ((theme, palette) in listOf(Theme.LIGHT to Palette.LIGHT, Theme.DARK to Palette.DARK, Theme.DIM to Palette.DIM, Theme.DUSK to Palette.DUSK)) {
            viewer.onActivity { it.findViewById<View>(R.id.theme).performClick() }
            viewer.waitFor(theme.title) { it.themeForTest == theme }
            viewer.assertShows(palette)
            assertEquals("Theme: ${theme.title}", button.tooltipText)
            assertEquals(Palette.dot(theme), viewer.onActivity { it.dotForTest })
        }
        viewer.onActivity { it.findViewById<View>(R.id.theme).performClick() }
        viewer.waitFor("System") { it.themeForTest == Theme.SYSTEM }
        // The same screen, not a new one, at the same place in the file.
        assertTrue(viewer.onActivity { it } === activity)
        assertTrue("first row ${viewer.firstVisible()}, was $first", kotlin.math.abs(viewer.firstVisible() - first) <= 2)
    }

    /**
     * The settings icon is drawn in code: as a vector drawable in an
     * ImageButton it made the first frame about 140 ms later on the emulator.
     */
    @Test
    fun theSettingsIconIsDrawnInCodeInTheLinkColour() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.onActivity { it.findViewById<View>(R.id.theme).performClick() } // Light
        viewer.waitFor("Light") { it.themeForTest == Theme.LIGHT }
        val (drawable, pixels) = viewer.onActivity { activity ->
            val button = activity.findViewById<android.widget.ImageButton>(R.id.settings)
            val icon = button.drawable
            val bitmap = android.graphics.Bitmap.createBitmap(icon.intrinsicWidth, icon.intrinsicHeight, android.graphics.Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, bitmap.width, bitmap.height)
            icon.draw(android.graphics.Canvas(bitmap))
            val all = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            icon to all.count { (it and 0xFFFFFF) == (Palette.LIGHT.link and 0xFFFFFF) && (it ushr 24) == 0xFF }
        }
        assertTrue("icon: ${drawable.javaClass.name}", drawable is SlidersDrawable)
        assertTrue("$pixels pixels in the link colour", pixels > 20)
    }

    @Test
    fun theSettingsSheetSetsThemeAndZoomForTheTableOnly() = Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
        viewer.waitForScan()
        viewer.rowText(0)
        val textSize = viewer.onActivity { it.stateForTest!!.style.text.textSize }
        val rowHeight = viewer.onActivity { it.stateForTest!!.style.rowHeight }
        val titleSize = viewer.onActivity { it.findViewById<TextView>(R.id.title).textSize }
        viewer.onActivity { it.findViewById<View>(R.id.settings).performClick() }
        viewer.waitFor("the settings sheet") { it.settingsForTest?.isShowing == true }
        viewer.onActivity { it.settingsForTest!!.findViewById<RadioButton>(R.id.theme_dusk).performClick() }
        viewer.waitFor("Dusk") { it.themeForTest == Theme.DUSK }
        viewer.assertShows(Palette.DUSK)

        viewer.onActivity { it.settingsForTest!!.findViewById<View>(R.id.zoom_in).performClick() }
        viewer.waitFor("110%") { it.settingsForTest!!.findViewById<TextView>(R.id.zoom_value).text == "110%" }
        assertEquals(textSize * 1.1f, viewer.onActivity { it.stateForTest!!.style.text.textSize }, 0.01f)
        assertTrue(viewer.onActivity { it.stateForTest!!.style.rowHeight } > rowHeight)
        viewer.waitFor("taller rows") { it.listForTest.getChildAt(0).height == it.stateForTest!!.style.rowHeight }
        // The toolbar keeps its size.
        assertEquals(titleSize, viewer.onActivity { it.findViewById<TextView>(R.id.title).textSize }, 0f)

        viewer.onActivity { it.settingsForTest!!.findViewById<View>(R.id.zoom_reset).performClick() }
        viewer.waitFor("100%") { it.settingsForTest!!.findViewById<TextView>(R.id.zoom_value).text == "100%" }
        viewer.onActivity { it.settingsForTest!!.findViewById<View>(R.id.zoom_out).performClick() }
        viewer.waitFor("90%") { it.zoomForTest == 0.9 }
        assertEquals(textSize * 0.9f, viewer.onActivity { it.stateForTest!!.style.text.textSize }, 0.01f)
        viewer.onActivity { it.settingsForTest!!.findViewById<View>(R.id.settings_close).performClick() }
        viewer.waitFor("the sheet to close") { it.settingsForTest?.isShowing != true }
    }

    @Test
    fun themeAndZoomAreRememberedBetweenRuns() {
        Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
            viewer.waitForScan()
            viewer.onActivity { it.findViewById<View>(R.id.settings).performClick() }
            viewer.waitFor("the settings sheet") { it.settingsForTest?.isShowing == true }
            viewer.onActivity {
                it.settingsForTest!!.findViewById<RadioButton>(R.id.theme_dim).performClick()
                it.settingsForTest!!.findViewById<View>(R.id.zoom_in).performClick()
                it.settingsForTest!!.findViewById<View>(R.id.zoom_in).performClick()
            }
            viewer.waitFor("125%") { it.zoomForTest == 1.25 }
        }
        Viewer.view("file/desktop/weather-stations.csv").use { viewer ->
            viewer.waitForScan()
            assertEquals(Theme.DIM, viewer.onActivity { it.themeForTest })
            assertEquals(1.25, viewer.onActivity { it.zoomForTest }, 0.0)
            viewer.assertShows(Palette.DIM)
        }
    }

    @Test
    fun theSystemSwitchingToDarkKeepsTheFileAndThePlace() {
        // The system dark theme, and the shell command that switches it, came with Android 10.
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        systemSwitch()
    }

    private fun systemSwitch() = Viewer.view("file/generated/large.log").use { viewer ->
        viewer.waitForScan()
        viewer.onActivity { it.listForTest.scrollToPosition(5_000) }
        viewer.rowText(5_000)
        val first = viewer.firstVisible()
        val activity = viewer.onActivity { it }
        shell("cmd uimode night yes")
        viewer.waitFor("dark colours") { it.paletteForTest == Palette.DARK }
        viewer.assertShows(Palette.DARK)
        assertTrue("the same activity", viewer.onActivity { it } === activity)
        assertTrue("first row ${viewer.firstVisible()}, was $first", kotlin.math.abs(viewer.firstVisible() - first) <= 2)
        assertTrue(viewer.onActivity { it.tableForTest?.scanned == true })
    }
}
