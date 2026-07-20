package com.example.deskpad

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.deskpad.core.PadFrame
import com.example.deskpad.core.PadPhase

/**
 * The phone-side trackpad surface. Translates raw [MotionEvent]s into framework-free [PadFrame]s
 * and hands them to [onPadFrame]; all interpretation happens in the pure `GestureRecognizer`.
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onPadFrame: ((PadFrame) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> PadPhase.DOWN
            MotionEvent.ACTION_MOVE -> PadPhase.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> PadPhase.UP
            else -> return false
        }
        if (phase == PadPhase.UP) performClick()
        onPadFrame?.invoke(
            PadFrame(phase, event.pointerCount, event.getX(0), event.getY(0), event.eventTime)
        )
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
