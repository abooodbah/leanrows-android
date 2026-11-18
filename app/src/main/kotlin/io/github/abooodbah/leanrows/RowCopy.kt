package io.github.abooodbah.leanrows

/**
 * The text Copy row puts on the clipboard, as desktop LeanRows copies a row
 * with Ctrl+C: the row's number, then each cell as shown, separated by tabs.
 * A row with fewer cells than [columns] keeps an empty place for each
 * missing one, so the copy lines up with the column titles. Columns that
 * are [hidden] from the view are left out, as on the desktop.
 */
object RowCopy {
    fun text(number: String, cells: List<String>, columns: Int, hidden: (Int) -> Boolean = { false }): String {
        val text = StringBuilder(number)
        for (column in 0 until maxOf(columns, cells.size)) {
            if (hidden(column)) continue
            text.append('\t').append(cells.getOrElse(column) { "" })
        }
        return text.toString()
    }
}
