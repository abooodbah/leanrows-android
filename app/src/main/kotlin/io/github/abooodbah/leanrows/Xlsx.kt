package io.github.abooodbah.leanrows

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.Reader
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToLong

/** A workbook that cannot be shown, with a message for the person who opened it. */
class XlsxException(message: String) : IOException(message)

/** The limits in docs/XLSX.md. Tests lower them to reach them with small files. */
data class XlsxLimits(
    val rows: Long = 1_048_576,
    val columns: Int = 16_384,
    val entryBytes: Long = 1L shl 30,
    val ratio: Long = 200,
    val ratioAfterBytes: Long = 1L shl 20,
    val sharedStringChars: Long = 256L shl 20,
)

class XlsxSheet(val name: String, internal val part: String)

/**
 * An .xlsx workbook opened read-only. [writeCsv] turns one visible sheet into
 * CSV, one row at a time, by the rules in docs/XLSX.md; nothing in the file
 * is calculated, run or followed. [XlsxZip] counts every part as it is
 * unpacked and stops at the limits, whatever sizes the zip claims.
 */
class XlsxBook private constructor(
    private val zip: XlsxZip,
    val sheets: List<XlsxSheet>,
    private val date1904: Boolean,
    private val limits: XlsxLimits,
) : Closeable {
    private var shared: List<String>? = null
    private var kinds: List<DateKind>? = null

    override fun close() = zip.close()

    /**
     * Writes sheet [index] to [out] as CSV and returns the number of rows,
     * empty rows included. [cancelled] is checked once per row, and
     * [progress] hears the count every 10,000 rows.
     */
    fun writeCsv(
        index: Int,
        out: OutputStream,
        cancelled: () -> Boolean = { false },
        progress: (Long) -> Unit = {},
    ): Long {
        val sheet = sheets[index]
        val strings = shared ?: readSharedStrings().also { shared = it }
        val styles = kinds ?: readStyles().also { kinds = it }
        val writer = CsvWriter(BufferedOutputStream(out, 256 * 1024))
        var written = 0L
        parse(sheet.part) { xml ->
            var cells = ArrayList<String>()
            var column = 0
            var nextRow = 1L
            var rowNumber = 0L
            var inSheetData = false
            // The cell being read.
            var cellColumn = 0
            var cellType = "n"
            var cellStyle = 0
            var value: StringBuilder? = null
            var inline: StringBuilder? = null
            var inRun = false
            var inPhonetic = false
            var inValue = false
            var inText = false
            while (true) {
                when (xml.next()) {
                    Xml.Event.START -> when (xml.name) {
                        "sheetData" -> inSheetData = true
                        "row" -> if (inSheetData) {
                            rowNumber = xml.attribute("r")?.toLongOrNull() ?: nextRow
                            if (rowNumber < nextRow) throw XlsxException("Sheet \"${sheet.name}\" lists its rows out of order.")
                            if (rowNumber > limits.rows) throw XlsxException("This sheet has more than ${number(limits.rows)} rows.")
                            while (nextRow < rowNumber) {
                                if (cancelled()) throw IOException("cancelled")
                                writer.endRow()
                                written++
                                nextRow++
                            }
                            cells = ArrayList()
                            column = 0
                        }
                        "c" -> if (inSheetData) {
                            cellColumn = xml.attribute("r")?.let { columnOf(it) } ?: column
                            if (cellColumn >= limits.columns) throw XlsxException("This sheet has more than ${number(limits.columns.toLong())} columns.")
                            cellType = xml.attribute("t") ?: "n"
                            cellStyle = xml.attribute("s")?.toIntOrNull() ?: 0
                            value = null
                            inline = null
                        }
                        "v" -> if (inSheetData) {
                            inValue = true
                            value = StringBuilder()
                        }
                        "is" -> if (inSheetData) inline = StringBuilder()
                        "r" -> inRun = true
                        "rPh" -> inPhonetic = true
                        "t" -> inText = inline != null && !inPhonetic
                    }
                    Xml.Event.TEXT -> when {
                        inValue -> value?.append(xml.text)
                        inText -> inline?.append(xml.text)
                    }
                    Xml.Event.END -> when (xml.name) {
                        "sheetData" -> inSheetData = false
                        "v" -> inValue = false
                        "t" -> inText = false
                        "r" -> inRun = false
                        "rPh" -> inPhonetic = false
                        "c" -> if (inSheetData) {
                            while (cells.size < cellColumn) cells.add("")
                            val text = cellText(cellType, cellStyle, value?.toString(), inline?.toString(), strings, styles)
                            if (cellColumn < cells.size) cells[cellColumn] = text else cells.add(text)
                            column = cellColumn + 1
                        }
                        "row" -> if (inSheetData) {
                            if (cancelled()) throw IOException("cancelled")
                            for (cell in cells) writer.cell(cell)
                            writer.endRow()
                            written++
                            if (written % 10_000 == 0L) progress(written)
                            nextRow = rowNumber + 1
                        }
                    }
                    Xml.Event.EOF -> break
                }
            }
        }
        writer.flush()
        return written
    }

    private fun cellText(
        type: String,
        style: Int,
        value: String?,
        inline: String?,
        strings: List<String>,
        styles: List<DateKind>,
    ): String = when (type) {
        "s" -> value?.trim()?.toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
        "inlineStr" -> inline.orEmpty()
        "b" -> when (value?.trim()) {
            null -> ""
            "1" -> "TRUE"
            else -> "FALSE"
        }
        "str", "e", "d" -> value.orEmpty()
        else -> {
            val stored = value?.trim()
            val kind = styles.getOrNull(style) ?: DateKind.NONE
            when {
                stored.isNullOrEmpty() -> ""
                kind == DateKind.NONE -> stored
                else -> dateText(stored, kind) ?: stored
            }
        }
    }

    private fun dateText(stored: String, kind: DateKind): String? {
        val serial = stored.toDoubleOrNull() ?: return null
        if (serial.isNaN() || serial < 0 || serial > 2_958_466) return null
        val base = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
        // Excel counts a 29 February 1900 that never was; before it, its
        // serials are one day ahead of the calendar.
        val days = if (!date1904 && serial < 61) serial + 1 else serial
        val moment = base.plusSeconds((days * 86_400.0).roundToLong())
        return when (kind) {
            DateKind.TIME -> String.format(Locale.ROOT, "%02d:%02d:%02d", moment.hour, moment.minute, moment.second)
            DateKind.DATE -> String.format(Locale.ROOT, "%04d-%02d-%02d", moment.year, moment.monthValue, moment.dayOfMonth)
            DateKind.DATE_TIME -> String.format(
                Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d",
                moment.year, moment.monthValue, moment.dayOfMonth, moment.hour, moment.minute, moment.second,
            )
            DateKind.NONE -> null
        }
    }

    private fun readSharedStrings(): List<String> {
        val strings = ArrayList<String>()
        if (zip.entry(SHARED_STRINGS) == null) return strings
        var total = 0L
        parse(SHARED_STRINGS) { xml ->
            var item: StringBuilder? = null
            var inPhonetic = false
            var inText = false
            while (true) {
                when (xml.next()) {
                    Xml.Event.START -> when (xml.name) {
                        "si" -> item = StringBuilder()
                        "rPh" -> inPhonetic = true
                        "t" -> inText = item != null && !inPhonetic
                    }
                    Xml.Event.TEXT -> if (inText) {
                        item?.append(xml.text)
                        total += xml.text.length
                        if (total > limits.sharedStringChars) {
                            throw XlsxException("This workbook has more than ${number(limits.sharedStringChars shr 20)} MiB of shared text.")
                        }
                    }
                    Xml.Event.END -> when (xml.name) {
                        "si" -> {
                            strings.add(item?.toString().orEmpty())
                            item = null
                        }
                        "rPh" -> inPhonetic = false
                        "t" -> inText = false
                    }
                    Xml.Event.EOF -> break
                }
            }
        }
        return strings
    }

    private fun readStyles(): List<DateKind> {
        if (zip.entry(STYLES) == null) return emptyList()
        val codes = HashMap<Int, String>()
        val kinds = ArrayList<DateKind>()
        parse(STYLES) { xml ->
            var inCellFormats = false
            while (true) {
                when (xml.next()) {
                    Xml.Event.START -> when (xml.name) {
                        "numFmt" -> {
                            val id = xml.attribute("numFmtId")?.toIntOrNull()
                            if (id != null) codes[id] = xml.attribute("formatCode").orEmpty()
                        }
                        "cellXfs" -> inCellFormats = true
                        "xf" -> if (inCellFormats) {
                            val id = xml.attribute("numFmtId")?.toIntOrNull() ?: 0
                            kinds.add(codes[id]?.let { formatKind(it) } ?: builtInKind(id))
                        }
                    }
                    Xml.Event.END -> if (xml.name == "cellXfs") inCellFormats = false
                    Xml.Event.TEXT -> Unit
                    Xml.Event.EOF -> break
                }
            }
        }
        return kinds
    }

    private fun parse(part: String, read: (Xml) -> Unit) {
        val entry = zip.entry(part) ?: throw XlsxException("This workbook is missing its part $part.")
        try {
            zip.open(entry, limits).use { input -> read(Xml(InputStreamReader(input, Charsets.UTF_8), part)) }
        } catch (error: java.io.EOFException) {
            throw XlsxException("This is not a complete .xlsx file: $part ends too early.")
        }
    }

    internal enum class DateKind { NONE, DATE, DATE_TIME, TIME }

    companion object {
        const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val SHARED_STRINGS = "xl/sharedStrings.xml"
        private const val STYLES = "xl/styles.xml"

        /** Opens [file] and reads its list of sheets. */
        fun open(file: File, limits: XlsxLimits = XlsxLimits()): XlsxBook {
            val data = RandomAccessFile(file, "r")
            val source = object : ByteSource {
                override val size = data.length()
                override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int = synchronized(data) {
                    data.seek(position)
                    data.read(into, offset, length)
                }
            }
            return open(XlsxZip(source, data), limits)
        }

        /** Reads the list of sheets from [source], which the caller closes. */
        fun open(source: ByteSource, limits: XlsxLimits = XlsxLimits()): XlsxBook = open(XlsxZip(source), limits)

        private fun open(zip: XlsxZip, limits: XlsxLimits): XlsxBook {
            try {
                val workbook = readWorkbook(zip, limits)
                return XlsxBook(zip, workbook.first, workbook.second, limits)
            } catch (error: Throwable) {
                zip.close()
                throw error
            }
        }

        /** True when the file is an .xlsx workbook, by its name, its type or its first bytes. */
        fun recognises(name: String?, mimeType: String?, sample: ByteArray): Boolean {
            val zipped = sample.size >= 4 && sample[0] == 'P'.code.toByte() && sample[1] == 'K'.code.toByte() &&
                sample[2] == 3.toByte() && sample[3] == 4.toByte()
            if (!zipped) return false
            val extension = name?.substringAfterLast('.', "")?.lowercase()
            val type = mimeType?.lowercase()?.substringBefore(';')?.trim()
            if (extension == "xlsx" || type == MIME_TYPE) return true
            val unknown = type == null || type == "application/octet-stream" || type == "application/zip"
            return unknown && (extension.isNullOrEmpty() || extension == "zip") &&
                String(sample, Charsets.ISO_8859_1).contains("xl/")
        }

        /** The sheet's letters for column [index], counting from 0: A, ..., Z, AA, ... */
        fun columnName(index: Int): String {
            val letters = StringBuilder()
            var rest = index + 1
            while (rest > 0) {
                val digit = (rest - 1) % 26
                letters.append('A' + digit)
                rest = (rest - 1) / 26
            }
            return letters.reverse().toString()
        }

        internal fun columnOf(reference: String): Int {
            var number = 0
            for (char in reference) {
                if (char !in 'A'..'Z' && char !in 'a'..'z') break
                number = number * 26 + (char.uppercaseChar() - 'A' + 1)
                if (number > 1_000_000) break
            }
            return number - 1
        }

        private fun readWorkbook(zip: XlsxZip, limits: XlsxLimits): Pair<List<XlsxSheet>, Boolean> {
            val targets = HashMap<String, String>()
            parseEntry(zip, "xl/_rels/workbook.xml.rels", limits) { xml ->
                while (true) {
                    when (xml.next()) {
                        Xml.Event.START -> if (xml.name == "Relationship" && xml.attribute("TargetMode") != "External") {
                            val id = xml.attribute("Id")
                            val target = xml.attribute("Target")
                            if (id != null && target != null) targets[id] = target
                        }
                        Xml.Event.EOF -> break
                        else -> Unit
                    }
                }
            }
            val sheets = ArrayList<XlsxSheet>()
            var date1904 = false
            parseEntry(zip, "xl/workbook.xml", limits) { xml ->
                while (true) {
                    when (xml.next()) {
                        Xml.Event.START -> when (xml.name) {
                            "workbookPr" -> date1904 = xml.attribute("date1904").let { it == "1" || it == "true" }
                            "sheet" -> {
                                val state = xml.attribute("state")
                                val target = xml.attribute("id")?.let { targets[it] }
                                if (state != "hidden" && state != "veryHidden" && target != null) {
                                    sheets.add(XlsxSheet(xml.attribute("name") ?: "Sheet ${sheets.size + 1}", resolve(target)))
                                }
                            }
                        }
                        Xml.Event.EOF -> break
                        else -> Unit
                    }
                }
            }
            if (sheets.isEmpty()) throw XlsxException("This workbook has no visible sheet.")
            return sheets to date1904
        }

        private fun resolve(target: String): String {
            val path = if (target.startsWith("/")) target.substring(1) else "xl/$target"
            val parts = ArrayList<String>()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(part)
                }
            }
            return parts.joinToString("/")
        }

        private fun parseEntry(zip: XlsxZip, part: String, limits: XlsxLimits, read: (Xml) -> Unit) {
            val entry = zip.entry(part) ?: throw XlsxException("This is not an Excel workbook: it has no $part.")
            try {
                zip.open(entry, limits).use { input -> read(Xml(InputStreamReader(input, Charsets.UTF_8), part)) }
            } catch (error: java.io.EOFException) {
                throw XlsxException("This is not a complete .xlsx file: $part ends too early.")
            }
        }

        /** Built-in number formats 14 to 22 and 45 to 47 are dates and times. */
        internal fun builtInKind(id: Int): DateKind = when (id) {
            in 14..17 -> DateKind.DATE
            22 -> DateKind.DATE_TIME
            in 18..21, in 45..47 -> DateKind.TIME
            else -> DateKind.NONE
        }

        /** Whether a custom format code shows a date, a time, both, or neither. */
        internal fun formatKind(code: String): DateKind {
            val withoutLiterals = StringBuilder()
            var index = 0
            while (index < code.length) {
                val char = code[index]
                when {
                    char == '"' -> {
                        val close = code.indexOf('"', index + 1)
                        index = if (close < 0) code.length else close + 1
                        continue
                    }
                    char == '\\' || char == '_' || char == '*' -> {
                        index += 2
                        continue
                    }
                    else -> withoutLiterals.append(char)
                }
                index++
            }
            val cleaned = withoutLiterals.toString()
            val elapsed = Regex("\\[(h+|m+|s+)]", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)
            val section = cleaned.replace(Regex("\\[[^]]*]"), "").lowercase(Locale.ROOT).substringBefore(';')
            val hasTimeLetter = section.any { it == 'h' || it == 's' }
            val hasDate = section.any { it == 'd' || it == 'y' } || ('m' in section && !hasTimeLetter)
            val hasTime = elapsed || hasTimeLetter
            return when {
                hasDate && hasTime -> DateKind.DATE_TIME
                hasDate -> DateKind.DATE
                hasTime -> DateKind.TIME
                else -> DateKind.NONE
            }
        }

        private fun number(value: Long): String = String.format(Locale.ROOT, "%,d", value)
    }

    /** Writes CSV records: every non-empty cell quoted, quotes doubled, rows ending in LF. */
    private class CsvWriter(private val out: OutputStream) {
        private var first = true

        fun cell(text: String) {
            if (!first) out.write(','.code)
            first = false
            if (text.isEmpty()) return
            out.write('"'.code)
            out.write(text.replace("\"", "\"\"").toByteArray(Charsets.UTF_8))
            out.write('"'.code)
        }

        fun endRow() {
            out.write('\n'.code)
            first = true
        }

        fun flush() = out.flush()
    }
}

/**
 * A small pull reader for the XML in a workbook: elements, attributes, text,
 * character and the five predefined entity references, CDATA. A DOCTYPE is
 * refused, so no entity can be declared and nothing outside the file is read.
 * Names are given without their namespace prefix.
 */
internal class Xml(private val reader: Reader, private val part: String) {
    enum class Event { START, END, TEXT, EOF }

    var name: String = ""
        private set
    var text: String = ""
        private set

    private val buffer = CharArray(64 * 1024)
    private var length = 0
    private var position = 0
    private val attributes = ArrayList<String>()
    private var pendingEnd: String? = null
    private val scratch = StringBuilder()

    fun attribute(local: String): String? {
        var index = 0
        while (index < attributes.size) {
            if (attributes[index] == local) return attributes[index + 1]
            index += 2
        }
        return null
    }

    fun next(): Event {
        pendingEnd?.let {
            pendingEnd = null
            name = it
            attributes.clear()
            return Event.END
        }
        while (true) {
            val char = peek()
            if (char < 0) return Event.EOF
            if (char != '<'.code) {
                text = readText()
                return Event.TEXT
            }
            position++
            when (peek()) {
                '?'.code -> skipPast("?>")
                '!'.code -> {
                    if (startsWith("!--")) {
                        skipPast("-->")
                    } else if (startsWith("![CDATA[")) {
                        position += "![CDATA[".length
                        text = readUntil("]]>")
                        return Event.TEXT
                    } else {
                        throw XlsxException("This workbook's $part has a DOCTYPE or a declaration, which LeanRows does not read.")
                    }
                }
                '/'.code -> {
                    position++
                    name = local(readName())
                    skipPast(">")
                    attributes.clear()
                    return Event.END
                }
                else -> {
                    name = local(readName())
                    readAttributes()
                    return Event.START
                }
            }
        }
    }

    private fun readAttributes() {
        attributes.clear()
        while (true) {
            skipSpace()
            val char = peek()
            when {
                char < 0 -> throw XlsxException("This is not a complete .xlsx file: $part ends inside a tag.")
                char == '/'.code -> {
                    position++
                    expect('>')
                    pendingEnd = name
                    return
                }
                char == '>'.code -> {
                    position++
                    return
                }
                else -> {
                    val attributeName = local(readName())
                    skipSpace()
                    expect('=')
                    skipSpace()
                    val quote = peek()
                    if (quote != '"'.code && quote != '\''.code) throw broken()
                    position++
                    attributes.add(attributeName)
                    attributes.add(readValue(quote.toChar()))
                }
            }
        }
    }

    private fun readText(): String {
        scratch.setLength(0)
        while (true) {
            if (position >= length && !fill()) break
            val char = buffer[position]
            if (char == '<') break
            if (char == '&') readReference() else {
                scratch.append(char)
                position++
            }
        }
        return scratch.toString()
    }

    private fun readValue(quote: Char): String {
        scratch.setLength(0)
        while (true) {
            if (position >= length && !fill()) throw broken()
            val char = buffer[position]
            when (char) {
                quote -> {
                    position++
                    return scratch.toString()
                }
                '&' -> readReference()
                '<' -> throw broken()
                else -> {
                    scratch.append(char)
                    position++
                }
            }
        }
    }

    private fun readReference() {
        position++ // '&'
        val start = StringBuilder()
        while (true) {
            if (position >= length && !fill()) throw broken()
            val char = buffer[position++]
            if (char == ';') break
            start.append(char)
            if (start.length > 12) throw broken()
        }
        val reference = start.toString()
        when {
            reference == "lt" -> scratch.append('<')
            reference == "gt" -> scratch.append('>')
            reference == "amp" -> scratch.append('&')
            reference == "quot" -> scratch.append('"')
            reference == "apos" -> scratch.append('\'')
            reference.startsWith("#x") || reference.startsWith("#X") ->
                scratch.appendCodePoint(reference.substring(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) } ?: throw broken())
            reference.startsWith("#") ->
                scratch.appendCodePoint(reference.substring(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) } ?: throw broken())
            else -> throw XlsxException("This workbook's $part uses an entity, &$reference;, that LeanRows does not read.")
        }
    }

    private fun readName(): String {
        val out = StringBuilder()
        while (true) {
            if (position >= length && !fill()) break
            val char = buffer[position]
            if (char.isWhitespace() || char == '>' || char == '/' || char == '=') break
            out.append(char)
            position++
        }
        if (out.isEmpty()) throw broken()
        return out.toString()
    }

    private fun readUntil(end: String): String {
        val out = StringBuilder()
        while (!startsWith(end)) {
            if (position >= length && !fill()) throw broken()
            out.append(buffer[position++])
        }
        position += end.length
        return out.toString()
    }

    private fun skipPast(end: String) {
        while (!startsWith(end)) {
            if (position >= length && !fill()) throw broken()
            position++
        }
        position += end.length
    }

    private fun skipSpace() {
        while (true) {
            if (position >= length && !fill()) return
            if (!buffer[position].isWhitespace()) return
            position++
        }
    }

    private fun expect(char: Char) {
        if (peek() != char.code) throw broken()
        position++
    }

    private fun startsWith(prefix: String): Boolean {
        if (length - position < prefix.length) {
            compact()
            while (length - position < prefix.length) if (!fillMore()) return false
        }
        for (index in prefix.indices) if (buffer[position + index] != prefix[index]) return false
        return true
    }

    private fun peek(): Int {
        if (position >= length && !fill()) return -1
        return buffer[position].code
    }

    private fun fill(): Boolean {
        position = 0
        length = 0
        val read = reader.read(buffer, 0, buffer.size)
        if (read <= 0) return false
        length = read
        // A byte order mark at the start of the part.
        if (buffer[0] == '\uFEFF' && first) position = 1
        first = false
        return position < length
    }

    private var first = true

    private fun compact() {
        if (position > 0) {
            System.arraycopy(buffer, position, buffer, 0, length - position)
            length -= position
            position = 0
        }
    }

    private fun fillMore(): Boolean {
        if (length == buffer.size) return false
        val read = reader.read(buffer, length, buffer.size - length)
        if (read <= 0) return false
        length += read
        return true
    }

    private fun local(qualified: String): String = qualified.substringAfter(':')

    private fun broken() = XlsxException("This is not a complete .xlsx file: $part is not well-formed XML.")
}
