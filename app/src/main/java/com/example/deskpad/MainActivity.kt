package com.example.deskpad

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Option A — full-bleed trackpad. Touches drive [DeskPadA11yService] (cursor + gestures on the
 * external display).
 *
 * Keyboard: on-screen keys (Buttons, which never take input focus, so the desktop field you clicked
 * stays selected) that edit that focused field's text via the accessibility service.
 */
class MainActivity : Activity() {

    private lateinit var touchpad: TouchpadView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var lockButton: Button
    private lateinit var lockOverlay: FrameLayout
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var kbStatus: TextView
    private lateinit var statusBar: LinearLayout
    private lateinit var rowsContainer: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var keyPreview: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = Runnable { pollForService() }
    private var unlockHold: Runnable? = null
    private var wiredService: DeskPadA11yService? = null
    private var shiftOn = false
    private var page = LETTERS // LETTERS, SYMBOLS, or MORE_SYMBOLS
    private var shiftKey: Button? = null

    private val service get() = DeskPadA11yService.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollRunnable)
        pollForService()
    }

    override fun onPause() {
        super.onPause()
        wiredService?.onState = null
        wiredService = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun pollForService() {
        val svc = service
        if (svc !== wiredService) {
            wiredService?.onState = null
            wiredService = svc
            if (svc != null) {
                touchpad.onPadFrame = { service?.submitPadFrame(it) }
                svc.onState = { renderState(it) }
            }
        }
        if (svc == null) {
            statusDot.setBackgroundColor(WARN)
            statusText.text = "Tap here to enable DeskPad in Accessibility"
        }
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    private fun renderState(s: DeskPadA11yService.State) {
        val ready = s.external != null
        statusDot.setBackgroundColor(if (ready) GOOD else WARN)
        statusText.text = when {
            s.external == null -> "Enabled · connect a display (Desktop mode)"
            else -> "Ready · display ${s.external.id} · ${s.external.width}×${s.external.height}"
        }
        lockButton.text = if (s.locked) "🔒" else "🔓"
        lockButton.setTextColor(if (s.locked) WARN else MUTED)
        lockOverlay.visibility = if (s.locked) View.VISIBLE else View.GONE
    }

    private fun openAccessibilitySettings() {
        if (service == null) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Enable “DeskPad”, then return here", Toast.LENGTH_LONG).show()
        }
    }

    // ---- keyboard ----

    private fun locked() = service?.isLocked == true

    private fun toggleKeyboard() {
        val show = keyboardPanel.visibility != View.VISIBLE
        keyboardPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) refreshKbStatus()
    }

    private fun onKey(c: Char) {
        if (locked()) return
        val ch = if (shiftOn && c in 'a'..'z') c.uppercaseChar() else c
        service?.typeText(ch.toString())
        if (shiftOn) { shiftOn = false; refreshShift() }
        refreshKbStatus()
    }

    /** Shows what the accessibility service can actually see — the key on-device signal. */
    private fun refreshKbStatus() {
        if (!::kbStatus.isInitialized) return
        val svc = service
        if (svc == null) { setKb("enable DeskPad accessibility first", WARN); return }
        val diag = svc.keyboardDiagnostics()
        setKb(diag, if (diag.startsWith("●")) GOOD else WARN)
    }

    private fun setKb(text: String, color: Int) {
        kbStatus.text = text; kbStatus.setTextColor(color)
    }

    private fun buildKeyboardPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090D12.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(12))
            visibility = View.GONE
        }
        kbStatus = TextView(this).apply {
            text = "click a desktop text field first"
            setTextColor(MUTED); textSize = 11f; typeface = Typeface.MONOSPACE
            setPadding(dp(6), 0, 0, dp(2))
        }
        panel.addView(kbStatus)
        rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(rowsContainer)
        populateRows()
        panel.addView(actionsRow())
        return panel
    }

    private fun populateRows() {
        rowsContainer.removeAllViews()
        letterKeys.clear()
        shiftKey = null
        when (page) {
            SYMBOLS -> {
                rowsContainer.addView(keyRow("1234567890"))
                rowsContainer.addView(keyRow("@#\$_&-+()/"))
                rowsContainer.addView(row {
                    addView(specialKey("=\\<", 1.5f) { page = MORE_SYMBOLS; populateRows() })
                    for (c in "*\"':;!?") addView(charKey(c))
                    addView(backspaceKey())
                })
            }
            MORE_SYMBOLS -> {
                rowsContainer.addView(keyRow("~`|•√π÷×¶∆"))
                rowsContainer.addView(keyRow("£¢€¥^°={}"))
                rowsContainer.addView(row {
                    addView(specialKey("?123", 1.5f) { page = SYMBOLS; populateRows() })
                    for (c in "\\©®™℅[]") addView(charKey(c))
                    addView(backspaceKey())
                })
            }
            else -> { // LETTERS — Gboard's default layout (no persistent number row)
                rowsContainer.addView(keyRow("qwertyuiop"))
                rowsContainer.addView(centeredKeyRow("asdfghjkl"))
                rowsContainer.addView(row {
                    shiftKey = specialKey("⇧", 1.5f) { shiftOn = !shiftOn; refreshShift() }
                    addView(shiftKey)
                    for (c in "zxcvbnm") addView(charKey(c))
                    addView(backspaceKey())
                })
            }
        }
        rowsContainer.addView(bottomRow())
        refreshShift()
    }

    /** Gboard-style bottom row: mode-switch · comma · wide space · period · enter. */
    private fun bottomRow() = row {
        if (page == LETTERS) {
            addView(specialKey("?123", 1.5f) { page = SYMBOLS; shiftOn = false; populateRows() })
        } else {
            addView(specialKey("ABC", 1.5f) { page = LETTERS; populateRows() })
        }
        addView(charKey(if (page == MORE_SYMBOLS) '<' else ','))
        addView(specialKey("space", 5f) { onKey(' ') })
        addView(charKey(if (page == MORE_SYMBOLS) '>' else '.'))
        addView(specialKey("⏎", 1.5f) { if (!locked()) service?.enter() })
    }

    private fun backspaceKey() = specialKey("⌫", 1.5f) { if (!locked()) service?.backspace() }

    private fun actionsRow() = row {
        addView(specialKey("Copy", 1f) { if (!locked()) service?.editAction(AccessibilityNodeInfo.ACTION_COPY) })
        addView(specialKey("Paste", 1f) { if (!locked()) service?.editAction(AccessibilityNodeInfo.ACTION_PASTE) })
        addView(specialKey("Cut", 1f) { if (!locked()) service?.editAction(AccessibilityNodeInfo.ACTION_CUT) })
        addView(specialKey("All", 1f) { if (!locked()) service?.selectAll() })
    }

    private val letterKeys = mutableListOf<Button>()

    private fun keyRow(chars: String) = row { for (c in chars) addView(charKey(c)) }

    /** Like [keyRow] but inset by half a key on each side, so a 9-key row centers under a 10-key one. */
    private fun centeredKeyRow(chars: String) = row {
        addView(spacer(0.5f))
        for (c in chars) addView(charKey(c))
        addView(spacer(0.5f))
    }

    // Space (not a bare View) shrink-wraps to zero height; a plain View would expand to fill the
    // row's available height and stretch the whole panel.
    private fun spacer(weight: Float) = android.widget.Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP, weight)
    }

    private fun row(block: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        block()
    }

    private fun charKey(c: Char): Button {
        val b = Button(this).apply {
            text = c.toString(); textSize = 13f; setTextColor(TEXT); isAllCaps = false
            setBackgroundColor(0xFF1B2531.toInt())
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(3) }
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { onKey(c) }
            // Gboard-style feedback: haptic + a magnified character bubble while the key is held.
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { hapticTap(v); showKeyPreview(v, displayLabel(c)) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hideKeyPreview()
                }
                false // don't consume — let the button fire its click (the actual keystroke)
            }
        }
        if (c in 'a'..'z') letterKeys.add(b)
        return b
    }

    /** The glyph shown in the preview bubble — uppercased while shift is engaged. */
    private fun displayLabel(c: Char) =
        if (shiftOn && c in 'a'..'z') c.uppercaseChar().toString() else c.toString()

    private fun specialKey(label: String, weight: Float, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 12f; setTextColor(MUTED); isAllCaps = false
        setBackgroundColor(0xFF141B24.toInt())
        layoutParams = LinearLayout.LayoutParams(0, WRAP, weight).apply { marginEnd = dp(3) }
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { v -> hapticTap(v); onClick() }
    }

    private fun hapticTap(v: View) {
        v.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )
    }

    // ---- key-preview bubble (Gboard-style) ----

    private fun makeKeyPreview() = TextView(this).apply {
        setTextColor(TEXT); textSize = 26f; isAllCaps = false; gravity = Gravity.CENTER
        setPadding(dp(16), dp(6), dp(16), dp(10))
        visibility = View.GONE
        elevation = dp(8).toFloat()
        background = GradientDrawable().apply {
            setColor(0xFF2A3646.toInt()); cornerRadius = dp(10).toFloat()
            setStroke(dp(1), 0xFF3D4C5E.toInt())
        }
        layoutParams = FrameLayout.LayoutParams(WRAP, WRAP)
    }

    private fun showKeyPreview(anchor: View, label: String) {
        keyPreview.text = label
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        keyPreview.measure(spec, spec)
        val a = IntArray(2); anchor.getLocationOnScreen(a)
        val r = IntArray(2); root.getLocationOnScreen(r)
        (keyPreview.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (a[0] - r[0] + anchor.width / 2 - keyPreview.measuredWidth / 2).coerceAtLeast(dp(2))
            topMargin = (a[1] - r[1] - keyPreview.measuredHeight - dp(6)).coerceAtLeast(dp(2))
        }
        keyPreview.requestLayout()
        keyPreview.visibility = View.VISIBLE
    }

    private fun hideKeyPreview() {
        if (::keyPreview.isInitialized) keyPreview.visibility = View.GONE
    }

    private fun refreshShift() {
        letterKeys.forEach { it.text = if (shiftOn) it.text.toString().uppercase() else it.text.toString().lowercase() }
        // Filled key when shift is engaged, outline when not — the Gboard cue.
        shiftKey?.apply {
            text = if (shiftOn) "⬆" else "⇧"
            setBackgroundColor(if (shiftOn) SHIFT_ON else 0xFF141B24.toInt())
        }
    }

    // ---- UI scaffold ----

    private fun buildUi(): View {
        root = FrameLayout(this).apply { setBackgroundColor(APP_BG) }

        touchpad = TouchpadView(this).apply { setBackgroundColor(PAD_BG) }
        root.addView(touchpad, FrameLayout.LayoutParams(MATCH, MATCH))

        statusBar = buildStatusBar()
        root.addView(statusBar, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.TOP })

        keyboardPanel = buildKeyboardPanel()
        root.addView(keyboardPanel, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM })

        // Above the keyboard panel so the bubble is never clipped by it; below the lock overlay.
        keyPreview = makeKeyPreview()
        root.addView(keyPreview)

        lockOverlay = buildLockOverlay()
        root.addView(lockOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // Inset the top bar below the status bar / cutout, and the keyboard panel above the
        // navigation bar (3-button or gesture), so its bottom row is never hidden.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.getInsets(WindowInsets.Type.statusBars()).top
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            statusBar.setPadding(dp(14), top + dp(8), dp(14), dp(10))
            keyboardPanel.setPadding(dp(8), dp(8), dp(8), dp(12) + nav)
            insets
        }
        return root
    }

    private fun buildStatusBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            setOnClickListener { openAccessibilitySettings() }
        }
        statusDot = View(this)
        bar.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        statusText = TextView(this).apply {
            text = "Starting…"; setTextColor(TEXT); textSize = 12f; typeface = Typeface.MONOSPACE
        }
        bar.addView(statusText, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(iconButton("⌨") { toggleKeyboard() })
        lockButton = iconButton("🔓") { service?.setLocked(true) }
        bar.addView(lockButton)
        return bar
    }

    private fun buildLockOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xD1080C12.toInt()); visibility = View.GONE; isClickable = true
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(TextView(this).apply {
            text = "🔒 Touchpad locked"; setTextColor(TEXT); textSize = 16f; gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "no input reaches the desktop"
            setTextColor(MUTED); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })
        col.addView(Button(this).apply {
            text = "Hold to unlock"; setTextColor(TEXT); setBackgroundColor(APP_SURFACE)
            setOnTouchListener { _, e -> onUnlockTouch(e); true }
        })
        overlay.addView(col, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return overlay
    }

    private fun onUnlockTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                unlockHold = Runnable { service?.setLocked(false) }
                handler.postDelayed(unlockHold!!, UNLOCK_HOLD_MS)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                unlockHold?.let { handler.removeCallbacks(it) }
        }
    }

    private fun iconButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setTextColor(MUTED); textSize = 16f
        setBackgroundColor(Color.TRANSPARENT); minWidth = dp(44)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val UNLOCK_HOLD_MS = 600L
        const val POLL_MS = 1500L
        const val LETTERS = 0
        const val SYMBOLS = 1
        const val MORE_SYMBOLS = 2
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        val APP_BG = 0xFF0D1117.toInt()
        val PAD_BG = 0xFF0B0F15.toInt()
        val APP_SURFACE = 0xFF151C26.toInt()
        val TEXT = 0xFFD7DEE7.toInt()
        val MUTED = 0xFF7D8894.toInt()
        val WARN = 0xFFD29922.toInt()
        val GOOD = 0xFF3FB950.toInt()
        val SHIFT_ON = 0xFF2D3B4E.toInt()
    }
}
