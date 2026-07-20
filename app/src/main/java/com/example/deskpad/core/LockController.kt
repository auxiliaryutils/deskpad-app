package com.example.deskpad.core

/**
 * Client-side lock gate that sits ABOVE the Shizuku boundary (SPEC §3.1).
 * While locked, [gate] drops every action so nothing reaches the privileged process.
 */
class LockController {
    private var locked = false

    val isLocked: Boolean get() = locked

    fun lock() { locked = true }

    fun unlock() { locked = false }

    /** Returns [actions] unchanged when unlocked; an empty list when locked. */
    fun <T> gate(actions: List<T>): List<T> = if (locked) emptyList() else actions
}
