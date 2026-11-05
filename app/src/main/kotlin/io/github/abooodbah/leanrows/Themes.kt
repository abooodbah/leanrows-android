package io.github.abooodbah.leanrows

/**
 * The five themes of LeanMark 0.6.0, with its exact colours (the theme tokens
 * in LeanMark's assets/reader.css). System shows Light or Dark as the device
 * does. The names are the ones every Lean app saves.
 */
enum class Theme(val key: String, val title: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    DIM("dim", "Dim"),
    DUSK("dusk", "Dusk"),
    ;

    /** The theme whose colours are on screen: System shows Light or Dark. */
    fun shown(systemDark: Boolean): Theme = if (this == SYSTEM) (if (systemDark) DARK else LIGHT) else this

    /** "Dusk", or "System (Dark)", so the name never depends on colour alone. */
    fun displayName(systemDark: Boolean): String = if (this == SYSTEM) "System (${shown(systemDark).title})" else title

    /** The next theme for the theme button, as LeanMark cycles them. */
    val next: Theme get() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(key: String?): Theme = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** One theme's colours, as ARGB. Names follow LeanMark's tokens. */
data class Palette(
    val dark: Boolean,
    val canvas: Int,
    val surface: Int,
    val surfaceMuted: Int,
    val border: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textDisabled: Int,
    val ring: Int,
    val link: Int,
    val selection: Int,
    val find: Int,
    val findCurrent: Int,
    val findText: Int,
) {
    companion object {
        private fun rgb(value: Long) = (0xFF000000L or value).toInt()

        val LIGHT = Palette(
            dark = false,
            canvas = rgb(0xFAF9F7),
            surface = rgb(0xFFFFFF),
            surfaceMuted = rgb(0xF4F2EE),
            border = rgb(0xE8E4DC),
            textPrimary = rgb(0x1A1917),
            textSecondary = rgb(0x5A554C),
            textDisabled = rgb(0xA7A195),
            ring = rgb(0x3B82F6),
            link = rgb(0x1D4ED8),
            selection = rgb(0xCBDCFF),
            find = rgb(0xF8D66D),
            findCurrent = rgb(0xEF9F27),
            findText = rgb(0x0E0D0B),
        )

        val DARK = Palette(
            dark = true,
            canvas = rgb(0x0E0D0B),
            surface = rgb(0x1A1917),
            surfaceMuted = rgb(0x2B2924),
            border = rgb(0x2B2924),
            textPrimary = rgb(0xFAF9F7),
            textSecondary = rgb(0xD5CFC3),
            textDisabled = rgb(0x716B5F),
            ring = rgb(0x3B82F6),
            link = rgb(0x60A5FA),
            selection = rgb(0x23468C),
            find = rgb(0x735D1E),
            findCurrent = rgb(0xB45309),
            findText = rgb(0x0E0D0B),
        )

        val DIM = Palette(
            dark = true,
            canvas = rgb(0x1E2023),
            surface = rgb(0x25282C),
            surfaceMuted = rgb(0x2D3035),
            border = rgb(0x393D43),
            textPrimary = rgb(0xC8CCD2),
            textSecondary = rgb(0x9EA4AC),
            textDisabled = rgb(0x6C727A),
            ring = rgb(0x6C9BE0),
            link = rgb(0x86AEE6),
            selection = rgb(0x34486A),
            find = rgb(0x5C4D1F),
            findCurrent = rgb(0x7A5317),
            findText = rgb(0xE8E4DC),
        )

        val DUSK = Palette(
            dark = true,
            canvas = rgb(0x221E19),
            surface = rgb(0x29241E),
            surfaceMuted = rgb(0x322C25),
            border = rgb(0x3F3830),
            textPrimary = rgb(0xD4C8B4),
            textSecondary = rgb(0xA99C87),
            textDisabled = rgb(0x776C5C),
            ring = rgb(0xC99A62),
            link = rgb(0xE0A86E),
            selection = rgb(0x4D3F2C),
            find = rgb(0x6A5420),
            findCurrent = rgb(0x82561C),
            findText = rgb(0xF1E9DB),
        )

        fun of(theme: Theme, systemDark: Boolean): Palette = when (theme.shown(systemDark)) {
            Theme.DARK -> DARK
            Theme.DIM -> DIM
            Theme.DUSK -> DUSK
            else -> LIGHT
        }

        /**
         * The theme button's dot: one fixed colour per theme with the same grey
         * ring, so the five dots differ on any toolbar. System is half Light,
         * half Dark: the left and right colours.
         */
        fun dot(theme: Theme): Pair<Int, Int> = when (theme) {
            Theme.SYSTEM -> rgb(0xFFFFFF) to rgb(0x000000)
            Theme.LIGHT -> rgb(0xFFFFFF) to rgb(0xFFFFFF)
            Theme.DARK -> rgb(0x000000) to rgb(0x000000)
            Theme.DIM -> rgb(0x7F8A99) to rgb(0x7F8A99)
            Theme.DUSK -> rgb(0xC98F4F) to rgb(0xC98F4F)
        }

        const val DOT_RING = 0xFF8C8C8C.toInt()
    }
}

/** Table zoom, on LeanMark's ladder of levels, as fractions of actual size. */
object Zoom {
    val LEVELS = doubleArrayOf(0.5, 0.67, 0.75, 0.8, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)
    const val DEFAULT = 1.0

    /** The next level above (direction > 0) or below (< 0), staying at the ends; 0 resets. */
    fun step(current: Double, direction: Int): Double {
        val tolerance = 0.001
        if (direction == 0 || !current.isFinite()) return DEFAULT
        if (direction > 0) return LEVELS.firstOrNull { it > current + tolerance } ?: LEVELS.last()
        return LEVELS.lastOrNull { it < current - tolerance } ?: LEVELS.first()
    }

    /** A saved level snapped to the ladder; anything out of range gives the default. */
    fun normalize(value: Double): Double {
        if (!value.isFinite() || value < LEVELS.first() - 0.01 || value > LEVELS.last() + 0.01) return DEFAULT
        return LEVELS.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT
    }

    fun percent(level: Double): Int = Math.round(level * 100).toInt()
}
