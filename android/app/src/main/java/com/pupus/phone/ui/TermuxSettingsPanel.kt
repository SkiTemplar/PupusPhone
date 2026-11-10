package com.pupus.phone.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.pupus.phone.AppSettings
import com.pupus.phone.R
import com.pupus.phone.voice.TermuxLauncher
import com.pupus.phone.voice.TermuxRunCommand
import com.pupus.phone.voice.TermuxSetupCommand
import com.pupus.phone.voice.TermuxStatus
import com.pupus.phone.voice.VoiceService

/**
 * "Arrancar el bridge en Termux": switch, script path, a status line with the permission button
 * and "Configurar Termux", which copies the install command for the phone-only bridge.
 */
class TermuxSettingsPanel(private val activity: Activity, private val settings: AppSettings) {
    private val launcher = TermuxLauncher(activity)
    private val pathInput: EditText = activity.findViewById(R.id.termuxPathInput)
    private val navigationInput: EditText = activity.findViewById(R.id.navigationAppsInput)
    private val statusText: TextView = activity.findViewById(R.id.termuxStatusText)
    private val permissionButton: Button = activity.findViewById(R.id.termuxPermissionButton)

    fun bind() {
        pathInput.setText(settings.termuxScriptPath)
        navigationInput.setText(settings.navigationPackages)
        activity.findViewById<Switch>(R.id.termuxSwitch).apply {
            isChecked = settings.isTermuxLaunchEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settings.isTermuxLaunchEnabled = isChecked
                render()
            }
        }
        permissionButton.setOnClickListener {
            activity.requestPermissions(arrayOf(TermuxRunCommand.PERMISSION), TERMUX_PERMISSION_REQUEST)
        }
        activity.findViewById<Button>(R.id.termuxSetupButton).setOnClickListener { copySetupCommand() }
    }

    /**
     * Points the app at the loopback bridge, keeps (or creates and saves) the bridge token and copies
     * the one-line installer, which writes the same tokens into Termux's ~/.pupusphone.env.
     */
    private fun copySetupCommand() {
        val urlInput = activity.findViewById<EditText>(R.id.bridgeUrlInput)
        val tokenInput = activity.findViewById<EditText>(R.id.bridgeTokenInput)
        val bridgeToken = TermuxSetupCommand.ensureBridgeToken(tokenInput.text.toString())
        val command = try {
            TermuxSetupCommand.build(settings.deviceToken, bridgeToken)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Cannot build the Termux setup command", e)
            Toast.makeText(activity, R.string.termux_setup_failed, Toast.LENGTH_LONG).show()
            return
        }
        settings.bridgeToken = bridgeToken
        settings.bridgeUrl = TermuxSetupCommand.LOOPBACK_BRIDGE_URL
        tokenInput.setText(bridgeToken)
        urlInput.setText(TermuxSetupCommand.LOOPBACK_BRIDGE_URL)
        val clip = ClipData.newPlainText("PupusPhone Termux setup", command).apply {
            // Hides the tokens from the clipboard preview and keyboard suggestions.
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        render()
        VoiceService.current?.connectBridge()
        Toast.makeText(activity, R.string.termux_setup_copied, Toast.LENGTH_LONG).show()
    }

    /** Saved together with the bridge URL by "Guardar" (the navigation apps of the car-mode bubble too). */
    fun save() {
        settings.termuxScriptPath = pathInput.text.toString()
        pathInput.setText(settings.termuxScriptPath)
        settings.navigationPackages = navigationInput.text.toString()
        navigationInput.setText(settings.navigationPackages)
    }

    fun render() {
        val status = launcher.status(settings)
        statusText.setText(statusRes(status))
        val colorRes = when (status) {
            TermuxStatus.READY -> R.color.status_ok
            TermuxStatus.DISABLED, TermuxStatus.NOT_LOOPBACK -> R.color.text_tertiary
            TermuxStatus.NOT_INSTALLED, TermuxStatus.PERMISSION_MISSING, TermuxStatus.INVALID_PATH -> R.color.status_bad
        }
        statusText.setTextColor(activity.getColor(colorRes))
        // Asked for whenever it is missing, so it is ready before the bridge URL is switched to loopback.
        val isPermissionNeeded = launcher.isTermuxInstalled() && !launcher.isPermissionGranted()
        permissionButton.visibility = if (isPermissionNeeded) View.VISIBLE else View.GONE
    }

    private fun statusRes(status: TermuxStatus): Int = when (status) {
        TermuxStatus.DISABLED -> R.string.termux_status_disabled
        TermuxStatus.NOT_LOOPBACK -> R.string.termux_status_not_loopback
        TermuxStatus.NOT_INSTALLED -> R.string.termux_status_not_installed
        TermuxStatus.PERMISSION_MISSING -> R.string.termux_status_permission
        TermuxStatus.INVALID_PATH -> R.string.termux_status_path
        TermuxStatus.READY -> R.string.termux_status_ready
    }

    companion object {
        const val TERMUX_PERMISSION_REQUEST = 2
        private const val TAG = "PupusTermux"
    }
}
