package com.example.deskpad.core

import kotlin.math.hypot

/** Normalized touch phase for one frame from the phone's touchpad surface. */
enum class PadPhase { DOWN, MOVE, UP }

/**
 * A framework-free snapshot of the touchpad at one instant: the current pointer count and the
 * primary pointer position. Produced by the Android `TouchpadView` from `MotionEvent`s.
 */
data class PadFrame(
    val phase: PadPhase,
    val pointerCount: Int,
    val x: Float,
    val y: Float,
    val timeMs: Long,
)

/** Semantic actions the recognizer emits (SPEC §3 mapping table). */
sealed interface GestureAction {
    data class Move(val dx: Float, val dy: Float) : GestureAction
    data object LeftClick : GestureAction
    data object RightClick : GestureAction
    data class Scroll(val dx: Float, val dy: Float) : GestureAction
    data object DragStart : GestureAction
    data class DragMove(val dx: Float, val dy: Float) : GestureAction
    data object DragEnd : GestureAction
}

/** Tunable thresholds for gesture recognition. */
data class GestureConfig(
    val tapSlopPx: Float = 16f,
    val tapTimeoutMs: Long = 250,
    val doubleTapWindowMs: Long = 300,
)

/**
 * Stateful interpreter: fed a stream of [PadFrame]s, emits [GestureAction]s. Pure — the only
 * state is gesture bookkeeping; no Android types cross this boundary.
 *
 * Recognition rules:
 *  - 1-finger down→up within slop + timeout → LeftClick
 *  - 1-finger move beyond slop → Move deltas (no click on release)
 *  - 2-finger down→up (little movement) → RightClick
 *  - 2-finger move → Scroll deltas
 *  - a down arriving within doubleTapWindow of a tap, then moving → DragStart/DragMove/DragEnd
 */
class GestureRecognizer(private val config: GestureConfig = GestureConfig()) {

    private var active = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var maxPointers = 0
    private var moved = false
    private var twoFinger = false
    private var dragArmed = false
    private var dragging = false
    private var lastTapUpTime = NO_TAP

    fun onFrame(frame: PadFrame): List<GestureAction> {
        val out = mutableListOf<GestureAction>()
        when (frame.phase) {
            PadPhase.DOWN -> onDown(frame)
            PadPhase.MOVE -> onMove(frame, out)
            PadPhase.UP -> onUp(frame, out)
        }
        return out
    }

    private fun onDown(frame: PadFrame) {
        if (!active) {
            active = true
            downX = frame.x; downY = frame.y; downTime = frame.timeMs
            lastX = frame.x; lastY = frame.y
            maxPointers = frame.pointerCount.coerceAtLeast(1)
            moved = false
            twoFinger = frame.pointerCount >= 2
            dragging = false
            dragArmed = lastTapUpTime != NO_TAP &&
                (frame.timeMs - lastTapUpTime) <= config.doubleTapWindowMs
        } else {
            // Additional finger touched down during the gesture.
            maxPointers = maxOf(maxPointers, frame.pointerCount)
            if (frame.pointerCount >= 2) twoFinger = true
            lastX = frame.x; lastY = frame.y
            dragArmed = false // multi-finger cancels a pending drag
        }
    }

    private fun onMove(frame: PadFrame, out: MutableList<GestureAction>) {
        if (!active) return
        val dx = frame.x - lastX
        val dy = frame.y - lastY
        lastX = frame.x; lastY = frame.y
        if (hypot(frame.x - downX, frame.y - downY) > config.tapSlopPx) moved = true

        when {
            twoFinger || frame.pointerCount >= 2 -> out += GestureAction.Scroll(dx, dy)
            dragArmed || dragging -> {
                if (!dragging) {
                    out += GestureAction.DragStart
                    dragging = true
                    dragArmed = false
                }
                out += GestureAction.DragMove(dx, dy)
            }
            moved -> out += GestureAction.Move(dx, dy)
        }
    }

    private fun onUp(frame: PadFrame, out: MutableList<GestureAction>) {
        if (!active) return
        val dist = hypot(frame.x - downX, frame.y - downY)
        val duration = frame.timeMs - downTime
        val isTap = !moved && dist <= config.tapSlopPx && duration <= config.tapTimeoutMs

        when {
            dragging -> out += GestureAction.DragEnd
            twoFinger || maxPointers >= 2 -> if (isTap) out += GestureAction.RightClick
            isTap -> {
                out += GestureAction.LeftClick
                lastTapUpTime = frame.timeMs
            }
        }
        reset()
    }

    private fun reset() {
        active = false
        twoFinger = false
        dragging = false
        dragArmed = false
        maxPointers = 0
        moved = false
    }

    private companion object {
        const val NO_TAP = Long.MIN_VALUE
    }
}
