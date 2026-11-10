package com.pupus.phone.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pure synthesis of the two cues: a soft rising (start) or falling (end) two-note chime. */
object ToneSynth {
    const val SAMPLE_RATE = 44_100
    const val LOW_HZ = 784.0 // G5
    const val HIGH_HZ = 1_175.0 // D6
    const val FIRST_NOTE_MS = 90
    const val SECOND_NOTE_MS = 140
    const val DURATION_MS = FIRST_NOTE_MS + SECOND_NOTE_MS
    const val PEAK = 0.45
    private const val ATTACK_MS = 8

    fun start(): ShortArray = twoNotes(LOW_HZ, HIGH_HZ)

    fun end(): ShortArray = twoNotes(HIGH_HZ, LOW_HZ)

    fun twoNotes(firstHz: Double, secondHz: Double): ShortArray =
        note(firstHz, FIRST_NOTE_MS) + note(secondHz, SECOND_NOTE_MS)

    /** Sine plus a quiet octave, with raised-cosine attack and decay so it never clicks. */
    fun note(hz: Double, ms: Int): ShortArray {
        val count = SAMPLE_RATE * ms / 1000
        val attack = SAMPLE_RATE * ATTACK_MS / 1000
        return ShortArray(count) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val wave = sin(2 * PI * hz * t) * 0.8 + sin(4 * PI * hz * t) * 0.2
            (wave * envelope(i, count, attack) * PEAK * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun envelope(i: Int, count: Int, attack: Int): Double {
        if (i < attack) return 0.5 - 0.5 * cos(PI * i / attack)
        val progress = (i - attack).toDouble() / (count - 1 - attack)
        return 0.5 + 0.5 * cos(PI * progress)
    }
}

/** Plays the generated cues on the assistant (media) stream. Main thread only. */
class TonePlayer {
    enum class Cue { START, END }

    private val main = Handler(Looper.getMainLooper())
    private val pcm = mapOf(Cue.START to ToneSynth.start(), Cue.END to ToneSynth.end())

    fun play(cue: Cue) {
        val samples = pcm.getValue(cue)
        val track = try {
            build(samples)
        } catch (e: RuntimeException) {
            // UnsupportedOperationException / IllegalArgumentException / IllegalStateException.
            Log.e(TAG, "Cannot build tone track; continuing without tone", e)
            return
        }
        try {
            track.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Tone not played", e)
            track.release()
            return
        }
        main.postDelayed({ track.release() }, ToneSynth.DURATION_MS + RELEASE_MARGIN_MS)
    }

    private fun build(samples: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ToneSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        val written = track.write(samples, 0, samples.size)
        if (written != samples.size) {
            track.release()
            throw IllegalStateException("Tone write returned $written of ${samples.size}")
        }
        return track
    }

    private companion object {
        const val TAG = "PupusTone"
        const val RELEASE_MARGIN_MS = 500L
    }
}
