package io.github.abooodbah.leanrows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Find works like the desktop's: literal UTF-8 bytes anywhere in a record,
 * ASCII letters folded unless case must match. Expected rows were read off
 * the files with grep, or follow from how the desktop script writes them.
 */
class FindTest {
    private fun table(bytes: ByteArray, format: Format, scan: Boolean = true) =
        Table(ByteArraySource(bytes), format).also { if (scan) it.scan() }

    private val weather by lazy {
        table(File("src/sharedTest/fixtures/desktop/weather-stations.csv").readBytes(), Format.CSV)
    }

    private fun Table.next(text: String, after: Long, matchCase: Boolean = false) =
        Finder(this).next(text, matchCase, after)

    private fun Table.previous(text: String, before: Long, matchCase: Boolean = false) =
        Finder(this).previous(text, matchCase, before)

    @Test fun nextFindsTheFirstRowAfterTheCurrentOne() {
        assertEquals(Finder.Hit(4, wrapped = false), weather.next("Desert Flats", after = -1))
        assertEquals(Finder.Hit(10, wrapped = false), weather.next("Desert Flats", after = 4))
    }

    @Test fun lettersAreFoldedUnlessCaseMustMatch() {
        assertEquals(4L, weather.next("desert flats", after = -1)?.row)
        assertNull(weather.next("desert flats", after = -1, matchCase = true))
        assertEquals(4L, weather.next("Desert Flats", after = -1, matchCase = true)?.row)
    }

    @Test fun nextWrapsToTheTop() {
        assertEquals(Finder.Hit(4, wrapped = true), weather.next("Desert Flats", after = 1438))
    }

    @Test fun previousFindsTheLastRowBeforeTheCurrentOne() {
        assertEquals(Finder.Hit(1438, wrapped = false), weather.previous("Desert Flats", before = 1441))
        assertEquals(Finder.Hit(4, wrapped = false), weather.previous("Desert Flats", before = 10))
        assertEquals(Finder.Hit(1438, wrapped = true), weather.previous("Desert Flats", before = 4))
    }

    @Test fun textThatIsNowhereFindsNothing() {
        assertNull(weather.next("Lake Placid", after = -1))
        assertNull(weather.previous("Lake Placid", before = 1441))
    }

    @Test fun matchesInsideQuotedLineBreaksBelongToTheirRecord() {
        // Data row 970 is a multiline record (970 is a multiple of 97); it is
        // table row 971 because row 0 is the header.
        val large = table(DesktopFixtures.bytes("large-multiline.csv"), Format.CSV)
        assertEquals(971L, large.next("continuation 970 ", after = -1)?.row)
        // Rows after it are counted right, so the next multiline record is
        // found on its own row too.
        assertEquals(971L + 97, large.next("continuation 1067 ", after = 971)?.row)
    }

    @Test fun aMatchNeverSpansTwoRecords() {
        val malformed = table(DesktopFixtures.bytes("malformed.csv"), Format.CSV)
        assertNull(malformed.next("valid2", after = -1))
        assertEquals(1L, malformed.next("valid", after = -1)?.row)
        assertEquals(5L, malformed.next("unterminated", after = -1)?.row)
    }

    @Test fun findWorksBeforeTheScanHasFinished() {
        val large = table(DesktopFixtures.bytes("large.log"), Format.LOG, scan = false)
        assertEquals(99_999L, large.next("row_id=99999 ", after = -1)?.row)
    }

    @Test fun nonAsciiTextIsMatchedByteForByte() {
        val bytes = "name\nZoë\nZOË\nzoë\n".toByteArray()
        val names = table(bytes, Format.LOG)
        // Only ASCII letters fold, so "ZOË" (capital Ë) is not a match for "zoë".
        assertEquals(listOf(1L, 3L), generateSequence(names.next("zoë", -1)?.takeIf { !it.wrapped }) { hit ->
            names.next("zoë", hit.row)?.takeIf { !it.wrapped }
        }.map { it.row }.toList())
    }

    @Test fun matchesAcrossReadChunksAreFound() {
        val record = "x".repeat(Table.CHUNK - 3) + "needle\n"
        val table = table((record + record).toByteArray(), Format.LOG)
        assertEquals(0L, table.next("needle", after = -1)?.row)
        assertEquals(1L, table.next("needle", after = 0)?.row)
    }

    /** Counts the bytes a search reads from the file. */
    private class CountingSource(private val inner: ByteSource) : ByteSource {
        var bytes = 0L
        override val size: Long get() = inner.size
        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int =
            inner.read(position, into, offset, length).also { if (it > 0) bytes += it }
    }

    @Test fun previousWithoutAMatchReadsTheFileAboutOnce() {
        val data = DesktopFixtures.bytes("large-multiline.csv")
        val source = CountingSource(ByteArraySource(data))
        val large = Table(source, Format.CSV).also { it.scan() }
        source.bytes = 0
        assertNull(Finder(large).previous("zzqxj", false, large.rowCount))
        assertTrue("read ${source.bytes} bytes of a ${data.size}-byte file", source.bytes <= data.size * 6L / 5)
    }

    @Test fun previousFindsAMatchFarBackAndReadsLittleForANearOne() {
        val data = DesktopFixtures.bytes("large.log")
        val source = CountingSource(ByteArraySource(data))
        val large = Table(source, Format.LOG).also { it.scan() }
        source.bytes = 0
        assertEquals(Finder.Hit(5, wrapped = false), Finder(large).previous("row_id=5 ", false, 100_000))
        assertTrue("read ${source.bytes} bytes of a ${data.size}-byte file", source.bytes <= data.size * 6L / 5)
        source.bytes = 0
        assertEquals(Finder.Hit(99_990, wrapped = false), Finder(large).previous("row_id=99990 ", false, 99_995))
        assertTrue("read ${source.bytes} bytes for a match five rows back", source.bytes <= 2L * 1024 * 1024)
    }

    @Test fun cancellingStopsTheSearch() {
        val large = table(DesktopFixtures.bytes("large.log"), Format.LOG)
        val finder = Finder(large)
        assertNull(finder.next("row_id=99999 ", false, -1) { true })
        assertFalse(finder.lastSearchCompleted)
        assertTrue(finder.next("row_id=99999 ", false, -1) != null && finder.lastSearchCompleted)
    }
}
