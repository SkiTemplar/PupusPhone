package com.pupus.phone.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.pupus.phone.AppSettings
import com.pupus.phone.R

/** Outcome of a launch attempt; [message] is shown to the user, null when there is nothing to say. */
data class TermuxLaunchResult(val status: TermuxStatus, val isSent: Boolean, val message: String?)

/**
 * Asks Termux to start the voice bridge (scripts/termux-start.sh) through its RUN_COMMAND service.
 * Termux rejects the command on its side (with its own notification) unless
 * `allow-external-apps = true` is set in ~/.termux/termux.properties: that failure never reaches us.
 */
class TermuxLauncher(context: Context) {
    private val context = context.applicationContext

    fun isTermuxInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TermuxRunCommand.PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isPermissionGranted(): Boolean =
        context.checkSelfPermission(TermuxRunCommand.PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun environment(settings: AppSettings): TermuxEnvironment = TermuxEnvironment(
        isEnabled = settings.isTermuxLaunchEnabled,
        bridgeUrl = settings.bridgeUrl,
        scriptPath = settings.termuxScriptPath,
        isTermuxInstalled = isTermuxInstalled(),
        isPermissionGranted = isPermissionGranted(),
    )

    fun status(settings: AppSettings): TermuxStatus = TermuxLaunchPolicy.status(environment(settings))

    /** Car-mode start: sends the intent only when the policy says READY. Never throws. */
    fun launchIfNeeded(settings: AppSettings): TermuxLaunchResult {
        val env = environment(settings)
        val status = TermuxLaunchPolicy.status(env)
        if (!TermuxLaunchPolicy.shouldLaunch(env)) {
            val message = if (TermuxLaunchPolicy.isBlocking(status)) blockingMessage(status) else null
            if (message != null) Log.w(TAG, "Not starting the Termux bridge: $status")
            return TermuxLaunchResult(status, isSent = false, message = message)
        }
        return send(env.scriptPath, status)
    }

    private fun send(scriptPath: String, status: TermuxStatus): TermuxLaunchResult {
        // Background: Termux runs the script in an app shell, without opening a terminal session.
        val intent = Intent(TermuxRunCommand.ACTION)
            .setClassName(TermuxRunCommand.PACKAGE, TermuxRunCommand.SERVICE)
            .putExtra(TermuxRunCommand.EXTRA_COMMAND_PATH, scriptPath.trim())
            .putExtra(TermuxRunCommand.EXTRA_BACKGROUND, true)
        return try {
            if (context.startForegroundService(intent) == null) {
                Log.e(TAG, "Termux RunCommandService not found")
                failed(status, context.getString(R.string.termux_error_not_found))
            } else {
                Log.i(TAG, "Asked Termux to run $scriptPath")
                TermuxLaunchResult(status, isSent = true, message = context.getString(R.string.termux_starting))
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Termux RUN_COMMAND permission rejected", e)
            failed(status, context.getString(R.string.termux_error_permission))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException extends IllegalStateException.
            Log.e(TAG, "Cannot start the Termux service from this context", e)
            failed(status, context.getString(R.string.termux_error_start))
        }
    }

    private fun failed(status: TermuxStatus, message: String) = TermuxLaunchResult(status, isSent = false, message = message)

    private fun blockingMessage(status: TermuxStatus): String? = when (status) {
        TermuxStatus.NOT_INSTALLED -> context.getString(R.string.termux_error_not_installed)
        TermuxStatus.PERMISSION_MISSING -> context.getString(R.string.termux_error_permission)
        TermuxStatus.INVALID_PATH -> context.getString(R.string.termux_error_path)
        TermuxStatus.DISABLED, TermuxStatus.NOT_LOOPBACK, TermuxStatus.READY -> null
    }

    private companion object {
        const val TAG = "PupusTermux"
    }
}
