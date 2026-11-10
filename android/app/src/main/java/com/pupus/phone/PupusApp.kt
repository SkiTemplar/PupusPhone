package com.pupus.phone

import android.app.Application
import com.pupus.phone.guard.AccessibilityGuard

/** Process start: after a ROM kill the accessibility service is not rebound, so the guard checks it. */
class PupusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Delayed: when the system itself starts the process to bind the service, let that bind finish.
        AccessibilityGuard.checkLater(this, "process-start", START_DELAY_MS)
    }

    private companion object {
        const val START_DELAY_MS = 5_000L
    }
}
