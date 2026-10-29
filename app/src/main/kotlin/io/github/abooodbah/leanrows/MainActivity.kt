package io.github.abooodbah.leanrows

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.FileNotFoundException
import java.io.IOException
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val numbers: NumberFormat = NumberFormat.getIntegerInstance()

    private lateinit var root: View
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var tableView: View
    private lateinit var header: HeaderView
    private lateinit var list: RecyclerView
    private lateinit var message: View
    private lateinit var messageText: TextView
    private lateinit var findBar: View
    private lateinit var findText: EditText
    private lateinit var matchCase: CheckBox
    private lateinit var layout: LinearLayoutManager
    private lateinit var style: TableStyle
    private lateinit var pan: HorizontalPan

    /** Bumped on every open, so work for an older file never reaches the screen. */
    private val generation = AtomicInteger()
    private var document: Document? = null
    private var drawnReported = false

    /** One open file and the threads working on it. */
    private inner class Document(
        val name: String,
        val table: Table,
        val closeable: AutoCloseable?,
        val state: TableState,
        val generation: Int,
    ) {
        val scanThread: ExecutorService = Executors.newSingleThreadExecutor()
        val readThread: ExecutorService = Executors.newSingleThreadExecutor()
        val findThread: ExecutorService = Executors.newSingleThreadExecutor()
        val finder = Finder(table)
        val findGeneration = AtomicInteger()
        var shownRows = 0L
        var scanError: IOException? = null
        var lastProgress = 0L

        val windows = RowWindows(
            table,
            readThread,
            post = { main.post(it) },
            loaded = { first, rows -> rowsLoaded(this, first, rows) },
            failed = { error -> readFailed(this, error) },
        )
        val adapter = RowsAdapter(this)

        val current: Boolean get() = this@MainActivity.generation.get() == generation && !isDestroyed

        fun close() {
            windows.close()
            scanThread.shutdownNow()
            readThread.shutdownNow()
            findThread.shutdownNow()
            // Close the file after the threads are done with it.
            Thread {
                scanThread.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                readThread.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                findThread.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                try {
                    closeable?.close()
                } catch (ignored: Exception) {
                }
            }.start()
        }
    }

    private inner class Holder(val row: RowView) : RecyclerView.ViewHolder(row)

    private inner class RowsAdapter(private val document: Document) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount(): Int = minOf(document.shownRows, Int.MAX_VALUE.toLong()).toInt()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = RowView(parent.context, document.state)
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, style.rowHeight)
            row.setOnClickListener { showCell(row) }
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.row.bind(position.toLong(), document.windows.row(position.toLong()))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        title = findViewById(R.id.title)
        status = findViewById(R.id.status)
        tableView = findViewById(R.id.table)
        header = findViewById(R.id.header)
        list = findViewById(R.id.rows)
        message = findViewById(R.id.message)
        messageText = findViewById(R.id.message_text)
        findBar = findViewById(R.id.find_bar)
        findText = findViewById(R.id.find_text)
        matchCase = findViewById(R.id.match_case)
        style = TableStyle(this)
        layOutEdgeToEdge()

        layout = LinearLayoutManager(this)
        list.layoutManager = layout
        list.setHasFixedSize(true)
        list.itemAnimator = null
        pan = HorizontalPan(this, { document?.state }, ::panned)
        list.addOnItemTouchListener(pan)
        list.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            document?.state?.let { state ->
                state.viewportWidth = right - left
                state.clampOffset()
            }
        }

        findViewById<Button>(R.id.open).setOnClickListener { pickFile() }
        findViewById<Button>(R.id.message_open).setOnClickListener { pickFile() }
        findViewById<Button>(R.id.go_to).setOnClickListener { askForRow() }
        findViewById<Button>(R.id.find).setOnClickListener { showFindBar() }
        findViewById<Button>(R.id.find_close).setOnClickListener { hideFindBar() }
        findViewById<Button>(R.id.find_next).setOnClickListener { find(forward = true) }
        findViewById<Button>(R.id.find_previous).setOnClickListener { find(forward = false) }
        findText.setOnEditorActionListener { _, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (action == EditorInfo.IME_ACTION_SEARCH || enter) {
                find(forward = true)
                true
            } else {
                false
            }
        }

        Sources.clean(cacheDir)
        if (!openFrom(intent)) showMessage(getString(R.string.empty), showOpen = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        document?.close()
        document = null
        super.onDestroy()
    }

    @Deprecated("Activity result API from the platform; AndroidX is not used.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_OPEN && resultCode == RESULT_OK && uri != null) open(uri, data.type)
    }

    // Opening

    /** Returns false when the intent carries nothing to open. */
    private fun openFrom(intent: Intent?): Boolean {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let {
                open(it, intent.type)
                return true
            }
            Intent.ACTION_SEND -> {
                val stream = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (stream != null) {
                    open(stream, intent.type)
                    return true
                }
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    openText(text, intent.type)
                    return true
                }
            }
        }
        return false
    }

    private fun open(uri: Uri, type: String?) {
        // A file:// URI would be read with this app's own access, not a grant.
        if (uri.scheme != "content") {
            showMessage("LeanRows opens files that another app shares with it, or that you pick with Open.", showOpen = true)
            return
        }
        val opening = begin()
        title.text = uri.lastPathSegment ?: getString(R.string.app_name)
        status.text = "Opening…"
        val cacheDir = cacheDir
        Thread {
            val result = try {
                Sources.open(contentResolver, uri, cacheDir, { generation.get() != opening }) { copied ->
                    main.post {
                        if (generation.get() == opening) status.text = "Copying the file from the app that shared it: ${megabytes(copied)}"
                    }
                }
            } catch (error: Exception) {
                main.post { if (generation.get() == opening) openFailed(error) }
                return@Thread
            }
            val sample = ByteArray(minOf(result.source.size, 4096L).toInt())
            try {
                readFully(result.source, sample)
            } catch (error: IOException) {
                result.source.close()
                main.post { if (generation.get() == opening) openFailed(error) }
                return@Thread
            }
            val format = Format.infer(result.name, type ?: contentResolver.getType(uri), sample)
            main.post {
                if (generation.get() == opening) {
                    show(result.name, Table(result.source, format), result.source, opening)
                } else {
                    result.source.close()
                }
            }
        }.start()
    }

    private fun openText(text: String, type: String?) {
        val opening = begin()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val format = Format.infer(null, type, bytes.copyOf(minOf(bytes.size, 4096)))
        show("Shared text", Table(ByteArraySource(bytes), format), null, opening)
    }

    /** Stops work on the current file and returns the new generation. */
    private fun begin(): Int {
        val opening = generation.incrementAndGet()
        document?.close()
        document = null
        list.adapter = null
        hideFindBar()
        return opening
    }

    private fun show(name: String, table: Table, closeable: AutoCloseable?, opening: Int) {
        val state = TableState(style, style.columns(table.format.delimiter == null))
        state.viewportWidth = list.width
        val document = Document(name, table, closeable, state, opening)
        this.document = document
        title.text = name
        header.state = state
        list.adapter = document.adapter
        message.visibility = View.GONE
        tableView.visibility = View.VISIBLE
        updateStatus(document)
        document.scanThread.execute {
            try {
                val started = SystemClock.uptimeMillis()
                val finished = table.scan(cancelled = { !document.current }) { rows ->
                    scanProgress(document, rows, done = false)
                }
                // scripts/measure.sh reads this line.
                if (finished) Log.i(TAG, "indexed ${table.rowCount} rows in ${SystemClock.uptimeMillis() - started} ms")
                scanProgress(document, table.rowCount, done = true)
            } catch (error: IOException) {
                main.post {
                    document.scanError = error
                    if (document.current) updateStatus(document)
                }
            }
        }
    }

    private fun scanProgress(document: Document, rows: Long, done: Boolean) {
        // About ten screen updates a second while indexing.
        val now = SystemClock.uptimeMillis()
        if (!done && now - document.lastProgress < 100) return
        document.lastProgress = now
        main.post {
            if (!document.current) return@post
            val before = document.shownRows
            val after = maxOf(rows, before)
            if (after > before) {
                document.shownRows = after
                document.adapter.notifyItemRangeInserted(before.toInt(), (after - before).toInt())
            }
            if (document.state.columns.fitRowNumbers(after)) relayout(document)
            updateStatus(document)
        }
    }

    private fun rowsLoaded(document: Document, first: Long, rows: List<Row>) {
        if (!document.current) return
        val count = rows.size
        if (first + count > document.shownRows) {
            // Rows can be read before the scan has counted them.
            val before = document.shownRows
            document.shownRows = first + count
            document.adapter.notifyItemRangeInserted(before.toInt(), (document.shownRows - before).toInt())
        }
        // The first rows read decide the column widths.
        if (document.state.columns.fit(rows)) {
            relayout(document)
        } else {
            document.adapter.notifyItemRangeChanged(first.toInt(), count)
        }
        if (!drawnReported) {
            drawnReported = true
            list.post { reportFullyDrawn() }
        }
    }

    private fun relayout(document: Document) {
        document.state.clampOffset()
        header.requestLayout()
        header.invalidate()
        document.adapter.notifyItemRangeChanged(0, document.adapter.itemCount)
    }

    private fun readFailed(document: Document, error: IOException) {
        if (!document.current) return
        Log.w(TAG, "read failed", error)
        status.text = "LeanRows could not read these rows: ${error.message}. The file may have changed. Open it again."
    }

    private fun openFailed(error: Exception) {
        Log.w(TAG, "open failed", error)
        val text = when (error) {
            is NotEnoughSpace ->
                "There is not enough free space to open this file. The app that shared it sends it as a " +
                    "stream, which LeanRows has to copy before it can show it."
            is FileNotFoundException, is SecurityException ->
                "LeanRows could not open this file. The app that shared it may no longer allow reading it."
            else -> "LeanRows could not read this file: ${error.message}"
        }
        title.text = getString(R.string.app_name)
        status.text = ""
        showMessage(text, showOpen = true)
    }

    private fun showMessage(text: String, showOpen: Boolean) {
        tableView.visibility = View.GONE
        message.visibility = View.VISIBLE
        messageText.text = text
        findViewById<View>(R.id.message_open).visibility = if (showOpen) View.VISIBLE else View.GONE
        if (!drawnReported) {
            drawnReported = true
            message.post { reportFullyDrawn() }
        }
    }

    private fun updateStatus(document: Document) {
        val table = document.table
        val parts = mutableListOf<String>()
        val rows = maxOf(table.rowCount, document.shownRows)
        parts += when {
            table.scanned -> if (rows == 1L) "1 row" else "${numbers.format(rows)} rows"
            else -> "Indexing: ${numbers.format(rows)} rows so far"
        }
        parts += when (table.format) {
            Format.CSV -> "CSV"
            Format.TSV -> "TSV"
            Format.JSONL -> "JSON Lines"
            Format.LOG -> "text lines"
        }
        var text = parts.joinToString(" · ")
        if (table.scanned && rows == 0L) text = "The file is empty · " + parts.last()
        if (table.unterminatedQuote) text += "\nThe file ends inside a quoted field, so the last row runs to the end of the file."
        document.scanError?.let { text += "\nReading stopped early: ${it.message}" }
        status.text = text
    }

    private fun pickFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, PICKER_TYPES)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_OPEN)
        } catch (error: ActivityNotFoundException) {
            status.text = "This device has no file picker."
        }
    }

    // Moving around

    private fun panned() {
        header.invalidate()
        for (index in 0 until list.childCount) list.getChildAt(index).invalidate()
    }

    private fun scrollTo(row: Long) {
        val document = document ?: return
        document.state.highlightedRow = row
        layout.scrollToPositionWithOffset(row.toInt(), style.rowHeight * 2)
        document.adapter.notifyItemRangeChanged(0, document.adapter.itemCount)
    }

    private fun askForRow() {
        val document = document ?: return
        val rows = maxOf(document.table.rowCount, document.shownRows)
        if (rows == 0L) return
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 to ${numbers.format(rows)}"
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.go_to_row)
            .setView(field)
            .setPositiveButton("Go", null)
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = field.text.toString().toLongOrNull()
                val available = maxOf(document.table.rowCount, document.shownRows)
                if (number == null || number < 1 || number > available) {
                    field.error = if (document.table.scanned) {
                        "Enter a row from 1 to ${numbers.format(available)}."
                    } else {
                        "Rows 1 to ${numbers.format(available)} are indexed so far."
                    }
                } else {
                    dialog.dismiss()
                    scrollTo(number - 1)
                }
            }
        }
        dialog.show()
        dialogForTest = dialog
        field.requestFocus()
    }

    private fun showCell(row: RowView) {
        val (column, text) = row.cellAtLastTouch() ?: return
        val document = document ?: return
        val name = document.state.columns.titles.getOrElse(column) { "Column ${column + 1}" }
        val body = TextView(this).apply {
            this.text = text.ifEmpty { "(empty)" }
            setTextIsSelectable(true)
            setTextColor(getColor(R.color.ink))
            textSize = 15f
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }
        dialogForTest = AlertDialog.Builder(this)
            .setTitle("Row ${numbers.format(row.index + 1)}, $name")
            .setView(body)
            .setPositiveButton(R.string.copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("$name, row ${row.index + 1}", text))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // Find

    private fun showFindBar() {
        if (document == null) return
        findBar.visibility = View.VISIBLE
        findText.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(findText, 0)
    }

    private fun hideFindBar() {
        if (findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(findText.windowToken, 0)
        document?.let {
            it.findGeneration.incrementAndGet()
            if (it.state.highlightedRow >= 0) {
                it.state.highlightedRow = -1
                it.adapter.notifyItemRangeChanged(0, it.adapter.itemCount)
            }
        }
    }

    private fun find(forward: Boolean) {
        val document = document ?: return
        val text = findText.text.toString()
        if (text.isEmpty()) {
            status.text = "Enter text to find."
            return
        }
        val exact = matchCase.isChecked
        val from = if (document.state.highlightedRow >= 0) {
            document.state.highlightedRow
        } else if (forward) {
            layout.findFirstVisibleItemPosition().toLong() - 1
        } else {
            layout.findFirstVisibleItemPosition().toLong().coerceAtLeast(0)
        }
        val search = document.findGeneration.incrementAndGet()
        status.text = "Finding “$text”…"
        document.findThread.execute {
            val stale = { !document.current || document.findGeneration.get() != search }
            val hit = try {
                if (forward) {
                    document.finder.next(text, exact, from, stale)
                } else {
                    document.finder.previous(text, exact, from, stale)
                }
            } catch (error: IOException) {
                main.post { if (!stale()) status.text = "Find stopped: ${error.message}" }
                return@execute
            }
            main.post {
                if (stale()) return@post
                if (hit == null) {
                    updateStatus(document)
                    status.text = "No row contains “$text”." + if (exact) " Case must match." else ""
                } else {
                    scrollTo(hit.row)
                    val wrapped = when {
                        !hit.wrapped -> ""
                        forward -> ", from the top"
                        else -> ", from the bottom"
                    }
                    status.text = "“$text” in row ${numbers.format(hit.row + 1)}$wrapped"
                }
            }
        }
    }

    // Window

    @Suppress("DEPRECATION")
    private fun layOutEdgeToEdge() {
        if (Build.VERSION.SDK_INT < 30) return
        // Android 15 and later always draw behind the bars; doing the same from
        // Android 11 keeps one code path for insets and the keyboard.
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    private fun megabytes(bytes: Long) = "${numbers.format(bytes / (1024 * 1024))} MB"

    private fun readFully(source: ByteSource, into: ByteArray) {
        var filled = 0
        while (filled < into.size) {
            val read = source.read(filled.toLong(), into, filled, into.size - filled)
            if (read <= 0) throw IOException("the file ended early")
            filled += read
        }
    }

    // For instrumented tests.
    internal val tableForTest: Table? get() = document?.table
    internal val listForTest: RecyclerView get() = list
    internal val statusForTest: String get() = status.text.toString()
    internal val messageForTest: String? get() = if (message.visibility == View.VISIBLE) messageText.text.toString() else null
    internal val stateForTest: TableState? get() = document?.state
    internal var dialogForTest: AlertDialog? = null
        private set

    companion object {
        private const val TAG = "LeanRows"
        private const val REQUEST_OPEN = 1
        private val PICKER_TYPES = arrayOf(
            "text/csv",
            "text/comma-separated-values",
            "text/tab-separated-values",
            "application/x-ndjson",
            "application/jsonl",
            "text/plain",
            "application/octet-stream",
        )
    }
}
