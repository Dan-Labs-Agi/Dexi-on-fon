package com.danny.glasses

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * Monochrome voice orb: one layered white-on-black sphere whose motion shows what Dani is doing.
 * The 21st.dev orb style, ported to a plain Android View (style ported, no components).
 *
 * IDLE       - slow dim breath
 * CONNECTING - faster pulse with a rotating arc
 * LISTENING  - steady breath that swells with the mic level
 * SPEAKING   - bright, lively shimmer
 */
class OrbView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    enum class State { IDLE, CONNECTING, LISTENING, SPEAKING }

    @Volatile var state = State.IDLE
    /** Mic level 0..100, set by the activity while listening. */
    @Volatile var level = 0

    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f * 0.92f
        if (maxR <= 0) return

        val levelF = level.coerceIn(0, 100) / 100f
        val breathe = when (state) {
            State.IDLE -> 0.025f * sin(phase)
            State.CONNECTING -> 0.05f * sin(phase * 2)
            State.LISTENING -> 0.03f * sin(phase * 1.5f) + levelF * 0.28f
            State.SPEAKING -> 0.09f * sin(phase * 3) + 0.05f * sin(phase * 6.3f)
        }
        val coreR = maxR * (0.40f * (1f + breathe))

        val coreColor: Int
        val glowAlpha: Float
        when (state) {
            State.IDLE -> { coreColor = 0xFF737373.toInt(); glowAlpha = 0.30f }
            State.CONNECTING -> { coreColor = 0xFFA3A3A3.toInt(); glowAlpha = 0.45f }
            State.LISTENING -> { coreColor = 0xFFFAFAFA.toInt(); glowAlpha = 0.50f + 0.45f * levelF }
            State.SPEAKING -> { coreColor = 0xFFFFFFFF.toInt(); glowAlpha = 0.85f }
        }

        // Soft outer glow
        paint.shader = RadialGradient(cx, cy, maxR, coreColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        paint.alpha = (glowAlpha * 255 * 0.55f).toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, maxR, paint)
        paint.shader = null
        paint.alpha = 255

        // Ripple rings while active
        if (state == State.LISTENING || state == State.SPEAKING) {
            val ringCount = if (state == State.SPEAKING) 3 else 2
            paint.style = Paint.Style.STROKE
            for (i in 0 until ringCount) {
                val f = (phase / (2 * Math.PI).toFloat() + i.toFloat() / ringCount) % 1f
                val r = coreR + (maxR - coreR) * f
                val a = ((1f - f) * (if (state == State.SPEAKING) 0.45f else 0.22f + 0.25f * levelF) * 255).toInt()
                paint.color = Color.WHITE
                paint.alpha = a
                paint.strokeWidth = 2f + 2f * (1f - f)
                canvas.drawCircle(cx, cy, r, paint)
            }
            paint.alpha = 255
            paint.style = Paint.Style.FILL
        }

        // Rotating arc while connecting
        if (state == State.CONNECTING) {
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            paint.alpha = 120
            paint.strokeWidth = 5f
            paint.strokeCap = Paint.Cap.ROUND
            val start = Math.toDegrees(phase.toDouble()).toFloat() * 2f
            val inset = coreR * 1.35f
            canvas.drawArc(RectF(cx - inset, cy - inset, cx + inset, cy + inset), start, 90f, false, paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 255
        }

        // Core sphere with a gentle top-lit gradient
        val edge = if (state == State.IDLE) 0xFF404040.toInt() else 0xFF8A8A8A.toInt()
        paint.shader = RadialGradient(cx - coreR * 0.3f, cy - coreR * 0.35f, coreR * 1.6f,
            coreColor, edge, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, coreR, paint)
        paint.shader = null
    }
}
