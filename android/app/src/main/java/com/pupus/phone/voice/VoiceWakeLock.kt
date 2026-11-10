package com.pupus.phone.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * CPU-only wake lock for car mode. It keeps a timeout as a safety net but is
 * re-armed periodically, so it never expires while the service runs. The screen
 * is kept on by FLAG_KEEP_SCREEN_ON on the activity and the overlay.
 */
class VoiceWakeLock(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val lock: PowerManager.WakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)
        .apply { setReferenceCounted(false) }
    private var isActive = false

    /** A non-reference-counted acquire on a held lock only restarts its timeout. */
    private val renew = object : Runnable {
        override fun run() {
            lock.acquire(TIMEOUT_MS)
            main.postDelayed(this, RENEW_MS)
        }
    }

    fun acquire() {
        if (isActive) return
        isActive = true
        renew.run()
    }

    fun release() {
        main.removeCallbacks(renew)
        isActive = false
        if (lock.isHeld) lock.release()
    }

    private companion object {
        const val LOCK_TAG = "PupusPhone:voice"
        const val TIMEOUT_MS = 2L * 60 * 60 * 1000
        const val RENEW_MS = 60L * 60 * 1000
    }
}
