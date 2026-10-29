package io.github.abooodbah.leanrows

import java.io.ByteArrayOutputStream

/**
 * Splits one record into display cells with the desktop's limits: at most 64
 * fields, 1,024 decoded bytes per field, 4,096 per record, and 1,024 UTF-16
 * units per cell, with " ..." marking anything cut short.
 */
object Fields {
    const val MAX_FIELDS = 64
    const val MAX_FIELD_BYTES = 1024
    const val MAX_RECORD_BYTES = 4096
    const val MAX_CELL_CHARS = 1024
    const val MARKER = " ..."

    /** The cells of [record]. A line format gives one cell with the whole line. */
    fun cells(record: ByteArray, length: Int, format: Format, recordTruncated: Boolean = false): List<String> {
        val delimiter = format.delimiter ?: return listOf(
            CellText.decode(record, 0, minOf(length, MAX_RECORD_BYTES), recordTruncated || length > MAX_RECORD_BYTES),
        )
        val cells = ArrayList<String>(8)
        var remaining = MAX_RECORD_BYTES
        val field = ByteArrayOutputStream(64)
        var fieldTruncated = false
        var state = 0 // 0 start, 1 unquoted, 2 quoted, 3 quote closed
        var index = 0

        fun finishField() {
            if (cells.size < MAX_FIELDS) {
                cells += CellText.decode(field.toByteArray(), 0, field.size(), fieldTruncated)
            }
            field.reset()
            fieldTruncated = false
        }

        fun push(byte: Byte) {
            if (field.size() < MAX_FIELD_BYTES && remaining > 0) {
                field.write(byte.toInt())
                remaining--
            } else {
                fieldTruncated = true
            }
        }

        while (index < length) {
            val byte = record[index]
            when (state) {
                0 -> when (byte) {
                    Format.QUOTE -> state = 2
                    delimiter -> finishField()
                    else -> { push(byte); state = 1 }
                }
                1 -> if (byte == delimiter) { finishField(); state = 0 } else push(byte)
                2 -> if (byte == Format.QUOTE) state = 3 else push(byte)
                3 -> when (byte) {
                    Format.QUOTE -> { push(byte); state = 2 }
                    delimiter -> { finishField(); state = 0 }
                    else -> { push(byte); state = 1 }
                }
            }
            index++
        }
        if (recordTruncated) fieldTruncated = true
        finishField()
        return cells
    }
}

/** Turns field bytes into text the way the desktop shows them. */
object CellText {
    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * Valid UTF-8 is shown as text. Each invalid byte becomes \xNN, line
     * breaks and tabs become \n, \r and \t, NUL becomes \0, and other control
     * characters become \u{NN}, so nothing hidden or misleading reaches the
     * screen. Text past [Fields.MAX_CELL_CHARS] is cut and marked.
     */
    fun decode(bytes: ByteArray, start: Int, length: Int, truncated: Boolean): String {
        val out = StringBuilder(minOf(length, Fields.MAX_CELL_CHARS) + 4)
        var cut = truncated
        var index = start
        val end = start + length
        while (index < end) {
            if (out.length >= Fields.MAX_CELL_CHARS) {
                cut = true
                break
            }
            val lead = bytes[index].toInt() and 0xFF
            val size = when {
                lead < 0x80 -> 1
                lead in 0xC2..0xDF -> 2
                lead in 0xE0..0xEF -> 3
                lead in 0xF0..0xF4 -> 4
                else -> 0
            }
            val codePoint = if (size == 0 || index + size > end) -1 else decodeAt(bytes, index, size)
            if (codePoint < 0) {
                appendLimited(out, "\\x" + HEX[lead shr 4] + HEX[lead and 0xF])
                index++
                continue
            }
            when {
                codePoint == '\n'.code -> appendLimited(out, "\\n")
                codePoint == '\r'.code -> appendLimited(out, "\\r")
                codePoint == '\t'.code -> appendLimited(out, "\\t")
                codePoint == 0 -> appendLimited(out, "\\0")
                Character.isISOControl(codePoint) -> appendLimited(out, "\\u{" + Integer.toHexString(codePoint).uppercase() + "}")
                else -> {
                    if (out.length + Character.charCount(codePoint) > Fields.MAX_CELL_CHARS) {
                        cut = true
                        break
                    }
                    out.appendCodePoint(codePoint)
                }
            }
            index += size
        }
        if (out.length > Fields.MAX_CELL_CHARS) {
            out.setLength(Fields.MAX_CELL_CHARS)
            cut = true
        }
        if (cut) {
            if (out.length > Fields.MAX_CELL_CHARS - Fields.MARKER.length) {
                out.setLength(Fields.MAX_CELL_CHARS - Fields.MARKER.length)
            }
            out.append(Fields.MARKER)
        }
        return out.toString()
    }

    private fun appendLimited(out: StringBuilder, text: String) {
        out.append(text)
    }

    /** The code point of a well-formed UTF-8 sequence, or -1. */
    private fun decodeAt(bytes: ByteArray, index: Int, size: Int): Int {
        val lead = bytes[index].toInt() and 0xFF
        if (size == 1) return lead
        var value = lead and (0xFF shr (size + 1))
        for (offset in 1 until size) {
            val next = bytes[index + offset].toInt() and 0xFF
            if (next and 0xC0 != 0x80) return -1
            value = (value shl 6) or (next and 0x3F)
        }
        val minimum = when (size) {
            2 -> 0x80
            3 -> 0x800
            else -> 0x10000
        }
        if (value < minimum || value > 0x10FFFF || value in 0xD800..0xDFFF) return -1
        return value
    }
}
