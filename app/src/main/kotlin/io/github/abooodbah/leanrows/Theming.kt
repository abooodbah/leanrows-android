package io.github.abooodbah.leanrows

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The theme button's dot, as LeanMark draws it: a filled circle with a
 * 1-pixel grey ring, the left and right halves in [left] and [right] (they
 * differ only for System).
 */
class DotDrawable(private val left: Int, private val right: Int, private val size: Int, private val ring: Float) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ring
        color = Palette.DOT_RING
    }
    private val oval = RectF()

    override fun getIntrinsicWidth() = size

    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val diameter = minOf(bounds.width(), bounds.height()).toFloat()
        val x = bounds.exactCenterX() - diameter / 2
        val y = bounds.exactCenterY() - diameter / 2
        oval.set(x + ring / 2, y + ring / 2, x + diameter - ring / 2, y + diameter - ring / 2)
        fill.color = left
        canvas.drawArc(oval, 90f, 180f, true, fill)
        fill.color = right
        canvas.drawArc(oval, 270f, 180f, true, fill)
        canvas.drawOval(oval, stroke)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * LeanMark's settings icon, three sliders on a 16-unit grid, drawn in code.
 * As a vector drawable in the toolbar it made the first frame about 140 ms
 * later on the emulator.
 */
class SlidersDrawable(private val size: Int, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidateSelf()
        }

    override fun getIntrinsicWidth() = size

    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        val unit = minOf(bounds.width(), bounds.height()) / 16f
        val x0 = bounds.exactCenterX() - 8 * unit
        val y0 = bounds.exactCenterY() - 8 * unit
        paint.strokeWidth = 1.6f * unit
        fun line(a: Float, b: Float, y: Float) = canvas.drawLine(x0 + a * unit, y0 + y * unit, x0 + b * unit, y0 + y * unit, paint)
        line(2f, 8f, 4f); line(11f, 14f, 4f)
        line(2f, 4f, 8f); line(7f, 14f, 8f)
        line(2f, 9f, 12f); line(12f, 14f, 12f)
        canvas.drawCircle(x0 + 9.5f * unit, y0 + 4 * unit, 1.5f * unit, paint)
        canvas.drawCircle(x0 + 5.5f * unit, y0 + 8 * unit, 1.5f * unit, paint)
        canvas.drawCircle(x0 + 10.5f * unit, y0 + 12 * unit, 1.5f * unit, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
