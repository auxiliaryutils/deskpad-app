package com.example.deskpad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayTargetResolverTest {

    private val r = DisplayTargetResolver()
    private val phone = DisplayInfo(id = 0, width = 1080, height = 2400, isDefault = true)

    @Test fun picksNonDefaultDisplay() {
        val external = DisplayInfo(id = 2, width = 1920, height = 1080, isDefault = false)
        assertEquals(external, r.pickExternal(listOf(phone, external)))
    }

    @Test fun nullWhenOnlyDefaultPresent() {
        assertNull(r.pickExternal(listOf(phone)))
    }

    @Test fun nullWhenEmpty() {
        assertNull(r.pickExternal(emptyList()))
    }

    @Test fun picksFirstExternalWhenMultiple() {
        val first = DisplayInfo(id = 2, width = 1920, height = 1080, isDefault = false)
        val second = DisplayInfo(id = 3, width = 1280, height = 720, isDefault = false)
        assertEquals(first, r.pickExternal(listOf(phone, first, second)))
    }
}
