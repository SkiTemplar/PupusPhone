package com.pupus.phone.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Switch
import android.widget.TextView
import com.pupus.phone.PhoneAccessibilityService
import com.pupus.phone.R
import com.pupus.phone.car.MusicAccess
import com.pupus.phone.guard.AccessibilityGuard
import com.pupus.phone.voice.VoiceBus
import com.pupus.phone.voice.VoiceLauncher
import com.pupus.phone.voice.VoiceState

/** Driving screen: one big push-to-talk circle, the state word and the conversation text. */
class MainActivity : Activity() {
    private lateinit var circle: PttCircleView
    private lateinit var stateText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var replyText: TextView
    private lateinit var showAllText: TextView
    private lateinit var statusText: TextView
    private lateinit var a11yChip: TextView
    private lateinit var bridgeChip: TextView
    private lateinit var carMode: Switch
    private var state = VoiceState.STOPPED
    private var isBridgeConnected = false
    private var transcript = ""
    private val carModeSwitch = CarModeSwitch()

    private val voiceListener = VoiceBus.Listener { newState, status ->
        state = newState
        statusText.text = status
        render()
    }
    private val transcriptListener = VoiceBus.TranscriptListener { text ->
        transcript = text
        renderTranscript()
    }
    private val detailsListener = VoiceBus.DetailsListener { isConnected, reply ->
        isBridgeConnected = isConnected
        replyText.text = reply
        render()
    }
    private val historyListener = VoiceBus.HistoryListener { history ->
        showAllText.visibility = if (history.isEmpty) View.INVISIBLE else View.VISIBLE
    }
    private val levelListener = VoiceBus.LevelListener { rmsDb -> circle.setLevel(rmsDb) }

    /** The accessibility service binds asynchronously (e.g. after an update): poll while visible. */
    private val a11yPoll = object : Runnable {
        override fun run() {
            renderA11yChip()
            a11yChip.postDelayed(this, A11Y_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        circle = findViewById(R.id.pttCircle)
        stateText = findViewById(R.id.stateText)
        transcriptText = findViewById(R.id.transcriptText)
        replyText = findViewById(R.id.replyText)
        showAllText = findViewById(R.id.showAllText)
        statusText = findViewById(R.id.statusText)
        a11yChip = findViewById(R.id.a11yChip)
        bridgeChip = findViewById(R.id.bridgeChip)
        carMode = findViewById(R.id.carModeSwitch)
        // The source of truth is whether the service runs, not the state saved before process death.
        carMode.isSaveEnabled = false
        bindControls()
        requestVoicePermissions()
        askMusicAccess()
    }

    /** Once per process while it is missing: the "Música" button of the Android Auto card needs it. */
    private fun askMusicAccess() {
        if (hasAskedMusicAccess || MusicAccess.isGranted(this)) return
        hasAskedMusicAccess = true
        AlertDialog.Builder(this)
            .setTitle(R.string.music_access_title)
            .setMessage(R.string.music_access_message)
            .setPositiveButton(R.string.music_access_grant) { _, _ -> MusicAccess.request(this) }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        AppForeground.onResume()
        AccessibilityGuard.check(this, "main-resume")
        VoiceBus.addListener(voiceListener)
        VoiceBus.addTranscriptListener(transcriptListener)
        VoiceBus.addDetailsListener(detailsListener)
        VoiceBus.addLevelListener(levelListener)
        VoiceBus.addHistoryListener(historyListener)
        a11yPoll.run()
    }

    override fun onPause() {
        AppForeground.onPause()
        VoiceBus.removeListener(voiceListener)
        VoiceBus.removeTranscriptListener(transcriptListener)
        VoiceBus.removeDetailsListener(detailsListener)
        VoiceBus.removeLevelListener(levelListener)
        VoiceBus.removeHistoryListener(historyListener)
        a11yChip.removeCallbacks(a11yPoll)
        super.onPause()
    }

    private fun bindControls() {
        // When car mode is off, a tap starts it and begins listening.
        circle.setOnClickListener { VoiceLauncher.tap(this) }
        findViewById<View>(R.id.carModeCard).setOnClickListener { carMode.toggle() }
        carMode.setOnCheckedChangeListener { _, isChecked ->
            val isRunning = VoiceBus.state != VoiceState.STOPPED
            when (carModeSwitch.onCheckedChanged(isChecked, isRunning)) {
                CarModeAction.START -> VoiceLauncher.start(this)
                CarModeAction.STOP -> VoiceLauncher.stop(this)
                CarModeAction.NONE -> Unit
            }
        }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.replyCard).setOnClickListener {
            startActivity(Intent(this, ConversationActivity::class.java))
        }
        findViewById<View>(R.id.resetButton).setOnClickListener { VoiceLauncher.reset(this) }
    }

    private fun render() {
        val style = PttStyles.of(state, isBridgeConnected)
        circle.render(style)
        stateText.setText(style.labelRes)
        stateText.setTextColor(getColor(style.colorRes))
        renderBridgeChip()
        renderTranscript()
        val isActive = state != VoiceState.STOPPED
        carModeSwitch.rendering { if (carMode.isChecked != isActive) carMode.isChecked = isActive }
        if (isActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Live dictation in large text; a hint when there is nothing to show. */
    private fun renderTranscript() {
        if (transcript.isNotEmpty()) {
            transcriptText.text = transcript
            transcriptText.setTextColor(getColor(R.color.text_primary))
            return
        }
        transcriptText.setText(if (state == VoiceState.STOPPED) R.string.hint_stopped else R.string.hint_idle)
        transcriptText.setTextColor(getColor(R.color.text_tertiary))
    }

    private fun renderA11yChip() {
        val isOn = PhoneAccessibilityService.instance != null
        renderChip(a11yChip, isOn, if (isOn) R.string.chip_a11y_on else R.string.chip_a11y_off)
    }

    private fun renderBridgeChip() {
        renderChip(bridgeChip, isBridgeConnected, if (isBridgeConnected) R.string.chip_bridge_on else R.string.chip_bridge_off)
    }

    private fun renderChip(chip: TextView, isOk: Boolean, textRes: Int) {
        chip.setText(textRes)
        chip.setTextColor(getColor(if (isOk) R.color.status_ok else R.color.status_bad))
    }

    private companion object {
        const val A11Y_POLL_MS = 2_000L

        /** Process-wide so a rotation or reopening the screen does not ask again. */
        private var hasAskedMusicAccess = false
    }
}
