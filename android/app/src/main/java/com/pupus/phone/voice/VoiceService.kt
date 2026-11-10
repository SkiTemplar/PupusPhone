package com.pupus.phone.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import com.pupus.phone.AppSettings
import com.pupus.phone.BuildConfig
import com.pupus.phone.R
import com.pupus.phone.guard.AccessibilityGuard
import com.pupus.phone.ui.MainActivity
import java.util.UUID

/**
 * Foreground (microphone) service that owns the voice turn:
 * tap -> dictate (until a second tap or the configured pause) -> `utterance` ->
 * speak `say` frames -> `done`.
 */
class VoiceService : Service(), BridgeClient.Listener, SpeechInput.Callback {
    private lateinit var bridge: BridgeClient
    private lateinit var input: SpeechInput
    private lateinit var output: SpeechOutput
    private lateinit var cues: AudioCues
    private lateinit var wakeLock: VoiceWakeLock
    private val turn = TurnTracker()
    private val pending = PendingRequests()
    private var isForeground = false
    private var isBridgeConfigured = false
    private val main = Handler(Looper.getMainLooper())
    private var watchdog = BridgeWatchdog()
    private val watchdogTick = object : Runnable {
        override fun run() {
            checkBridgeWatchdog()
            main.postDelayed(this, BridgeWatchdog.TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bridge = BridgeClient(this)
        cues = AudioCues(this)
        input = SpeechInput(this, RecognizerProvider(this), cues)
        output = SpeechOutput(this, ::onSpeechDrained, ::onSpeechFailed) {
            VoiceVolumeConfig.ttsVolume(AppSettings(this).ttsVolumePercent)
        }
        wakeLock = VoiceWakeLock(this)
        input.prepare()
    }

    /**
     * startForeground always runs first: stopping a service started with
     * startForegroundService before it goes foreground crashes the whole process,
     * which also hosts the device API. RECORD_AUDIO is checked by [VoiceLauncher].
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) return START_NOT_STICKY
        if (intent?.action == ACTION_TAP) onStartTap()
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
        wakeLock.acquire()
        // Car mode is starting: a loopback bridge URL means the bridge lives in Termux on this phone.
        // The script is idempotent and the BridgeClient backoff connects once it is listening.
        AccessibilityGuard.check(this, "voice-start")
        val termux = TermuxLauncher(this).launchIfNeeded(AppSettings(this))
        watchdog = BridgeWatchdog().let { if (termux.isSent) it.onLaunched(SystemClock.elapsedRealtime()) else it }
        main.postDelayed(watchdogTick, BridgeWatchdog.TICK_MS)
        connectBridge()
        VoiceBus.publish(VoiceState.IDLE, termux.message)
        // The status line is soon overwritten by connection updates: a failure also gets a toast.
        if (!termux.isSent && termux.message != null) Toast.makeText(this, termux.message, Toast.LENGTH_LONG).show()
        return true
    }

    /** The service was started by a tap: wait for the first connection instead of failing at once. */
    private fun onStartTap() {
        if (bridge.isConnected() || !isBridgeConfigured) {
            onTap()
            return
        }
        pending.queueTap()
        VoiceBus.publishStatus(getString(R.string.status_connecting))
    }

    /**
     * Termux may be killed (swiped away on HyperOS) while car mode is on: with a loopback bridge
     * down for more than 15 s, re-send the idempotent start, at most once per minute.
     */
    private fun checkBridgeWatchdog() {
        val settings = AppSettings(this)
        val isLoopback = TermuxLaunchPolicy.isLoopbackUrl(settings.bridgeUrl)
        val step = watchdog.tick(SystemClock.elapsedRealtime(), bridge.isConnected(), isLoopback)
        watchdog = step.state
        if (!step.shouldRelaunch) return
        Log.w(TAG, "Bridge down for more than ${BridgeWatchdog.GRACE_MS / 1000} s: asking Termux to start it again")
        val result = TermuxLauncher(this).launchIfNeeded(settings)
        if (!result.isSent) Log.w(TAG, "Watchdog relaunch not sent: ${result.status}")
    }

    override fun onDestroy() {
        main.removeCallbacks(watchdogTick)
        if (current === this) current = null
        bridge.shutdown()
        input.destroy()
        output.shutdown()
        cues.shutdown()
        wakeLock.release()
        // A newer instance may already be running after a quick off/on: do not clobber its state.
        if (current == null) {
            VoiceBus.publishBridge(false)
            VoiceBus.publish(VoiceState.STOPPED, getString(R.string.state_stopped))
        }
        super.onDestroy()
    }

    /** Car mode off. Publishes STOPPED now so an immediate off/on starts the service again. */
    fun stopVoice() {
        if (current === this) current = null
        isForeground = false
        main.removeCallbacks(watchdogTick)
        pending.clear()
        input.cancel()
        abortTurn()
        bridge.close()
        wakeLock.release()
        VoiceBus.publishBridge(false)
        VoiceBus.publish(VoiceState.STOPPED, getString(R.string.state_stopped))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** (Re)opens the bridge with the saved settings; returns false if they are incomplete. */
    fun connectBridge(): Boolean {
        val settings = AppSettings(this)
        val url = VoiceUrl.build(settings.bridgeUrl, settings.bridgeToken)
        isBridgeConfigured = url != null
        // The old socket is closed silently: a running turn would never receive `done`.
        if (turn.hasTurn) {
            abortTurn()
            VoiceBus.publish(VoiceState.IDLE)
        }
        VoiceBus.publishBridge(false)
        if (url == null) {
            bridge.close()
            VoiceBus.publishStatus(getString(R.string.error_bridge_not_configured))
            return false
        }
        bridge.connect(url)
        return true
    }

    /** New conversation: silences everything now; the bridge gets `reset` now or on reconnect. */
    fun resetConversation() {
        input.cancel()
        abortTurn(isNewConversation = true)
        VoiceBus.publishTranscript("")
        VoiceBus.publishReply("")
        VoiceBus.updateHistory { it.reset() }
        if (pending.requestReset(bridge.isConnected())) sendReset()
        val status = if (pending.isResetPending) R.string.status_reset_pending else R.string.status_new_conversation
        VoiceBus.publish(VoiceState.IDLE, getString(status))
    }

    private fun sendReset() {
        if (bridge.send(VoiceMessages.reset())) return
        Log.w(TAG, "reset not sent; queued for the next connection")
        pending.deferReset()
    }

    /** Tap to dictate. While listening, a tap sends now; while thinking/speaking, it interrupts. */
    fun onTap() {
        if (input.isActive) {
            input.sendNow()
            return
        }
        abortTurn()
        if (!bridge.isConnected()) {
            reportNoConnection()
            return
        }
        if (!input.isAvailable()) {
            speakLocal(getString(R.string.error_stt_unavailable))
            return
        }
        val settings = AppSettings(this)
        VoiceBus.publishTranscript("")
        VoiceBus.publish(VoiceState.LISTENING, getString(R.string.status_dictating))
        input.start(this, DictationConfig(silenceMs = settings.pauseToSendMs), settings.recognizerMode)
    }

    /** Refuses to listen: dictating a whole sentence that cannot be sent is worse. */
    private fun reportNoConnection() {
        cues.listeningEnded()
        val message = if (isBridgeConfigured) R.string.error_no_assistant else R.string.error_bridge_not_configured
        speakLocal(getString(message))
    }

    private fun abortTurn(isNewConversation: Boolean = false) {
        output.stop()
        cues.release()
        val id = (if (isNewConversation) turn.resetConversation() else turn.abort()) ?: return
        if (!bridge.send(VoiceMessages.cancel(id))) Log.w(TAG, "cancel not sent for $id")
    }

    override fun onUtterance(text: String) {
        val id = UUID.randomUUID().toString()
        if (!bridge.send(VoiceMessages.utterance(id, text))) {
            speakLocal(getString(R.string.error_no_bridge))
            return
        }
        turn.begin(id)
        VoiceBus.updateHistory { it.appendUtterance(id, text) }
        VoiceBus.publish(VoiceState.THINKING, "«$text»")
    }

    override fun onTranscript(text: String) = VoiceBus.publishTranscript(text)

    /** Nothing was said: end the turn quietly. */
    override fun onNothingHeard() {
        VoiceBus.publish(VoiceState.IDLE, getString(R.string.status_nothing_heard))
    }

    override fun onSpeechError(code: Int) {
        val message = when (code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.mic_permission_missing)
            else -> getString(R.string.error_stt_generic, code)
        }
        speakLocal(message)
    }

    override fun onConnected() {
        VoiceBus.publishBridge(true)
        VoiceBus.publishStatus(getString(R.string.status_connected))
        if (!bridge.send(VoiceMessages.hello(BuildConfig.VERSION_NAME))) Log.w(TAG, "hello not sent")
        val actions = pending.onConnected()
        if (actions.shouldReset) sendReset()
        if (actions.shouldTap) onTap()
    }

    override fun onRejected() {
        VoiceBus.publishBridge(false)
        val message = getString(R.string.error_bridge_bad_token)
        val hadTap = pending.onConnectFailed()
        if (turn.hasTurn || hadTap) speakLocal(message) else VoiceBus.publishStatus(message)
    }

    private fun onSpeechFailed() {
        VoiceBus.publishStatus(getString(R.string.error_tts_init))
    }

    override fun onDisconnected(retryInSeconds: Int, reason: String) {
        Log.w(TAG, "Bridge disconnected: $reason")
        VoiceBus.publishBridge(false)
        if (turn.hasTurn) speakLocal(getString(R.string.error_no_bridge))
        if (pending.onConnectFailed()) reportNoConnection()
        if (retryInSeconds >= 0) VoiceBus.publishStatus(getString(R.string.status_disconnected, retryInSeconds))
    }

    override fun onMessage(message: BridgeMessage) {
        when (message) {
            is BridgeMessage.Ready -> VoiceBus.publishStatus(getString(R.string.status_ready, message.model))
            is BridgeMessage.Say -> if (turn.isCurrent(message.id)) onSay(message.id, message.text)
            is BridgeMessage.Status -> if (turn.isCurrent(message.id)) onStatus(message.id, message.text)
            is BridgeMessage.Done -> if (turn.isCurrent(message.id)) finishWhenSilent(message.isSilent)
            is BridgeMessage.Error -> onBridgeError(message)
            is BridgeMessage.Invalid -> Log.w(TAG, "Ignoring invalid frame: ${message.reason}")
        }
    }

    /** Speaks a `say` frame and shows the whole reply of the turn on screen. */
    private fun onSay(id: String, text: String) {
        VoiceBus.publishReply(turn.appendReply(id, text, VoiceBus.reply))
        VoiceBus.updateHistory { it.appendSay(id, text) }
        speak(text)
    }

    private fun onStatus(id: String, text: String) {
        VoiceBus.updateHistory { it.appendStatus(id, text) }
        VoiceBus.publishStatus(text)
    }

    private fun onBridgeError(message: BridgeMessage.Error) {
        if (message.id != null && !turn.isCurrent(message.id)) return
        message.id?.let { id -> VoiceBus.updateHistory { it.appendStatus(id, message.message) } }
        speakLocal(message.message)
    }

    private fun speak(text: String) {
        cues.speechStarted(AppSettings(this).minMediaVolumePercent)
        VoiceBus.publish(VoiceState.SPEAKING)
        output.enqueue(text)
    }

    /** Speaks a local message and ends the turn once it is spoken (the bridge needs no cancel). */
    private fun speakLocal(text: String) {
        VoiceBus.publishStatus(text)
        turn.abort()
        speak(text)
    }

    /**
     * A silent turn (`done` with `silent`: the order was carried out with nothing to say) takes no
     * audio focus and says nothing, so the music is not interrupted; at most the end chime.
     */
    private fun finishWhenSilent(isSilent: Boolean) {
        turn.markDone()
        if (output.isSpeaking()) return
        if (isSilent) {
            if (AppSettings(this).isSilentDoneToneEnabled) cues.silentDone()
            VoiceBus.publishStatus(getString(R.string.status_done_silent))
        }
        endTurn()
    }

    /** The focus goes back as soon as the speech drains: music recovers while tools keep running. */
    private fun onSpeechDrained() {
        if (turn.isDone) {
            endTurn()
            return
        }
        cues.release()
        VoiceBus.publish(VoiceState.THINKING)
    }

    private fun endTurn() {
        turn.abort()
        if (input.isActive) return
        cues.release()
        VoiceBus.publish(VoiceState.IDLE)
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
            .setSmallIcon(R.drawable.ic_stat_pupus)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.pupus.phone.voice.START"
        const val ACTION_TAP = "com.pupus.phone.voice.TAP"
        private const val TAG = "PupusVoice"
        private const val CHANNEL_ID = "voice"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var current: VoiceService? = null
            private set
    }
}
