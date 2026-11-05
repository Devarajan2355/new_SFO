package com.sanya.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiColorTest {

    @Test
    fun withAlphaKeepsTheColour() {
        assertEquals(0x80123456.toInt(), Ui.withAlpha(0xFF123456.toInt(), 0x80))
    }

    @Test
    fun mixEndsAndMiddle() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(black, Ui.mix(black, white, 0f))
        assertEquals(white, Ui.mix(black, white, 1f))
        assertEquals(0xFF808080.toInt(), Ui.mix(black, white, 0.5f))
    }

    @Test
    fun farNoticesARealDifference() {
        assertFalse(Ui.far(0xFF102030.toInt(), 0xFF102534.toInt(), 10))
        assertTrue(Ui.far(0xFF102030.toInt(), 0xFF402030.toInt(), 10))
    }
}
