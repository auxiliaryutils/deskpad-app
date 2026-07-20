package com.example.deskpad

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager

/**
 * Draws the cursor on the EXTERNAL display so the user can see where they're pointing. Uses a
 * TYPE_ACCESSIBILITY_OVERLAY window attached to that display's context — available to an enabled
 * accessibility service without SYSTEM_ALERT_WINDOW. All view ops run on the main thread.
 */
class CursorOverlay(private val serviceContext: Context) {

    private val main = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var view: CursorView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attachedDisplayId = -1

    fun attach(display: Display) = main.post {
        if (attachedDisplayId == display.displayId && view != null) return@post
        detachNow()
        try {
            val ctx = serviceContext.createDisplayContext(display)
            val wm = ctx.getSystemService(WindowManager::class.java)
            val v = CursorView(ctx)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            wm.addView(v, p)
            windowManager = wm
            view = v
            params = p
            attachedDisplayId = display.displayId
            Log.i(TAG, "cursor overlay attached to display ${display.displayId}")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to attach cursor overlay", t)
        }
    }

    fun moveTo(x: Float, y: Float) = main.post {
        val wm = windowManager ?: return@post
        val v = view ?: return@post
        val p = params ?: return@post
        p.x = x.toInt()
        p.y = y.toInt()
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun setDimmed(dimmed: Boolean) = main.post { view?.setDimmed(dimmed) }

    fun detach() = main.post { detachNow() }

    private fun detachNow() {
        val wm = windowManager
        val v = view
        if (wm != null && v != null) runCatching { wm.removeView(v) }
        windowManager = null
        view = null
        params = null
        attachedDisplayId = -1
    }

    private companion object {
        const val TAG = "DeskPadCursor"
    }
}
