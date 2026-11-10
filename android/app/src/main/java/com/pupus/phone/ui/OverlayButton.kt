package com.pupus.phone.ui

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.pupus.phone.R
import com.pupus.phone.voice.VoiceBus
import com.pupus.phone.voice.VoiceLauncher
import com.pupus.phone.voice.VoiceState
import kotlin.math.abs

/**
 * Draggable round push-to-talk button drawn as TYPE_ACCESSIBILITY_OVERLAY, so it
 * floats over any app (e.g. Spotify) without SYSTEM_ALERT_WINDOW.
 */
class OverlayButton(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val sizePx = (SIZE_DP * service.resources.displayMetrics.density).toInt()
    private val background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var view: TextView? = null
    private val listener = VoiceBus.Listener { state, _ -> render(state) }

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = service.resources.displayMetrics.widthPixels - sizePx - MARGIN_PX
        y = service.resources.displayMetrics.heightPixels / 3
    }

    fun show() {
        if (view != null) return
        val button = TextView(service).apply {
            text = "🎤"
            textSize = ICON_SP
            gravity = Gravity.CENTER
            contentDescription = service.getString(R.string.push_to_talk_description)
            this.background = this@OverlayButton.background
            elevation = ELEVATION_PX
        }
        attachTouch(button)
        try {
            windowManager.addView(button, params)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot add overlay", e)
            return
        }
        view = button
        VoiceBus.addListener(listener)
    }

    fun hide() {
        VoiceBus.removeListener(listener)
        val current = view ?: return
        view = null
        try {
            windowManager.removeView(current)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay already detached", e)
        }
    }

    private fun render(state: VoiceState) {
        background.setColor(service.getColor(PttColors.colorRes(state)))
        view?.alpha = if (state == VoiceState.STOPPED) STOPPED_ALPHA else 1f
        // Car mode keeps the screen on through this window while another app is in front.
        view?.keepScreenOn = state != VoiceState.STOPPED
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(button: View) {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var isDragging = false
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    originX = params.x; originY = params.y
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && (abs(dx) > slop || abs(dy) > slop)) isDragging = true
                    if (isDragging) move(v, originX + dx.toInt(), originY + dy.toInt())
                }
                MotionEvent.ACTION_UP -> if (!isDragging) VoiceLauncher.tap(service)
            }
            true
        }
    }

    private fun move(v: View, x: Int, y: Int) {
        params.x = x
        params.y = y
        try {
            windowManager.updateViewLayout(v, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay not attached while moving", e)
        }
    }

    private companion object {
        const val TAG = "PupusOverlay"
        const val SIZE_DP = 72
        const val MARGIN_PX = 24
        const val ICON_SP = 28f
        const val ELEVATION_PX = 8f
        const val STOPPED_ALPHA = 0.6f
    }
}

object PttColors {
    fun colorRes(state: VoiceState): Int = when (state) {
        VoiceState.STOPPED -> R.color.ptt_stopped
        VoiceState.IDLE -> R.color.ptt_idle
        VoiceState.LISTENING -> R.color.ptt_listening
        VoiceState.THINKING -> R.color.ptt_thinking
        VoiceState.SPEAKING -> R.color.ptt_speaking
    }

    fun labelRes(state: VoiceState): Int = when (state) {
        VoiceState.STOPPED -> R.string.state_stopped
        VoiceState.IDLE -> R.string.state_idle
        VoiceState.LISTENING -> R.string.state_listening
        VoiceState.THINKING -> R.string.state_thinking
        VoiceState.SPEAKING -> R.string.state_speaking
    }
}
