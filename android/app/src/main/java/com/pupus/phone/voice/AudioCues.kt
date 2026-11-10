package com.pupus.phone.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Driving feedback. Exactly two sounds per listening turn: a rising chime when listening
 * starts and a falling one when it ends (sent, cancelled, nothing heard or failed).
 * The system recognizer plays its own earcon on every segment restart, so its streams are
 * muted while the microphone is open (after the start chime, until right before the end
 * chime). Also handles transient audio focus: music pauses while listening and ducks
 * while speaking. Main thread only.
 */
class AudioCues(context: Context) {
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val tones = TonePlayer()
    private val muter = earconMuter(context)
    private var focus: AudioFocusRequest? = null
    private var focusGain = 0

    /** Pauses music (exclusive focus) and plays the start chime. */
    fun listeningStarted() {
        acquire(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        tones.play(TonePlayer.Cue.START)
    }

    /** Silences the recognizer earcon; call right before each startListening. */
    fun muteRecognizerEarcons() = muter.mute()

    /** The single end-of-listening cue; restores the streams and gives the focus back. */
    fun listeningEnded() {
        muter.restore()
        tones.play(TonePlayer.Cue.END)
        release()
    }

    /** Ducks music while the assistant speaks. */
    fun speechStarted() = acquire(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)

    /** Also unmutes: every exit path (abort, error, destroy) goes through here. */
    fun release() {
        muter.restore()
        val request = focus ?: return
        audio.abandonAudioFocusRequest(request)
        focus = null
        focusGain = 0
    }

    fun shutdown() = release()

    private fun acquire(gain: Int) {
        if (focusGain == gain) return
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change -> Log.i(TAG, "Audio focus change: $change") }
            .build()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        val result = audio.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.w(TAG, "Audio focus $gain not granted: $result")
        focus = request
        focusGain = gain
    }

    companion object {
        /**
         * Delay before opening the microphone: the start chime (plus output latency, e.g.
         * Bluetooth) must finish before its stream is muted, and must not be transcribed.
         */
        const val START_DELAY_MS = ToneSynth.DURATION_MS + 220L

        /** Streams the recognizer earcon may use (media on most services, sonification on others). */
        val EARCON_STREAMS = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
        private const val TAG = "PupusAudio"
        private const val PREFS = "pupus_audio"
        private const val KEY_MUTED = "muted_streams"

        /** Unmutes streams left muted by a previous process. Safe to call at any time. */
        fun recoverMutedStreams(context: Context) {
            earconMuter(context)
        }

        private fun earconMuter(context: Context): StreamMuter {
            val audio = context.getSystemService(AudioManager::class.java)
            return StreamMuter(AudioManagerVolume(audio), PrefsMutedStore(context), EARCON_STREAMS).also { it.recover() }
        }
    }

    private class AudioManagerVolume(private val audio: AudioManager) : StreamVolume {
        override fun isMuted(stream: Int): Boolean = audio.isStreamMute(stream)

        override fun setMuted(stream: Int, isMuted: Boolean): Boolean {
            val direction = if (isMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            return try {
                audio.adjustStreamVolume(stream, direction, 0)
                true
            } catch (e: SecurityException) {
                // Streams aliased to the ringer need Do Not Disturb access to be muted.
                Log.w(TAG, "Cannot ${if (isMuted) "mute" else "unmute"} stream $stream", e)
                false
            }
        }
    }

    private class PrefsMutedStore(context: Context) : MutedStreamStore {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun load(): Set<Int> =
            prefs.getStringSet(KEY_MUTED, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

        override fun save(streams: Set<Int>) {
            if (!prefs.edit().putStringSet(KEY_MUTED, streams.map { it.toString() }.toSet()).commit()) {
                Log.w(TAG, "Muted streams not persisted")
            }
        }
    }
}
