package io.github.abooodbah.leanrows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun records(text: String, format: Format, chunk: Int = 3): List<String> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val scanner = RecordScanner(format)
        val spans = mutableListOf<Pair<Long, Long>>()
        var index = 0
        while (index < bytes.size) {
            val length = minOf(chunk, bytes.size - index)
            scanner.feed(bytes, index, length) { start, end -> spans += start to end; true }
            index += length
        }
        scanner.finish { start, end -> spans += start to end; true }
        return spans.map { (start, end) -> String(bytes, start.toInt(), (end - start).toInt(), Charsets.UTF_8) }
    }

    @Test
    fun recordsEndAtAnyLineBreakOutsideQuotes() {
        assertEquals(listOf("a,b", "c,d", "e,f", "g"), records("a,b\nc,d\r\ne,f\rg", Format.CSV))
        assertEquals(listOf("a,\"x\ny\",b", "c"), records("a,\"x\ny\",b\nc\n", Format.CSV))
        assertEquals(listOf("\"a\"\"\nb\",c"), records("\"a\"\"\nb\",c", Format.CSV))
        assertEquals(listOf("", "a", ""), records("\na\n\n", Format.CSV))
        // In a line format quotes mean nothing.
        assertEquals(listOf("{\"a\":\"x", "y\"}"), records("{\"a\":\"x\ny\"}", Format.JSONL))
    }

    @Test
    fun chunkBoundariesDoNotMatter() {
        val text = "id,name\r\n1,\"Smith, \"\"J\"\"\r\nsecond line\"\r\n2,plain\n"
        val expected = records(text, Format.CSV, chunk = text.length)
        for (size in 1..7) assertEquals("chunk $size", expected, records(text, Format.CSV, size))
        assertEquals(3, expected.size)
    }

    @Test
    fun anOpenQuoteAtTheEndIsReported() {
        val scanner = RecordScanner(Format.CSV)
        val bytes = "a,\"open".toByteArray()
        scanner.feed(bytes) { _, _ -> true }
        assertTrue(scanner.finish { _, _ -> true })
        val closed = RecordScanner(Format.CSV)
        closed.feed("a,\"ok\"".toByteArray()) { _, _ -> true }
        assertFalse(closed.finish { _, _ -> true })
    }

    @Test
    fun stoppingReturnsWhatWasConsumed() {
        val scanner = RecordScanner(Format.CSV)
        val bytes = "a\nb\nc\n".toByteArray()
        val consumed = scanner.feed(bytes) { _, _ -> false }
        assertEquals(2, consumed)
        assertEquals(2L, scanner.offset)
    }

    private fun cells(text: String, format: Format = Format.CSV): List<String> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return Fields.cells(bytes, bytes.size, format)
    }

    @Test
    fun fieldsAreUnquotedLikeTheDesktop() {
        assertEquals(listOf("a", "b", "c"), cells("a,b,c"))
        assertEquals(listOf("Smith, J", "say \"hi\"", ""), cells("\"Smith, J\",\"say \"\"hi\"\"\","))
        assertEquals(listOf("", "", ""), cells(",,"))
        assertEquals(listOf("a,b", "c"), cells("a,b\tc", Format.TSV))
        assertEquals(listOf("x\"y"), cells("x\"y"))
        assertEquals(listOf("a,b,c"), cells("a,b,c", Format.LOG))
    }

    @Test
    fun hiddenCharactersAreShownAsEscapes() {
        assertEquals(listOf("line\\nbreak", "tab\\there", "nul\\0", "bell\\u{7}"), cells("\"line\nbreak\",\"tab\there\",\"nul\u0000\",bell\u0007"))
        assertEquals(listOf("café 日本 🚀"), cells("café 日本 🚀"))
    }

    @Test
    fun invalidUtf8BytesAreShownAsHex() {
        val bytes = byteArrayOf('a'.code.toByte(), 0xE9.toByte(), 'b'.code.toByte(), 0xE2.toByte(), 0x82.toByte(), 'c'.code.toByte())
        assertEquals(listOf("a\\xE9b\\xE2\\x82c"), Fields.cells(bytes, bytes.size, Format.CSV))
        // An overlong encoding and a lone surrogate are not text either.
        val overlong = byteArrayOf(0xC0.toByte(), 0xAF.toByte(), 0xED.toByte(), 0xA0.toByte(), 0x80.toByte())
        assertEquals(listOf("\\xC0\\xAF\\xED\\xA0\\x80"), Fields.cells(overlong, overlong.size, Format.LOG))
    }

    @Test
    fun longValuesAreCutAndMarked() {
        val long = "x".repeat(2000)
        val cell = cells(long).single()
        assertTrue(cell.endsWith(Fields.MARKER))
        assertTrue(cell.length <= Fields.MAX_CELL_CHARS)
        val many = (1..100).joinToString(",") { "f$it" }
        assertEquals(Fields.MAX_FIELDS, cells(many).size)
        val truncatedRecord = "a,b".toByteArray()
        assertEquals(listOf("a", "b" + Fields.MARKER), Fields.cells(truncatedRecord, truncatedRecord.size, Format.CSV, recordTruncated = true))
    }

    @Test
    fun formatComesFromTheNameThenTheTypeThenTheContent() {
        val commas = "a,b\nc,d\n".toByteArray()
        val tabs = "a\tb\nc\td\n".toByteArray()
        assertEquals(Format.TSV, Format.infer("data.tsv", "text/csv", commas))
        assertEquals(Format.CSV, Format.infer("DATA.CSV", null, tabs))
        assertEquals(Format.JSONL, Format.infer("events.ndjson", null, commas))
        assertEquals(Format.LOG, Format.infer("server.log", null, commas))
        assertEquals(Format.TSV, Format.infer("export", "text/tab-separated-values", commas))
        assertEquals(Format.CSV, Format.infer(null, "text/csv; charset=utf-8", tabs))
        assertEquals(Format.TSV, Format.infer("download", "application/octet-stream", tabs))
        assertEquals(Format.CSV, Format.infer(null, null, commas))
        assertEquals(Format.LOG, Format.infer(null, null, "just words\nmore words\n".toByteArray()))
    }

    @Test
    fun theRowIndexStaysWithinItsCapacity() {
        val index = RowIndex(capacity = 8, initialStride = 2)
        for (row in 0L until 1000L) index.observe(row, row * 10)
        assertEquals(1000L, index.rowCount)
        assertTrue(index.checkpoints <= 8)
        // Eight checkpoints cover rows 0 to 999 once the stride reaches 128.
        assertEquals(128L, index.stride)
        for (row in listOf(0L, 1L, 255L, 256L, 999L)) {
            val (checkpointRow, offset) = index.nearest(row)
            assertTrue("$row from $checkpointRow", checkpointRow <= row && row - checkpointRow < index.stride)
            assertEquals(checkpointRow * 10, offset)
        }
    }
}
