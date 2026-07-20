package com.example.deskpad.core

/**
 * The seam between pure logic and privileged injection. The real implementation forwards over
 * AIDL to the Shizuku `Injector`; unit tests use a fake that records calls.
 */
interface InputSink {
    fun moveCursor(displayId: Int, x: Float, y: Float)
    fun button(displayId: Int, x: Float, y: Float, primary: Boolean, down: Boolean)
    fun scroll(displayId: Int, x: Float, y: Float, hScroll: Float, vScroll: Float)
    fun key(displayId: Int, keyCode: Int, metaState: Int, down: Boolean)
}
