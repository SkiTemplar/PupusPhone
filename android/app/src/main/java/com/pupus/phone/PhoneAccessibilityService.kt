package com.pupus.phone

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.pupus.phone.api.DeviceApiHost
import com.pupus.phone.api.RouteHandler
import com.pupus.phone.device.AndroidDeviceController
import com.pupus.phone.ui.OverlayButton
import com.pupus.phone.voice.AudioCues

/** Hosts the device API servers (port 8765, see [DeviceApiHost]) and the push-to-talk overlay. */
class PhoneAccessibilityService : AccessibilityService() {
    private var apiHost: DeviceApiHost? = null
    private var overlay: OverlayButton? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AudioCues.recoverMutedStreams(this)
        instance = this
        AppSettings(this).isA11yWanted = true
        startServer()
        overlay = OverlayButton(this).also { it.show() }
    }

    private fun startServer() {
        val settings = AppSettings(this)
        val handler = RouteHandler({ settings.deviceToken }, AndroidDeviceController(this))
        apiHost = DeviceApiHost(this, handler) { settings.isLanAllowed }.also { it.start() }
    }

    /** Re-applies the bind policy, e.g. after toggling "Permitir red local". Main thread. */
    fun rebindApi() {
        apiHost?.rebind()
    }

    /** True while at least one device API server (port 8765) is bound. */
    val isApiListening: Boolean
        get() = apiHost?.isListening == true

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Only a clean disable (Settings) gets here; a process kill does not, so the guard may revive it. */
    override fun onUnbind(intent: Intent?): Boolean {
        AppSettings(this).isA11yWanted = false
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        apiHost?.stop()
        apiHost = null
        overlay?.hide()
        overlay = null
        if (instance === this) instance = null
        Log.i(TAG, "Accessibility service stopped")
    }

    companion object {
        private const val TAG = "PupusA11y"

        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set
    }
}
