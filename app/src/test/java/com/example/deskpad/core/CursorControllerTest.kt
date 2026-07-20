package com.example.deskpad.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CursorControllerTest {

    private fun centered() = CursorController().apply { setBounds(2, Bounds(1000f, 500f)) }

    @Test fun initialPositionIsCentre() {
        assertEquals(Point(500f, 250f), centered().positionFor(2))
    }

    @Test fun moveAppliesDelta() {
        assertEquals(Point(510f, 240f), centered().move(2, 10f, -10f))
    }

    @Test fun clampsToLeftTopEdge() {
        assertEquals(Point(0f, 0f), centered().move(2, -9999f, -9999f))
    }

    @Test fun clampsToRightBottomEdge() {
        assertEquals(Point(1000f, 500f), centered().move(2, 9999f, 9999f))
    }

    @Test fun sensitivityScalesDelta() {
        val c = CursorController(2f).apply { setBounds(2, Bounds(1000f, 500f)) }
        assertEquals(Point(520f, 250f), c.move(2, 10f, 0f))
    }

    @Test fun reRegisteringSameBoundsKeepsPosition() {
        val c = centered()
        c.move(2, 100f, -30f) // -> (600, 220)
        c.setBounds(2, Bounds(1000f, 500f)) // same bounds again (a routine display refresh)
        assertEquals(Point(600f, 220f), c.positionFor(2))
    }

    @Test fun changedBoundsClampsExistingPosition() {
        val c = centered()
        c.move(2, 400f, 200f) // -> (900, 450)
        c.setBounds(2, Bounds(600f, 300f)) // display got smaller
        assertEquals(Point(600f, 300f), c.positionFor(2))
    }

    @Test fun displaysTrackIndependently() {
        val c = CursorController().apply {
            setBounds(1, Bounds(100f, 100f))
            setBounds(2, Bounds(1000f, 1000f))
        }
        c.move(1, 10f, 10f)
        assertEquals(Point(500f, 500f), c.positionFor(2))
    }
}
