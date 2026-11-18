package io.github.abooodbah.leanrows

/**
 * The text Copy row puts on the clipboard, as desktop LeanRows copies a row
 * with Ctrl+C: the row's number, then each cell as shown, separated by tabs.
 * A row with fewer cells than [columns] keeps an empty place for each
 * missing one, so the copy lines up with the column titles.
 */
object RowCopy {
    fun text(number: String, cells: List<String>, columns: Int): String {
        val text = StringBuilder(number)
        for (column in 0 until maxOf(columns, cells.size)) {
            text.append('\t').append(cells.getOrElse(column) { "" })
        }
        return text.toString()
    }
}
