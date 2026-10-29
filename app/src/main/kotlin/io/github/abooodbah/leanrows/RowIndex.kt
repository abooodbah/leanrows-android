package io.github.abooodbah.leanrows

/**
 * Where rows start in the file, kept for every [stride]-th row only. The
 * number of checkpoints never passes [capacity]: when it fills up, every other
 * checkpoint is dropped and the stride doubles, as the desktop's
 * AdaptiveRowIndex does. Memory stays the same for a 1,000-row file and a
 * 20-million-row file; only the distance to scan from a checkpoint grows.
 */
class RowIndex(private val capacity: Int = 4096, initialStride: Long = 16) {
    private val rows = LongArray(capacity)
    private val offsets = LongArray(capacity)
    private var count = 0

    var stride = initialStride
        private set

    /** Rows seen so far by [observe]. */
    var rowCount = 0L
        private set

    val checkpoints: Int get() = count

    init {
        require(capacity >= 2 && initialStride >= 1)
    }

    // The scan thread writes while the screen reads, so both sides lock.

    /** Called once per record, in order, with the byte offset where it starts. */
    @Synchronized
    fun observe(row: Long, offset: Long) {
        rowCount = row + 1
        if (row % stride != 0L) return
        if (count == capacity) {
            // Keep the checkpoints on the doubled stride.
            stride *= 2
            var kept = 0
            for (index in 0 until count) {
                if (rows[index] % stride == 0L) {
                    rows[kept] = rows[index]
                    offsets[kept] = offsets[index]
                    kept++
                }
            }
            count = kept
            if (row % stride != 0L) return
        }
        rows[count] = row
        offsets[count] = offset
        count++
    }

    /** The last checkpoint at or before [row]: its row and byte offset. */
    @Synchronized
    fun nearest(row: Long): Pair<Long, Long> {
        if (count == 0) return 0L to 0L
        var low = 0
        var high = count - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (rows[middle] <= row) low = middle else high = middle - 1
        }
        return rows[low] to offsets[low]
    }
}
