package io.github.abooodbah.leanrows

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Sizes and paints shared by the header and every row. */
class TableStyle(context: Context) {
    private val metrics = context.resources.displayMetrics
    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)

    val padding = dp(12f)
    val rowHeight = (sp(14f) * 2.6f).roundToInt()
    val headerHeight = (sp(13f) * 2.8f).roundToInt()
    val minimumWidth = dp(56f)
    val maximumWidth = dp(280f)
    val maximumPreviewWidth = dp(1600f)

    val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        color = context.getColor(R.color.ink)
    }
    val number = TextPaint(text).apply {
        color = context.getColor(R.color.muted)
        textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "tnum"
    }
    val title = TextPaint(text).apply {
        textSize = sp(13f)
        color = context.getColor(R.color.muted)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val rule = Paint().apply {
        color = context.getColor(R.color.rule)
        strokeWidth = dp(1f)
    }
    val surface = Paint().apply { color = context.getColor(R.color.surface) }
    val canvas = Paint().apply { color = context.getColor(R.color.canvas) }
    val highlight = Paint().apply { color = context.getColor(R.color.accent) and 0x00FFFFFF or 0x33000000 }

    fun columns(preview: Boolean) = Columns(
        preview,
        measure = { text.measureText(it) },
        padding = padding,
        minimum = minimumWidth,
        maximum = if (preview) maximumPreviewWidth else maximumWidth,
        measuredChars = if (preview) 240 else 80,
    )

    /** The baseline that centres one line of [paint] in [height]. */
    fun baseline(paint: Paint, height: Int): Float = (height - paint.descent() - paint.ascent()) / 2f
}

/** What the header and the rows draw: the columns and how far the table is panned. */
class TableState(val style: TableStyle, val columns: Columns) {
    var offsetX = 0f
    var viewportWidth = 0
    var highlightedRow = -1L

    val maxOffset: Float get() = maxOf(0f, columns.totalWidth - viewportWidth)

    fun clampOffset() {
        offsetX = offsetX.coerceIn(0f, maxOffset)
    }
}

/**
 * Draws one row: the row number stays at the left edge, and the cells move
 * under it as the table is panned. Text is cut to the column width once, when
 * the row is bound, so panning only redraws.
 */
class RowView(context: Context, private val state: TableState) : View(context) {
    var index = -1L
        private set
    private var number = ""
    private var cells: Array<CharSequence> = emptyArray()
    private var raw: List<String> = emptyList()
    var lastTouchX = 0f
        private set

    init {
        isClickable = true
        isFocusable = true
    }

    fun bind(index: Long, row: Row?) {
        val style = state.style
        val widths = state.columns.widths
        this.index = index
        number = (index + 1).toString()
        raw = row?.cells.orEmpty()
        cells = Array(minOf(raw.size, widths.size)) { column ->
            TextUtils.ellipsize(raw[column], style.text, widths[column] - 2 * style.padding, TextUtils.TruncateAt.END)
        }
        contentDescription = if (row == null) {
            "Row $number"
        } else {
            "Row $number: " + raw.joinToString(", ")
        }
        invalidate()
    }

    /** The full text of the cell under the last touch, or null. */
    fun cellAtLastTouch(): Pair<Int, String>? {
        val left = state.columns.rowNumberWidth
        if (lastTouchX < left) return null
        val column = state.columns.columnAt(lastTouchX - left + state.offsetX)
        return raw.getOrNull(column)?.let { column to it }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) lastTouchX = event.x
        return super.onTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), state.style.rowHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val style = state.style
        val columns = state.columns
        val height = height.toFloat()
        if (index == state.highlightedRow) canvas.drawRect(0f, 0f, width.toFloat(), height, style.highlight)
        val left = columns.rowNumberWidth
        val baseline = style.baseline(style.text, this.height)

        canvas.save()
        canvas.clipRect(left, 0f, width.toFloat(), height)
        var x = left - state.offsetX
        for (column in columns.widths.indices) {
            val columnWidth = columns.widths[column]
            if (x >= width) break
            if (x + columnWidth > left) {
                cells.getOrNull(column)?.let { text ->
                    canvas.drawText(text, 0, text.length, x + style.padding, baseline, style.text)
                }
                canvas.drawLine(x + columnWidth, 0f, x + columnWidth, height, style.rule)
            }
            x += columnWidth
        }
        canvas.restore()

        canvas.drawText(number, left - style.padding, baseline, style.number)
        canvas.drawLine(left, 0f, left, height, style.rule)
        canvas.drawLine(0f, height - style.rule.strokeWidth / 2, width.toFloat(), height - style.rule.strokeWidth / 2, style.rule)
    }
}

/** The column titles, panned with the rows. */
class HeaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var state: TableState? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = state?.style?.headerHeight ?: 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        val state = state ?: return
        val style = state.style
        val columns = state.columns
        val height = height.toFloat()
        canvas.drawRect(0f, 0f, width.toFloat(), height, style.surface)
        val left = columns.rowNumberWidth
        val baseline = style.baseline(style.title, this.height)
        canvas.save()
        canvas.clipRect(left, 0f, width.toFloat(), height)
        var x = left - state.offsetX
        for ((column, title) in columns.titles.withIndex()) {
            val columnWidth = columns.widths[column]
            if (x >= width) break
            if (x + columnWidth > left) {
                canvas.drawText(title, x + style.padding, baseline, style.title)
                canvas.drawLine(x + columnWidth, 0f, x + columnWidth, height, style.rule)
            }
            x += columnWidth
        }
        canvas.restore()
        canvas.drawText(Columns.ROW, style.padding, baseline, style.title)
        canvas.drawLine(left, 0f, left, height, style.rule)
        canvas.drawLine(0f, height - style.rule.strokeWidth / 2, width.toFloat(), height - style.rule.strokeWidth / 2, style.rule)
    }
}

/**
 * Pans the table sideways. RecyclerView scrolls the rows vertically; a drag
 * that starts out more sideways than vertical is taken here instead and moves
 * every row and the header together, with a fling at the end.
 */
class HorizontalPan(context: Context, private val state: () -> TableState?, private val panned: () -> Unit) :
    RecyclerView.OnItemTouchListener {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var list: RecyclerView? = null

    private val frame = object : Runnable {
        override fun run() {
            val table = state() ?: return
            if (scroller.computeScrollOffset()) {
                table.offsetX = scroller.currX.toFloat()
                table.clampOffset()
                panned()
                list?.postOnAnimation(this)
            }
        }
    }

    fun stop() = scroller.forceFinished(true)

    override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
        list = recyclerView
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stop()
                downX = event.x
                downY = event.y
                lastX = event.x
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val table = state()
                if (!dragging && table != null && table.maxOffset > 0 &&
                    recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE
                ) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        dragging = true
                        lastX = event.x
                        recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) {
                velocity?.recycle()
                velocity = null
            }
        }
        return dragging
    }

    override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
        val table = state() ?: return
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                table.offsetX += lastX - event.x
                table.clampOffset()
                lastX = event.x
                panned()
            }
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000, maxVelocity)
                val speed = velocity?.xVelocity ?: 0f
                scroller.fling(table.offsetX.roundToInt(), 0, -speed.roundToInt(), 0, 0, table.maxOffset.roundToInt(), 0, 0)
                recyclerView.postOnAnimation(frame)
                finish()
            }
            MotionEvent.ACTION_CANCEL -> finish()
        }
    }

    private fun finish() {
        dragging = false
        velocity?.recycle()
        velocity = null
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
}
