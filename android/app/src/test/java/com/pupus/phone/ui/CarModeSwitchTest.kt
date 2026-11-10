package com.pupus.phone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CarModeSwitchTest {

    @Test
    fun userToggleStartsOrStopsTheService() {
        val switch = CarModeSwitch()
        assertEquals(CarModeAction.START, switch.onCheckedChanged(isChecked = true, isRunning = false))
        assertEquals(CarModeAction.STOP, switch.onCheckedChanged(isChecked = false, isRunning = true))
        assertEquals(CarModeAction.NONE, switch.onCheckedChanged(isChecked = true, isRunning = true))
        assertEquals(CarModeAction.NONE, switch.onCheckedChanged(isChecked = false, isRunning = false))
    }

    @Test
    fun renderingNeverStartsOrStopsTheService() {
        val switch = CarModeSwitch()
        val actions = mutableListOf<CarModeAction>()
        switch.rendering {
            actions += switch.onCheckedChanged(isChecked = false, isRunning = true)
            actions += switch.onCheckedChanged(isChecked = true, isRunning = false)
        }
        assertEquals(listOf(CarModeAction.NONE, CarModeAction.NONE), actions)
        assertFalse(switch.isRendering)
    }

    @Test
    fun guardIsReleasedWhenRenderThrows() {
        val switch = CarModeSwitch()
        try {
            switch.rendering { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertFalse(switch.isRendering)
        assertEquals(CarModeAction.START, switch.onCheckedChanged(isChecked = true, isRunning = false))
    }
}
