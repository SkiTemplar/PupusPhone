package com.pupus.phone.guard

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.pupus.phone.AppSettings
import com.pupus.phone.PhoneAccessibilityService

/** Last guard run, for the "Estado" card. [repairedAtMs] is elapsedRealtime of the last write. */
data class GuardReport(val outcome: A11yOutcome, val repairedAtMs: Long?)

/**
 * Self-heal for the accessibility service. When the ROM kills the process (HyperOS "SwipeUpClean")
 * Android lists the service under "Crashed services" and never rebinds it; with WRITE_SECURE_SETTINGS
 * (granted once over adb) rewriting ENABLED_ACCESSIBILITY_SERVICES forces the rebind.
 * Main thread only. Never loops: runs on explicit triggers and at most once per 10 s.
 */
object AccessibilityGuard {
    const val GRANT_COMMAND = "adb shell pm grant com.pupus.phone android.permission.WRITE_SECURE_SETTINGS"
    private const val TAG = "PupusGuard"
    private const val REWRITE_DELAY_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    private var lastAttemptMs: Long? = null
    private var repairedAtMs: Long? = null

    @Volatile
    var lastReport: GuardReport? = null
        private set

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Live instance, or bound according to the system (the instance is null in a fresh process). */
    fun isRunning(context: Context): Boolean {
        if (PhoneAccessibilityService.instance != null) return true
        val id = component(context).flattenToShortString()
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.id == id }
    }

    /** Lets a bind that is already under way (process start, boot) finish before judging. */
    fun checkLater(context: Context, trigger: String, delayMs: Long) {
        val app = context.applicationContext
        main.postDelayed({ check(app, trigger) }, delayMs)
    }

    /** Dry run for the "Estado" card: what [check] would decide now, without writing anything. */
    fun evaluate(context: Context): A11yOutcome {
        val app = context.applicationContext
        return policy(app).decide(snapshot(app)).outcome
    }

    /** Milliseconds since the last successful repair write, null if none in this process. */
    fun repairedAgoMs(): Long? = repairedAtMs?.let { SystemClock.elapsedRealtime() - it }

    fun check(context: Context, trigger: String): GuardReport {
        val app = context.applicationContext
        val snapshot = snapshot(app)
        val decision = policy(app).decide(snapshot)
        if (decision.outcome != A11yOutcome.RUNNING) Log.i(TAG, "[$trigger] accessibility ${decision.outcome}")
        if (decision.isRepair) apply(app, decision, snapshot.nowMs)
        return GuardReport(decision.outcome, repairedAtMs).also { lastReport = it }
    }

    private fun snapshot(app: Context): A11ySnapshot {
        val resolver = app.contentResolver
        return A11ySnapshot(
            enabledServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            isAccessibilityEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1,
            isRunning = isRunning(app),
            isWanted = AppSettings(app).isA11yWanted,
            canWriteSecureSettings = hasPermission(app),
            nowMs = SystemClock.elapsedRealtime(),
            lastAttemptMs = lastAttemptMs,
        )
    }

    private fun apply(context: Context, decision: A11yDecision, nowMs: Long) {
        lastAttemptMs = nowMs
        val first = decision.writes.first()
        if (!write(context, first)) return
        repairedAtMs = nowMs
        val rest = decision.writes.drop(1)
        // A second write in the same instant may be coalesced by the settings observer: no rebind.
        if (rest.isEmpty()) finishEnable(context) else main.postDelayed({ writeAll(context, rest) }, REWRITE_DELAY_MS)
    }

    private fun writeAll(context: Context, values: List<String>) {
        if (values.all { write(context, it) }) {
            finishEnable(context)
            return
        }
        // Our component may now be missing: the next trigger re-adds it (ENABLE) after the rate limit.
        Log.e(TAG, "Rebind left ENABLED_ACCESSIBILITY_SERVICES incomplete")
    }

    private fun finishEnable(context: Context) {
        try {
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot set ACCESSIBILITY_ENABLED", e)
        }
    }

    private fun write(context: Context, value: String): Boolean = try {
        val isWritten = Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
        Log.i(TAG, "ENABLED_ACCESSIBILITY_SERVICES <- \"$value\" (written=$isWritten)")
        isWritten
    } catch (e: SecurityException) {
        Log.e(TAG, "WRITE_SECURE_SETTINGS rejected", e)
        false
    }

    private fun component(context: Context) = ComponentName(context, PhoneAccessibilityService::class.java)

    private fun policy(context: Context): AccessibilityRepairPolicy {
        val component = component(context)
        return AccessibilityRepairPolicy(component.packageName, component.className)
    }
}
