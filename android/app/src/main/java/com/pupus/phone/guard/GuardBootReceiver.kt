package com.pupus.phone.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/** BOOT_COMPLETED and MY_PACKAGE_REPLACED: runs the accessibility guard once the system had time to bind. */
class GuardBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.w(TAG, "Ignoring unexpected broadcast $action")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                AccessibilityGuard.check(app, action.substringAfterLast('.'))
            } finally {
                pending.finish()
            }
        }, BIND_GRACE_MS)
    }

    private companion object {
        const val TAG = "PupusGuard"
        // goAsync() allows about 10 s: wait for the normal bind, then judge.
        const val BIND_GRACE_MS = 6_000L
    }
}
