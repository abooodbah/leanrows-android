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
