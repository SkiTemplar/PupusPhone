package com.pupus.phone.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.pupus.phone.PhoneAccessibilityService
import com.pupus.phone.R
import com.pupus.phone.guard.A11yDisplay
import com.pupus.phone.guard.A11yOutcome
import com.pupus.phone.guard.AccessibilityGuard
import com.pupus.phone.voice.TermuxLauncher
import com.pupus.phone.voice.VoiceBus

/**
 * "Estado" card: accessibility (with the self-heal result), device API, bridge, Termux,
 * WRITE_SECURE_SETTINGS (with the adb grant command), battery optimisation and the Xiaomi tip.
 */
class DiagnosticsPanel(private val activity: Activity) {
    private val termux = TermuxLauncher(activity)
    private val a11yText: TextView = activity.findViewById(R.id.diagA11yText)
    private val grantGroup: View = activity.findViewById(R.id.diagGrantGroup)
    private val batteryButton: Button = activity.findViewById(R.id.diagBatteryButton)

    fun bind() {
        activity.findViewById<TextView>(R.id.diagGrantCommand).text = AccessibilityGuard.GRANT_COMMAND
        activity.findViewById<Button>(R.id.diagCopyGrantButton).setOnClickListener { copyGrantCommand() }
        batteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
        activity.findViewById<Button>(R.id.diagRepairButton).setOnClickListener { repairNow() }
        val isXiaomi = Build.MANUFACTURER.equals(XIAOMI, ignoreCase = true)
        activity.findViewById<View>(R.id.diagXiaomiTip).visibility = if (isXiaomi) View.VISIBLE else View.GONE
    }

    fun render() {
        renderA11y()
        val isApiOn = PhoneAccessibilityService.instance?.isApiListening == true
        line(R.id.diagApiText, isApiOn, if (isApiOn) R.string.diag_api_on else R.string.diag_api_off)
        val isBridgeOn = VoiceBus.isBridgeConnected
        line(R.id.diagBridgeText, isBridgeOn, if (isBridgeOn) R.string.diag_bridge_on else R.string.diag_bridge_off)
        renderTermux()
        val canHeal = AccessibilityGuard.hasPermission(activity)
        line(R.id.diagSecureText, canHeal, if (canHeal) R.string.diag_secure_on else R.string.diag_secure_off)
        grantGroup.visibility = if (canHeal) View.GONE else View.VISIBLE
        val isBatteryFree = activity.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(activity.packageName) == true
        line(R.id.diagBatteryText, isBatteryFree, if (isBatteryFree) R.string.diag_battery_on else R.string.diag_battery_off)
        batteryButton.visibility = if (isBatteryFree) View.GONE else View.VISIBLE
    }

    private fun renderA11y() {
        val display = A11yDisplay.of(AccessibilityGuard.evaluate(activity), AccessibilityGuard.repairedAgoMs())
        val (textRes, colorRes) = when (display) {
            A11yDisplay.RUNNING -> R.string.diag_a11y_running to R.color.status_ok
            A11yDisplay.REPAIRED -> R.string.diag_a11y_repaired to R.color.warning
            A11yDisplay.NO_PERMISSION -> R.string.diag_a11y_no_permission to R.color.status_bad
            A11yDisplay.OFF -> R.string.diag_a11y_off to R.color.status_bad
            A11yDisplay.DEAD -> R.string.diag_a11y_dead to R.color.status_bad
        }
        a11yText.setText(textRes)
        a11yText.setTextColor(activity.getColor(colorRes))
    }

    private fun renderTermux() {
        val textRes = when {
            !termux.isTermuxInstalled() -> R.string.diag_termux_missing
            !termux.isPermissionGranted() -> R.string.diag_termux_no_permission
            else -> R.string.diag_termux_ok
        }
        line(R.id.diagTermuxText, textRes == R.string.diag_termux_ok, textRes)
    }

    private fun line(id: Int, isOk: Boolean, textRes: Int) {
        activity.findViewById<TextView>(id).apply {
            setText(textRes)
            setTextColor(activity.getColor(if (isOk) R.color.status_ok else R.color.status_bad))
        }
    }

    private fun repairNow() {
        val report = AccessibilityGuard.check(activity, "repair-button")
        val message = when (report.outcome) {
            A11yOutcome.RUNNING -> activity.getString(R.string.diag_repair_not_needed)
            A11yOutcome.REBIND, A11yOutcome.ENABLE, A11yOutcome.RATE_LIMITED -> activity.getString(R.string.diag_repair_done)
            A11yOutcome.NEEDS_PERMISSION -> activity.getString(R.string.diag_a11y_no_permission)
            A11yOutcome.OFF -> activity.getString(R.string.diag_a11y_off)
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        render()
    }

    private fun copyGrantCommand() {
        val clip = ClipData.newPlainText("PupusPhone adb grant", AccessibilityGuard.GRANT_COMMAND)
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** Direct per-app dialog; falls back to the system list if the ROM does not provide it. */
    // Sideloaded app, not on Play: the always-on voice service is the legitimate use case.
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
        try {
            activity.startActivity(request)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Battery optimisation request not available", e)
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private companion object {
        const val TAG = "PupusDiag"
        const val XIAOMI = "Xiaomi"
    }
}
