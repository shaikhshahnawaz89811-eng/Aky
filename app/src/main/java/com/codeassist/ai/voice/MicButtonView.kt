package com.codeassist.ai.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.codeassist.ai.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The mic that lives inside the chat box, left of Send: a 40dp circle like the other composer
 * buttons. The pulse rings and halo are drawn outside its bounds (the two parents stop clipping). Gestures:
 *  - tap          -> tap-to-talk (or stop, when something is already running)
 *  - press & hold -> push-to-talk; release sends, slide left cancels, slide up locks to continuous
 */
class MicButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Visual { IDLE, TAP, HOLD, CONTINUOUS, THINKING, SPEAKING }

    interface Listener {
        fun onTap()
        fun onHoldStart()
        fun onHoldEnd(cancelled: Boolean)
        fun onHoldLock()
        fun onHoldDrag(cancelling: Boolean, locking: Boolean)
    }

    companion object {
        const val BLUE = 0xFF6EC1FF.toInt()
        const val BLUE_GLOW = 0xFF59C2FF.toInt()
        const val PURPLE = 0xFFB49CFF.toInt()
        const val GREEN = 0xFF7BE3A8.toInt()
        private const val BODY_DARK = 0xFF121924.toInt()
        private const val ICON_IDLE = 0xFF93A1B3.toInt()
        private const val ICON_ACTIVE = 0xFFEAF6FF.toInt()
        private const val REACH = 8f
    }

    var listener: Listener? = null

    var visual: Visual = Visual.IDLE
        set(value) {
            if (field == value) return
            field = value
            contentDescription = when (value) {
                Visual.IDLE -> "Mic. Tap karke bolo, ya dabaye rakho"
                Visual.TAP, Visual.HOLD, Visual.CONTINUOUS -> "Sunna band karo"
                Visual.THINKING -> "Jawab ban raha hai. Rokne ke liye dabao"
                Visual.SPEAKING -> "Bolna band karo"
            }
            syncAnimator()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcBox = RectF()
    private val barBox = RectF()
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_mic)?.mutate()

    private var bodyShader: Shader? = null
    private var lightShader: Shader? = null
    private var haloBlue: Shader? = null
    private var haloPurple: Shader? = null
    private var haloGreen: Shader? = null

    private var t = 0f
    private var level = 0f
    private var animator: ValueAnimator? = null

    // touch state
    private var downX = 0f
    private var downY = 0f
    private var pressed = false
    private var holding = false
    private var locked = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val cancelDistance = 70f * density
    private val lockDistance = 64f * density
    private val longPress = Runnable {
        if (pressed && visual == Visual.IDLE) {
            holding = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onHoldStart()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Mic. Tap karke bolo, ya dabaye rakho"
    }

    fun setLevel(value: Float) {
        level = level * 0.55f + value.coerceIn(0f, 1f) * 0.45f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // pulse rings extend past the 40dp row, so the two parents must not clip them
        var p = parent
        var depth = 0
        while (p is ViewGroup && depth < 2) {
            p.clipChildren = false
            p.clipToPadding = false
            p = p.parent
            depth++
        }
        syncAnimator()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun syncAnimator() {
        if (visual == Visual.IDLE || !isAttachedToWindow) {
            animator?.cancel()
            animator = null
            t = 0f
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1700L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedFraction
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val base = 20f * density
        bodyShader = LinearGradient(
            cx - base, cy - base, cx + base, cy + base,
            0xFF7CC8FF.toInt(), 0xFF2F81C4.toInt(), Shader.TileMode.CLAMP
        )
        lightShader = RadialGradient(
            cx - base * 0.3f, cy - base * 0.45f, base * 1.15f,
            intArrayOf(0x59FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        val haloR = base + REACH * density * 0.75f
        haloBlue = makeHalo(cx, cy, haloR, BLUE_GLOW)
        haloPurple = makeHalo(cx, cy, haloR, PURPLE)
        haloGreen = makeHalo(cx, cy, haloR, GREEN)
    }

    private fun makeHalo(cx: Float, cy: Float, r: Float, color: Int): Shader {
        val transparent = color and 0x00FFFFFF
        val strong = (110 shl 24) or (color and 0x00FFFFFF)
        return RadialGradient(cx, cy, r, intArrayOf(strong, strong, transparent), floatArrayOf(0f, 0.68f, 1f), Shader.TileMode.CLAMP)
    }

    private fun withAlpha(color: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = 20f * density
        val v = visual
        val active = v == Visual.TAP || v == Visual.HOLD || v == Visual.CONTINUOUS
        val pulse = (sin(t * 2.0 * PI).toFloat() * 0.5f) + 0.5f
        var r = base
        if (v == Visual.HOLD) r = base * (1.06f + 0.10f * pulse) else if (active) r = base * (0.98f + 0.05f * pulse)

        // pulse rings + halo (listening)
        if (active) {
            line.strokeWidth = 1.5f * density
            for (k in 0..1) {
                val p = (t + k * 0.5f) % 1f
                val rr = base + REACH * density * p
                line.color = withAlpha(BLUE_GLOW, (150 * (1f - p)).toInt())
                canvas.drawCircle(cx, cy, rr, line)
            }
            halo.shader = haloBlue
            halo.alpha = (150 + 105 * pulse).toInt()
            canvas.drawCircle(cx, cy, base + REACH * density * 0.75f, halo)
        } else if (v == Visual.SPEAKING) {
            halo.shader = haloGreen
            halo.alpha = (120 + 90 * pulse).toInt()
            canvas.drawCircle(cx, cy, base + REACH * density * 0.75f, halo)
        } else if (v == Visual.THINKING) {
            halo.shader = haloPurple
            halo.alpha = (70 + 60 * pulse).toInt()
            canvas.drawCircle(cx, cy, base + REACH * density * 0.75f, halo)
        }

        // body
        when {
            active -> {
                fill.shader = bodyShader
                canvas.drawCircle(cx, cy, r, fill)
                fill.shader = lightShader
                canvas.drawCircle(cx, cy, r, fill)
                fill.shader = null
                line.strokeWidth = 1f * density
                line.color = withAlpha(0xFFFFFF, 80)
                canvas.drawCircle(cx, cy, r - 1.5f * density, line)
            }
            v == Visual.THINKING -> {
                fill.shader = null
                fill.color = BODY_DARK
                canvas.drawCircle(cx, cy, base, fill)
                line.strokeWidth = 2f * density
                line.color = withAlpha(PURPLE, 64)
                canvas.drawCircle(cx, cy, base - density, line)
                line.color = PURPLE
                line.strokeCap = Paint.Cap.ROUND
                val inset = density
                arcBox.set(cx - base + inset, cy - base + inset, cx + base - inset, cy + base - inset)
                canvas.drawArc(arcBox, t * 720f, 100f, false, line)
                line.strokeCap = Paint.Cap.BUTT
            }
            v == Visual.SPEAKING -> {
                fill.shader = null
                fill.color = withAlpha(GREEN, 36)
                canvas.drawCircle(cx, cy, base, fill)
                line.strokeWidth = density
                line.color = withAlpha(GREEN, 155)
                canvas.drawCircle(cx, cy, base - 0.5f * density, line)
            }
            else -> {
                fill.shader = null
                fill.color = BODY_DARK
                canvas.drawCircle(cx, cy, base, fill)
                line.strokeWidth = density
                line.color = withAlpha(0xFFFFFF, 41)
                canvas.drawCircle(cx, cy, base - 0.5f * density, line)
            }
        }

        // glyph
        when (v) {
            Visual.IDLE -> drawIcon(canvas, cx, cy, ICON_IDLE)
            Visual.TAP, Visual.HOLD -> drawIcon(canvas, cx, cy, ICON_ACTIVE)
            Visual.THINKING -> drawIcon(canvas, cx, cy, withAlpha(PURPLE, 150))
            Visual.CONTINUOUS -> drawBars(canvas, cx, cy)
            Visual.SPEAKING -> {
                fill.shader = null
                fill.color = GREEN
                val half = 6f * density
                barBox.set(cx - half, cy - half, cx + half, cy + half)
                canvas.drawRoundRect(barBox, 2.5f * density, 2.5f * density, fill)
            }
        }
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val d = icon ?: return
        val half = (10f * density).toInt()
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.setTint(color)
        d.draw(canvas)
    }

    private fun drawBars(canvas: Canvas, cx: Float, cy: Float) {
        fill.shader = null
        fill.color = ICON_ACTIVE
        val w = 3f * density
        val gap = 3.2f * density
        for (i in 0 until 4) {
            val wave = (0.55f + 0.45f * sin((t * 2.0 + i * 0.23) * 2.0 * PI).toFloat())
            val amp = max(0.18f, min(1f, (0.22f + 0.78f * level) * wave))
            val h = 5f * density + 14f * density * amp
            val x = cx + (i - 1.5f) * (w + gap)
            barBox.set(x - w / 2f, cy - h / 2f, x + w / 2f, cy + h / 2f)
            canvas.drawRoundRect(barBox, w / 2f, w / 2f, fill)
        }
    }

    // ---------- gestures ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                pressed = true
                holding = false
                locked = false
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (holding && !locked) {
                    val cancelling = dx < -cancelDistance
                    val locking = dy < -lockDistance
                    listener?.onHoldDrag(cancelling, locking)
                    if (locking && !cancelling) {
                        locked = true
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        listener?.onHoldLock()
                    }
                } else if (!holding && (kotlin.math.abs(dx) > slop * 2 || kotlin.math.abs(dy) > slop * 2)) {
                    removeCallbacks(longPress)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val wasHolding = holding
                val dx = event.rawX - downX
                pressed = false
                holding = false
                if (wasHolding) {
                    if (!locked) listener?.onHoldEnd(dx < -cancelDistance)
                } else {
                    performClick()
                    listener?.onTap()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val wasHolding = holding
                pressed = false
                holding = false
                if (wasHolding && !locked) listener?.onHoldEnd(true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
