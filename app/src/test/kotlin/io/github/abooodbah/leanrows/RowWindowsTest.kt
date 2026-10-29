package io.github.abooodbah.leanrows

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowWindowsTest {
    private val weather = Table(
        ByteArraySource(File("src/sharedTest/fixtures/desktop/weather-stations.csv").readBytes()),
        Format.CSV,
    )

    private val loads = mutableListOf<Pair<Long, Int>>()
    private val queue = ArrayDeque<Runnable>()

    /** Runs background work only when [drain] is called, like a busy worker thread. */
    private fun windows(table: Table = weather, failed: (IOException) -> Unit = { throw it }) =
        RowWindows(table, { queue.addLast(it) }, { it.run() }, { first, rows -> loads += first to rows.size }, failed)

    private fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    @Test fun aRowIsReadInTheBackgroundAndThenServedFromMemory() {
        val windows = windows()
        assertNull(windows.row(5))
        drain()
        assertEquals(listOf(0L to Table.WINDOW), loads)
        val row = windows.row(5)
        assertNotNull(row)
        assertEquals(6L, row!!.number)
        assertEquals("ST005", row.cells[1]) // line 6 of the file is 2026-01-01T00:00:00Z,ST005,...
    }

    @Test fun theNextWindowIsReadBeforeTheScreenReachesIt() {
        val windows = windows()
        windows.row(0)
        drain()
        windows.row(Table.WINDOW - 10L)
        drain()
        assertEquals(listOf(0L to Table.WINDOW, Table.WINDOW.toLong() to Table.WINDOW), loads)
    }

    @Test fun windowsTheScreenHasLeftAreSkipped() {
        val windows = windows()
        windows.row(0)
        windows.row(5L * Table.WINDOW)
        windows.row(10L * Table.WINDOW)
        drain()
        // Only window 10 and the one before it (read ahead, because row 0 of
        // a window is near its top) are read; windows 0 to 6 are skipped.
        assertEquals(listOf(9L * Table.WINDOW, 10L * Table.WINDOW), loads.map { it.first })
        // A skipped window is read again when the screen comes back to it.
        windows.row(0)
        drain()
        assertEquals(0L, loads.last().first)
    }

    @Test fun memoryStaysBoundedWhileScrollingThroughTheWholeFile() {
        val windows = windows()
        for (row in 0L until 1441L) {
            windows.row(row)
            drain()
            assertTrue("windows kept: ${windows.cachedWindows}", windows.cachedWindows <= 3)
        }
        assertEquals(1441L, windows.row(1440)?.number)
        assertNull(windows.row(1441))
    }

    @Test fun readErrorsAreReported() {
        val broken = Table(object : ByteSource {
            override val size = 100L
            override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int =
                throw IOException("gone")
        }, Format.CSV)
        val errors = mutableListOf<IOException>()
        val windows = windows(broken) { errors += it }
        windows.row(0)
        drain()
        assertEquals("gone", errors.single().message)
    }

    @Test fun nothingIsDeliveredAfterClose() {
        val windows = windows()
        windows.row(0)
        windows.close()
        drain()
        assertEquals(emptyList<Pair<Long, Int>>(), loads)
    }
}
