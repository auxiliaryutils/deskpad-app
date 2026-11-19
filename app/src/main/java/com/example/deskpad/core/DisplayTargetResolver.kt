package com.example.deskpad.core

/** Framework-free description of a display, produced from `DisplayManager` in the Android layer. */
data class DisplayInfo(
    val id: Int,
    val width: Int,
    val height: Int,
    val isDefault: Boolean,
    val isDesktop: Boolean = true,
)

/** Chooses which display to drive: the external (non-default) desktop one. Pure. */
class DisplayTargetResolver {
    /** The external desktop display to target, or null if none is connected or if mirrored. */
    fun pickExternal(displays: List<DisplayInfo>): DisplayInfo? =
        displays.firstOrNull { !it.isDefault && it.isDesktop }
}
