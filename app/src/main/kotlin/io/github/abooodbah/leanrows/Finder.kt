package io.github.abooodbah.leanrows

/**
 * Find in the file, as the desktop app does it: the text's UTF-8 bytes
 * anywhere in a record's raw bytes, with only ASCII letters folded when case
 * does not have to match. A match can cross a line break inside a quoted CSV
 * field but never the end of a record. The search reads the file itself, so
 * it also works on rows the background scan has not reached yet.
 */
class Finder(private val table: Table) {
    data class Hit(val row: Long, val wrapped: Boolean)

    /** False when the last search was cancelled before it finished. */
    @Volatile var lastSearchCompleted = true
        private set

    /** The first row after [after] that contains [text], wrapping to the top once. */
    fun next(text: String, matchCase: Boolean, after: Long, cancelled: () -> Boolean = { false }): Hit? {
        val needle = needle(text, matchCase) ?: return null
        lastSearchCompleted = true
        first(needle, matchCase, after + 1, Long.MAX_VALUE, cancelled)?.let { return Hit(it, wrapped = false) }
        if (cancelled()) return cancelledResult()
        return first(needle, matchCase, 0, after + 1, cancelled)?.let { Hit(it, wrapped = true) }
            ?: if (cancelled()) cancelledResult() else null
    }

    /** The last row before [before] that contains [text], wrapping to the bottom once. */
    fun previous(text: String, matchCase: Boolean, before: Long, cancelled: () -> Boolean = { false }): Hit? {
        val needle = needle(text, matchCase) ?: return null
        lastSearchCompleted = true
        last(needle, matchCase, before, cancelled)?.let { return Hit(it, wrapped = false) }
        if (cancelled()) return cancelledResult()
        return last(needle, matchCase, Long.MAX_VALUE, cancelled, floor = before)
            ?.let { Hit(it, wrapped = true) }
            ?: if (cancelled()) cancelledResult() else null
    }

    private fun cancelledResult(): Hit? {
        lastSearchCompleted = false
        return null
    }

    private fun needle(text: String, matchCase: Boolean): ByteArray? {
        // The find field is one line, and a record never contains a line break
        // outside quotes, so line breaks cannot be part of a match.
        val bytes = text.replace("\r", "").replace("\n", "").toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return null
        return if (matchCase) bytes else ByteArray(bytes.size) { fold(bytes[it]) }
    }

    /**
     * The last matching row in [floor, before), searching back a section of
     * at least [SECTION_BYTES] at a time, from checkpoint to checkpoint. A
     * section is read once from its start, so the file is read about once.
     */
    private fun last(
        needle: ByteArray,
        matchCase: Boolean,
        before: Long,
        cancelled: () -> Boolean,
        floor: Long = 0,
    ): Long? {
        val buffers = Buffers()
        var end = before
        while (end > floor) {
            val (endRow, endOffset) = table.index.nearest(end - 1)
            // Never past the checkpoint before end, so every step moves back.
            val start = minOf(table.index.nearestOffset(endOffset - SECTION_BYTES).first, endRow)
            var found: Long? = null
            walk(needle, matchCase, maxOf(start, floor), end, cancelled, buffers) { row ->
                found = row
                true
            }
            if (found != null || cancelled()) return found
            if (start <= floor) return null
            end = start
        }
        return null
    }

    private fun first(needle: ByteArray, matchCase: Boolean, from: Long, until: Long, cancelled: () -> Boolean): Long? {
        var found: Long? = null
        walk(needle, matchCase, from, until, cancelled, Buffers()) { row ->
            found = row
            false
        }
        return found
    }

    /** Read buffers for one search, reused from section to section. */
    private class Buffers {
        val chunk = ByteArray(Table.CHUNK)
        val ends = LongArray(Table.CHUNK + 1)
        var endCount = 0
        val collect: (Long, Long) -> Boolean = { _, end ->
            ends[endCount++] = end
            true
        }
    }

    /**
     * Calls [onMatch] once for each row in [from, until) that contains the
     * needle, in order, until it returns false.
     */
    private fun walk(
        needle: ByteArray,
        matchCase: Boolean,
        from: Long,
        until: Long,
        cancelled: () -> Boolean,
        buffers: Buffers,
        onMatch: (Long) -> Boolean,
    ) {
        val (checkpointRow, checkpointOffset) = table.index.nearest(from)
        val scanner = RecordScanner(table.format, checkpointOffset)
        val buffer = buffers.chunk
        val ends = buffers.ends
        val collect = buffers.collect
        val failure = failureTable(needle)
        var matched = 0
        var position = checkpointOffset
        var rowsBefore = checkpointRow // records that ended before this chunk
        var lastReported = -1L
        while (true) {
            if (cancelled()) return
            val read = table.source.read(position, buffer, 0, buffer.size)
            buffers.endCount = 0
            if (read <= 0) {
                scanner.finish(collect)
                return
            }
            scanner.feed(buffer, 0, read, collect)
            val endCount = buffers.endCount
            var endIndex = 0
            for (i in 0 until read) {
                val byte = if (matchCase) buffer[i] else fold(buffer[i])
                while (matched > 0 && needle[matched] != byte) matched = failure[matched - 1]
                if (needle[matched] == byte) matched++
                if (matched == needle.size) {
                    matched = failure[matched - 1]
                    val start = position + i + 1 - needle.size
                    while (endIndex < endCount && ends[endIndex] <= start) endIndex++
                    val row = rowsBefore + endIndex
                    if (row >= until) return
                    if (row >= from && row != lastReported) {
                        lastReported = row
                        if (!onMatch(row)) return
                    }
                }
            }
            rowsBefore += endCount
            if (rowsBefore >= until) return
            position += read
        }
    }

    private fun failureTable(needle: ByteArray): IntArray {
        val failure = IntArray(needle.size)
        var length = 0
        for (i in 1 until needle.size) {
            while (length > 0 && needle[i] != needle[length]) length = failure[length - 1]
            if (needle[i] == needle[length]) length++
            failure[i] = length
        }
        return failure
    }

    private fun fold(byte: Byte): Byte =
        if (byte in UPPER_A..UPPER_Z) (byte + CASE_OFFSET).toByte() else byte

    private companion object {
        /** Find previous steps back at least this much of the file at a time. */
        const val SECTION_BYTES = 1024L * 1024
        const val UPPER_A = 'A'.code.toByte()
        const val UPPER_Z = 'Z'.code.toByte()
        const val CASE_OFFSET = 'a'.code - 'A'.code
    }
}
