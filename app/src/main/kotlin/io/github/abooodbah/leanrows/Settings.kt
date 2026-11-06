package io.github.abooodbah.leanrows

import android.content.Context

/** The theme and the table zoom, remembered between runs. */
class SavedSettings(context: Context) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var theme: Theme
        get() = Theme.of(preferences.getString(THEME, null))
        set(value) = preferences.edit().putString(THEME, value.key).apply()

    var zoom: Double
        get() = Zoom.normalize(preferences.getFloat(ZOOM, Zoom.DEFAULT.toFloat()).toDouble())
        set(value) = preferences.edit().putFloat(ZOOM, value.toFloat()).apply()

    companion object {
        const val FILE = "settings"
        private const val THEME = "theme"
        private const val ZOOM = "zoom"
    }
}
