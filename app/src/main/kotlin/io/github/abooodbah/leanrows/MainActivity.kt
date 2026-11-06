package io.github.abooodbah.leanrows

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
    private lateinit var sortCancel: Button
    private lateinit var themeButton: ImageButton
    private lateinit var saved: SavedSettings
    private var chosenTheme = Theme.SYSTEM
    private var zoom = Zoom.DEFAULT
    private var palette = Palette.LIGHT
    private var settingsSheet: Dialog? = null

    /**
     * The colours the window and the system bars have now. At start they are
     * the XML theme's, which match System in the current light or dark mode,
     * so a start in those colours does not touch the window: changing the
     * bars before the first frame cost about 150 ms on the emulator.
     */
    private var windowPalette: Palette? = null
    private lateinit var layout: LinearLayoutManager
    private lateinit var style: TableStyle
    private lateinit var pan: HorizontalPan

    /** Bumped on every open, so work for an older file never reaches the screen. */
    private val generation = AtomicInteger()
    private var document: Document? = null
    private var drawnReported = false
    private var created = 0L

    /** An open .xlsx file. Its sheets are shown one at a time, each as CSV. */
    private class Workbook(val book: XlsxBook, val source: DescriptorSource) : AutoCloseable {
        override fun close() {
            book.close()
            source.close()
        }
    }

    /** One open file and the threads working on it. */
    private inner class Document(
        val name: String,
        val table: Table,
        val closeable: AutoCloseable?,
        val state: TableState,
        val generation: Int,
        val workbook: Workbook? = null,
        val sheet: Int = 0,
    ) {
        /** Set when another sheet of the same workbook replaces this one. */
        var keepWorkbook = false
        val scanThread: ExecutorService = Executors.newSingleThreadExecutor()
        val readThread: ExecutorService = Executors.newSingleThreadExecutor()
        val findThread: ExecutorService = Executors.newSingleThreadExecutor()
        val sortThread: ExecutorService = Executors.newSingleThreadExecutor()
        val finder = Finder(table)
        val findGeneration = AtomicInteger()
        var shownRows = 0L
        var scanError: IOException? = null
        var lastProgress = 0L

        /** The finished sort the rows are shown in, or null for the file's order. */
        var order: SortedOrder? = null

        /** The sort that is running, if any; [sortGeneration] cancels it. */
        var sorting: SortRequest? = null
        val sortGeneration = AtomicInteger()
        var sortProgress = ""
        var sortNote: String? = null

        /** The row (in the file, from 0) of the last Find match. */
        var lastHitRow = -1L

        var windows = rowWindows { first, count -> table.rows(first, count) }
        val adapter = RowsAdapter(this)

        val current: Boolean get() = this@MainActivity.generation.get() == generation && !isDestroyed

        fun rowWindows(read: (Long, Int) -> List<Row>) = RowWindows(
            read,
            readThread,
            post = { main.post(it) },
            loaded = { first, rows -> rowsLoaded(this, first, rows) },
            failed = { error -> readFailed(this, error) },
        )

        fun close() {
            windows.close()
            sortGeneration.incrementAndGet()
            scanThread.shutdownNow()
            readThread.shutdownNow()
            findThread.shutdownNow()
            sortThread.shutdownNow()
            // Close the file after the threads are done with it.
            val workbook = if (keepWorkbook) null else workbook
            val order = order
            Thread {
                scanThread.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                readThread.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                findThread.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                sortThread.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                try {
                    order?.close()
                    closeable?.close()
                } catch (ignored: Exception) {
                }
                try {
                    workbook?.close()
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
            // The height follows the zoom: RowView measures itself.
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            row.setOnClickListener { showCell(row) }
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.row.bind(position.toLong(), document.windows.row(position.toLong()))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        created = SystemClock.uptimeMillis()
        setContentView(R.layout.activity_main)
        val inflated = SystemClock.uptimeMillis()
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
        sortCancel = findViewById(R.id.sort_cancel)
        themeButton = findViewById(R.id.theme)
        saved = SavedSettings(this)
        chosenTheme = saved.theme
        zoom = saved.zoom
        palette = Palette.of(chosenTheme, systemDark())
        style = TableStyle(this, palette, zoom)
        val settingsRead = SystemClock.uptimeMillis()
        applyTheme()
        val themed = SystemClock.uptimeMillis()
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
        themeButton.setOnClickListener { chooseTheme(chosenTheme.next) }
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.sheet).setOnClickListener { askForSheet() }
        findViewById<Button>(R.id.find).setOnClickListener { showFindBar() }
        findViewById<Button>(R.id.more).setOnClickListener { showMore(it) }
        sortCancel.setOnClickListener { document?.let { cancelSort(it) } }
        header.listener = object : HeaderView.Listener {
            override fun tapped(column: Int) = headerTapped(column)
            override fun longPressed(column: Int) = showColumnMenu(column)
        }
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
        // scripts/measure.sh prints this line: where the start spends its time.
        val steps = "process start to create ${created - android.os.Process.getStartUptimeMillis()} ms, layout ${inflated - created} ms, " +
            "settings ${settingsRead - inflated} ms, theme ${themed - settingsRead} ms, rest ${SystemClock.uptimeMillis() - themed} ms"
        var firstDraw = true
        window.decorView.viewTreeObserver.addOnDrawListener {
            if (firstDraw) {
                firstDraw = false
                Log.i(TAG, "create steps: $steps, first draw ${SystemClock.uptimeMillis() - created} ms after create")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The system switched light and dark: recolour in place, keeping the
        // open file and the place in it.
        if (chosenTheme == Theme.SYSTEM && Palette.of(chosenTheme, systemDark()) != palette) applyTheme()
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
            val mimeType = type ?: contentResolver.getType(uri)
            if (XlsxBook.recognises(result.name, mimeType, sample)) {
                val workbook = try {
                    Workbook(XlsxBook.open(result.source), result.source)
                } catch (error: Exception) {
                    result.source.close()
                    main.post { if (generation.get() == opening) openFailed(error) }
                    return@Thread
                }
                showSheet(result.name, workbook, 0, opening)
                return@Thread
            }
            val format = Format.infer(result.name, mimeType, sample)
            main.post {
                if (generation.get() == opening) {
                    show(result.name, Table(result.source, format), result.source, opening)
                } else {
                    result.source.close()
                }
            }
        }.start()
    }

    /**
     * Writes sheet [index] of [workbook] as CSV into the cache and shows it.
     * Runs on a worker thread. The CSV is unlinked once it is open.
     */
    private fun showSheet(name: String, workbook: Workbook, index: Int, opening: Int) {
        val sheet = workbook.book.sheets[index]
        main.post { if (generation.get() == opening) status.text = "Reading sheet ${sheet.name}…" }
        val folder = File(cacheDir, "sheets").apply { mkdirs() }
        val cancelled = { generation.get() != opening }
        val source = try {
            val file = File.createTempFile("sheet", ".csv", folder)
            try {
                val started = SystemClock.uptimeMillis()
                val written = FileOutputStream(file).use { output ->
                    workbook.book.writeCsv(index, SpaceCheck(output, folder), cancelled) { rows ->
                        main.post {
                            if (generation.get() == opening) status.text = "Reading sheet ${sheet.name}: ${numbers.format(rows)} rows so far"
                        }
                    }
                }
                // scripts/measure.sh reads this line.
                Log.i(TAG, "wrote sheet ${index + 1} as $written CSV rows in ${SystemClock.uptimeMillis() - started} ms")
                val copy = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                DescriptorSource(copy, copy.statSize)
            } finally {
                file.delete()
            }
        } catch (error: Exception) {
            main.post {
                if (generation.get() == opening) {
                    openFailed(error)
                    workbook.close()
                } else if (document?.workbook !== workbook) {
                    workbook.close()
                }
            }
            return
        }
        main.post {
            if (generation.get() == opening) {
                show(name, Table(source, Format.CSV), source, opening, workbook, index)
            } else {
                source.close()
                if (document?.workbook !== workbook) workbook.close()
            }
        }
    }

    /** Stops writing a sheet before the device runs out of space. */
    private class SpaceCheck(output: FileOutputStream, private val folder: File) : FilterOutputStream(output) {
        private var written = 0L

        override fun write(b: Int) {
            out.write(b)
            counted(1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            counted(len)
        }

        private fun counted(bytes: Int) {
            written += bytes
            if (written % (8L * 1024 * 1024) < bytes && folder.usableSpace < Sources.RESERVE_BYTES) throw NotEnoughSpace()
        }
    }

    private fun askForSheet() {
        val document = document ?: return
        val workbook = document.workbook ?: return
        val names = workbook.book.sheets.map { it.name }.toTypedArray()
        dialogForTest = AlertDialog.Builder(dialogContext())
            .setTitle("Sheet")
            .setItems(names) { _, which -> if (which != document.sheet) switchSheet(document, which) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun switchSheet(current: Document, index: Int) {
        val workbook = current.workbook ?: return
        if (this.document !== current) return
        current.keepWorkbook = true
        val opening = begin()
        Thread { showSheet(current.name, workbook, index, opening) }.start()
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
        // The start screen's text is not the answer for this file.
        message.visibility = View.GONE
        findViewById<View>(R.id.sheet).visibility = View.GONE
        sortCancel.visibility = View.GONE
        hideFindBar()
        return opening
    }

    private fun show(
        name: String,
        table: Table,
        closeable: AutoCloseable?,
        opening: Int,
        workbook: Workbook? = null,
        sheet: Int = 0,
    ) {
        val state = TableState(style, style.columns(table.format.delimiter == null, lettered = workbook != null))
        state.viewportWidth = list.width
        val document = Document(name, table, closeable, state, opening, workbook, sheet)
        findViewById<View>(R.id.sheet).visibility =
            if (workbook != null && workbook.book.sheets.size > 1) View.VISIBLE else View.GONE
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
            if (after > before && document.order == null) {
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
            list.post {
                reportFullyDrawn()
                // scripts/measure.sh reads this line, also after the activity is recreated.
                Log.i(TAG, "rows drawn ${SystemClock.uptimeMillis() - created} ms after create")
            }
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
            is XlsxException -> error.message.orEmpty()
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
        val rows = if (document.order != null) table.rowCount else maxOf(table.rowCount, document.shownRows)
        parts += when {
            table.scanned -> if (rows == 1L) "1 row" else "${numbers.format(rows)} rows"
            else -> "Indexing: ${numbers.format(rows)} rows so far"
        }
        val workbook = document.workbook
        parts += if (workbook != null) "Excel, sheet ${workbook.book.sheets[document.sheet].name}" else when (table.format) {
            Format.CSV -> "CSV"
            Format.TSV -> "TSV"
            Format.JSONL -> "JSON Lines"
            Format.LOG -> "text lines"
        }
        document.order?.let { parts += "sorted by " + describe(document, it.request) }
        var text = parts.joinToString(" · ")
        if (table.scanned && rows == 0L) text = "The file is empty · " + parts.last()
        if (document.sorting != null) text = document.sortProgress
        document.sortNote?.let { text += "\n" + it }
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
        val field = EditText(dialogContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 to ${numbers.format(rows)}"
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(dialogContext())
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
                    goTo(number - 1)
                }
            }
        }
        dialog.show()
        dialogForTest = dialog
        field.requestFocus()
    }

    /** Shows row [row] (from 0) of the file, wherever the sort has put it. */
    private fun goTo(row: Long) {
        val document = document ?: return
        val position = try {
            document.order?.rankOf(row) ?: row
        } catch (error: IOException) {
            status.text = "LeanRows could not read the sorted order: ${error.message}"
            return
        }
        scrollTo(position)
    }

    private fun showCell(row: RowView) {
        val (column, text) = row.cellAtLastTouch() ?: return
        val document = document ?: return
        val name = document.state.columns.titles.getOrElse(column) { "Column ${column + 1}" }
        val body = TextView(dialogContext()).apply {
            this.text = text.ifEmpty { "(empty)" }
            setTextIsSelectable(true)
            setTextColor(palette.textPrimary)
            textSize = 15f
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }
        dialogForTest = AlertDialog.Builder(dialogContext())
            .setTitle("Row ${row.number.toLongOrNull()?.let { numbers.format(it) } ?: row.number}, $name")
            .setView(body)
            .setPositiveButton(R.string.copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("$name, row ${row.number}", text))
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
            it.lastHitRow = -1
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
        val order = document.order
        // In a sorted view Find still moves through the file's own order.
        val from = if (order != null) {
            if (document.lastHitRow >= 0) document.lastHitRow else if (forward) -1 else Long.MAX_VALUE
        } else if (document.state.highlightedRow >= 0) {
            document.state.highlightedRow
        } else if (forward) {
            layout.findFirstVisibleItemPosition().toLong() - 1
        } else {
            layout.findFirstVisibleItemPosition().toLong().coerceAtLeast(0)
        }
        val search = document.findGeneration.incrementAndGet()
        status.text = "Finding “$text”…"
        val started = SystemClock.uptimeMillis()
        document.findThread.execute {
            val stale = { !document.current || document.findGeneration.get() != search || document.order !== order }
            var position = -1L
            val hit = try {
                if (forward) {
                    document.finder.next(text, exact, from, stale)
                } else {
                    document.finder.previous(text, exact, from, stale)
                }.also { hit -> if (hit != null) position = order?.rankOf(hit.row) ?: hit.row }
            } catch (error: IOException) {
                main.post { if (!stale()) status.text = "Find stopped: ${error.message}" }
                return@execute
            }
            // scripts/measure.sh reads this line.
            Log.i(TAG, "find ${if (forward) "next" else "previous"} ${hit?.row ?: "none"} in ${SystemClock.uptimeMillis() - started} ms")
            main.post {
                if (stale()) return@post
                if (hit == null) {
                    updateStatus(document)
                    status.text = "No row contains “$text”." + if (exact) " Case must match." else ""
                } else {
                    document.lastHitRow = hit.row
                    scrollTo(position)
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

    // Sorting

    private fun headerTapped(column: Int) {
        val document = document ?: return
        if (column == HeaderView.ROW) {
            restoreOrder(document)
            return
        }
        val current = document.sorting ?: document.order?.request
        val next = if (current != null && current.column == column) {
            current.copy(descending = !current.descending)
        } else {
            SortRequest(column, SortMode.TEXT, descending = false)
        }
        sort(document, next)
    }

    private fun showColumnMenu(column: Int) {
        val document = document ?: return
        val popup = PopupMenu(menuContext(), header)
        if (column >= 0) addSortItems(popup)
        popup.menu.add(0, R.id.restore_order, 0, R.string.restore_order).isEnabled = document.order != null
        showPopup(popup, document, column)
    }

    private fun showMore(anchor: View) {
        val document = document
        val popup = PopupMenu(menuContext(), anchor)
        if (document != null) {
            popup.menu.add(0, R.id.go_to, 0, R.string.go_to_row)
            popup.menu.add(0, R.id.sort_by, 0, R.string.sort_by)
            val column = (document.sorting ?: document.order?.request)?.column
            if (column != null) addSortItems(popup)
            popup.menu.add(0, R.id.restore_order, 0, R.string.restore_order).isEnabled = document.order != null
            popup.menu.add(0, R.id.cancel_sort, 0, R.string.cancel_sort).isEnabled = document.sorting != null
        }
        showPopup(popup, document, column = -1)
    }

    private fun addSortItems(popup: PopupMenu) {
        for (id in listOf(R.id.sort_text_ascending, R.id.sort_text_descending, R.id.sort_numbers_ascending, R.id.sort_numbers_descending)) {
            popup.menu.add(0, id, 0, sortItemTitle(id))
        }
    }

    private fun sortItemTitle(id: Int) = getString(
        when (id) {
            R.id.sort_text_ascending -> R.string.sort_text_ascending
            R.id.sort_text_descending -> R.string.sort_text_descending
            R.id.sort_numbers_ascending -> R.string.sort_numbers_ascending
            else -> R.string.sort_numbers_descending
        },
    )

    private fun showPopup(popup: PopupMenu, document: Document?, column: Int) {
        popup.setOnMenuItemClickListener { item ->
            val target = if (column >= 0) column else (document?.sorting ?: document?.order?.request)?.column ?: 0
            if (document != null) {
                when (item.itemId) {
                    R.id.go_to -> askForRow()
                    R.id.sort_by -> askForSortColumn(document)
                    R.id.sort_text_ascending -> sort(document, SortRequest(target, SortMode.TEXT, false))
                    R.id.sort_text_descending -> sort(document, SortRequest(target, SortMode.TEXT, true))
                    R.id.sort_numbers_ascending -> sort(document, SortRequest(target, SortMode.NUMERIC, false))
                    R.id.sort_numbers_descending -> sort(document, SortRequest(target, SortMode.NUMERIC, true))
                    R.id.restore_order -> restoreOrder(document)
                    R.id.cancel_sort -> cancelSort(document)
                }
            }
            true
        }
        popup.setOnDismissListener { if (popupForTest === popup) popupForTest = null }
        popup.show()
        popupForTest = popup
    }

    private fun askForSortColumn(document: Document) {
        val titles = document.state.columns.titles.toTypedArray()
        dialogForTest = AlertDialog.Builder(dialogContext())
            .setTitle(R.string.sort_by)
            .setItems(titles) { _, which -> headerTapped(which) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** "Column 3, text, ascending". */
    private fun describe(document: Document, request: SortRequest): String {
        val title = document.state.columns.titles.getOrElse(request.column) { "Column ${request.column + 1}" }
        val mode = if (request.mode == SortMode.TEXT) "text" else "numbers"
        return "$title, $mode, " + if (request.descending) "descending" else "ascending"
    }

    private fun sort(document: Document, request: SortRequest) {
        if (this.document !== document) return
        if (document.workbook != null) {
            status.text = getString(R.string.no_sort_xlsx)
            return
        }
        if (request.column >= document.state.columns.titles.size) return
        val table = document.table
        val folder = File(cacheDir, "sort").apply { mkdirs() }
        val rows = Sorter.expectedRows(maxOf(table.rowCount, document.shownRows), table.scanned, table.scannedBytes, table.source.size)
        Sorter.checkSpace(rows, table.source.size, freeSpaceForTest ?: folder.usableSpace)?.let {
            document.sortNote = it
            updateStatus(document)
            return
        }
        val mine = document.sortGeneration.incrementAndGet()
        document.sorting = request
        document.sortNote = null
        document.sortProgress = "Sorting by ${describe(document, request)}…"
        sortCancel.visibility = View.VISIBLE
        updateStatus(document)
        val started = SystemClock.uptimeMillis()
        document.sortThread.execute {
            val cancelled = { !document.current || document.sortGeneration.get() != mine }
            // scripts/measure.sh reads this line.
            Log.i(TAG, "sort started")
            var shown = 0L
            val sorter = Sorter(table.source, table.format, request, folder)
            val order = try {
                sorter.run(cancelled) { phase, done, total ->
                    val now = SystemClock.uptimeMillis()
                    if (now - shown >= 100) {
                        shown = now
                        val percent = if (total > 0) done * 100 / total else 0
                        val step = when (phase) {
                            SortPhase.READING -> "$percent% read"
                            SortPhase.MERGING -> "merging, $percent%"
                            SortPhase.FINISHING -> "finishing, $percent%"
                        }
                        main.post {
                            if (!cancelled()) {
                                document.sortProgress = "Sorting by ${describe(document, request)}: $step"
                                updateStatus(document)
                            }
                        }
                    }
                }
            } catch (stopped: SortCancelled) {
                return@execute
            } catch (error: Exception) {
                Log.w(TAG, "sort failed", error)
                main.post { if (!cancelled()) sortEnded(document, null, "Sort failed: ${error.message}") }
                return@execute
            }
            // scripts/measure.sh reads this line.
            Log.i(TAG, "sorted ${order.count} rows by column ${request.column + 1} in ${SystemClock.uptimeMillis() - started} ms")
            main.post {
                if (cancelled()) order.close() else sortEnded(document, order, null)
            }
        }
    }

    /** A sort finished: show [order], or keep the view and say [note]. */
    private fun sortEnded(document: Document, order: SortedOrder?, note: String?) {
        document.sorting = null
        document.sortNote = note
        sortCancel.visibility = View.GONE
        if (order != null) showOrder(document, order)
        updateStatus(document)
    }

    private fun cancelSort(document: Document) {
        if (document.sorting == null) return
        document.sortGeneration.incrementAndGet()
        sortEnded(document, null, "Sort cancelled. The order you had is kept.")
    }

    private fun restoreOrder(document: Document) {
        if (document.sorting != null) {
            document.sortGeneration.incrementAndGet()
            document.sorting = null
            sortCancel.visibility = View.GONE
        }
        document.sortNote = null
        if (document.order != null) showOrder(document, null)
        updateStatus(document)
    }

    /** Shows the rows in [order], or in the file's order when it is null, from the top. */
    private fun showOrder(document: Document, order: SortedOrder?) {
        val old = document.order
        document.order = order
        document.windows.close()
        document.windows = if (order == null) {
            document.rowWindows { first, count -> document.table.rows(first, count) }
        } else {
            document.rowWindows { first, count -> order.rows(first, count) }
        }
        document.shownRows = order?.count ?: document.table.rowCount
        document.state.sortColumn = order?.request?.column ?: -1
        document.state.sortDescending = order?.request?.descending ?: false
        document.state.highlightedRow = -1
        document.lastHitRow = -1
        document.findGeneration.incrementAndGet()
        @Suppress("NotifyDataSetChanged") // Every row changes place.
        document.adapter.notifyDataSetChanged()
        layout.scrollToPositionWithOffset(0, 0)
        header.invalidate()
        // Closed after the reads and searches already queued for it.
        if (old != null) {
            try {
                document.readThread.execute {
                    try {
                        document.findThread.execute { old.close() }
                    } catch (stopped: RejectedExecutionException) {
                        old.close()
                    }
                }
            } catch (stopped: RejectedExecutionException) {
                old.close()
            }
        }
    }

    // Themes and zoom

    private fun systemDark() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun chooseTheme(chosen: Theme) {
        chosenTheme = chosen
        saved.theme = chosen
        applyTheme()
    }

    /** Colours every view, the table and the system bars in the current theme. */
    private fun applyTheme() {
        val started = SystemClock.uptimeMillis()
        palette = Palette.of(chosenTheme, systemDark())
        style.apply(palette, zoom)
        for (id in listOf(R.id.root, R.id.toolbar, R.id.find_bar, R.id.status_bar)) findViewById<View>(id).setBackgroundColor(palette.surface)
        findViewById<View>(R.id.table_frame).setBackgroundColor(palette.canvas)
        title.setTextColor(palette.textPrimary)
        status.setTextColor(palette.textSecondary)
        messageText.setTextColor(palette.textPrimary)
        for (id in listOf(R.id.sheet, R.id.find, R.id.open, R.id.more, R.id.find_previous, R.id.find_next, R.id.find_close, R.id.message_open, R.id.sort_cancel)) {
            findViewById<Button>(id).setTextColor(palette.link)
        }
        findText.setTextColor(palette.textPrimary)
        findText.setHintTextColor(palette.textDisabled)
        findText.backgroundTintList = ColorStateList.valueOf(palette.link)
        matchCase.setTextColor(palette.textPrimary)
        matchCase.buttonTintList = ColorStateList.valueOf(palette.link)
        findViewById<ImageButton>(R.id.settings).imageTintList = ColorStateList.valueOf(palette.link)
        val (left, right) = Palette.dot(chosenTheme)
        themeButton.setImageDrawable(DotDrawable(left, right, dp(14), dp(1).toFloat().coerceAtLeast(1f)))
        val name = chosenTheme.displayName(systemDark())
        themeButton.contentDescription = "Theme: $name. Activate to change theme."
        themeButton.tooltipText = "Theme: $name"
        if (windowPalette == null) windowPalette = Palette.of(Theme.SYSTEM, systemDark())
        if (palette != windowPalette) {
            windowPalette = palette
            window.setBackgroundDrawable(ColorDrawable(palette.canvas))
            @Suppress("DEPRECATION")
            window.statusBarColor = palette.surface
            @Suppress("DEPRECATION")
            window.navigationBarColor = palette.surface
            lightBars(!palette.dark)
        }
        header.invalidate()
        for (index in 0 until list.childCount) list.getChildAt(index).invalidate()
        settingsSheet?.let { colourSheet(it) }
        Choreographer.getInstance().postFrameCallback {
            // scripts/measure.sh reads this line.
            Log.i(TAG, "theme applied in ${SystemClock.uptimeMillis() - started} ms")
        }
    }

    @Suppress("DEPRECATION")
    private fun lightBars(light: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val both = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) both else 0, both)
        } else {
            val both = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            val flags = window.decorView.systemUiVisibility and both.inv()
            window.decorView.systemUiVisibility = if (light) flags or both else flags
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun dialogContext() = ContextThemeWrapper(
        this,
        when (palette) {
            Palette.DARK -> R.style.Dialog_Dark
            Palette.DIM -> R.style.Dialog_Dim
            Palette.DUSK -> R.style.Dialog_Dusk
            else -> R.style.Dialog_Light
        },
    )

    private fun menuContext() = ContextThemeWrapper(
        this,
        when (palette) {
            Palette.DARK -> R.style.Menu_Dark
            Palette.DIM -> R.style.Menu_Dim
            Palette.DUSK -> R.style.Menu_Dusk
            else -> R.style.Menu_Light
        },
    )

    /** Zooms the table only, keeping the first row on screen in place. */
    private fun setZoom(level: Double) {
        if (level == zoom) return
        val started = SystemClock.uptimeMillis()
        val factor = (level / zoom).toFloat()
        zoom = level
        saved.zoom = level
        style.apply(palette, zoom)
        val first = layout.findFirstVisibleItemPosition()
        document?.let { document ->
            document.state.columns.scale(factor)
            document.state.offsetX *= factor
            document.state.clampOffset()
            list.recycledViewPool.clear()
            @Suppress("NotifyDataSetChanged") // Every row changes size.
            document.adapter.notifyDataSetChanged()
            if (first >= 0) layout.scrollToPositionWithOffset(first, 0)
        }
        header.requestLayout()
        header.invalidate()
        settingsSheet?.findViewById<TextView>(R.id.zoom_value)?.text = "${Zoom.percent(zoom)}%"
        Choreographer.getInstance().postFrameCallback {
            // scripts/measure.sh reads this line.
            Log.i(TAG, "zoom ${Zoom.percent(zoom)}% applied in ${SystemClock.uptimeMillis() - started} ms")
        }
    }

    private val themeChoices = listOf(
        R.id.theme_system to Theme.SYSTEM,
        R.id.theme_light to Theme.LIGHT,
        R.id.theme_dark to Theme.DARK,
        R.id.theme_dim to Theme.DIM,
        R.id.theme_dusk to Theme.DUSK,
    )

    /** The settings sheet: theme and zoom, at the bottom of the screen. */
    private fun showSettings() {
        val sheet = Dialog(dialogContext())
        sheet.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        sheet.setContentView(R.layout.settings_sheet)
        sheet.window?.let { window ->
            window.setGravity(Gravity.BOTTOM)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val choices = sheet.findViewById<RadioGroup>(R.id.theme_choices)
        for ((id, choice) in themeChoices) {
            val button = sheet.findViewById<RadioButton>(id)
            val (left, right) = Palette.dot(choice)
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(DotDrawable(left, right, dp(12), 1f), null, null, null)
            button.isChecked = choice == chosenTheme
        }
        choices.setOnCheckedChangeListener { _, checked ->
            themeChoices.firstOrNull { it.first == checked }?.let { (_, chosen) -> if (chosen != chosenTheme) chooseTheme(chosen) }
        }
        sheet.findViewById<TextView>(R.id.zoom_value).text = "${Zoom.percent(zoom)}%"
        sheet.findViewById<View>(R.id.zoom_in).setOnClickListener { setZoom(Zoom.step(zoom, 1)) }
        sheet.findViewById<View>(R.id.zoom_out).setOnClickListener { setZoom(Zoom.step(zoom, -1)) }
        sheet.findViewById<View>(R.id.zoom_reset).setOnClickListener { setZoom(Zoom.step(zoom, 0)) }
        sheet.findViewById<View>(R.id.settings_close).setOnClickListener { sheet.dismiss() }
        sheet.setOnDismissListener { if (settingsSheet === sheet) settingsSheet = null }
        colourSheet(sheet)
        settingsSheet = sheet
        sheet.show()
    }

    private fun colourSheet(sheet: Dialog) {
        sheet.window?.setBackgroundDrawable(ColorDrawable(palette.surface))
        sheet.findViewById<TextView>(R.id.settings_title).setTextColor(palette.textPrimary)
        sheet.findViewById<TextView>(R.id.zoom_value).setTextColor(palette.textPrimary)
        for (id in listOf(R.id.theme_label, R.id.zoom_label)) sheet.findViewById<TextView>(id).setTextColor(palette.textSecondary)
        for (id in listOf(R.id.settings_close, R.id.zoom_in, R.id.zoom_out, R.id.zoom_reset)) sheet.findViewById<Button>(id).setTextColor(palette.link)
        for ((id, _) in themeChoices) {
            val button = sheet.findViewById<RadioButton>(id)
            button.setTextColor(palette.textPrimary)
            button.buttonTintList = ColorStateList.valueOf(palette.link)
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
    internal var popupForTest: PopupMenu? = null
        private set
    internal val sortedForTest: SortedOrder? get() = document?.order
    internal val sortingForTest: Boolean get() = document?.sorting != null
    internal fun goToRowForTest(number: Long) = goTo(number - 1)
    internal val paletteForTest: Palette get() = palette
    internal val themeForTest: Theme get() = chosenTheme
    internal val zoomForTest: Double get() = zoom
    internal val dotForTest: Pair<Int, Int> get() = Palette.dot(chosenTheme)
    internal val settingsForTest: Dialog? get() = settingsSheet

    /** Stands in for the cache's free space in tests. */
    internal var freeSpaceForTest: Long? = null

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
            XlsxBook.MIME_TYPE,
            "application/octet-stream",
        )
    }
}
