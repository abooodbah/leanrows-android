package io.github.abooodbah.leanrows

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail

/** Opens files in LeanRows the way other apps do and reads what it shows. */
class Viewer(val scenario: ActivityScenario<MainActivity>) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: T? = null
        scenario.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun waitFor(what: String, timeoutMs: Long = 20_000, condition: (MainActivity) -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (onActivity(condition)) return
            SystemClock.sleep(50)
        }
        fail("timed out waiting for $what; status: ${onActivity { it.statusForTest }}; message: ${onActivity { it.messageForTest }}")
    }

    fun waitForScan(timeoutMs: Long = 60_000) =
        waitFor("the scan to finish", timeoutMs) { it.tableForTest?.scanned == true }

    /** The row view bound to [position] once its cells have been read, as "Row N: a, b, c". */
    fun rowText(position: Int, timeoutMs: Long = 20_000): String {
        var text = ""
        waitFor("row ${position + 1} on screen", timeoutMs) { activity ->
            val view = activity.listForTest.findViewHolderForAdapterPosition(position)?.itemView as? RowView
            text = view?.contentDescription?.toString().orEmpty()
            text.contains(':')
        }
        return text
    }

    fun firstVisible(): Int = onActivity {
        (it.listForTest.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()
    }

    fun lastVisible(): Int = onActivity {
        (it.listForTest.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findLastVisibleItemPosition()
    }

    /** A finger drag across the screen, in pixels. */
    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int = 12) {
        val start = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, fromX, fromY, 0))
        for (step in 1..steps) {
            val x = fromX + (toX - fromX) * step / steps
            val y = fromY + (toY - fromY) * step / steps
            instrumentation.sendPointerSync(MotionEvent.obtain(start, start + step * 16L, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        instrumentation.sendPointerSync(MotionEvent.obtain(start, start + (steps + 1) * 16L, MotionEvent.ACTION_UP, toX, toY, 0))
        instrumentation.waitForIdleSync()
    }

    fun tap(x: Float, y: Float) {
        val start = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(start, start + 50, MotionEvent.ACTION_UP, x, y, 0))
        instrumentation.waitForIdleSync()
    }

    override fun close() = scenario.close()

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        fun view(path: String, type: String? = null): Viewer {
            val uri = FixtureProvider.uri(path)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, type ?: context.contentResolver.getType(uri))
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return Viewer(ActivityScenario.launch(intent))
        }

        fun launch(intent: Intent): Viewer =
            Viewer(ActivityScenario.launch(intent.setClass(context, MainActivity::class.java)))

        fun uri(path: String): Uri = FixtureProvider.uri(path)
    }
}
