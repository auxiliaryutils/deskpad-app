package com.example.deskpad.core

/**
 * Translates [GestureAction]s into [InputSink] calls, tracking the cursor via [CursorController]
 * and gating everything through [LockController] (SPEC §3.1). When locked, [dispatch] is a no-op.
 */
class InputPipeline(
    private val sink: InputSink,
    private val cursor: CursorController,
    private val lock: LockController,
) {
    fun dispatch(displayId: Int, action: GestureAction) {
        if (lock.isLocked) return
        when (action) {
            is GestureAction.Move -> {
                val p = cursor.move(displayId, action.dx, action.dy)
                sink.moveCursor(displayId, p.x, p.y)
            }
            GestureAction.LeftClick -> click(displayId, primary = true)
            GestureAction.RightClick -> click(displayId, primary = false)
            is GestureAction.Scroll -> {
                val p = cursor.positionFor(displayId)
                sink.scroll(displayId, p.x, p.y, action.dx, action.dy)
            }
            GestureAction.DragStart -> {
                val p = cursor.positionFor(displayId)
                sink.button(displayId, p.x, p.y, primary = true, down = true)
            }
            is GestureAction.DragMove -> {
                val p = cursor.move(displayId, action.dx, action.dy)
                sink.moveCursor(displayId, p.x, p.y)
            }
            GestureAction.DragEnd -> {
                val p = cursor.positionFor(displayId)
                sink.button(displayId, p.x, p.y, primary = true, down = false)
            }
        }
    }

    private fun click(displayId: Int, primary: Boolean) {
        val p = cursor.positionFor(displayId)
        sink.button(displayId, p.x, p.y, primary, down = true)
        sink.button(displayId, p.x, p.y, primary, down = false)
    }
}
