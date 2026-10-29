package io.github.abooodbah.leanrows

import java.io.IOException
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * The rows the screen is showing, read [Table.WINDOW] at a time in the
 * background and kept for at most [keep] windows. That bounds memory no
 * matter how large the file is: about 400 rows, each at most 4 KiB of text
 * (the desktop's record limit). [row] and every callback run on the main
 * thread; [post] gets back to it.
 */
class RowWindows(
    private val table: Table,
    private val background: Executor,
    private val post: (Runnable) -> Unit,
    private val loaded: (first: Long, rows: List<Row>) -> Unit,
    private val failed: (IOException) -> Unit,
    private val keep: Int = 3,
) {
    private val cache = LinkedHashMap<Long, List<Row>>(keep + 1, 0.75f, true)
    private val pending = HashSet<Long>()

    @Volatile private var focus = 0L

    @Volatile private var closed = false

    val cachedWindows: Int get() = cache.size

    /** Row [index] (0-based) if it has been read; otherwise null, and it is being read. */
    fun row(index: Long): Row? {
        val window = index / Table.WINDOW
        focus = window
        val offset = (index - window * Table.WINDOW).toInt()
        // Read the neighbouring window before the screen gets there.
        if (offset >= Table.WINDOW * 3 / 4) request(window + 1)
        if (offset < Table.WINDOW / 4 && window > 0) request(window - 1)
        val rows = cache[window] ?: return null.also { request(window) }
        return rows.getOrNull(offset)
    }

    fun close() {
        closed = true
        cache.clear()
    }

    private fun request(window: Long) {
        if (window in pending || cache.containsKey(window)) return
        pending += window
        background.execute {
            // During a fast scroll most requested windows are already off
            // screen by the time their turn comes; skip those.
            if (closed || abs(window - focus) > 1) {
                post(Runnable { pending -= window })
                return@execute
            }
            val rows = try {
                table.rows(window * Table.WINDOW, Table.WINDOW)
            } catch (error: IOException) {
                post(Runnable {
                    pending -= window
                    if (!closed) failed(error)
                })
                return@execute
            }
            post(Runnable {
                pending -= window
                if (closed) return@Runnable
                cache[window] = rows
                while (cache.size > keep) {
                    val eldest = cache.keys.first { it != window }
                    cache.remove(eldest)
                }
                if (rows.isNotEmpty()) loaded(window * Table.WINDOW, rows)
            })
        }
    }
}
