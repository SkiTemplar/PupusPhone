package com.pupus.phone.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.pupus.phone.AppSettings
import com.pupus.phone.NetworkAddresses
import com.pupus.phone.PhoneAccessibilityService
import com.pupus.phone.R
import com.pupus.phone.api.DeviceToken
import com.pupus.phone.voice.VoiceService
import com.pupus.phone.voice.VoiceUrl

/** Settings grouped in cards: connection, voice, security and permissions. */
class SettingsActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var termuxPanel: TermuxSettingsPanel
    private lateinit var diagnostics: DiagnosticsPanel
    private lateinit var statusCard: View

    /** The accessibility rebind and the bridge connection are asynchronous: refresh "Estado" while visible. */
    private val statusPoll = object : Runnable {
        override fun run() {
            diagnostics.render()
            statusCard.postDelayed(this, STATUS_POLL_MS)
        }
    }
    private var isTokenShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps the device token out of screenshots and the recents thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        termuxPanel = TermuxSettingsPanel(this, settings).apply { bind() }
        diagnostics = DiagnosticsPanel(this).apply { bind() }
        statusCard = findViewById(R.id.diagA11yText)
        bindConnection()
        VoiceSettingsPanel(this, settings).bind()
        bindLanSwitch()
        bindPermissions()
    }

    override fun onResume() {
        super.onResume()
        AppForeground.onResume()
        refreshInfo()
        statusPoll.run()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == TermuxSettingsPanel.TERMUX_PERMISSION_REQUEST) {
            termuxPanel.render()
            diagnostics.render()
        }
    }

    override fun onPause() {
        statusCard.removeCallbacks(statusPoll)
        AppForeground.onPause()
        super.onPause()
    }

    private fun bindConnection() {
        val urlInput = findViewById<EditText>(R.id.bridgeUrlInput).apply { setText(settings.bridgeUrl) }
        val tokenInput = findViewById<EditText>(R.id.bridgeTokenInput).apply { setText(settings.bridgeToken) }
        findViewById<View>(R.id.saveButton).setOnClickListener {
            saveBridge(urlInput.text.toString(), tokenInput.text.toString())
        }
        findViewById<View>(R.id.copyTokenButton).setOnClickListener { copyDeviceToken() }
        findViewById<View>(R.id.showTokenButton).setOnClickListener {
            isTokenShown = !isTokenShown
            renderDeviceToken()
        }
        findViewById<View>(R.id.regenerateTokenButton).setOnClickListener { confirmRegenerateToken() }
    }

    /** Masked (`abcd…wxyz`) unless "Mostrar" was pressed. */
    private fun renderDeviceToken() {
        val token = settings.deviceToken
        findViewById<TextView>(R.id.deviceTokenText).text = if (isTokenShown) token else DeviceToken.mask(token)
        findViewById<Button>(R.id.showTokenButton).setText(if (isTokenShown) R.string.hide else R.string.show)
    }

    private fun confirmRegenerateToken() {
        AlertDialog.Builder(this)
            .setTitle(R.string.regenerate_token_title)
            .setMessage(R.string.regenerate_token_message)
            .setPositiveButton(R.string.regenerate) { _, _ -> regenerateToken() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The device API reads the token per request, so the old one is rejected from now on. */
    private fun regenerateToken() {
        val isStored = settings.regenerateDeviceToken()
        isTokenShown = false
        renderDeviceToken()
        val message = if (isStored) R.string.token_regenerated else R.string.token_not_saved
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun bindLanSwitch() {
        findViewById<Switch>(R.id.allowLanSwitch).apply {
            isChecked = settings.isLanAllowed
            setOnCheckedChangeListener { _, isChecked ->
                settings.isLanAllowed = isChecked
                PhoneAccessibilityService.instance?.rebindApi()
            }
        }
    }

    private fun bindPermissions() {
        findViewById<View>(R.id.permissionsButton).setOnClickListener { requestVoicePermissions() }
        findViewById<View>(R.id.accessibilityButton).setOnClickListener {
            openSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.batteryButton).setOnClickListener {
            openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<View>(R.id.notificationAccessButton).setOnClickListener {
            openSystemSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun saveBridge(url: String, token: String) {
        if (url.isNotBlank() && !VoiceUrl.isValidBase(url)) {
            Toast.makeText(this, R.string.invalid_bridge_url, Toast.LENGTH_LONG).show()
            return
        }
        settings.bridgeUrl = url
        settings.bridgeToken = token
        termuxPanel.save()
        termuxPanel.render()
        VoiceService.current?.connectBridge()
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    private fun copyDeviceToken() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("PupusPhone token", settings.deviceToken).apply {
            // Hides the token from the clipboard preview and keyboard suggestions.
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun refreshInfo() {
        val isA11yOn = PhoneAccessibilityService.instance != null
        findViewById<TextView>(R.id.accessibilityText).apply {
            setText(if (isA11yOn) R.string.accessibility_on else R.string.accessibility_off)
            setTextColor(getColor(if (isA11yOn) R.color.status_ok else R.color.status_bad))
        }
        renderDeviceToken()
        termuxPanel.render()
        val ips = NetworkAddresses.list()
        findViewById<TextView>(R.id.ipText).text =
            if (ips.isEmpty()) getString(R.string.no_ips) else ips.joinToString("\n")
    }

    private fun openSystemSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Settings screen not available: ${intent.action}", e)
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private companion object {
        const val TAG = "PupusSettings"
        const val STATUS_POLL_MS = 2_000L
    }
}

private const val PERMISSIONS_REQUEST = 1

/** Asks for the microphone and notification permissions that are still missing. */
internal fun Activity.requestVoicePermissions() {
    val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSIONS_REQUEST)
}
