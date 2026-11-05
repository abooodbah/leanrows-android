package io.github.abooodbah.leanrows

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

enum class SortMode { TEXT, NUMERIC }

/** Sort by [column] (from 0), in text or numeric order. */
data class SortRequest(val column: Int, val mode: SortMode, val descending: Boolean)

/** A sort that cannot go on, with a message for the status line. */
class SortException(message: String) : IOException(message)

class SortCancelled : IOException("sort cancelled")

/**
 * How much memory and disk a sort may use. The defaults are the app's; tests
 * shrink them to force many runs and several merge passes.
 */
class SortLimits(
    /** Bytes of sort entries held in memory before a run is written out. */
    val runBytes: Int = 6 * 1024 * 1024,
    /** Runs joined in one merge pass, each read through its own buffer. */
    val fanIn: Int = 96,
    val readBuffer: Int = 64 * 1024,
    /** Rows whose rank is placed in one pass when the reverse map is built. */
    val inverseRows: Int = 1024 * 1024,
    /** Temporary disk the sort may use, as on the desktop. */
    val diskBytes: Long = 8L * 1024 * 1024 * 1024,
    /** Free space to leave on the device. */
    val reserveBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(runBytes >= Sorter.MAX_ENTRY && fanIn >= 2 && readBuffer >= Sorter.MAX_ENTRY && inverseRows >= 1)
    }
}

enum class SortPhase { READING, MERGING, FINISHING }

/**
 * Sorts a whole file by one column without loading it, as desktop LeanRows
 * 0.4.0 does, with the same order: text compares the field's bytes, numbers
 * compare exactly (integers, decimals, exponents), numbers come first, then
 * text, then empty values in both directions, and equal values keep the
 * file's order. Two things differ from the desktop: the file is read once,
 * from start to end, and each field's key is taken from that read; and runs
 * are joined [SortLimits.fanIn] at a time instead of two at a time.
 *
 * Memory stays under about 8 MiB whatever the size of the file. Temporary
 * files go in [folder] and are unlinked as soon as they are open, so they
 * vanish when they are closed or the process dies.
 */
class Sorter(
    private val source: ByteSource,
    private val format: Format,
    private val request: SortRequest,
    private val folder: File,
    private val limits: SortLimits = SortLimits(),
) {
    private val compare = EntryCompare(source, format, request)
    private val opened = ArrayList<Scratch>()
    private var diskUsed = 0L
    private var lastSpaceCheck = 0L

    /** Bytes read from the file to break ties between long keys, for the measurements. */
    val fallbackBytes: Long get() = compare.fallbackBytes

    fun run(cancelled: () -> Boolean = { false }, progress: (SortPhase, Long, Long) -> Unit = { _, _, _ -> }): SortedOrder {
        folder.mkdirs()
        try {
            val read = readRuns(cancelled, progress)
            val memory = read.memory
            if (memory != null) {
                // Everything fit in memory: no run file at all.
                return finish(read.total, cancelled, progress) { out -> memory.forEach(out) }
            }
            var input = read.file!!
            var runs = read.runs
            while (runs.size > limits.fanIn) {
                val output = scratch("pass")
                val writer = output.writer()
                val next = ArrayList<Run>()
                var merged = 0L
                for (group in runs.chunked(limits.fanIn)) {
                    val start = writer.position
                    var count = 0L
                    merge(input, group, cancelled) { entry, length ->
                        writer.write(entry, 0, length)
                        count++
                        if (++merged and 4095L == 0L) progress(SortPhase.MERGING, merged, read.total)
                    }
                    writer.flush()
                    next += Run(start, writer.position - start, count)
                }
                input.close()
                input = output
                runs = next
            }
            val last = input
            val group = runs
            return finish(read.total, cancelled, progress) { out ->
                merge(last, group, cancelled, out)
                last.close()
            }
        } finally {
            // Everything but the finished order's own files.
            for (file in opened) file.close()
            opened.clear()
        }
    }

    private class Read(val total: Long, val memory: RunBuffer?, val file: Scratch?, val runs: List<Run>)

    /** Reads the file once, from start to end, writing sorted runs when memory is full. */
    private fun readRuns(cancelled: () -> Boolean, progress: (SortPhase, Long, Long) -> Unit): Read {
        val buffer = RunBuffer(limits.runBytes)
        val runs = ArrayList<Run>()
        var file: Scratch? = null
        var total = 0L
        val reader = KeyReader(format, request) { entry, length ->
            if (!buffer.add(entry, length)) {
                val out = file ?: scratch("runs").also { file = it }
                runs += buffer.writeTo(out, compare)
                check(buffer.add(entry, length))
            }
            total++
            if (total > Int.MAX_VALUE) throw SortException("This file has too many rows to sort.")
        }
        val chunk = ByteArray(Table.CHUNK)
        var position = 0L
        while (true) {
            if (cancelled()) throw SortCancelled()
            val read = source.read(position, chunk, 0, chunk.size)
            if (read <= 0) break
            reader.feed(chunk, read)
            position += read
            progress(SortPhase.READING, position, source.size)
        }
        reader.finish()
        val out = file
        if (out == null) {
            buffer.sort(compare)
            return Read(total, buffer, null, emptyList())
        }
        runs += buffer.writeTo(out, compare)
        return Read(total, null, out, runs)
    }

    /** Writes the rank files from [entries] in sorted order, then the reverse map. */
    private fun finish(
        total: Long,
        cancelled: () -> Boolean,
        progress: (SortPhase, Long, Long) -> Unit,
        entries: ((ByteArray, Int) -> Unit) -> Unit,
    ): SortedOrder {
        val rows = scratch("rows")
        val spans = scratch("spans")
        val inverse = scratch("inverse")
        val rowWriter = rows.writer()
        val spanWriter = spans.writer()
        var done = 0L
        entries { entry, _ ->
            rowWriter.writeInt(Entry.row(entry, 0))
            spanWriter.writeLong(Entry.start(entry, 0))
            spanWriter.writeInt(Entry.length(entry, 0))
            done++
            if (done and 4095L == 0L) {
                if (cancelled()) throw SortCancelled()
                progress(SortPhase.MERGING, done, total)
            }
        }
        rowWriter.flush()
        spanWriter.flush()
        check(done == total) { "sorted $done of $total rows" }
        // The runs are no longer needed; free their space before the last step.
        for (file in opened) if (file !== rows && file !== spans && file !== inverse) file.close()
        buildInverse(rows, inverse, total, cancelled, progress)
        opened.clear()
        return SortedOrder(source, format, request, total, rows, spans, inverse)
    }

    /** For each row, its rank: a few sequential passes over the rank file. */
    private fun buildInverse(rows: Scratch, inverse: Scratch, total: Long, cancelled: () -> Boolean, progress: (SortPhase, Long, Long) -> Unit) {
        val chunkRows = minOf(total, limits.inverseRows.toLong()).toInt()
        val ranks = IntArray(maxOf(chunkRows, 1))
        val block = ByteArray(limits.readBuffer / 4 * 4)
        val writer = inverse.writer()
        var low = 0L
        while (low < total) {
            if (cancelled()) throw SortCancelled()
            val high = minOf(total, low + chunkRows)
            var rank = 0L
            var position = 0L
            while (rank < total) {
                val want = minOf(block.size.toLong(), (total - rank) * 4).toInt()
                rows.readFully(position, block, 0, want)
                for (offset in 0 until want step 4) {
                    val row = Entry.int(block, offset).toLong()
                    if (row in low until high) ranks[(row - low).toInt()] = rank.toInt()
                    rank++
                }
                position += want
            }
            for (index in 0 until (high - low).toInt()) writer.writeInt(ranks[index])
            low = high
            progress(SortPhase.FINISHING, low, total)
        }
        writer.flush()
    }

    /** Joins sorted [runs] of [file] into one order, calling [out] for each entry. */
    private fun merge(file: Scratch, runs: List<Run>, cancelled: () -> Boolean, out: (ByteArray, Int) -> Unit) {
        val cursors = runs.map { RunCursor(file, it, limits.readBuffer) }.filter { it.advance() }.toTypedArray()
        // A binary heap of cursors, smallest entry on top.
        var size = cursors.size
        fun less(a: RunCursor, b: RunCursor) = compare.compare(a.entry, 0, b.entry, 0) < 0
        fun siftDown(from: Int) {
            var index = from
            while (true) {
                val left = 2 * index + 1
                if (left >= size) return
                val right = left + 1
                val smaller = if (right < size && less(cursors[right], cursors[left])) right else left
                if (!less(cursors[smaller], cursors[index])) return
                val swap = cursors[index]
                cursors[index] = cursors[smaller]
                cursors[smaller] = swap
                index = smaller
            }
        }
        for (index in size / 2 - 1 downTo 0) siftDown(index)
        var count = 0L
        while (size > 0) {
            val top = cursors[0]
            out(top.entry, top.length)
            if (++count and 4095L == 0L && cancelled()) throw SortCancelled()
            if (!top.advance()) {
                size--
                cursors[0] = cursors[size]
            }
            siftDown(0)
        }
    }

    private fun scratch(name: String): Scratch {
        val file = File.createTempFile("sort-$name-", ".bin", folder)
        val access = try {
            RandomAccessFile(file, "rw")
        } finally {
            // Unlinked at once: the space comes back when the file is closed,
            // also if the process is killed.
            file.delete()
        }
        return Scratch(access, this).also { opened += it }
    }

    internal fun wrote(bytes: Int) {
        diskUsed += bytes
        if (diskUsed > limits.diskBytes) {
            throw SortException("This file is too large to sort: it needs more than the ${sizeText(limits.diskBytes)} sort limit of temporary space.")
        }
        if (diskUsed - lastSpaceCheck >= SPACE_CHECK_BYTES) {
            lastSpaceCheck = diskUsed
            if (folder.usableSpace < limits.reserveBytes) throw NotEnoughSpace()
        }
    }

    companion object {
        /** Key bytes kept in each entry; longer keys are compared from the file on a tie. */
        const val KEY_BYTES = 256
        const val MAX_ENTRY = Entry.HEADER + Entry.EXTRA + KEY_BYTES
        private const val SPACE_CHECK_BYTES = 8L * 1024 * 1024

        /** Free space a sort leaves on the device at its peak, as on the desktop. */
        const val MARGIN_BYTES = 64L * 1024 * 1024

        /**
         * Temporary disk a sort of [rows] rows of [fileBytes] bytes needs at
         * its peak, an upper bound: a merge pass holds two copies of every
         * row's entry.
         */
        fun neededBytes(rows: Long, fileBytes: Long): Long {
            if (rows <= 0) return 0
            val key = minOf(fileBytes / rows + 1, KEY_BYTES.toLong())
            val perRow = 2 * (Entry.HEADER + Entry.EXTRA + key)
            return if (rows > Long.MAX_VALUE / perRow) Long.MAX_VALUE else rows * perRow
        }

        /**
         * Null when a sort of [rows] rows fits, or the message that says why
         * not. [free] is the usable space of the sort folder.
         */
        fun checkSpace(rows: Long, fileBytes: Long, free: Long, limit: Long = SortLimits().diskBytes): String? {
            val needed = neededBytes(rows, fileBytes)
            if (needed > limit) {
                return "This file is too large to sort: it needs about ${sizeText(needed)} of temporary space, more than the ${sizeText(limit)} sort limit."
            }
            val wanted = needed + MARGIN_BYTES
            if (wanted > free) {
                return "Not enough free space to sort: it needs about ${sizeText(wanted)} and the device has ${sizeText(free)} free."
            }
            return null
        }

        /** Rows the sort will read: the count so far, scaled up to the whole file while indexing runs. */
        fun expectedRows(rows: Long, complete: Boolean, scannedBytes: Long, fileBytes: Long): Long {
            if (complete || scannedBytes <= 0 || scannedBytes >= fileBytes) return rows
            val scaled = rows.toBigInteger() * fileBytes.toBigInteger() / scannedBytes.toBigInteger()
            return if (scaled.bitLength() > 62) Long.MAX_VALUE else scaled.toLong()
        }

        /** Sizes as the desktop shows them: binary units, labelled GB and MB. */
        fun sizeText(bytes: Long): String {
            val mb = 1L shl 20
            val gb = 1L shl 30
            return if (bytes >= gb) {
                val tenths = (bytes.toBigInteger() * 10.toBigInteger() + (gb - 1).toBigInteger()) / gb.toBigInteger()
                "${tenths / 10.toBigInteger()}.${tenths % 10.toBigInteger()} GB"
            } else {
                "${(bytes + mb - 1) / mb} MB"
            }
        }
    }
}

/** One sorted run inside a run file. */
internal class Run(val start: Long, val bytes: Long, val count: Long)

/**
 * A temporary file, already unlinked. Writes go through one buffer at the
 * end of the file; reads are positional.
 */
internal class Scratch(private val file: RandomAccessFile, private val sorter: Sorter?) : Closeable {
    val channel: FileChannel = file.channel

    fun writer() = Writer()

    inner class Writer {
        private val buffer = ByteArray(256 * 1024)
        private var filled = 0
        var position = channel.size()
            private set

        fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (filled + length > buffer.size) flush()
            System.arraycopy(bytes, offset, buffer, filled, length)
            filled += length
            position += length
        }

        fun writeInt(value: Int) {
            if (filled + 4 > buffer.size) flush()
            Entry.putInt(buffer, filled, value)
            filled += 4
            position += 4
        }

        fun writeLong(value: Long) {
            if (filled + 8 > buffer.size) flush()
            Entry.putLong(buffer, filled, value)
            filled += 8
            position += 8
        }

        fun flush() {
            if (filled == 0) return
            sorter?.wrote(filled)
            val bytes = ByteBuffer.wrap(buffer, 0, filled)
            var at = position - filled
            while (bytes.hasRemaining()) at += channel.write(bytes, at)
            filled = 0
        }
    }

    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(into, offset, length), position)

    fun readFully(position: Long, into: ByteArray, offset: Int, length: Int) {
        var filled = 0
        while (filled < length) {
            val read = read(position + filled, into, offset + filled, length - filled)
            if (read <= 0) throw IOException("a sort file ended early")
            filled += read
        }
    }

    override fun close() = file.close()
}

/** Reads one run's entries in order through its own buffer. */
private class RunCursor(private val file: Scratch, run: Run, bufferBytes: Int) {
    private val buffer = ByteArray(bufferBytes)
    private var filled = 0
    private var at = 0
    private var position = run.start
    private val end = run.start + run.bytes
    val entry = ByteArray(Sorter.MAX_ENTRY)
    var length = 0
        private set

    fun advance(): Boolean {
        if (!ensure(Entry.HEADER)) return false
        val size = Entry.size(buffer, at)
        if (!ensure(size)) throw IOException("a sort run ended early")
        System.arraycopy(buffer, at, entry, 0, size)
        length = size
        at += size
        return true
    }

    /** Makes [count] bytes available from [at]; false at the end of the run. */
    private fun ensure(count: Int): Boolean {
        if (filled - at >= count) return true
        val left = filled - at
        System.arraycopy(buffer, at, buffer, 0, left)
        filled = left
        at = 0
        val want = minOf((buffer.size - filled).toLong(), end - position).toInt()
        if (want > 0) {
            file.readFully(position, buffer, filled, want)
            filled += want
            position += want
        }
        return filled >= count
    }
}

/**
 * One row's sort entry, as bytes, so a run of them is one array and not an
 * object per row. Big-endian fields:
 *
 *     0  row        Int    the row in the file, from 0
 *     4  start      Long   where the record starts
 *    12  length     Int    the record's length, at most Fields.MAX_RECORD_BYTES + 1
 *    16  group      Byte   numeric order: 0 number, 1 text, 2 empty; text order: 1
 *    17  sign       Byte   -1, 0 or 1 for a number
 *    18  flags      Byte   LONG: the key is longer than kept; QUOTED: a quoted field
 *    19  magnitude  Long   a number's decimal exponent: 1 for 1 to 9, 0 for 0.1 to 0.9
 *    27  key length Short  bytes of key kept, at most Sorter.KEY_BYTES
 *    29  key start, key end  Long, Long  only with LONG: where the field is, to read it again
 *        key        the field's text, or for a number its significant digits
 */
internal object Entry {
    const val HEADER = 29
    const val EXTRA = 16
    const val LONG = 1
    const val QUOTED = 2

    fun row(b: ByteArray, at: Int) = int(b, at)
    fun start(b: ByteArray, at: Int) = long(b, at + 4)
    fun length(b: ByteArray, at: Int) = int(b, at + 12)
    fun group(b: ByteArray, at: Int) = b[at + 16].toInt()
    fun sign(b: ByteArray, at: Int) = b[at + 17].toInt()
    fun flags(b: ByteArray, at: Int) = b[at + 18].toInt()
    fun magnitude(b: ByteArray, at: Int) = long(b, at + 19)
    fun keyLength(b: ByteArray, at: Int) = ((b[at + 27].toInt() and 0xFF) shl 8) or (b[at + 28].toInt() and 0xFF)
    fun keyStart(b: ByteArray, at: Int) = long(b, at + HEADER)
    fun keyEnd(b: ByteArray, at: Int) = long(b, at + HEADER + 8)
    fun keyOffset(b: ByteArray, at: Int) = at + HEADER + if (flags(b, at) and LONG != 0) EXTRA else 0
    fun size(b: ByteArray, at: Int) = keyOffset(b, at) - at + keyLength(b, at)

    fun int(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    fun long(b: ByteArray, at: Int): Long = (int(b, at).toLong() shl 32) or (int(b, at + 4).toLong() and 0xFFFFFFFFL)

    fun putInt(b: ByteArray, at: Int, value: Int) {
        b[at] = (value ushr 24).toByte()
        b[at + 1] = (value ushr 16).toByte()
        b[at + 2] = (value ushr 8).toByte()
        b[at + 3] = value.toByte()
    }

    fun putLong(b: ByteArray, at: Int, value: Long) {
        putInt(b, at, (value ushr 32).toInt())
        putInt(b, at + 4, value.toInt())
    }
}

/**
 * Walks the file once, finds every record as [RecordScanner] does and the
 * sort field in it, as the desktop's Locator does, and hands each row's
 * entry to [emit] (the array is reused). Quotes are taken off a quoted
 * field and doubled quotes become one, as the desktop compares them.
 */
internal class KeyReader(
    format: Format,
    request: SortRequest,
    private val emit: (ByteArray, Int) -> Unit,
) {
    private enum class State { FIELD_START, UNQUOTED, QUOTED, QUOTE_CLOSED }

    private val delimiter = format.delimiter
    private val target = request.column
    private val numeric = request.mode == SortMode.NUMERIC

    private var state = State.FIELD_START
    private var offset = 0L
    private var pendingCrEnd = -1L
    private var recordStart = 0L
    private var row = 0
    private var column = 0
    private var fieldStart = 0L
    private var keyEnd = -1L
    private var quoted = false

    private val text = ByteArray(Sorter.KEY_BYTES)
    private var textLength = 0L
    private val digits = ByteArray(Sorter.KEY_BYTES)
    private val number = NumberParser()
    private val entry = ByteArray(Sorter.MAX_ENTRY)

    fun feed(chunk: ByteArray, length: Int) {
        var index = 0
        while (index < length) {
            val byte = chunk[index]
            if (pendingCrEnd >= 0) {
                val end = pendingCrEnd
                pendingCrEnd = -1
                if (byte == LF) {
                    offset++
                    index++
                    endRecord(end)
                    continue
                }
                endRecord(end)
            }
            if (state != State.QUOTED && byte == CR) {
                pendingCrEnd = offset
                offset++
                index++
                continue
            }
            if (state != State.QUOTED && byte == LF) {
                val end = offset
                offset++
                index++
                endRecord(end)
                continue
            }
            if (delimiter == null) {
                key(byte)
            } else {
                when (state) {
                    State.FIELD_START -> when (byte) {
                        Format.QUOTE -> {
                            state = State.QUOTED
                            if (column == target) quoted = true
                        }
                        delimiter -> endField()
                        else -> {
                            state = State.UNQUOTED
                            key(byte)
                        }
                    }
                    State.UNQUOTED -> if (byte == delimiter) {
                        endField()
                        state = State.FIELD_START
                    } else {
                        key(byte)
                    }
                    State.QUOTED -> if (byte == Format.QUOTE) state = State.QUOTE_CLOSED else key(byte)
                    State.QUOTE_CLOSED -> when (byte) {
                        Format.QUOTE -> {
                            state = State.QUOTED
                            key(byte)
                        }
                        delimiter -> {
                            endField()
                            state = State.FIELD_START
                        }
                        else -> {
                            state = State.UNQUOTED
                            key(byte)
                        }
                    }
                }
            }
            offset++
            index++
        }
    }

    /** The end of the file: a last record without a line break, if any. */
    fun finish() {
        if (pendingCrEnd >= 0) {
            val end = pendingCrEnd
            pendingCrEnd = -1
            endRecord(end)
        } else if (recordStart < offset) {
            endRecord(offset)
        }
    }

    private fun key(byte: Byte) {
        if (column != target || keyEnd >= 0) return
        if (textLength < Sorter.KEY_BYTES) text[textLength.toInt()] = byte
        textLength++
        if (numeric) number.push(byte, digits)
    }

    private fun endField() {
        if (column == target && keyEnd < 0) keyEnd = offset
        column++
        if (column == target) fieldStart = offset + 1
    }

    private fun endRecord(end: Long) {
        val keyStart: Long
        val keyStop: Long
        when {
            column < target -> {
                // The record has no such field: an empty key.
                keyStart = end
                keyStop = end
            }
            keyEnd >= 0 -> {
                keyStart = if (target == 0) recordStart else fieldStart
                keyStop = keyEnd
            }
            else -> {
                keyStart = if (target == 0) recordStart else fieldStart
                keyStop = end
            }
        }
        write(end, keyStart, keyStop)
        row++
        recordStart = offset
        fieldStart = offset
        column = 0
        keyEnd = -1
        quoted = false
        textLength = 0
        number.reset()
        state = State.FIELD_START
    }

    private fun write(end: Long, keyStart: Long, keyStop: Long) {
        var group = 1
        var sign = 0
        var magnitude = 0L
        var key = text
        var keyLength = minOf(textLength, Sorter.KEY_BYTES.toLong()).toInt()
        var long = textLength > Sorter.KEY_BYTES
        if (numeric) {
            group = number.group(textLength)
            if (group == 0) {
                sign = number.sign
                magnitude = number.magnitude()
                key = digits
                keyLength = number.keptDigits
                long = number.digitsLong
            } else if (group == 2) {
                keyLength = 0
                long = false
            }
        }
        Entry.putInt(entry, 0, row)
        Entry.putLong(entry, 4, recordStart)
        Entry.putInt(entry, 12, minOf(end - recordStart, Fields.MAX_RECORD_BYTES + 1L).toInt())
        entry[16] = group.toByte()
        entry[17] = sign.toByte()
        entry[18] = ((if (long) Entry.LONG else 0) or (if (quoted) Entry.QUOTED else 0)).toByte()
        Entry.putLong(entry, 19, magnitude)
        entry[27] = (keyLength ushr 8).toByte()
        entry[28] = keyLength.toByte()
        var at = Entry.HEADER
        if (long) {
            Entry.putLong(entry, at, keyStart)
            Entry.putLong(entry, at + 8, keyStop)
            at += Entry.EXTRA
        }
        System.arraycopy(key, 0, entry, at, keyLength)
        emit(entry, at + keyLength)
    }

    private companion object {
        const val CR: Byte = '\r'.code.toByte()
        const val LF: Byte = '\n'.code.toByte()
    }
}

/**
 * Reads a field as a number with the desktop's rules: optional ASCII
 * whitespace around it, an optional sign, digits with at most one decimal
 * point, and an optional exponent. Anything else makes it text. The value
 * is kept exactly: a sign, a decimal exponent and the significant digits.
 */
internal class NumberParser {
    private var state = 0
    private var negative = false
    private var expNegative = false
    private var exp = 0L
    private var expDigits = 0
    private var beforeDot = 0L
    private var digitCount = 0L
    private var leading = 0L
    private var significant = false
    private var invalid = false
    private var expOverflow = false

    /** Significant digits kept, with trailing zeros left off. */
    var keptDigits = 0
        private set

    /** True when significant digits past [Sorter.KEY_BYTES] are not all zero. */
    var digitsLong = false
        private set
    private var significantCount = 0L

    val sign: Int get() = if (!significant) 0 else if (negative) -1 else 1

    fun reset() {
        state = 0
        negative = false
        expNegative = false
        exp = 0
        expDigits = 0
        beforeDot = 0
        digitCount = 0
        leading = 0
        significant = false
        invalid = false
        expOverflow = false
        keptDigits = 0
        digitsLong = false
        significantCount = 0
    }

    fun push(byte: Byte, digits: ByteArray) {
        if (invalid) return
        val b = byte.toInt() and 0xFF
        if (whitespace(b)) {
            if (state == 1 || state == 4 || state == 5) invalid = true
            if (state != 0) state = 7
            return
        }
        when (state) {
            0 -> when {
                b == '+'.code || b == '-'.code -> {
                    negative = b == '-'.code
                    state = 1
                }
                b == '.'.code -> state = 3
                b in '0'.code..'9'.code -> {
                    state = 2
                    digit(b, true, digits)
                }
                else -> invalid = true
            }
            1, 2 -> when {
                b in '0'.code..'9'.code -> {
                    state = 2
                    digit(b, true, digits)
                }
                b == '.'.code -> state = 3
                (b == 'e'.code || b == 'E'.code) && digitCount > 0 -> state = 4
                else -> invalid = true
            }
            3 -> when {
                b in '0'.code..'9'.code -> digit(b, false, digits)
                (b == 'e'.code || b == 'E'.code) && digitCount > 0 -> state = 4
                else -> invalid = true
            }
            4 -> when {
                b == '+'.code || b == '-'.code -> {
                    expNegative = b == '-'.code
                    state = 5
                }
                b in '0'.code..'9'.code -> {
                    state = 6
                    exponent(b)
                }
                else -> invalid = true
            }
            5, 6 -> if (b in '0'.code..'9'.code) {
                state = 6
                exponent(b)
            } else {
                invalid = true
            }
            else -> invalid = true
        }
    }

    private fun digit(b: Int, integer: Boolean, digits: ByteArray) {
        digitCount++
        if (integer) beforeDot++
        if (!significant && b == '0'.code) {
            leading++
            return
        }
        significant = true
        val index = significantCount++
        if (b != '0'.code) {
            if (index < Sorter.KEY_BYTES) keptDigits = (index + 1).toInt() else digitsLong = true
        }
        if (index < Sorter.KEY_BYTES) digits[index.toInt()] = b.toByte()
    }

    private fun exponent(b: Int) {
        expDigits++
        if (expOverflow) return
        val value = b - '0'.code
        if (exp > (Long.MAX_VALUE - value) / 10) expOverflow = true else exp = exp * 10 + value
    }

    /** 0 for a number, 1 for text, 2 for empty or only whitespace. */
    fun group(length: Long): Int {
        val group = if (length == 0L || (state == 0 && !invalid)) 2 else 1
        if (invalid || digitCount == 0L || state == 1 || state == 4 || state == 5 || (expDigits == 0 && state == 6)) return group
        if (expOverflow) throw SortException(NUMERIC_RANGE)
        return 0
    }

    /** The decimal exponent of the first significant digit, plus one. */
    fun magnitude(): Long = try {
        Math.addExact(beforeDot - leading, if (expNegative) -exp else exp)
    } catch (overflow: ArithmeticException) {
        throw SortException(NUMERIC_RANGE)
    }

    companion object {
        const val NUMERIC_RANGE =
            "A number in this column has an exponent too large to sort, such as 1e99999999999999999999."

        /** ASCII whitespace as Rust's is_ascii_whitespace: space, tab, LF, FF and CR. */
        fun whitespace(b: Int) = b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0C || b == 0x0D
    }
}

/**
 * The order of two entries: by group in numeric order, then by value in the
 * chosen direction, then by row so equal values keep the file's order. Keys
 * longer than [Sorter.KEY_BYTES] that tie on their kept bytes are read again
 * from the file; that is the only read out of order.
 */
internal class EntryCompare(private val source: ByteSource, format: Format, request: SortRequest) {
    private val descending = request.descending
    private val numeric = request.mode == SortMode.NUMERIC
    private val lines = format.delimiter == null
    private val left = KeyStream(source, lines)
    private val right = KeyStream(source, lines)

    var fallbackBytes = 0L
        private set

    fun compare(a: ByteArray, at: Int, b: ByteArray, bt: Int): Int {
        val value = value(a, at, b, bt)
        if (value != 0) return value
        return Entry.row(a, at).compareTo(Entry.row(b, bt))
    }

    /** The order of the values alone; 0 means a tie, broken by row. */
    private fun value(a: ByteArray, at: Int, b: ByteArray, bt: Int): Int {
        if (numeric) {
            val groupA = Entry.group(a, at)
            val groupB = Entry.group(b, bt)
            // Numbers, text, empty, in both directions.
            if (groupA != groupB) return groupA.compareTo(groupB)
            if (groupA == 2) return 0
            if (groupA == 0) return directed(number(a, at, b, bt))
        }
        return directed(text(a, at, b, bt))
    }

    private fun directed(value: Int) = if (descending) -value else value

    private fun number(a: ByteArray, at: Int, b: ByteArray, bt: Int): Int {
        val signA = Entry.sign(a, at)
        val signB = Entry.sign(b, bt)
        if (signA != signB) return signA.compareTo(signB)
        if (signA == 0) return 0
        var value = Entry.magnitude(a, at).compareTo(Entry.magnitude(b, bt))
        if (value == 0) value = digits(a, at, b, bt)
        return if (signA < 0) -value else value
    }

    /** Significant digits, a missing digit counting as 0. */
    private fun digits(a: ByteArray, at: Int, b: ByteArray, bt: Int): Int {
        val lengthA = Entry.keyLength(a, at)
        val lengthB = Entry.keyLength(b, bt)
        val keyA = Entry.keyOffset(a, at)
        val keyB = Entry.keyOffset(b, bt)
        for (index in 0 until Sorter.KEY_BYTES) {
            if (index >= lengthA && index >= lengthB) break
            val x = if (index < lengthA) a[keyA + index].toInt() else '0'.code
            val y = if (index < lengthB) b[keyB + index].toInt() else '0'.code
            if (x != y) return x.compareTo(y)
        }
        val longA = Entry.flags(a, at) and Entry.LONG != 0
        val longB = Entry.flags(b, bt) and Entry.LONG != 0
        return when {
            longA && longB -> fromFile(a, at, b, bt, digits = true)
            // Only the long one has a non-zero digit further on.
            longA -> 1
            longB -> -1
            else -> 0
        }
    }

    private fun text(a: ByteArray, at: Int, b: ByteArray, bt: Int): Int {
        val lengthA = Entry.keyLength(a, at)
        val lengthB = Entry.keyLength(b, bt)
        val keyA = Entry.keyOffset(a, at)
        val keyB = Entry.keyOffset(b, bt)
        val common = minOf(lengthA, lengthB)
        for (index in 0 until common) {
            val x = a[keyA + index].toInt() and 0xFF
            val y = b[keyB + index].toInt() and 0xFF
            if (x != y) return x.compareTo(y)
        }
        val longA = Entry.flags(a, at) and Entry.LONG != 0
        val longB = Entry.flags(b, bt) and Entry.LONG != 0
        if (longA && longB) return fromFile(a, at, b, bt, digits = false)
        // A key kept whole that ends first is the smaller.
        return lengthA.compareTo(lengthB).let { if (it != 0) it else if (longA) 1 else if (longB) -1 else 0 }
    }

    /** Compares two long keys from the file, byte by byte. */
    private fun fromFile(a: ByteArray, at: Int, b: ByteArray, bt: Int, digits: Boolean): Int {
        left.open(Entry.keyStart(a, at), Entry.keyEnd(a, at), Entry.flags(a, at) and Entry.QUOTED != 0, digits)
        right.open(Entry.keyStart(b, bt), Entry.keyEnd(b, bt), Entry.flags(b, bt) and Entry.QUOTED != 0, digits)
        try {
            while (true) {
                val x = left.next()
                val y = right.next()
                if (x < 0 && y < 0) return 0
                val value = if (digits) {
                    (if (x < 0) '0'.code else x).compareTo(if (y < 0) '0'.code else y)
                } else {
                    x.compareTo(y)
                }
                if (value != 0) return value
            }
        } finally {
            fallbackBytes += left.read + right.read
        }
    }
}

/** A field's bytes read again from the file, with its quotes taken off, or its significant digits. */
private class KeyStream(private val source: ByteSource, private val lines: Boolean) {
    private val buffer = ByteArray(4096)
    private var position = 0L
    private var end = 0L
    private var filled = 0
    private var at = 0
    private var quoted = false
    private var closed = false
    private var first = true
    private var digits = false
    private var skipping = true
    private var stopped = false
    var read = 0L
        private set

    fun open(start: Long, stop: Long, quoted: Boolean, digits: Boolean) {
        position = start
        end = stop
        filled = 0
        at = 0
        this.quoted = quoted && !lines
        closed = false
        first = true
        this.digits = digits
        skipping = true
        stopped = false
        read = 0
    }

    /** The next byte (0 to 255), or -1 at the end. */
    fun next(): Int {
        while (true) {
            val byte = decoded()
            if (byte < 0 || !digits) return byte
            if (stopped) return -1
            if (byte == 'e'.code || byte == 'E'.code) {
                stopped = true
                return -1
            }
            if (byte !in '0'.code..'9'.code) continue
            if (skipping && byte == '0'.code) continue
            skipping = false
            return byte
        }
    }

    private fun decoded(): Int {
        while (true) {
            val byte = raw()
            if (byte < 0) return -1
            if (first) {
                first = false
                if (quoted) continue
            }
            if (quoted) {
                if (closed) {
                    closed = false
                    if (byte != '"'.code) quoted = false
                    return byte
                }
                if (byte == '"'.code) {
                    closed = true
                    continue
                }
            }
            return byte
        }
    }

    private fun raw(): Int {
        if (at == filled) {
            if (position >= end) return -1
            val want = minOf(buffer.size.toLong(), end - position).toInt()
            val count = source.read(position, buffer, 0, want)
            if (count <= 0) throw IOException("the file ended early")
            position += count
            read += count
            filled = count
            at = 0
        }
        return buffer[at++].toInt() and 0xFF
    }
}

/**
 * Entries in memory before they are written out as one sorted run: one byte
 * array and the offset of each entry, sorted with a merge sort on the
 * offsets so no object is made per row.
 */
internal class RunBuffer(capacity: Int) {
    private val bytes = ByteArray(capacity)
    private var used = 0
    private var offsets = IntArray(capacity / Entry.HEADER + 1)
    private var spare = IntArray(offsets.size)
    private var count = 0

    fun add(entry: ByteArray, length: Int): Boolean {
        if (used + length > bytes.size || count == offsets.size) return false
        System.arraycopy(entry, 0, bytes, used, length)
        offsets[count++] = used
        used += length
        return true
    }

    fun sort(compare: EntryCompare) {
        // Bottom-up merge sort, with insertion sort for short stretches.
        val run = 16
        var start = 0
        while (start < count) {
            val end = minOf(start + run, count)
            for (i in start + 1 until end) {
                val value = offsets[i]
                var j = i - 1
                while (j >= start && compare.compare(bytes, offsets[j], bytes, value) > 0) {
                    offsets[j + 1] = offsets[j]
                    j--
                }
                offsets[j + 1] = value
            }
            start = end
        }
        var width = run
        var from = offsets
        var into = spare
        while (width < count) {
            var low = 0
            while (low < count) {
                val middle = minOf(low + width, count)
                val high = minOf(low + 2 * width, count)
                var i = low
                var j = middle
                var k = low
                while (i < middle && j < high) {
                    into[k++] = if (compare.compare(bytes, from[i], bytes, from[j]) <= 0) from[i++] else from[j++]
                }
                while (i < middle) into[k++] = from[i++]
                while (j < high) into[k++] = from[j++]
                low = high
            }
            val swap = from
            from = into
            into = swap
            width *= 2
        }
        offsets = from
        spare = into
    }

    fun forEach(out: (ByteArray, Int) -> Unit) {
        val entry = ByteArray(Sorter.MAX_ENTRY)
        for (index in 0 until count) {
            val at = offsets[index]
            val size = Entry.size(bytes, at)
            System.arraycopy(bytes, at, entry, 0, size)
            out(entry, size)
        }
    }

    /** Sorts, writes the entries to the end of [file] and empties the buffer. */
    fun writeTo(file: Scratch, compare: EntryCompare): Run {
        sort(compare)
        val writer = file.writer()
        val start = writer.position
        for (index in 0 until count) {
            val at = offsets[index]
            writer.write(bytes, at, Entry.size(bytes, at))
        }
        writer.flush()
        val run = Run(start, writer.position - start, count.toLong())
        used = 0
        count = 0
        return run
    }
}

/**
 * The finished order. [rows] reads any stretch of it, with each row's own
 * number from the file; [rankOf] and [rowAt] map between a row and its place.
 * Closing it frees the temporary files.
 */
class SortedOrder internal constructor(
    private val source: ByteSource,
    private val format: Format,
    val request: SortRequest,
    val count: Long,
    private val rowFile: Scratch,
    private val spanFile: Scratch,
    private val inverseFile: Scratch,
) : Closeable {
    /** The row (from 0) shown at [rank]. */
    fun rowAt(rank: Long): Long {
        val bytes = ByteArray(4)
        rowFile.readFully(rank * 4, bytes, 0, 4)
        return Entry.int(bytes, 0).toLong()
    }

    /** Where [row] (from 0) is shown. */
    fun rankOf(row: Long): Long {
        val bytes = ByteArray(4)
        inverseFile.readFully(row * 4, bytes, 0, 4)
        return Entry.int(bytes, 0).toLong()
    }

    /** Ranks [first] to [first] + [count] - 1, or fewer at the end. */
    fun rows(first: Long, count: Int): List<Row> {
        val n = minOf(count.toLong(), this.count - first).toInt()
        if (n <= 0) return emptyList()
        val numbers = ByteArray(n * 4)
        val spans = ByteArray(n * 12)
        rowFile.readFully(first * 4, numbers, 0, numbers.size)
        spanFile.readFully(first * 12, spans, 0, spans.size)
        val record = ByteArray(Fields.MAX_RECORD_BYTES)
        return List(n) { index ->
            val start = Entry.long(spans, index * 12)
            val length = Entry.int(spans, index * 12 + 8)
            val kept = minOf(length, Fields.MAX_RECORD_BYTES)
            var filled = 0
            while (filled < kept) {
                val read = source.read(start + filled, record, filled, kept - filled)
                if (read <= 0) throw IOException("the file ended early")
                filled += read
            }
            Row(Entry.int(numbers, index * 4) + 1L, Fields.cells(record, kept, format, length > Fields.MAX_RECORD_BYTES))
        }
    }

    override fun close() {
        rowFile.close()
        spanFile.close()
        inverseFile.close()
    }
}
