package com.pupus.phone.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Driving feedback: short tones when listening starts and when a turn is sent, and
 * transient audio focus so music pauses while listening and ducks while speaking.
 * Main thread only.
 */
class AudioCues(context: Context) {
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var focusGain = 0
    private var tones: ToneGenerator? = null
    private var hasToneFailed = false

    /** Pauses music (exclusive focus) and beeps. */
    fun listeningStarted() {
        acquire(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        tone(ToneGenerator.TONE_PROP_BEEP, START_TONE_MS)
    }

    /** Confirms the utterance was sent and gives the audio focus back. */
    fun utteranceSent() {
        tone(ToneGenerator.TONE_PROP_ACK, SENT_TONE_MS)
        release()
    }

    /** Ducks music while the assistant speaks. */
    fun speechStarted() = acquire(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)

    fun release() {
        val request = focus ?: return
        audio.abandonAudioFocusRequest(request)
        focus = null
        focusGain = 0
    }

    fun shutdown() {
        release()
        tones?.release()
        tones = null
    }

    private fun acquire(gain: Int) {
        if (focusGain == gain) return
        release()
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change -> Log.i(TAG, "Audio focus change: $change") }
            .build()
        val result = audio.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.w(TAG, "Audio focus $gain not granted: $result")
        focus = request
        focusGain = gain
    }

    private fun tone(type: Int, durationMs: Int) {
        val generator = tones ?: createToneGenerator() ?: return
        if (!generator.startTone(type, durationMs)) Log.w(TAG, "Tone $type not played")
    }

    private fun createToneGenerator(): ToneGenerator? {
        if (hasToneFailed) return null
        return try {
            ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME).also { tones = it }
        } catch (e: RuntimeException) {
            Log.e(TAG, "ToneGenerator unavailable; continuing without tones", e)
            hasToneFailed = true
            null
        }
    }

    companion object {
        /** Delay before opening the microphone so the start tone is not transcribed. */
        const val START_TONE_MS = 120
        private const val SENT_TONE_MS = 150
        private const val TONE_VOLUME = 80
        private const val TAG = "PupusAudio"
    }
}
