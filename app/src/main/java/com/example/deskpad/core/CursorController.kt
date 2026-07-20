package com.example.deskpad.core

/**
 * Tracks an absolute cursor position per display. Touchpad deltas are scaled by [sensitivity]
 * and clamped to the display bounds. Pure — no Android dependency.
 */
class CursorController(private val sensitivity: Float = 1f) {

    private class State(val bounds: Bounds, var pos: Point)

    private val states = mutableMapOf<Int, State>()

    private fun centre(b: Bounds) = Point(b.width / 2f, b.height / 2f)

    /**
     * Registers the pixel bounds of a display. A newly-seen display starts centred; an already-known
     * display keeps its current cursor position (clamped if the bounds changed) so routine display
     * refreshes never snap the cursor back to centre.
     */
    fun setBounds(displayId: Int, bounds: Bounds) {
        val existing = states[displayId]
        states[displayId] = when {
            existing == null -> State(bounds, centre(bounds))
            existing.bounds == bounds -> existing
            else -> State(
                bounds,
                Point(existing.pos.x.coerceIn(0f, bounds.width), existing.pos.y.coerceIn(0f, bounds.height)),
            )
        }
    }

    /** Current cursor position for a display (centre if untouched). */
    fun positionFor(displayId: Int): Point = state(displayId).pos

    /** Applies a delta (× sensitivity), clamps to bounds, returns the new position. */
    fun move(displayId: Int, dx: Float, dy: Float): Point {
        val s = state(displayId)
        val nx = (s.pos.x + dx * sensitivity).coerceIn(0f, s.bounds.width)
        val ny = (s.pos.y + dy * sensitivity).coerceIn(0f, s.bounds.height)
        s.pos = Point(nx, ny)
        return s.pos
    }

    /** Re-centres the cursor for a display. */
    fun reset(displayId: Int) {
        val s = state(displayId)
        s.pos = centre(s.bounds)
    }

    private fun state(displayId: Int): State =
        states[displayId] ?: error("no bounds registered for display $displayId")
}
