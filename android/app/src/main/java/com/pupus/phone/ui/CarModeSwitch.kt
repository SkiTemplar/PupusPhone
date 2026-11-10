package com.pupus.phone.ui

/** What a change of the car-mode switch must do to the voice service. */
enum class CarModeAction { START, STOP, NONE }

/**
 * Tells user toggles apart from the UI syncing the switch with the service
 * state: only the former may start or stop car mode.
 */
class CarModeSwitch {
    private var depth = 0

    val isRendering: Boolean get() = depth > 0

    /** Runs [block] with switch changes treated as programmatic. */
    fun rendering(block: () -> Unit) {
        depth++
        try {
            block()
        } finally {
            depth--
        }
    }

    fun onCheckedChanged(isChecked: Boolean, isRunning: Boolean): CarModeAction = when {
        isRendering -> CarModeAction.NONE
        isChecked && !isRunning -> CarModeAction.START
        !isChecked && isRunning -> CarModeAction.STOP
        else -> CarModeAction.NONE
    }
}
