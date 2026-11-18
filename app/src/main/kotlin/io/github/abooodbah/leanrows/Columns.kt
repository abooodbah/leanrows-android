package io.github.abooodbah.leanrows

/**
 * Column titles and widths, as the desktop names them: "Row", then
 * "Column 1" to "Column N" for delimited files, or one "Preview" column for
 * line formats. Excel sheets use the sheet's letters, A to Z, AA and on.
 * Widths follow the widest text in the first rows read,
 * between [minimum] and [maximum] pixels, and then stay put so the table does
 * not shift while scrolling; later rows only add columns. Longer cells are
 * cut with an ellipsis and shown whole when tapped.
 *
 * Columns can be hidden from the view, as in desktop LeanRows 0.6.0. A
 * hidden column keeps its title, its width and its place in the file; it is
 * only left out of what is drawn, tapped, panned and copied. The last shown
 * column cannot be hidden. Nothing about it is saved.
 */
class Columns(
    val preview: Boolean,
    private val measure: (String) -> Float,
    private var padding: Float,
    private var minimum: Float,
    private var maximum: Float,
    /** Characters measured per cell; anything longer is at [maximum] anyway. */
    private val measuredChars: Int = 80,
    /** Title columns A, B, C as a spreadsheet does, instead of "Column 1". */
    private val lettered: Boolean = false,
) {
    private var sized = false

    var titles: List<String> = if (preview) listOf(PREVIEW) else emptyList()
        private set

    var widths: FloatArray = FloatArray(titles.size) { width(titles[it]) }
        private set

    var rowNumberWidth = 0f
        private set

    /** One flag per column; true when the column is hidden from the view. */
    private var hidden = BooleanArray(titles.size)

    var hiddenCount = 0
        private set

    /** The shown columns, in order, by their index in the file. */
    val shown: List<Int> get() = titles.indices.filter { !hidden[it] }

    val totalWidth: Float
        get() {
            var total = rowNumberWidth
            for (index in widths.indices) if (!hidden[index]) total += widths[index]
            return total
        }

    fun isHidden(column: Int): Boolean = hidden.getOrElse(column) { false }

    /** A shown column can be hidden while another column stays shown. */
    fun canHide(column: Int): Boolean = column in titles.indices && !hidden[column] && titles.size - hiddenCount > 1

    /** Hides [column] from the view when [canHide] allows it, and says whether it did. */
    fun hide(column: Int): Boolean {
        if (!canHide(column)) return false
        hidden[column] = true
        hiddenCount++
        return true
    }

    /** Shows every column again. Returns false when none was hidden. */
    fun showAll(): Boolean {
        if (hiddenCount == 0) return false
        hidden.fill(false)
        hiddenCount = 0
        return true
    }

    /** Pixels from the left edge of the first shown column to [column]'s left edge. */
    fun left(column: Int): Float {
        var left = 0f
        for (index in 0 until column) if (!hidden[index]) left += widths[index]
        return left
    }

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
            titles = titles + (titles.size + 1..count).map { if (lettered) XlsxBook.columnName(it - 1) else "Column $it" }
            widths = widths.copyOf(count).also { grown ->
                for (index in widths.size until count) grown[index] = width(titles[index])
            }
            // Columns that later rows add are shown.
            hidden = hidden.copyOf(count)
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

    /**
     * Zooms every width by [factor], with the limits for later rows. [measure]
     * must already measure at the new size.
     */
    fun scale(factor: Float) {
        padding *= factor
        minimum *= factor
        maximum *= factor
        for (index in widths.indices) widths[index] *= factor
        rowNumberWidth *= factor
    }

    /** The shown column at [x] pixels from the left edge of the first shown column, or -1. */
    fun columnAt(x: Float): Int {
        var left = 0f
        for ((index, width) in widths.withIndex()) {
            if (hidden[index]) continue
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
