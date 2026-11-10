package com.pupus.phone.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
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
import com.pupus.phone.voice.VoiceBus
import com.pupus.phone.voice.VoiceLauncher
import com.pupus.phone.voice.VoiceService
import com.pupus.phone.voice.VoiceState
import com.pupus.phone.voice.VoiceUrl

class MainActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var stateText: TextView
    private lateinit var statusText: TextView
    private lateinit var carMode: Switch
    private val pttBackground = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val voiceListener = VoiceBus.Listener { state, status -> render(state, status) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = AppSettings(this)
        stateText = findViewById(R.id.stateText)
        statusText = findViewById(R.id.statusText)
        carMode = findViewById(R.id.carModeSwitch)
        bindVoiceControls()
        bindSettings()
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        VoiceBus.addListener(voiceListener)
        refreshSettingsInfo()
    }

    override fun onPause() {
        VoiceBus.removeListener(voiceListener)
        super.onPause()
    }

    private fun bindVoiceControls() {
        findViewById<Button>(R.id.pttButton).apply {
            background = pttBackground
            setOnClickListener { VoiceLauncher.tap(this@MainActivity) }
        }
        carMode.setOnCheckedChangeListener { _, isChecked ->
            val isRunning = VoiceService.current != null
            if (isChecked && !isRunning) VoiceLauncher.start(this)
            if (!isChecked && isRunning) VoiceLauncher.stop(this)
        }
        findViewById<Button>(R.id.settingsToggle).setOnClickListener {
            val panel = findViewById<View>(R.id.settingsPanel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun render(state: VoiceState, status: String) {
        stateText.setText(PttColors.labelRes(state))
        statusText.text = status
        pttBackground.setColor(getColor(PttColors.colorRes(state)))
        val isActive = state != VoiceState.STOPPED
        if (carMode.isChecked != isActive) carMode.isChecked = isActive
        if (isActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun bindSettings() {
        val urlInput = findViewById<EditText>(R.id.bridgeUrlInput).apply { setText(settings.bridgeUrl) }
        val tokenInput = findViewById<EditText>(R.id.bridgeTokenInput).apply { setText(settings.bridgeToken) }
        findViewById<Button>(R.id.saveButton).setOnClickListener {
            saveBridge(urlInput.text.toString(), tokenInput.text.toString())
        }
        findViewById<Button>(R.id.copyTokenButton).setOnClickListener { copyDeviceToken() }
        bindLanSwitch()
        findViewById<Button>(R.id.permissionsButton).setOnClickListener { requestMissingPermissions() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<Button>(R.id.resetButton).setOnClickListener { VoiceLauncher.reset(this) }
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

    private fun refreshSettingsInfo() {
        val isA11yOn = PhoneAccessibilityService.instance != null
        findViewById<TextView>(R.id.accessibilityText)
            .setText(if (isA11yOn) R.string.accessibility_on else R.string.accessibility_off)
        findViewById<TextView>(R.id.deviceTokenText).text = settings.deviceToken
        val ips = NetworkAddresses.list()
        findViewById<TextView>(R.id.ipText).text =
            if (ips.isEmpty()) getString(R.string.no_ips) else ips.joinToString("\n")
    }

    private fun requestMissingPermissions() {
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSIONS_REQUEST)
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Settings screen not available: ${intent.action}", e)
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private companion object {
        const val TAG = "PupusMain"
        const val PERMISSIONS_REQUEST = 1
    }
}
