package com.pupus.phone.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.speech.SpeechRecognizer
import android.util.Log
import com.pupus.phone.AppSettings
import com.pupus.phone.BuildConfig
import com.pupus.phone.R
import com.pupus.phone.ui.MainActivity
import java.util.UUID

/**
 * Foreground (microphone) service that owns the voice turn:
 * tap -> listen one utterance -> `utterance` -> speak `say` frames -> `done`.
 */
class VoiceService : Service(), BridgeClient.Listener, SpeechInput.Callback {
    private lateinit var bridge: BridgeClient
    private lateinit var input: SpeechInput
    private lateinit var output: SpeechOutput
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForeground = false
    private var currentId: String? = null
    private var isTurnDone = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bridge = BridgeClient(this)
        input = SpeechInput(this)
        output = SpeechOutput(this, ::onSpeechDrained, ::onSpeechFailed)
    }

    /**
     * startForeground always runs first: stopping a service started with
     * startForegroundService before it goes foreground crashes the whole process,
     * which also hosts the device API. RECORD_AUDIO is checked by [VoiceLauncher].
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_TAP -> onTap()
            ACTION_RESET -> resetConversation()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException (IllegalStateException) or SecurityException
            // (permission revoked, or a background start without a while-in-use exemption).
            Log.e(TAG, "startForeground(microphone) rejected", e)
            VoiceBus.publish(VoiceState.STOPPED, getString(R.string.error_voice_start))
            stopSelf()
            return false
        }
        if (isForeground) return true
        isForeground = true
        current = this
        acquireWakeLock()
        connectBridge()
        VoiceBus.publish(VoiceState.IDLE)
        return true
    }

    override fun onDestroy() {
        current = null
        bridge.shutdown()
        input.destroy()
        output.shutdown()
        releaseWakeLock()
        VoiceBus.publish(VoiceState.STOPPED, getString(R.string.state_stopped))
        super.onDestroy()
    }

    fun stopVoice() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun connectBridge() {
        val settings = AppSettings(this)
        val url = VoiceUrl.build(settings.bridgeUrl, settings.bridgeToken)
        if (url == null) {
            VoiceBus.publishStatus(getString(R.string.error_bridge_not_configured))
            return
        }
        bridge.connect(url)
    }

    fun resetConversation() {
        abortTurn()
        if (!bridge.send(VoiceMessages.reset())) speakLocal(getString(R.string.error_no_bridge))
    }

    /** Push-to-talk. While listening, a tap cancels; while thinking/speaking, it interrupts. */
    fun onTap() {
        if (VoiceBus.state == VoiceState.LISTENING) {
            input.cancel()
            VoiceBus.publish(VoiceState.IDLE)
            return
        }
        abortTurn()
        if (!input.isAvailable()) {
            speakLocal(getString(R.string.error_stt_unavailable))
            return
        }
        VoiceBus.publish(VoiceState.LISTENING)
        input.listen(this)
    }

    private fun abortTurn() {
        output.stop()
        val id = currentId ?: return
        currentId = null
        isTurnDone = true
        if (!bridge.send(VoiceMessages.cancel(id))) Log.w(TAG, "cancel not sent for $id")
    }

    override fun onUtterance(text: String) {
        val id = UUID.randomUUID().toString()
        if (!bridge.send(VoiceMessages.utterance(id, text))) {
            speakLocal(getString(R.string.error_no_bridge))
            return
        }
        currentId = id
        isTurnDone = false
        VoiceBus.publish(VoiceState.THINKING, "«$text»")
    }

    override fun onSpeechError(code: Int) {
        val message = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.error_stt_nomatch)
            else -> getString(R.string.error_stt_generic, code)
        }
        if (code == SpeechRecognizer.ERROR_CLIENT) {
            VoiceBus.publish(VoiceState.IDLE)
            return
        }
        speakLocal(message)
    }

    override fun onConnected() {
        VoiceBus.publishStatus(getString(R.string.status_connected))
        if (!bridge.send(VoiceMessages.hello(BuildConfig.VERSION_NAME))) Log.w(TAG, "hello not sent")
    }

    override fun onRejected() {
        val message = getString(R.string.error_bridge_bad_token)
        if (currentId != null) speakLocal(message) else VoiceBus.publishStatus(message)
    }

    private fun onSpeechFailed() {
        VoiceBus.publishStatus(getString(R.string.error_tts_init))
    }

    override fun onDisconnected(retryInSeconds: Int, reason: String) {
        Log.w(TAG, "Bridge disconnected: $reason")
        if (currentId != null) {
            currentId = null
            speakLocal(getString(R.string.error_no_bridge))
        }
        if (retryInSeconds >= 0) VoiceBus.publishStatus(getString(R.string.status_disconnected, retryInSeconds))
    }

    override fun onMessage(message: BridgeMessage) {
        when (message) {
            is BridgeMessage.Ready -> VoiceBus.publishStatus(getString(R.string.status_ready, message.model))
            is BridgeMessage.Say -> if (message.id == currentId) speak(message.text)
            is BridgeMessage.Status -> if (message.id == currentId) VoiceBus.publishStatus(message.text)
            is BridgeMessage.Done -> if (message.id == currentId) finishWhenSilent()
            is BridgeMessage.Error -> onBridgeError(message)
            is BridgeMessage.Invalid -> Log.w(TAG, "Ignoring invalid frame: ${message.reason}")
        }
    }

    private fun onBridgeError(message: BridgeMessage.Error) {
        if (message.id != null && message.id != currentId) return
        speakLocal(message.message)
    }

    private fun speak(text: String) {
        VoiceBus.publish(VoiceState.SPEAKING)
        output.enqueue(text)
    }

    /** Speaks a local message and ends the turn once it is spoken. */
    private fun speakLocal(text: String) {
        VoiceBus.publishStatus(text)
        isTurnDone = true
        currentId = null
        speak(text)
    }

    private fun finishWhenSilent() {
        isTurnDone = true
        if (!output.isSpeaking()) endTurn()
    }

    private fun onSpeechDrained() {
        if (isTurnDone) endTurn() else VoiceBus.publish(VoiceState.THINKING)
    }

    private fun endTurn() {
        currentId = null
        if (VoiceBus.state != VoiceState.LISTENING) VoiceBus.publish(VoiceState.IDLE)
    }

    /** CPU only: the screen is kept on by FLAG_KEEP_SCREEN_ON on the activity and the overlay. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PupusPhone:voice").also { it.acquire(WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.pupus.phone.voice.START"
        const val ACTION_TAP = "com.pupus.phone.voice.TAP"
        const val ACTION_RESET = "com.pupus.phone.voice.RESET"
        private const val TAG = "PupusVoice"
        private const val CHANNEL_ID = "voice"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_MS = 4L * 60 * 60 * 1000

        @Volatile
        var current: VoiceService? = null
            private set
    }
}
