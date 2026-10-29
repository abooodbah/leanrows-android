package io.github.abooodbah.leanrows

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reads the parts of a zip file for [XlsxBook]. It finds each part through
 * the central directory and unpacks it with its own [Inflater], so it can
 * count both the bytes it reads from the file and the bytes it unpacks, and
 * stop at the limits while unpacking. The unpacked sizes written in the zip
 * are not used.
 */
internal class XlsxZip(private val source: ByteSource, private val owned: Closeable? = null) : Closeable {
    class Entry(val name: String, val method: Int, val compressedSize: Long, val headerOffset: Long)

    private val length = source.size
    private val entries = HashMap<String, Entry>()

    init {
        try {
            readDirectory()
        } catch (error: Throwable) {
            owned?.close()
            throw error
        }
    }

    override fun close() {
        owned?.close()
    }

    fun entry(name: String): Entry? = entries[name]

    fun open(entry: Entry, limits: XlsxLimits): InputStream {
        val header = read(entry.headerOffset, 30)
        if (header.int != LOCAL_HEADER) throw incomplete()
        val nameLength = header.getShort(26).toInt() and 0xffff
        val extraLength = header.getShort(28).toInt() and 0xffff
        val start = entry.headerOffset + 30 + nameLength + extraLength
        if (start + entry.compressedSize > length) throw incomplete()
        return when (entry.method) {
            STORED -> PartStream(entry, start, limits, null)
            DEFLATED -> PartStream(entry, start, limits, Inflater(true))
            else -> throw XlsxException("Part ${entry.name} of this workbook is packed in a way LeanRows does not read.")
        }
    }

    private fun readDirectory() {
        if (length < 22) throw incomplete()
        // The end record is in the last 64 KiB plus its own 22 bytes.
        val tailLength = minOf(length, 65_557L).toInt()
        val tail = read(length - tailLength, tailLength)
        var end = -1
        for (at in tailLength - 22 downTo 0) {
            if (tail.getInt(at) == END_RECORD) {
                end = at
                break
            }
        }
        if (end < 0) throw incomplete()
        var count = (tail.getShort(end + 10).toLong() and 0xffff)
        var directorySize = tail.getInt(end + 12).toLong() and 0xffffffffL
        var directoryOffset = tail.getInt(end + 16).toLong() and 0xffffffffL
        if (count == 0xffffL || directorySize == 0xffffffffL || directoryOffset == 0xffffffffL) {
            val locatorAt = length - tailLength + end - 20
            if (locatorAt < 0) throw incomplete()
            val locator = read(locatorAt, 20)
            if (locator.int != ZIP64_LOCATOR) throw incomplete()
            val recordAt = locator.getLong(8)
            if (recordAt < 0 || recordAt + 56 > length) throw incomplete()
            val record = read(recordAt, 56)
            if (record.int != ZIP64_END_RECORD) throw incomplete()
            count = record.getLong(32)
            directorySize = record.getLong(40)
            directoryOffset = record.getLong(48)
        }
        if (directoryOffset < 0 || directorySize < 0 || directoryOffset + directorySize > length) throw incomplete()
        if (directorySize > 64L shl 20 || count > 100_000) {
            throw XlsxException("This workbook lists more parts than LeanRows reads.")
        }
        val directory = read(directoryOffset, directorySize.toInt())
        var at = 0
        repeat(count.toInt()) {
            if (at + 46 > directorySize || directory.getInt(at) != DIRECTORY_HEADER) throw incomplete()
            val flags = directory.getShort(at + 8).toInt()
            val method = directory.getShort(at + 10).toInt() and 0xffff
            var compressed = directory.getInt(at + 20).toLong() and 0xffffffffL
            val unpacked = directory.getInt(at + 24).toLong() and 0xffffffffL
            val nameLength = directory.getShort(at + 28).toInt() and 0xffff
            val extraLength = directory.getShort(at + 30).toInt() and 0xffff
            val commentLength = directory.getShort(at + 32).toInt() and 0xffff
            var offset = directory.getInt(at + 42).toLong() and 0xffffffffL
            val next = at + 46 + nameLength + extraLength + commentLength
            if (next > directorySize) throw incomplete()
            val nameBytes = ByteArray(nameLength)
            directory.position(at + 46)
            directory.get(nameBytes)
            // Zip64 sizes and offsets come in a fixed order, each only when its
            // 32-bit field is full.
            var extra = at + 46 + nameLength
            val extraEnd = extra + extraLength
            while (extra + 4 <= extraEnd) {
                val id = directory.getShort(extra).toInt() and 0xffff
                val size = directory.getShort(extra + 2).toInt() and 0xffff
                if (id == 1) {
                    var field = extra + 4
                    if (unpacked == 0xffffffffL) field += 8
                    if (compressed == 0xffffffffL && field + 8 <= extra + 4 + size) {
                        compressed = directory.getLong(field)
                        field += 8
                    }
                    if (offset == 0xffffffffL && field + 8 <= extra + 4 + size) offset = directory.getLong(field)
                }
                extra += 4 + size
            }
            if (flags and 1 != 0) throw XlsxException("This workbook is encrypted, and LeanRows does not open encrypted files.")
            if (compressed < 0 || offset < 0 || offset + 30 > length) throw incomplete()
            val name = String(nameBytes, Charsets.UTF_8)
            entries.putIfAbsent(name, Entry(name, method, compressed, offset))
            at = next
        }
    }

    private fun read(position: Long, count: Int): ByteBuffer {
        val bytes = ByteArray(count)
        readAt(position, bytes, 0, count)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun readAt(position: Long, into: ByteArray, offset: Int, count: Int) {
        var done = 0
        while (done < count) {
            val read = source.read(position + done, into, offset + done, count - done)
            if (read <= 0) throw incomplete()
            done += read
        }
    }

    private fun incomplete() = XlsxException("This is not a complete .xlsx file: its zip directory is missing or damaged.")

    /** One part, unpacked as it is read, with the limits counted on the way. */
    private inner class PartStream(
        private val entry: Entry,
        private var position: Long,
        private val limits: XlsxLimits,
        private val inflater: Inflater?,
    ) : InputStream() {
        private val input = ByteArray(16 * 1024)
        private var remaining = entry.compressedSize
        private var consumed = 0L
        private var unpacked = 0L
        private var finished = false
        private val single = ByteArray(1)

        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (finished) return -1
            val read = if (inflater == null) readStored(buffer, offset, count) else readDeflated(inflater, buffer, offset, count)
            if (read < 0) {
                finished = true
                return -1
            }
            unpacked += read
            if (unpacked > limits.entryBytes) {
                throw XlsxException("Part ${entry.name} of this workbook is larger than ${size(limits.entryBytes)} unpacked.")
            }
            if (unpacked > limits.ratioAfterBytes && unpacked > maxOf(consumed, 1L) * limits.ratio) {
                throw XlsxException("Part ${entry.name} of this workbook is compressed more than ${limits.ratio} to 1, as zip bombs are.")
            }
            return read
        }

        private fun readStored(buffer: ByteArray, offset: Int, count: Int): Int {
            if (remaining == 0L) return -1
            val take = minOf(count.toLong(), remaining).toInt()
            readAt(position, buffer, offset, take)
            position += take
            remaining -= take
            consumed += take
            return take
        }

        private fun readDeflated(inflater: Inflater, buffer: ByteArray, offset: Int, count: Int): Int {
            while (true) {
                val read = try {
                    inflater.inflate(buffer, offset, count)
                } catch (error: DataFormatException) {
                    throw XlsxException("This is not a complete .xlsx file: part ${entry.name} is damaged.")
                }
                consumed = inflater.bytesRead
                if (read > 0) return read
                if (inflater.finished()) return -1
                if (inflater.needsDictionary()) throw XlsxException("This is not a complete .xlsx file: part ${entry.name} is damaged.")
                if (inflater.needsInput()) {
                    if (remaining == 0L) throw XlsxException("This is not a complete .xlsx file: part ${entry.name} ends too early.")
                    val take = minOf(input.size.toLong(), remaining).toInt()
                    readAt(position, input, 0, take)
                    position += take
                    remaining -= take
                    inflater.setInput(input, 0, take)
                }
            }
        }

        override fun close() {
            inflater?.end()
            finished = true
        }

        private fun size(bytes: Long): String = when {
            bytes >= 1L shl 30 && bytes % (1L shl 30) == 0L -> "${bytes shr 30} GiB"
            bytes >= 1L shl 20 && bytes % (1L shl 20) == 0L -> "${bytes shr 20} MiB"
            else -> "${bytes shr 10} KiB"
        }
    }

    private companion object {
        const val LOCAL_HEADER = 0x04034b50
        const val DIRECTORY_HEADER = 0x02014b50
        const val END_RECORD = 0x06054b50
        const val ZIP64_LOCATOR = 0x07064b50
        const val ZIP64_END_RECORD = 0x06064b50
        const val STORED = 0
        const val DEFLATED = 8
    }
}
