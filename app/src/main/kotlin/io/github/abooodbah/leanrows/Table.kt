package io.github.abooodbah.leanrows

import java.io.IOException

/**
 * Random-access reads of the open file. The background scan and the screen
 * read at the same time, so implementations must allow concurrent calls
 * (positional reads such as pread do).
 */
interface ByteSource {
    val size: Long

    /** Reads up to [length] bytes at [position]; returns the count, 0 or less at the end. */
    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), into, offset, count)
        return count
    }
}

/** One row as shown: its number (from 1) and its cells. */
class Row(val number: Long, val cells: List<String>)

/**
 * A row-based file opened read-only. [scan] walks it once in the background
 * to count rows and keep a bounded set of checkpoints; [rows] reads any
 * window of rows by seeking to the nearest checkpoint. Only the rows asked
 * for are ever held as text, so memory follows the screen, not the file.
 */
class Table(val source: ByteSource, val format: Format) {
    val index = RowIndex()

    @Volatile var scanned = false
        private set

    @Volatile var unterminatedQuote = false
        private set

    @Volatile var rowCount = 0L
        private set

    /**
     * Reads the whole file once, in [CHUNK] steps. [progress] is called with
     * the rows found so far after each step; [cancelled] stops the walk early.
     * Returns false when cancelled.
     */
    fun scan(cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}): Boolean {
        val buffer = ByteArray(CHUNK)
        val scanner = RecordScanner(format)
        var position = 0L
        var row = 0L
        val observe: (Long, Long) -> Boolean = { start, _ ->
            index.observe(row, start)
            row++
            true
        }
        while (true) {
            if (cancelled()) return false
            val read = source.read(position, buffer, 0, buffer.size)
            if (read <= 0) break
            scanner.feed(buffer, 0, read, observe)
            position += read
            rowCount = row
            progress(row)
        }
        unterminatedQuote = scanner.finish(observe)
        rowCount = row
        scanned = true
        progress(row)
        return true
    }

    /** Rows [first] to [first] + [count] - 1 (0-based), or fewer at the end. */
    fun rows(first: Long, count: Int): List<Row> {
        if (count <= 0) return emptyList()
        val (checkpointRow, checkpointOffset) = index.nearest(first)
        val spans = ArrayList<LongArray>(count)
        val scanner = RecordScanner(format, checkpointOffset)
        val buffer = ByteArray(CHUNK)
        var position = checkpointOffset
        var row = checkpointRow
        val last = first + count
        val collect: (Long, Long) -> Boolean = { start, end ->
            if (row >= first && row < last) spans += longArrayOf(row, start, end)
            row++
            row < last
        }
        var more = true
        while (more) {
            val read = source.read(position, buffer, 0, buffer.size)
            if (read <= 0) {
                scanner.finish(collect)
                break
            }
            val consumed = scanner.feed(buffer, 0, read, collect)
            position += consumed
            more = consumed == read
        }
        val record = ByteArray(Fields.MAX_RECORD_BYTES)
        return spans.map { (number, start, end) ->
            val length = minOf(end - start, Fields.MAX_RECORD_BYTES.toLong()).toInt()
            var filled = 0
            while (filled < length) {
                val read = source.read(start + filled, record, filled, length - filled)
                if (read <= 0) throw IOException("the file ended early")
                filled += read
            }
            Row(number + 1, Fields.cells(record, length, format, end - start > Fields.MAX_RECORD_BYTES))
        }
    }

    companion object {
        const val CHUNK = 64 * 1024

        /** Rows read at once, as the desktop's viewport. */
        const val WINDOW = 128
    }
}
