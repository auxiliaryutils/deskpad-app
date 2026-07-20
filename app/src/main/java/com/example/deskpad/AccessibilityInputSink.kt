package com.example.deskpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.util.Log
import com.example.deskpad.core.InputSink
import kotlin.math.abs

/**
 * Drives the external display with no adb/root: pointer movement repositions the drawn [CursorOverlay];
 * tap/right-click/drag/scroll become dispatched touch gestures.
 *
 * Two gestures need care, and both use the same idea: keep exactly **one** gesture in flight at a
 * time and, when it completes, dispatch the next toward wherever the finger is now.
 *  - **Scroll**: per-frame deltas accumulate; each in-flight swipe drains what piled up. Firing a
 *    swipe per frame instead makes them overlap and race (felt inconsistent / unresponsive).
 *  - **Drag**: a *continued* gesture — the touch goes down and stays down, each segment continuing
 *    the last toward the current finger position, until release. Dispatching the whole drag as one
 *    stroke on release instead made the dragged item lurch at the end (the "delayed drag").
 *
 * Everything here runs on the main thread — pad frames and gesture callbacks both arrive there — so
 * no synchronization is needed.
 */
class AccessibilityInputSink(
    private val service: AccessibilityService,
    private val overlay: CursorOverlay,
) : InputSink {

    private var boundsW = Float.MAX_VALUE
    private var boundsH = Float.MAX_VALUE

    private var pressing = false
    private var startX = 0f
    private var startY = 0f

    // Live drag state.
    private var dragging = false
    private var dragInFlight = false
    private var dragEnd = false        // release requested; the next segment lifts the touch
    private var closing = false        // a willContinue=false (lifting) segment has been dispatched
    private var dragDisplayId = INVALID
    private var dragStroke: StrokeDescription? = null
    private var dragLastX = 0f         // where the current stroke ends (start of the next)
    private var dragLastY = 0f
    private var dragToX = 0f           // latest finger position to continue toward
    private var dragToY = 0f

    // Scroll state.
    private var scrollAccum = 0f
    private var scrollInFlight = false
    private var scrollX = 0f
    private var scrollY = 0f
    private var scrollDisplayId = INVALID

    fun setDisplayBounds(width: Float, height: Float) {
        boundsW = width
        boundsH = height
    }

    override fun moveCursor(displayId: Int, x: Float, y: Float) {
        overlay.moveTo(x, y)
        // While a primary press is held, cursor movement means a drag (the pipeline only calls
        // moveCursor during a press for DragMove) — so start the drag on the first move, then follow.
        if (pressing) {
            if (!dragging) beginDrag(dragDisplayId, startX, startY)
            dragToX = x; dragToY = y
            if (!dragInFlight) advanceDrag()
        }
    }

    override fun button(displayId: Int, x: Float, y: Float, primary: Boolean, down: Boolean) {
        overlay.moveTo(x, y)
        if (primary) {
            if (down) {
                pressing = true; startX = x; startY = y; dragDisplayId = displayId
            } else {
                pressing = false
                if (dragging) {
                    dragToX = x; dragToY = y; dragEnd = true
                    if (!dragInFlight) advanceDrag()
                } else {
                    dispatchStroke(displayId, x, y, x, y, TAP_MS) // press with no movement = a click
                }
            }
        } else if (!down) {
            dispatchStroke(displayId, x, y, x, y, LONG_PRESS_MS) // right-click = long-press
        }
    }

    // ---- live drag (continued gesture strokes) ----

    private fun beginDrag(displayId: Int, x: Float, y: Float) {
        dragging = true; dragEnd = false; closing = false
        dragLastX = x; dragLastY = y; dragToX = x; dragToY = y
        // The initial stroke presses down and must be non-degenerate; it stays down (willContinue).
        val path = Path().apply { moveTo(clampX(x), clampY(y)); lineTo(clampX(x) + 1f, clampY(y) + 1f) }
        dragLastX = clampX(x) + 1f; dragLastY = clampY(y) + 1f
        dispatchDrag(displayId, StrokeDescription(path, 0L, DRAG_SEG_MS, true))
    }

    private fun advanceDrag() {
        val prev = dragStroke ?: return
        val willContinue = !dragEnd
        if (!willContinue) closing = true
        val endX = clampX(dragToX); val endY = clampY(dragToY)
        // A continuation must start exactly where the previous stroke ended. If the finger hasn't
        // moved, nudge the endpoint by 1px so the path is non-degenerate, and remember that endpoint.
        val degenerate = endX == dragLastX && endY == dragLastY
        val tx = if (degenerate) endX + 1f else endX
        val ty = if (degenerate) endY else endY
        val path = Path().apply { moveTo(dragLastX, dragLastY); lineTo(tx, ty) }
        val stroke = prev.continueStroke(path, 0L, DRAG_SEG_MS, willContinue)
        dragLastX = tx; dragLastY = ty
        dispatchDrag(dragDisplayId, stroke)
    }

    private fun dispatchDrag(displayId: Int, stroke: StrokeDescription) {
        dragStroke = stroke
        val gesture = GestureDescription.Builder().addStroke(stroke).setDisplayId(displayId).build()
        dragInFlight = true
        val started = try {
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { dragInFlight = false; onDragSegmentDone() }
                override fun onCancelled(d: GestureDescription?) { dragInFlight = false; endDragState() }
            }, null)
        } catch (t: Throwable) {
            Log.e(TAG, "drag gesture failed", t); false
        }
        if (!started) { dragInFlight = false; endDragState() }
    }

    private fun onDragSegmentDone() {
        if (!dragging) return
        when {
            closing -> endDragState()          // the lifting segment finished — drag is over
            dragEnd -> advanceDrag()           // release requested — dispatch the lifting segment
            else -> advanceDrag()              // follow the finger, or hold in place to stay down
        }
    }

    private fun endDragState() {
        dragging = false; dragEnd = false; closing = false; dragStroke = null
    }

    /**
     * Two-finger scroll → a swipe on the external display. Deltas accumulate; a swipe is dispatched
     * only when none is in flight, and its completion drains whatever accumulated while it ran.
     */
    override fun scroll(displayId: Int, x: Float, y: Float, hScroll: Float, vScroll: Float) {
        scrollDisplayId = displayId
        scrollX = x; scrollY = y
        scrollAccum += vScroll
        dispatchScrollIfIdle()
    }

    private fun dispatchScrollIfIdle() {
        if (scrollInFlight) return
        if (abs(scrollAccum) < MIN_SCROLL) return
        val displayId = scrollDisplayId
        if (displayId == INVALID) return

        val dy = scrollAccum.coerceIn(-MAX_SWIPE, MAX_SWIPE)
        scrollAccum -= dy
        val cx = clampX(scrollX)
        val y1 = clampY(scrollY - dy / 2f)
        val y2 = clampY(scrollY + dy / 2f)
        val path = Path().apply { moveTo(cx, y1); lineTo(cx, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(StrokeDescription(path, 0, SCROLL_MS))
            .setDisplayId(displayId)
            .build()

        scrollInFlight = true
        val started = try {
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { scrollInFlight = false; dispatchScrollIfIdle() }
                override fun onCancelled(d: GestureDescription?) { scrollInFlight = false; dispatchScrollIfIdle() }
            }, null)
        } catch (t: Throwable) {
            Log.e(TAG, "scroll gesture failed", t); false
        }
        if (!started) scrollInFlight = false
    }

    override fun key(displayId: Int, keyCode: Int, metaState: Int, down: Boolean) {
        // Text goes through the service's keyboard path, not here.
    }

    // ---- one-shot strokes (tap / right-click) ----

    private fun dispatchStroke(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long) {
        val path = Path().apply {
            moveTo(clampX(x1), clampY(y1))
            lineTo(clampX(x2 + 1f), clampY(y2 + 1f)) // +1 so a tap is a non-degenerate stroke
        }
        val gesture = GestureDescription.Builder()
            .addStroke(StrokeDescription(path, 0, durMs))
            .setDisplayId(displayId)
            .build()
        try {
            service.dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.e(TAG, "dispatchGesture failed", t)
        }
    }

    private fun clampX(v: Float) = v.coerceIn(1f, boundsW - 1f)
    private fun clampY(v: Float) = v.coerceIn(1f, boundsH - 1f)

    private companion object {
        const val TAG = "DeskPadA11ySink"
        const val INVALID = -1
        const val TAP_MS = 40L
        const val LONG_PRESS_MS = 700L
        const val DRAG_SEG_MS = 45L   // per continued-drag segment; short = closely follows the finger
        const val MIN_SCROLL = 24f    // ignore sub-slop jitter; must exceed the view's touch slop
        const val MAX_SWIPE = 320f    // cap per-swipe travel (px) so a burst isn't a screen-long fling
        const val SCROLL_MS = 90L     // short enough to feel responsive, long enough to register
    }
}
