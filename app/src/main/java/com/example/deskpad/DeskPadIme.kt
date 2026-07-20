package com.example.deskpad

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/**
 * No-adb keyboard. A standard InputMethodService the user enables + selects in Settings. When a
 * text field is focused — including one on the external desktop display — [currentInputConnection]
 * targets it, and the phone-side keyboard in [MainActivity] drives text into it via [instance].
 *
 * The IME's own input view is a minimal strip (input happens on the phone), so it doesn't cover the
 * desktop. Whether the connection survives per-display focus in Desktop mode is the on-Pixel unknown.
 */
class DeskPadIme : InputMethodService() {

    private var active = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View = TextView(this).apply {
        text = "DeskPad keyboard active — type on your phone"
        setBackgroundColor(0xFF151C26.toInt())
        setTextColor(0xFFD7DEE7.toInt())
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(24, 20, 24, 20)
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        setActive(true)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        setActive(true)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        setActive(false)
    }

    // The editor's input view being hidden is a more reliable "no longer typing here" signal than
    // onFinishInput, which isn't guaranteed to fire across display/app switches.
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        setActive(false)
    }

    private fun setActive(value: Boolean) {
        active = value
        onActiveChanged?.invoke(value)
    }

    val isActive: Boolean get() = active

    // ---- text entry API (called by the phone keyboard) ----

    fun commit(text: CharSequence): Boolean =
        currentInputConnection?.commitText(text, 1) == true

    fun backspace(): Boolean =
        currentInputConnection?.deleteSurroundingText(1, 0) == true

    fun sendKey(keyCode: Int, metaState: Int = 0): Boolean {
        val ic = currentInputConnection ?: return false
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        return true
    }

    companion object {
        @Volatile
        var instance: DeskPadIme? = null
            private set

        /** UI observes this to reflect whether a field is focused / connected. */
        @Volatile
        var onActiveChanged: ((Boolean) -> Unit)? = null
    }
}
