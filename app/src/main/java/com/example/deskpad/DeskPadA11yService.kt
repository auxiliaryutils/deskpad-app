package com.example.deskpad

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.deskpad.core.Bounds
import com.example.deskpad.core.CursorController
import com.example.deskpad.core.DisplayInfo
import com.example.deskpad.core.DisplayTargetResolver
import com.example.deskpad.core.GestureRecognizer
import com.example.deskpad.core.InputPipeline
import com.example.deskpad.core.LockController
import com.example.deskpad.core.PadFrame

/**
 * The hub for the no-adb build. As an enabled AccessibilityService it runs whenever the user has
 * turned it on in Settings, and it: owns the pure pipeline (gesture → cursor → lock → sink), draws
 * the cursor on the external display, tracks which display is external, and dispatches gestures.
 * The UI ([MainActivity]) feeds it pad frames via [instance].
 *
 * Everything runs on the **main thread** — pad frames arrive there (touch events) and display
 * callbacks are delivered there — so [CursorController] is only ever touched from one thread and
 * `dispatchGesture` is called where the platform expects it. No background handler.
 */
class DeskPadA11yService : AccessibilityService() {

    data class State(val external: DisplayInfo?, val locked: Boolean)

    var onState: ((State) -> Unit)? = null
        set(value) { field = value; value?.invoke(currentState()) }

    private val lock = LockController()
    private val cursor = CursorController(sensitivity = 1.4f)
    private val gestures = GestureRecognizer()
    private val resolver = DisplayTargetResolver()

    private lateinit var overlay: CursorOverlay
    private lateinit var sink: AccessibilityInputSink
    private lateinit var pipeline: InputPipeline
    private lateinit var displayManager: DisplayManager
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var targetDisplayId = INVALID
    private var lastClick: com.example.deskpad.core.Point? = null

    // Local mirror of the clicked field's text, so typing is deterministic (SET_TEXT is async, so
    // re-reading the node every keystroke could drop characters). Synced when the field is clicked.
    private val buffer = StringBuilder()
    private var caret = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = CursorOverlay(this)
        sink = AccessibilityInputSink(this, overlay)
        pipeline = InputPipeline(sink, cursor, lock)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, main)
        instance = this
        refreshDisplays()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        if (::displayManager.isInitialized) runCatching { displayManager.unregisterDisplayListener(displayListener) }
        if (::overlay.isInitialized) runCatching { overlay.detach() }
    }

    /** Called on the main thread (touch events). Runs the whole pipeline inline. */
    fun submitPadFrame(frame: PadFrame) {
        val displayId = targetDisplayId
        if (displayId == INVALID) return
        for (action in gestures.onFrame(frame)) {
            // Remember where the user clicked on the desktop — that's the text field to type into,
            // since it is never truly input-focused while this app is foreground.
            if (action == com.example.deskpad.core.GestureAction.LeftClick) {
                lastClick = cursor.positionFor(displayId)
                syncBuffer()
            }
            pipeline.dispatch(displayId, action)
        }
    }

    fun setLocked(locked: Boolean) {
        if (locked) lock.lock() else lock.unlock()
        overlay.setDimmed(locked)
        notifyState()
    }

    val isLocked: Boolean get() = lock.isLocked

    // ---- keyboard (accessibility edits on the focused desktop field) ----
    //
    // The DeskPad keys are Buttons, which never take input focus, so the desktop field stays
    // focused while you type. Each keystroke edits that focused field's text via accessibility —
    // insert at the caret, delete, submit, and the standard clipboard actions.

    val hasFocusedField: Boolean get() = targetField() != null

    /** The field's real text ("" when it's only showing its hint placeholder). */
    private fun currentText(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText) "" else (n.text?.toString() ?: "")

    /** Load the clicked field's current text into the local buffer. */
    private fun syncBuffer() {
        val n = targetField()
        buffer.setLength(0)
        buffer.append(n?.let { currentText(it) } ?: "")
        caret = buffer.length
    }

    /** Insert [insert] at the caret and push the buffer to the clicked desktop field. */
    fun typeText(insert: CharSequence): Boolean {
        if (lock.isLocked) return false
        val n = targetField() ?: return false
        val c = caret.coerceIn(0, buffer.length)
        buffer.insert(c, insert)
        caret = c + insert.length
        return setText(n, buffer.toString(), caret)
    }

    /** A user-facing summary of what the accessibility service can see, for on-device debugging. */
    fun keyboardDiagnostics(): String {
        val all = windowsOnAllDisplays
        val ids = (0 until all.size()).map { all.keyAt(it) }
        val external = ids.filter { it != Display.DEFAULT_DISPLAY }
        return when {
            external.isEmpty() -> "a11y sees displays $ids — external display not visible"
            hasFocusedField -> "● field ready on display $external — type"
            else -> "sees display $external — click a text field there"
        }
    }

    /** Delete the selection, or the char before the caret. */
    fun backspace(): Boolean {
        if (lock.isLocked) return false
        val n = targetField() ?: return false
        val c = caret.coerceIn(0, buffer.length)
        if (c > 0) { buffer.deleteCharAt(c - 1); caret = c - 1 }
        return setText(n, buffer.toString(), caret)
    }

    /**
     * Enter submits the field (send / go / search), like pressing Enter on a real keyboard.
     *
     * The editor action only fires on an input-focused field with a bound IME, and the desktop field
     * is neither while this app is foreground. So we try, in order:
     *   1. the editor action directly (works if the field already has an active IME connection);
     *   2. request input focus (in desktop mode each display keeps its own focus, so this doesn't
     *      disturb the phone), wait for the IME to bind, then retry the editor action;
     *   3. click the app's own send/submit button — the fallback for fields with no editor action.
     */
    fun enter(): Boolean {
        if (lock.isLocked) return false
        val n = targetField() ?: return false
        // 1) A send/submit button on the field's own row (chat, search) — the most reliable submit,
        //    and it needs no input focus.
        if (clickSubmitNear(n)) { syncBuffer(); return true }
        // 2) Otherwise (e.g. a browser address bar with no button) give the field input focus, let the
        //    IME bind, then fire its editor action. IME_ENTER's return value is unreliable — it reports
        //    success even on an unfocused field where nothing happens — so we always focus first and
        //    don't branch on it.
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        main.postDelayed({
            targetField()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            syncBuffer()
        }, IME_ENTER_RETRY_MS)
        return true
    }

    /** Click a send/submit button sitting on the same row as [field] (a chat composer / search bar). */
    private fun clickSubmitNear(field: AccessibilityNodeInfo): Boolean {
        val displayId = targetDisplayId
        if (displayId == INVALID) return false
        val fieldRect = Rect().also { field.getBoundsInScreen(it) }
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) {
            if (all.keyAt(i) != displayId) continue
            for (w in all.valueAt(i)) {
                val hit = w.root?.let { findSubmitNear(it, fieldRect) }
                if (hit != null) return hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    /** A visible, clickable Send/Search/Go control that overlaps [fieldRect]'s row and sits right of it. */
    private fun findSubmitNear(node: AccessibilityNodeInfo, fieldRect: Rect): AccessibilityNodeInfo? {
        if (node.isClickable && node.isVisibleToUser) {
            val label = (node.contentDescription ?: node.text)?.toString()?.trim()?.lowercase()
            if (label != null && label.length <= 14 &&
                SUBMIT_HINTS.any { label == it || label.startsWith("$it ") }
            ) {
                val b = Rect().also { node.getBoundsInScreen(it) }
                val sameRow = b.top <= fieldRect.bottom && b.bottom >= fieldRect.top
                if (sameRow && b.left >= fieldRect.centerX()) return node
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> findSubmitNear(child, fieldRect)?.let { return it } }
        }
        return null
    }

    /** Clipboard / selection actions: ACTION_COPY / ACTION_PASTE / ACTION_CUT. */
    fun editAction(actionId: Int): Boolean {
        if (lock.isLocked) return false
        val ok = targetField()?.performAction(actionId) ?: false
        syncBuffer() // paste/cut change the field's text — re-read it
        return ok
    }

    fun selectAll(): Boolean {
        if (lock.isLocked) return false
        val n = targetField() ?: return false
        val len = n.text?.length ?: 0
        return n.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
            },
        )
    }

    private fun setText(n: AccessibilityNodeInfo, text: String, caret: Int): Boolean {
        val ok = n.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
        n.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            },
        )
        return ok
    }

    /**
     * The input-focused editable node on a NON-default display (the external desktop), if any.
     *
     * Uses getWindowsOnAllDisplays() — NOT getWindows(), which only returns the default display's
     * windows and is why earlier attempts never saw the external field.
     */
    /**
     * The editable node the user last clicked on the external display — found by coordinates, not
     * focus, because the desktop field is never truly input-focused while this app is foreground.
     */
    private fun targetField(): AccessibilityNodeInfo? {
        val p = lastClick ?: return null
        val displayId = targetDisplayId
        if (displayId == INVALID) return null
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) {
            if (all.keyAt(i) != displayId) continue
            for (w in all.valueAt(i)) {
                val hit = w.root?.let { editableAtPoint(it, p.x.toInt(), p.y.toInt()) }
                if (hit != null) return hit
            }
        }
        return null
    }

    /** Deepest editable node whose on-screen bounds contain (x, y). */
    private fun editableAtPoint(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.contains(x, y)) return null
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> editableAtPoint(child, x, y)?.let { return it } }
        }
        return if (node.isEditable) node else null
    }

    // ---- displays ----

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshDisplays()
        override fun onDisplayRemoved(displayId: Int) = refreshDisplays()
        override fun onDisplayChanged(displayId: Int) = refreshDisplays()
    }

    private fun refreshDisplays() {
        val external = resolveExternal()
        targetDisplayId = external?.id ?: INVALID
        if (external != null) {
            cursor.setBounds(external.id, Bounds(external.width.toFloat(), external.height.toFloat()))
            sink.setDisplayBounds(external.width.toFloat(), external.height.toFloat())
            displayManager.getDisplay(external.id)?.let { overlay.attach(it) }
            // Do NOT re-centre here — setBounds preserves the current position across refreshes.
            val p = cursor.positionFor(external.id)
            overlay.moveTo(p.x, p.y)
        } else {
            overlay.detach()
        }
        notifyState()
    }

    private fun resolveExternal(): DisplayInfo? =
        resolver.pickExternal(
            displayManager.displays.map { d ->
                val m = DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
                DisplayInfo(d.displayId, m.widthPixels, m.heightPixels, d.displayId == Display.DEFAULT_DISPLAY)
            }
        )

    private fun currentState() = State(if (targetDisplayId == INVALID) null else resolveExternal(), lock.isLocked)

    private fun notifyState() {
        val s = currentState()
        main.post { onState?.invoke(s) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val INVALID = -1
        private const val IME_ENTER_RETRY_MS = 180L
        private val SUBMIT_HINTS = listOf("send", "search", "go", "submit", "post", "reply", "return")

        /** Set while the service is connected; the UI talks to it through this. */
        @Volatile
        var instance: DeskPadA11yService? = null
            private set
    }
}
