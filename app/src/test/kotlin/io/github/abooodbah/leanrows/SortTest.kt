package io.github.abooodbah.leanrows

import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Column sorting with desktop LeanRows 0.4.0's order. The first cases are
 * the desktop's own (crates/leanrows-core/tests/sort_engine.rs) with the
 * same inputs and expected rows. Each case runs with the app's limits and
 * with limits so small that every row is its own run and runs are joined two
 * or three at a time, so the multi-way merge and the passes are exercised.
 */
class SortTest {
    private val folder: File = Files.createTempDirectory("leanrows-sort-test").toFile()

    @After fun cleanUp() {
        folder.deleteRecursively()
    }

    private val tiny = listOf(
        SortLimits(),
        SortLimits(runBytes = Sorter.MAX_ENTRY, fanIn = 2, readBuffer = Sorter.MAX_ENTRY, inverseRows = 1),
        SortLimits(runBytes = 2 * Sorter.MAX_ENTRY, fanIn = 3, readBuffer = Sorter.MAX_ENTRY, inverseRows = 2),
    )

    private fun sort(
        input: ByteArray,
        mode: SortMode = SortMode.TEXT,
        descending: Boolean = false,
        column: Int = 0,
        format: Format = Format.CSV,
        limits: SortLimits = SortLimits(),
    ): List<Long> {
        val order = Sorter(ByteArraySource(input), format, SortRequest(column, mode, descending), folder, limits).run()
        order.use {
            val rows = (0 until it.count).map { rank -> it.rowAt(rank) }
            for ((rank, row) in rows.withIndex()) assertEquals("rank of row $row", rank.toLong(), it.rankOf(row))
            // Temporary files are unlinked as soon as they are open.
            assertEquals(emptyList<String>(), folder.list()!!.toList())
            return rows
        }
    }

    /** The same rows with every set of limits. */
    private fun rows(input: String, mode: SortMode = SortMode.TEXT, descending: Boolean = false, column: Int = 0): List<Long> =
        rows(input.toByteArray(Charsets.ISO_8859_1), mode, descending, column)

    private fun rows(input: ByteArray, mode: SortMode = SortMode.TEXT, descending: Boolean = false, column: Int = 0): List<Long> {
        val results = tiny.map { sort(input, mode, descending, column, limits = it) }
        for (result in results) assertEquals(results[0], result)
        return results[0]
    }

    // The desktop's cases.

    @Test fun tinyBatchesForceAGlobalMerge() {
        assertEquals(listOf<Long>(2, 4, 1, 5, 3, 0), rows("z\nc\na\ny\nb\nx\n"))
    }

    @Test fun numbersAreExactAndTiesKeepTheFileOrder() {
        val input = "9007199254740993\n9007199254740992\n-3\n2.00\n2e0\n\nx\n-0\n0\n"
        assertEquals(listOf<Long>(2, 7, 8, 3, 4, 1, 0, 6, 5), rows(input, SortMode.NUMERIC))
        assertEquals(listOf<Long>(0, 1, 3, 4, 7, 8, 2, 6, 5), rows(input, SortMode.NUMERIC, descending = true))
    }

    @Test fun quotedLineBreaksEscapedQuotesAndMissingColumns() {
        assertEquals(listOf<Long>(2, 3, 1, 0), rows("1,\"z\nz\"\r\n2,\"a\"\"b\"\r\n3,\r\n4\r\n", column = 1))
        val long = "a".repeat(5000) + "z\n" + "a".repeat(5000) + "b\n"
        assertEquals(listOf<Long>(1, 0), rows(long))
    }

    @Test fun emptySingleAndInvalidBytes() {
        assertEquals(emptyList<Long>(), rows(""))
        assertEquals(listOf<Long>(0), rows("one"))
        assertEquals(listOf<Long>(2, 1, 0), rows(byteArrayOf(0xFF.toByte(), 10, 0x80.toByte(), 10, 'a'.code.toByte(), 10)))
    }

    @Test fun incompleteExponentsAreTextAndDecimalsAreExact() {
        assertEquals(listOf<Long>(3, 4, 2, 6, 5, 0, 1), rows("1e \n1e+ \n.01\n-.2\n2e-3\n1.\n  +000.01 \n", SortMode.NUMERIC))
    }

    @Test fun numericOrderMatchesAnIntegerOracle() {
        val numbers = (0 until 257).map { ((it * 73 + 19) % 257) - 128 }
        val expected = numbers.indices.sortedBy { numbers[it] }.map { it.toLong() }
        assertEquals(expected, rows(numbers.joinToString("") { "$it\n" }, SortMode.NUMERIC))
    }

    @Test fun aNonNumericSuffixIsTextButAHugeExponentIsRefused() {
        assertEquals(listOf<Long>(1, 0), rows("1e9223372036854775808x\n2\n", SortMode.NUMERIC))
        try {
            rows("1e9223372036854775808\n2\n", SortMode.NUMERIC)
            fail("sorted a number with an exponent past the range")
        } catch (refused: SortException) {
            assertTrue(refused.message!!, refused.message!!.contains("exponent"))
        }
    }

    @Test fun cancellingInEveryPhaseStopsAndLeavesNothingBehind() {
        val input = DesktopFixtures.bytes("large.log")
        val limits = SortLimits(runBytes = 64 * 1024, fanIn = 4, inverseRows = 10_000)
        for (phase in SortPhase.entries) {
            var stop = false
            var readAfterStop = 0L
            val source = object : ByteSource {
                val inner = ByteArraySource(input)
                override val size: Long get() = inner.size
                override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int =
                    inner.read(position, into, offset, length).also { if (stop && it > 0) readAfterStop += it }
            }
            try {
                Sorter(source, Format.LOG, SortRequest(0, SortMode.TEXT, false), folder, limits)
                    .run(cancelled = { stop }, progress = { now, _, _ -> if (now == phase) stop = true })
                fail("not cancelled while $phase")
            } catch (cancelled: SortCancelled) {
                assertEquals(emptyList<String>(), folder.list()!!.toList())
            }
            // It stops at once, not at the end of the step.
            assertTrue("read $readAfterStop bytes after cancelling while $phase", readAfterStop <= Table.CHUNK)
        }
    }

    @Test fun theTemporaryDiskLimitStopsTheSort() {
        val limits = SortLimits(runBytes = Sorter.MAX_ENTRY, fanIn = 2, readBuffer = Sorter.MAX_ENTRY, diskBytes = 4096)
        try {
            sort("z\nc\na\ny\nb\nx\n".repeat(50).toByteArray(), limits = limits)
            fail("sorted past the disk limit")
        } catch (refused: SortException) {
            assertTrue(refused.message!!, refused.message!!.startsWith("This file is too large to sort"))
        }
        assertEquals(emptyList<String>(), folder.list()!!.toList())
    }

    // The app's own.

    @Test fun descendingTextReversesValuesButNotTies() {
        assertEquals(listOf<Long>(1, 3, 0, 2), rows("a\nb\na\nb\n", descending = true))
    }

    @Test fun textComparesBytesCaseSensitively() {
        // "B" (0x42) before "a" (0x61), and a prefix before what extends it.
        assertEquals(listOf<Long>(2, 3, 0, 1), rows("a\nab\nB\nBa\n"))
    }

    @Test fun lineFormatsSortByTheWholeLine() {
        val input = "{\"id\":3}\n{\"id\":1}\n{\"id\":2}\n"
        val order = Sorter(ByteArraySource(input.toByteArray()), Format.JSONL, SortRequest(0, SortMode.TEXT, false), folder).run()
        order.use { assertEquals(listOf(2L, 3L, 1L), it.rows(0, 3).map { row -> row.number }) }
    }

    @Test fun keysLongerThanTheKeptBytesAreComparedFromTheFile() {
        val prefix = "p".repeat(Sorter.KEY_BYTES + 10)
        val input = "$prefix-b,1\n$prefix-a,2\n\"$prefix-a\"\"\",3\n$prefix,4\n"
        assertEquals(listOf<Long>(3, 1, 2, 0), rows(input))
        val digits = "1" + "0".repeat(Sorter.KEY_BYTES + 10)
        val numbers = "${digits}2\n${digits}1\n${digits}\n${digits}1e-1\n"
        // 10^266, then 10^266 + 0.1, then 10^267 + 1, then 10^267 + 2: the last
        // two tie on their kept digits and are told apart from the file.
        assertEquals(listOf<Long>(2, 3, 1, 0), rows(numbers, SortMode.NUMERIC))
        // The same text keys in the second column, one of them quoted.
        assertEquals(listOf<Long>(3, 1, 2, 0), rows(input.lines().filter { it.isNotEmpty() }.joinToString("") { "x,$it\n" }, column = 1))
    }

    @Test fun trailingZerosAndLeadingZerosDoNotChangeANumber() {
        assertEquals(listOf<Long>(0, 1, 2, 3), rows("0012.500\n12.5\n1.25e1\n125e-1\n", SortMode.NUMERIC))
        assertEquals(listOf<Long>(1, 0), rows("0.000101\n0.0001\n", SortMode.NUMERIC))
        // Zeros past the kept digits are still only zeros.
        val big = "1" + "0".repeat(300)
        assertEquals(listOf<Long>(0, 1), rows("$big\n1e300\n", SortMode.NUMERIC))
        assertEquals(listOf<Long>(0, 1), rows("1e300\n$big\n", SortMode.NUMERIC))
    }

    @Test fun sortedRowsKeepTheirOwnNumbersAndCells() {
        val input = File("src/sharedTest/fixtures/desktop/weather-stations.csv").readBytes()
        val table = Table(ByteArraySource(input), Format.CSV).also { it.scan() }
        val order = Sorter(ByteArraySource(input), Format.CSV, SortRequest(5, SortMode.NUMERIC, true), folder).run()
        order.use {
            assertEquals(table.rowCount, it.count)
            val sorted = it.rows(0, it.count.toInt())
            for (row in sorted) {
                val own = table.rows(row.number - 1, 1).single()
                assertEquals(own.cells, row.cells)
            }
            val temperatures = sorted.map { row -> row.cells[5] }
            // The header's "temperature_c" is text, after every number.
            assertEquals("temperature_c", temperatures.last())
            val values = temperatures.dropLast(1).map { value -> BigDecimal(value) }
            assertEquals(values.sortedDescending(), values)
        }
    }

    @Test fun recordsAreFoundAsTheTableFindsThem() {
        for (name in listOf("large-multiline.csv", "malformed.csv", "large.jsonl")) {
            val input = DesktopFixtures.bytes(name)
            val format = if (name.endsWith(".jsonl")) Format.JSONL else Format.CSV
            val table = Table(ByteArraySource(input), format).also { it.scan() }
            val cells = ArrayList<List<String>>()
            while (cells.size < table.rowCount) cells += table.rows(cells.size.toLong(), 1000).map { it.cells }
            val order = Sorter(ByteArraySource(input), format, SortRequest(0, SortMode.TEXT, false), folder).run()
            order.use {
                assertEquals(name, table.rowCount, it.count)
                val seen = BooleanArray(it.count.toInt())
                var rank = 0L
                while (rank < it.count) {
                    for (row in it.rows(rank, 1000)) {
                        assertEquals(name, cells[(row.number - 1).toInt()], row.cells)
                        seen[(row.number - 1).toInt()] = true
                    }
                    rank += 1000
                }
                assertTrue(name, seen.all { seen -> seen })
            }
        }
    }

    @Test fun randomFilesMatchAnIndependentOrder() {
        val random = Random(1729)
        val words = listOf("", " ", "apple", "Apple", "a\"b", "line\nbreak", "x,y", "12", "012", "1e3", "-0", "+.5", "1.", "e5", "-", "1e", " 7 ", "7 7", "0.000", "-12.25", "99999999999999999999", "1E-2")
        repeat(30) { round ->
            val count = random.nextInt(1, 400)
            val values = List(count) {
                when (random.nextInt(4)) {
                    0 -> words[random.nextInt(words.size)]
                    1 -> random.nextLong(-100000, 100000).toString()
                    2 -> "${random.nextInt(-999, 999)}.${random.nextInt(0, 1000)}e${random.nextInt(-5, 5)}"
                    else -> List(random.nextInt(0, 6)) { "abcAB\"0".random(random) }.joinToString("")
                }
            }
            val csv = StringBuilder()
            for ((index, value) in values.withIndex()) {
                val field = if (value.any { it == ',' || it == '"' || it == '\n' } || random.nextInt(5) == 0) {
                    "\"" + value.replace("\"", "\"\"") + "\""
                } else {
                    value
                }
                csv.append(index).append(',').append(field).append(if (random.nextBoolean()) "\r\n" else "\n")
            }
            for (mode in SortMode.entries) {
                for (descending in listOf(false, true)) {
                    val expected = oracle(values, mode, descending)
                    val limits = tiny[round % tiny.size]
                    assertEquals("round $round $mode descending=$descending", expected, sort(csv.toString().toByteArray(), mode, descending, 1, limits = limits))
                }
            }
        }
    }

    /** An independent order: Java strings by UTF-8 bytes, and BigDecimal for numbers. */
    private fun oracle(values: List<String>, mode: SortMode, descending: Boolean): List<Long> {
        fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)
        fun textOrder(a: String, b: String): Int {
            val x = bytes(a)
            val y = bytes(b)
            for (i in 0 until minOf(x.size, y.size)) {
                val c = (x[i].toInt() and 0xFF).compareTo(y[i].toInt() and 0xFF)
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
        fun trimmed(value: String) = value.trim { it == ' ' || it == '\t' || it == '\n' || it == '\u000C' || it == '\r' }
        fun number(value: String): BigDecimal? {
            val text = trimmed(value)
            if (text.isEmpty() || text.any { it.code > 127 || it.isWhitespace() }) return null
            if (!Regex("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?").matches(text)) return null
            return BigDecimal(text)
        }
        fun group(value: String) = when {
            number(value) != null -> 0
            trimmed(value).isEmpty() -> 2
            else -> 1
        }
        val comparator = Comparator<Int> { a, b ->
            val x = values[a]
            val y = values[b]
            var value = 0
            if (mode == SortMode.NUMERIC) {
                val gx = group(x)
                val gy = group(y)
                if (gx != gy) return@Comparator gx.compareTo(gy)
                value = when (gx) {
                    0 -> number(x)!!.compareTo(number(y)!!)
                    1 -> textOrder(x, y)
                    else -> 0
                }
            } else {
                value = textOrder(x, y)
            }
            if (descending) value = -value
            if (value != 0) value else a.compareTo(b)
        }
        return values.indices.sortedWith(comparator).map { it.toLong() }
    }

    // Space before a sort, as the desktop checks it.

    @Test fun theSpaceCheckRefusesOneByteShortOfFreeSpace() {
        val rows = 1_000_000L
        val needed = Sorter.neededBytes(rows, rows * 50) + Sorter.MARGIN_BYTES
        assertNull(Sorter.checkSpace(rows, rows * 50, free = needed))
        val refused = Sorter.checkSpace(rows, rows * 50, free = needed - 1)
        assertTrue(refused, refused!!.startsWith("Not enough free space to sort"))
    }

    @Test fun theSpaceCheckRefusesFilesOverTheSortLimit() {
        val refused = Sorter.checkSpace(100_000_000L, 100_000_000L * 300, free = Long.MAX_VALUE)
        assertTrue(refused, refused!!.startsWith("This file is too large to sort"))
        assertTrue(refused, refused.contains("8.0 GB sort limit"))
    }

    @Test fun expectedRowsScaleAPartialIndexToTheWholeFile() {
        assertEquals(10L, Sorter.expectedRows(10, true, 50, 100))
        assertEquals(20L, Sorter.expectedRows(10, false, 50, 100))
        assertEquals(10L, Sorter.expectedRows(10, false, 0, 100))
        assertEquals(Long.MAX_VALUE, Sorter.expectedRows(Long.MAX_VALUE, false, 1, 2))
    }

    @Test fun sizesReadAsTheDesktopShowsThem() {
        assertEquals("8.0 GB", Sorter.sizeText(8L * 1024 * 1024 * 1024))
        assertEquals("245 MB", Sorter.sizeText(256_000_000))
        assertEquals("2.4 GB", Sorter.sizeText(2_560_000_000))
    }
}
