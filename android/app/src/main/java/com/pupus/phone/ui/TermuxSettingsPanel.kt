package com.pupus.phone.ui

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.pupus.phone.AppSettings
import com.pupus.phone.R
import com.pupus.phone.voice.TermuxLauncher
import com.pupus.phone.voice.TermuxRunCommand
import com.pupus.phone.voice.TermuxStatus

/** "Arrancar el bridge en Termux": switch, script path and a status line with the permission button. */
class TermuxSettingsPanel(private val activity: Activity, private val settings: AppSettings) {
    private val launcher = TermuxLauncher(activity)
    private val pathInput: EditText = activity.findViewById(R.id.termuxPathInput)
    private val statusText: TextView = activity.findViewById(R.id.termuxStatusText)
    private val permissionButton: Button = activity.findViewById(R.id.termuxPermissionButton)

    fun bind() {
        pathInput.setText(settings.termuxScriptPath)
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
    }

    /** Saved together with the bridge URL by "Guardar". */
    fun save() {
        settings.termuxScriptPath = pathInput.text.toString()
        pathInput.setText(settings.termuxScriptPath)
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
    }
}
