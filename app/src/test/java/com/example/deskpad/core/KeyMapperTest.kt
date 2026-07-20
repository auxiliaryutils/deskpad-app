package com.example.deskpad.core

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMapperTest {

    private val m = KeyMapper()

    @Test fun lowercaseLetterIsSingleKey() {
        assertEquals(
            listOf(
                KeyStroke(AndroidKey.A, 0, down = true),
                KeyStroke(AndroidKey.A, 0, down = false),
            ),
            m.forText("a"),
        )
    }

    @Test fun uppercaseLetterIsWrappedInShift() {
        assertEquals(
            listOf(
                KeyStroke(AndroidKey.SHIFT_LEFT, AndroidKey.META_SHIFT_ON, down = true),
                KeyStroke(AndroidKey.A, AndroidKey.META_SHIFT_ON, down = true),
                KeyStroke(AndroidKey.A, AndroidKey.META_SHIFT_ON, down = false),
                KeyStroke(AndroidKey.SHIFT_LEFT, 0, down = false),
            ),
            m.forText("A"),
        )
    }

    @Test fun multipleCharsConcatenate() {
        assertEquals(4, m.forText("ab").size)
    }

    @Test fun newlineMapsToEnter() {
        assertEquals(
            listOf(
                KeyStroke(AndroidKey.ENTER, 0, down = true),
                KeyStroke(AndroidKey.ENTER, 0, down = false),
            ),
            m.forText("\n"),
        )
    }

    @Test fun namedBackspaceMapsToDel() {
        assertEquals(
            listOf(
                KeyStroke(AndroidKey.DEL, 0, down = true),
                KeyStroke(AndroidKey.DEL, 0, down = false),
            ),
            m.forNamedKey(NamedKey.BACKSPACE),
        )
    }

    @Test fun ctrlComboWrapsModifier() {
        assertEquals(
            listOf(
                KeyStroke(AndroidKey.CTRL_LEFT, AndroidKey.META_CTRL_ON, down = true),
                KeyStroke(AndroidKey.C, AndroidKey.META_CTRL_ON, down = true),
                KeyStroke(AndroidKey.C, AndroidKey.META_CTRL_ON, down = false),
                KeyStroke(AndroidKey.CTRL_LEFT, 0, down = false),
            ),
            m.forCombo(setOf(Modifier.CTRL), AndroidKey.C),
        )
    }
}
