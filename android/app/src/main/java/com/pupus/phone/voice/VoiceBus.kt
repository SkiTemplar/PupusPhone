package com.pupus.phone.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pupus.phone.R
import java.util.concurrent.CopyOnWriteArrayList

enum class VoiceState { STOPPED, IDLE, LISTENING, THINKING, SPEAKING }

/** Process-wide voice state shared by MainActivity, the overlay and VoiceService. */
object VoiceBus {
    fun interface Listener {
        fun onVoiceChanged(state: VoiceState, status: String)
    }

    /** Live dictation text of the current (or last) listening turn. */
    fun interface TranscriptListener {
        fun onTranscript(text: String)
    }

    /** Microphone level (SpeechRecognizer rmsdB) while listening; high frequency. */
    fun interface LevelListener {
        fun onLevel(rmsDb: Float)
    }

    /** Bridge connection and the last assistant reply (text of `say` frames). */
    fun interface DetailsListener {
        fun onDetails(isBridgeConnected: Boolean, reply: String)
    }

    /** History of the current conversation (user turns and full replies). */
    fun interface HistoryListener {
        fun onHistory(history: ConversationHistory)
    }

    private val main = Handler(Looper.getMainLooper())
    private val historyListeners = CopyOnWriteArrayList<HistoryListener>()

    @Volatile
    var history: ConversationHistory = ConversationHistory()
        private set
    private val levelListeners = CopyOnWriteArrayList<LevelListener>()
    private val detailsListeners = CopyOnWriteArrayList<DetailsListener>()

    @Volatile
    var isBridgeConnected: Boolean = false
        private set

    @Volatile
    var reply: String = ""
        private set
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val transcriptListeners = CopyOnWriteArrayList<TranscriptListener>()

    @Volatile
    var state: VoiceState = VoiceState.STOPPED
        private set

    @Volatile
    var status: String = ""
        private set

    @Volatile
    var transcript: String = ""
        private set

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onVoiceChanged(state, status)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun publish(newState: VoiceState, newStatus: String? = null) {
        state = newState
        if (newStatus != null) status = newStatus
        main.post { listeners.forEach { it.onVoiceChanged(state, status) } }
    }

    fun publishStatus(newStatus: String) = publish(state, newStatus)

    fun addTranscriptListener(listener: TranscriptListener) {
        transcriptListeners.add(listener)
        listener.onTranscript(transcript)
    }

    fun removeTranscriptListener(listener: TranscriptListener) {
        transcriptListeners.remove(listener)
    }

    fun publishTranscript(text: String) {
        transcript = text
        main.post { transcriptListeners.forEach { it.onTranscript(transcript) } }
    }

    fun addLevelListener(listener: LevelListener) {
        levelListeners.add(listener)
    }

    fun removeLevelListener(listener: LevelListener) {
        levelListeners.remove(listener)
    }

    fun publishLevel(rmsDb: Float) {
        main.post { levelListeners.forEach { it.onLevel(rmsDb) } }
    }

    fun addDetailsListener(listener: DetailsListener) {
        detailsListeners.add(listener)
        listener.onDetails(isBridgeConnected, reply)
    }

    fun removeDetailsListener(listener: DetailsListener) {
        detailsListeners.remove(listener)
    }

    fun publishBridge(isConnected: Boolean) {
        isBridgeConnected = isConnected
        publishDetails()
    }

    fun publishReply(text: String) {
        reply = text
        publishDetails()
    }

    fun addHistoryListener(listener: HistoryListener) {
        historyListeners.add(listener)
        listener.onHistory(history)
    }

    fun removeHistoryListener(listener: HistoryListener) {
        historyListeners.remove(listener)
    }

    /** Applies [transform] to the history and notifies listeners if it changed. */
    @Synchronized
    fun updateHistory(transform: (ConversationHistory) -> ConversationHistory) {
        val updated = transform(history)
        if (updated === history) return
        history = updated
        main.post { historyListeners.forEach { it.onHistory(updated) } }
    }

    private fun publishDetails() {
        main.post { detailsListeners.forEach { it.onDetails(isBridgeConnected, reply) } }
    }
}

/** Entry points used by the UI and the overlay to drive [VoiceService]. */
object VoiceLauncher {
    private const val TAG = "PupusVoice"

    /** Push-to-talk: reuses the running service or starts it in the foreground. */
    fun tap(context: Context) {
        val running = VoiceService.current
        if (running != null) {
            running.onTap()
            return
        }
        send(context, VoiceService.ACTION_TAP)
    }

    fun start(context: Context) = send(context, VoiceService.ACTION_START)

    fun stop(context: Context) {
        VoiceService.current?.stopVoice() ?: Log.i(TAG, "stop: service not running")
    }

    /** New conversation. Without car mode there is no bridge connection, so it is not started. */
    fun reset(context: Context) {
        val running = VoiceService.current
        if (running != null) {
            running.resetConversation()
            return
        }
        val message = context.getString(R.string.reset_needs_car_mode)
        VoiceBus.publishStatus(message)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun send(context: Context, action: String) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Checked here: the service must never stop before startForeground (it would crash the process).
            Log.e(TAG, "RECORD_AUDIO not granted; not starting VoiceService")
            VoiceBus.publish(VoiceState.STOPPED, context.getString(R.string.mic_permission_missing))
            return
        }
        val intent = Intent(context, VoiceService::class.java).setAction(action)
        try {
            context.startForegroundService(intent)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException extends IllegalStateException.
            Log.e(TAG, "Cannot start VoiceService from this context", e)
            VoiceBus.publish(VoiceState.STOPPED, context.getString(R.string.error_voice_start))
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to start VoiceService", e)
            VoiceBus.publish(VoiceState.STOPPED, context.getString(R.string.mic_permission_missing))
        }
    }
}
