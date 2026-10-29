package io.github.abooodbah.leanrows

/**
 * Finds record boundaries in a byte stream without keeping the bytes, as the
 * desktop CsvBoundaryScanner and LineBoundaryScanner do. A record ends at LF,
 * CR or CRLF, except inside a quoted field of a delimited format. Feed chunks
 * in order; [emit] gets each record's start and end offsets (the end excludes
 * the line break) and returns false to stop.
 */
class RecordScanner(private val format: Format, startOffset: Long = 0) {
    private enum class State { FIELD_START, UNQUOTED, QUOTED, QUOTE_CLOSED }

    private var state = State.FIELD_START
    private var recordStart = startOffset
    private var pendingCrEnd = -1L

    /** The absolute position after every byte consumed so far. */
    var offset = startOffset
        private set

    /** Bytes of [chunk] consumed. Less than [length] only when [emit] stopped. */
    fun feed(chunk: ByteArray, start: Int = 0, length: Int = chunk.size, emit: (Long, Long) -> Boolean): Int {
        val delimiter = format.delimiter
        var index = start
        val end = start + length
        while (index < end) {
            val byte = chunk[index]
            if (pendingCrEnd >= 0) {
                val recordEnd = pendingCrEnd
                pendingCrEnd = -1
                if (byte == LF) {
                    offset++
                    index++
                    if (!complete(recordEnd, emit)) return index - start
                    continue
                }
                if (!complete(recordEnd, emit)) return index - start
            }
            if (state != State.QUOTED && byte == CR) {
                pendingCrEnd = offset
                offset++
                index++
                continue
            }
            if (state != State.QUOTED && byte == LF) {
                val recordEnd = offset
                offset++
                index++
                if (!complete(recordEnd, emit)) return index - start
                continue
            }
            if (delimiter != null) {
                state = when (state) {
                    State.FIELD_START -> when (byte) {
                        Format.QUOTE -> State.QUOTED
                        delimiter -> State.FIELD_START
                        else -> State.UNQUOTED
                    }
                    State.UNQUOTED -> if (byte == delimiter) State.FIELD_START else State.UNQUOTED
                    State.QUOTED -> if (byte == Format.QUOTE) State.QUOTE_CLOSED else State.QUOTED
                    State.QUOTE_CLOSED -> when (byte) {
                        Format.QUOTE -> State.QUOTED
                        delimiter -> State.FIELD_START
                        else -> State.UNQUOTED
                    }
                }
            }
            offset++
            index++
        }
        return length
    }

    /**
     * Ends the stream: emits a last record without a line break, if any.
     * Returns true when a quoted field was still open, which the desktop
     * reports as an unterminated quote rather than repairing.
     */
    fun finish(emit: (Long, Long) -> Boolean): Boolean {
        val unterminated = state == State.QUOTED
        if (pendingCrEnd >= 0) {
            val recordEnd = pendingCrEnd
            pendingCrEnd = -1
            complete(recordEnd, emit)
        } else if (recordStart < offset) {
            emit(recordStart, offset)
            recordStart = offset
        }
        return unterminated
    }

    private fun complete(end: Long, emit: (Long, Long) -> Boolean): Boolean {
        val keepGoing = emit(recordStart, end)
        recordStart = offset
        state = State.FIELD_START
        return keepGoing
    }

    private companion object {
        const val CR: Byte = '\r'.code.toByte()
        const val LF: Byte = '\n'.code.toByte()
    }
}
