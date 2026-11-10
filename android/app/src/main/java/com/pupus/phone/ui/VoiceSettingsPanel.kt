package com.pupus.phone.ui

import android.app.Activity
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import com.pupus.phone.AppSettings
import com.pupus.phone.R
import com.pupus.phone.voice.DictationConfig
import com.pupus.phone.voice.RecognizerMode
import java.util.Locale

/** Binds "Pausa para enviar" and the recognizer choice. Changes apply from the next turn. */
class VoiceSettingsPanel(private val activity: Activity, private val settings: AppSettings) {
    private val pauseLabel: TextView = activity.findViewById(R.id.pauseLabel)

    fun bind() {
        bindPause()
        bindRecognizer()
    }

    private fun bindPause() {
        val seek = activity.findViewById<SeekBar>(R.id.pauseSeek)
        seek.max = ((DictationConfig.MAX_SILENCE_MS - DictationConfig.MIN_SILENCE_MS) / STEP_MS).toInt()
        seek.progress = ((settings.pauseToSendMs - DictationConfig.MIN_SILENCE_MS) / STEP_MS).toInt()
        showPause(settings.pauseToSendMs)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = DictationConfig.MIN_SILENCE_MS + progress * STEP_MS
                showPause(ms)
                if (fromUser) settings.pauseToSendMs = ms
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun showPause(ms: Long) {
        val seconds = String.format(SPANISH, "%.2f", ms / 1000.0).trimEnd('0').trimEnd(',')
        pauseLabel.text = activity.getString(R.string.pause_to_send, seconds)
    }

    private fun bindRecognizer() {
        val group = activity.findViewById<RadioGroup>(R.id.recognizerGroup)
        group.check(
            when (settings.recognizerMode) {
                RecognizerMode.AUTO -> R.id.recognizerAuto
                RecognizerMode.ON_DEVICE -> R.id.recognizerOnDevice
                RecognizerMode.DEFAULT -> R.id.recognizerDefault
            },
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            settings.recognizerMode = when (checkedId) {
                R.id.recognizerOnDevice -> RecognizerMode.ON_DEVICE
                R.id.recognizerDefault -> RecognizerMode.DEFAULT
                else -> RecognizerMode.AUTO
            }
        }
    }

    private companion object {
        const val STEP_MS = 250L
        val SPANISH: Locale = Locale.forLanguageTag("es-ES")
    }
}
