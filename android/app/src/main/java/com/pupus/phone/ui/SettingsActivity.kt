package com.pupus.phone.ui

import android.Manifest
import android.app.Activity
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
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.pupus.phone.AppSettings
import com.pupus.phone.NetworkAddresses
import com.pupus.phone.PhoneAccessibilityService
import com.pupus.phone.R
import com.pupus.phone.voice.VoiceService
import com.pupus.phone.voice.VoiceUrl

/** Settings grouped in cards: connection, voice, security and permissions. */
class SettingsActivity : Activity() {
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        bindConnection()
        VoiceSettingsPanel(this, settings).bind()
        bindLanSwitch()
        bindPermissions()
    }

    override fun onResume() {
        super.onResume()
        AppForeground.onResume()
        refreshInfo()
    }

    override fun onPause() {
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
    }

    private fun saveBridge(url: String, token: String) {
        if (url.isNotBlank() && !VoiceUrl.isValidBase(url)) {
            Toast.makeText(this, R.string.invalid_bridge_url, Toast.LENGTH_LONG).show()
            return
        }
        settings.bridgeUrl = url
        settings.bridgeToken = token
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
        findViewById<TextView>(R.id.deviceTokenText).text = settings.deviceToken
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
    }
}

private const val PERMISSIONS_REQUEST = 1

/** Asks for the microphone and notification permissions that are still missing. */
internal fun Activity.requestVoicePermissions() {
    val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSIONS_REQUEST)
}
