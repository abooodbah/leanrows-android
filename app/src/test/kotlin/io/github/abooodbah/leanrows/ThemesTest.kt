package io.github.abooodbah.leanrows

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * The five themes carry LeanMark 0.6.0's exact colours: the theme tokens in
 * LeanMark's assets/reader.css, copied here by hand as the expected values.
 */
class ThemesTest {
    private fun hex(color: Int) = "#%06x".format(color and 0xFFFFFF)

    private fun tokens(palette: Palette) = listOf(
        palette.canvas, palette.surface, palette.surfaceMuted, palette.border, palette.textPrimary,
        palette.textSecondary, palette.textDisabled, palette.ring, palette.link, palette.selection,
        palette.find, palette.findCurrent, palette.findText,
    ).map(::hex)

    @Test fun lightHasLeanMarksColours() = assertEquals(
        listOf("#faf9f7", "#ffffff", "#f4f2ee", "#e8e4dc", "#1a1917", "#5a554c", "#a7a195", "#3b82f6", "#1d4ed8", "#cbdcff", "#f8d66d", "#ef9f27", "#0e0d0b"),
        tokens(Palette.LIGHT),
    )

    @Test fun darkHasLeanMarksColours() = assertEquals(
        listOf("#0e0d0b", "#1a1917", "#2b2924", "#2b2924", "#faf9f7", "#d5cfc3", "#716b5f", "#3b82f6", "#60a5fa", "#23468c", "#735d1e", "#b45309", "#0e0d0b"),
        tokens(Palette.DARK),
    )

    @Test fun dimHasLeanMarksColours() = assertEquals(
        listOf("#1e2023", "#25282c", "#2d3035", "#393d43", "#c8ccd2", "#9ea4ac", "#6c727a", "#6c9be0", "#86aee6", "#34486a", "#5c4d1f", "#7a5317", "#e8e4dc"),
        tokens(Palette.DIM),
    )

    @Test fun duskHasLeanMarksColours() = assertEquals(
        listOf("#221e19", "#29241e", "#322c25", "#3f3830", "#d4c8b4", "#a99c87", "#776c5c", "#c99a62", "#e0a86e", "#4d3f2c", "#6a5420", "#82561c", "#f1e9db"),
        tokens(Palette.DUSK),
    )

    @Test fun systemShowsLightOrDarkAsTheDeviceDoes() {
        assertEquals(Palette.LIGHT, Palette.of(Theme.SYSTEM, systemDark = false))
        assertEquals(Palette.DARK, Palette.of(Theme.SYSTEM, systemDark = true))
        assertEquals(Palette.DUSK, Palette.of(Theme.DUSK, systemDark = false))
        assertEquals("System (Dark)", Theme.SYSTEM.displayName(systemDark = true))
        assertEquals("Dim", Theme.DIM.displayName(systemDark = true))
    }

    @Test fun theButtonCyclesInLeanMarksOrder() {
        assertEquals(
            listOf(Theme.LIGHT, Theme.DARK, Theme.DIM, Theme.DUSK, Theme.SYSTEM),
            generateSequence(Theme.SYSTEM.next) { if (it == Theme.SYSTEM) null else it.next }.toList(),
        )
        assertEquals(Theme.DUSK, Theme.of("dusk"))
        assertEquals(Theme.SYSTEM, Theme.of("sepia"))
        assertEquals(Theme.SYSTEM, Theme.of(null))
    }

    @Test fun eachThemeHasLeanMarksDot() {
        assertEquals("#ffffff #000000", Palette.dot(Theme.SYSTEM).let { "${hex(it.first)} ${hex(it.second)}" })
        assertEquals(listOf("#ffffff", "#000000", "#7f8a99", "#c98f4f"), listOf(Theme.LIGHT, Theme.DARK, Theme.DIM, Theme.DUSK).map { hex(Palette.dot(it).first) })
        assertEquals("#8c8c8c", hex(Palette.DOT_RING))
    }

    @Test fun zoomStepsOnLeanMarksLadder() {
        assertEquals(1.1, Zoom.step(1.0, 1), 0.0)
        assertEquals(0.9, Zoom.step(1.0, -1), 0.0)
        assertEquals(3.0, Zoom.step(3.0, 1), 0.0)
        assertEquals(0.5, Zoom.step(0.5, -1), 0.0)
        assertEquals(1.0, Zoom.step(2.5, 0), 0.0)
        assertEquals(1.25, Zoom.step(1.12, 1), 0.0)
        assertEquals(1.25, Zoom.normalize(1.3), 0.0)
        assertEquals(1.0, Zoom.normalize(9.0), 0.0)
        assertEquals(1.0, Zoom.normalize(Double.NaN), 0.0)
        assertEquals(67, Zoom.percent(0.67))
    }

    @Test fun theDialogColoursInResourcesMatchThePalettes() {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/colors.xml")).documentElement
        val colors = (0 until root.getElementsByTagName("color").length)
            .map { root.getElementsByTagName("color").item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim().lowercase() }
        for ((name, palette) in listOf("light" to Palette.LIGHT, "dark" to Palette.DARK, "dim" to Palette.DIM, "dusk" to Palette.DUSK)) {
            assertEquals(name, hex(palette.surface), colors["${name}_surface"])
            assertEquals(name, hex(palette.textPrimary), colors["${name}_text"])
            assertEquals(name, hex(palette.textSecondary), colors["${name}_text_secondary"])
            assertEquals(name, hex(palette.link), colors["${name}_link"])
        }
    }
}
