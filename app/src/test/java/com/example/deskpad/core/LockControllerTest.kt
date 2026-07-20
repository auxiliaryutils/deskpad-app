package com.example.deskpad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockControllerTest {

    @Test fun startsUnlocked() {
        assertFalse(LockController().isLocked)
    }

    @Test fun lockSetsLocked() {
        val c = LockController()
        c.lock()
        assertTrue(c.isLocked)
    }

    @Test fun gatePassesActionsWhenUnlocked() {
        val c = LockController()
        assertEquals(listOf("a", "b"), c.gate(listOf("a", "b")))
    }

    @Test fun gateBlocksActionsWhenLocked() {
        val c = LockController()
        c.lock()
        assertTrue(c.gate(listOf("a", "b")).isEmpty())
    }

    @Test fun unlockRestoresForwarding() {
        val c = LockController()
        c.lock()
        c.unlock()
        assertEquals(listOf("x"), c.gate(listOf("x")))
    }
}
