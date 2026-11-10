package com.pupus.phone.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.pupus.phone.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Round push-to-talk button: a circle filled with the state colour, a soft glow and a
 * microphone. Listening: the glow and an outer ring follow the microphone level.
 * Thinking: a rotating arc. Speaking: a gentle pulse. Ready: a slow breathing glow.
 * Always square; the circle takes [circleFraction] of the side, the rest is glow room.
 */
class PttCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var circleFraction = DEFAULT_CIRCLE_FRACTION

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val icon: Drawable = requireNotNull(context.getDrawable(R.drawable.ic_mic)).mutate()
    private val arcBounds = RectF()
    private var color = context.getColor(R.color.ptt_stopped)
    private var iconColor = context.getColor(R.color.on_dark_fill)
    private var motion = PttMotion.NONE
    private var style: PttStyle? = null
    private var colorAnimator: ValueAnimator? = null
    private var targetLevel = 0f
    private var level = 0f

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.push_to_talk_description)
        icon.setTint(iconColor)
    }

    fun render(next: PttStyle) {
        if (next == style) return
        style = next
        motion = next.motion
        if (motion != PttMotion.LEVEL) targetLevel = 0f
        iconColor = context.getColor(next.iconColorRes)
        icon.setTint(iconColor)
        animateColor(context.getColor(next.colorRes))
        invalidate()
    }

    fun setLevel(rmsDb: Float) {
        if (motion == PttMotion.LEVEL) targetLevel = PttStyles.level(rmsDb)
    }

    private fun animateColor(target: Int) {
        colorAnimator?.cancel()
        if (target == color) return
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), color, target).apply {
            duration = COLOR_MS
            addUpdateListener {
                color = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val isHeightFree = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
        val side = if (isHeightFree || height == 0) width else min(width, height)
        setMeasuredDimension(side, side)
    }

    override fun onDetachedFromWindow() {
        colorAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val side = min(width, height).toFloat()
        val radius = side * circleFraction / 2f
        val room = side / 2f - radius
        val now = SystemClock.uptimeMillis()
        level += (targetLevel - level) * LEVEL_SMOOTHING

        val glow = room * glowAmount(now)
        if (glow > 1f) drawGlow(canvas, cx, cy, radius, glow)
        if (motion == PttMotion.LEVEL) drawLevelRing(canvas, cx, cy, radius, room)
        fillPaint.color = color
        canvas.drawCircle(cx, cy, radius, fillPaint)
        if (motion == PttMotion.SPIN) drawSpinner(canvas, cx, cy, radius, now)
        drawIcon(canvas, cx, cy, radius)
        if (motion != PttMotion.NONE || colorAnimator?.isRunning == true) postInvalidateOnAnimation()
    }

    /** Fraction (0..1) of the free room covered by the glow. */
    private fun glowAmount(now: Long): Float = when (motion) {
        PttMotion.NONE -> 0f
        PttMotion.BREATHE -> 0.35f + 0.15f * wave(now, BREATHE_MS)
        PttMotion.LEVEL -> 0.35f + 0.65f * level
        PttMotion.SPIN -> 0.4f
        PttMotion.PULSE -> 0.3f + 0.5f * wave(now, PULSE_MS)
    }

    private fun wave(now: Long, periodMs: Long): Float =
        (0.5 + 0.5 * sin(2 * PI * (now % periodMs) / periodMs)).toFloat()

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, glow: Float) {
        val outer = radius + glow
        glowPaint.shader = RadialGradient(
            cx, cy, outer,
            intArrayOf(withAlpha(GLOW_ALPHA), withAlpha(GLOW_ALPHA), Color.TRANSPARENT),
            floatArrayOf(0f, radius / outer, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, outer, glowPaint)
    }

    private fun drawLevelRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, room: Float) {
        ringPaint.strokeWidth = RING_DP * density
        ringPaint.color = withAlpha((RING_ALPHA * (0.4f + 0.6f * level)).toInt())
        val ringRadius = radius + ringPaint.strokeWidth + (room - 2 * ringPaint.strokeWidth) * level * 0.8f
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)
    }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, radius: Float, now: Long) {
        ringPaint.strokeWidth = SPINNER_DP * density
        ringPaint.color = iconColor
        ringPaint.alpha = SPINNER_ALPHA
        val inset = radius - ringPaint.strokeWidth * 1.5f
        arcBounds.set(cx - inset, cy - inset, cx + inset, cy + inset)
        val start = 360f * (now % SPIN_MS) / SPIN_MS
        canvas.drawArc(arcBounds, start, SPINNER_SWEEP, false, ringPaint)
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val half = (radius * ICON_FRACTION).toInt()
        icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        icon.draw(canvas)
    }

    private fun withAlpha(alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private companion object {
        const val DEFAULT_CIRCLE_FRACTION = 0.8f
        const val COLOR_MS = 250L
        const val BREATHE_MS = 3_200L
        const val PULSE_MS = 1_100L
        const val SPIN_MS = 1_400L
        const val LEVEL_SMOOTHING = 0.25f
        const val GLOW_ALPHA = 0x55
        const val RING_ALPHA = 0xCC
        const val RING_DP = 3f
        const val SPINNER_DP = 5f
        const val SPINNER_ALPHA = 0xE6
        const val SPINNER_SWEEP = 110f
        const val ICON_FRACTION = 0.42f
    }
}
