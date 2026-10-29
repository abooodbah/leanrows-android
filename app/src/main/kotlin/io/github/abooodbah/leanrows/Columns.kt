package io.github.abooodbah.leanrows

/**
 * Column titles and widths, as the desktop names them: "Row", then
 * "Column 1" to "Column N" for delimited files, or one "Preview" column for
 * line formats. Widths follow the widest text in the first rows read,
 * between [minimum] and [maximum] pixels, and then stay put so the table does
 * not shift while scrolling; later rows only add columns. Longer cells are
 * cut with an ellipsis and shown whole when tapped.
 */
class Columns(
    val preview: Boolean,
    private val measure: (String) -> Float,
    private val padding: Float,
    private val minimum: Float,
    private val maximum: Float,
    /** Characters measured per cell; anything longer is at [maximum] anyway. */
    private val measuredChars: Int = 80,
) {
    private var sized = false

    var titles: List<String> = if (preview) listOf(PREVIEW) else emptyList()
        private set

    var widths: FloatArray = FloatArray(titles.size) { width(titles[it]) }
        private set

    var rowNumberWidth = 0f
        private set

    val totalWidth: Float get() = rowNumberWidth + widths.sum()

    init {
        fitRowNumbers(999_999)
    }

    /**
     * Sizes the columns from the first [rows] read, and adds columns that
     * later rows have. Returns true when anything changed.
     */
    fun fit(rows: List<Row>): Boolean {
        var changed = false
        val previousCount = titles.size
        val count = rows.maxOfOrNull { it.cells.size } ?: 0
        if (!preview && count > titles.size) {
            titles = titles + (titles.size + 1..count).map { "Column $it" }
            widths = widths.copyOf(count).also { grown ->
                for (index in widths.size until count) grown[index] = width(titles[index])
            }
            changed = true
        }
        if (sized && !changed) return false
        val firstTime = !sized
        sized = true
        for (row in rows) {
            for ((index, cell) in row.cells.withIndex()) {
                if (!firstTime && index < previousCount) continue
                if (index >= widths.size) break
                val wanted = width(cell)
                if (wanted > widths[index]) {
                    widths[index] = wanted
                    changed = true
                }
            }
        }
        return changed
    }

    /** Makes the row number column wide enough for [rows]. Returns true when it grew. */
    fun fitRowNumbers(rows: Long): Boolean {
        val wanted = maxOf(width(ROW), measure(rows.toString()) + 2 * padding)
        if (wanted <= rowNumberWidth) return false
        rowNumberWidth = wanted
        return true
    }

    /** The column at [x] pixels from the left edge of the first column, or -1. */
    fun columnAt(x: Float): Int {
        var left = 0f
        for ((index, width) in widths.withIndex()) {
            if (x >= left && x < left + width) return index
            left += width
        }
        return -1
    }

    private fun width(text: String): Float =
        (measure(if (text.length > measuredChars) text.substring(0, measuredChars) else text) + 2 * padding)
            .coerceIn(minimum, maximum)

    companion object {
        const val ROW = "Row"
        const val PREVIEW = "Preview"
    }
}
