package com.example.deskpad.core

/**
 * Android keycode + meta-state values, kept as plain Ints so this stays framework-free and
 * unit-testable. Values mirror `android.view.KeyEvent`; the Injector uses them directly.
 */
object AndroidKey {
    const val A = 29; const val B = 30; const val C = 31
    const val ZERO = 7
    const val ENTER = 66; const val DEL = 67; const val TAB = 61; const val SPACE = 62
    const val DPAD_UP = 19; const val DPAD_DOWN = 20; const val DPAD_LEFT = 21; const val DPAD_RIGHT = 22
    const val SHIFT_LEFT = 59; const val CTRL_LEFT = 113; const val ALT_LEFT = 57
    const val COMMA = 55; const val PERIOD = 56; const val MINUS = 69; const val SLASH = 76

    const val META_SHIFT_ON = 0x1
    const val META_ALT_ON = 0x2
    const val META_CTRL_ON = 0x1000
}

enum class Modifier { CTRL, ALT, SHIFT }
enum class NamedKey { ENTER, BACKSPACE, TAB, LEFT, RIGHT, UP, DOWN }

/** One key transition to inject: a keycode, the meta-state in effect, and up/down. */
data class KeyStroke(val keyCode: Int, val metaState: Int, val down: Boolean)

/**
 * Converts text / named keys / modifier combos into ordered [KeyStroke]s (v1: ASCII + control
 * and navigation keys + Ctrl/Alt/Shift combos). Pure.
 */
class KeyMapper {

    /** Printable ASCII → strokes; uppercase/shifted chars are wrapped in Shift. */
    fun forText(text: CharSequence): List<KeyStroke> {
        val out = mutableListOf<KeyStroke>()
        for (c in text) {
            val (code, shifted) = mapChar(c) ?: continue
            if (shifted) {
                out += KeyStroke(AndroidKey.SHIFT_LEFT, AndroidKey.META_SHIFT_ON, down = true)
                out += KeyStroke(code, AndroidKey.META_SHIFT_ON, down = true)
                out += KeyStroke(code, AndroidKey.META_SHIFT_ON, down = false)
                out += KeyStroke(AndroidKey.SHIFT_LEFT, 0, down = false)
            } else {
                out += KeyStroke(code, 0, down = true)
                out += KeyStroke(code, 0, down = false)
            }
        }
        return out
    }

    /** A named control/navigation key → down+up strokes. */
    fun forNamedKey(key: NamedKey): List<KeyStroke> {
        val code = when (key) {
            NamedKey.ENTER -> AndroidKey.ENTER
            NamedKey.BACKSPACE -> AndroidKey.DEL
            NamedKey.TAB -> AndroidKey.TAB
            NamedKey.LEFT -> AndroidKey.DPAD_LEFT
            NamedKey.RIGHT -> AndroidKey.DPAD_RIGHT
            NamedKey.UP -> AndroidKey.DPAD_UP
            NamedKey.DOWN -> AndroidKey.DPAD_DOWN
        }
        return listOf(KeyStroke(code, 0, down = true), KeyStroke(code, 0, down = false))
    }

    /** A shortcut like Ctrl+C → modifier down(s), key down, key up, modifier up(s). */
    fun forCombo(modifiers: Set<Modifier>, keyCode: Int): List<KeyStroke> {
        val order = MODIFIER_ORDER.filter { it in modifiers }
        val out = mutableListOf<KeyStroke>()
        var meta = 0
        for (mod in order) {
            meta = meta or metaBit(mod)
            out += KeyStroke(keyCodeOf(mod), meta, down = true)
        }
        out += KeyStroke(keyCode, meta, down = true)
        out += KeyStroke(keyCode, meta, down = false)
        for (mod in order.asReversed()) {
            meta = meta and metaBit(mod).inv()
            out += KeyStroke(keyCodeOf(mod), meta, down = false)
        }
        return out
    }

    private fun mapChar(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> (AndroidKey.A + (c - 'a')) to false
        in 'A'..'Z' -> (AndroidKey.A + (c - 'A')) to true
        in '0'..'9' -> (AndroidKey.ZERO + (c - '0')) to false
        ' ' -> AndroidKey.SPACE to false
        '\n' -> AndroidKey.ENTER to false
        '\t' -> AndroidKey.TAB to false
        ',' -> AndroidKey.COMMA to false
        '.' -> AndroidKey.PERIOD to false
        '-' -> AndroidKey.MINUS to false
        '/' -> AndroidKey.SLASH to false
        else -> null
    }

    private fun metaBit(mod: Modifier) = when (mod) {
        Modifier.CTRL -> AndroidKey.META_CTRL_ON
        Modifier.ALT -> AndroidKey.META_ALT_ON
        Modifier.SHIFT -> AndroidKey.META_SHIFT_ON
    }

    private fun keyCodeOf(mod: Modifier) = when (mod) {
        Modifier.CTRL -> AndroidKey.CTRL_LEFT
        Modifier.ALT -> AndroidKey.ALT_LEFT
        Modifier.SHIFT -> AndroidKey.SHIFT_LEFT
    }

    private companion object {
        val MODIFIER_ORDER = listOf(Modifier.CTRL, Modifier.ALT, Modifier.SHIFT)
    }
}
