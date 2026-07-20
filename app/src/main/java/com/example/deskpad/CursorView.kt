package com.example.deskpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * A classic arrow pointer, drawn so it reads on any background (white fill, dark outline, soft
 * shadow). The tip sits near the view's top-left so the overlay window's (x,y) is the hot-spot.
 */
class CursorView(context: Context) : View(context) {

    private val size = (18f * resources.displayMetrics.density).toInt().coerceAtLeast(28)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 1f, 0x66000000)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B1116")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init {
        // Needed for setShadowLayer to render.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private var alphaLevel = 1f
    fun setDimmed(dimmed: Boolean) {
        alphaLevel = if (dimmed) 0.35f else 1f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        path.moveTo(w * 0.06f, h * 0.04f)
        path.lineTo(w * 0.06f, h * 0.80f)
        path.lineTo(w * 0.28f, h * 0.60f)
        path.lineTo(w * 0.43f, h * 0.94f)
        path.lineTo(w * 0.57f, h * 0.88f)
        path.lineTo(w * 0.41f, h * 0.55f)
        path.lineTo(w * 0.70f, h * 0.55f)
        path.close()
        fill.alpha = (255 * alphaLevel).toInt()
        outline.alpha = (255 * alphaLevel).toInt()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, outline)
    }
}
