package com.example.deskpad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputPipelineTest {

    /** Records InputSink calls — the injection interface mocked in tests. */
    private class FakeInputSink : InputSink {
        data class Call(val name: String, val args: List<Any?>)
        val calls = mutableListOf<Call>()
        override fun moveCursor(displayId: Int, x: Float, y: Float) {
            calls += Call("moveCursor", listOf(displayId, x, y))
        }
        override fun button(displayId: Int, x: Float, y: Float, primary: Boolean, down: Boolean) {
            calls += Call("button", listOf(displayId, x, y, primary, down))
        }
        override fun scroll(displayId: Int, x: Float, y: Float, hScroll: Float, vScroll: Float) {
            calls += Call("scroll", listOf(displayId, x, y, hScroll, vScroll))
        }
        override fun key(displayId: Int, keyCode: Int, metaState: Int, down: Boolean) {
            calls += Call("key", listOf(displayId, keyCode, metaState, down))
        }
    }

    private fun build(lock: LockController): Pair<InputPipeline, FakeInputSink> {
        val sink = FakeInputSink()
        val cursor = CursorController().apply { setBounds(2, Bounds(1000f, 1000f)) }
        return InputPipeline(sink, cursor, lock) to sink
    }

    @Test fun moveForwardsToCursor() {
        val (pipeline, sink) = build(LockController())
        pipeline.dispatch(2, GestureAction.Move(10f, 0f))
        assertEquals(1, sink.calls.count { it.name == "moveCursor" })
    }

    @Test fun leftClickIsButtonDownThenUp() {
        val (pipeline, sink) = build(LockController())
        pipeline.dispatch(2, GestureAction.LeftClick)
        val downs = sink.calls.filter { it.name == "button" }.map { it.args[4] }
        assertEquals(listOf(true, false), downs)
    }

    @Test fun scrollForwards() {
        val (pipeline, sink) = build(LockController())
        pipeline.dispatch(2, GestureAction.Scroll(0f, 20f))
        assertEquals(1, sink.calls.count { it.name == "scroll" })
    }

    @Test fun lockedDropsEveryAction() {
        val lock = LockController().apply { lock() }
        val (pipeline, sink) = build(lock)
        pipeline.dispatch(2, GestureAction.Move(10f, 0f))
        pipeline.dispatch(2, GestureAction.LeftClick)
        pipeline.dispatch(2, GestureAction.Scroll(0f, 20f))
        assertTrue("locked pipeline must not call the sink", sink.calls.isEmpty())
    }
}
