package com.codeassist.ai.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Scrolling bar waveform. Every bar is a real microphone level sample from the speech recognizer. */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val count = 44
    private val levels = FloatArray(count)
    private var head = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    var barColor: Int = 0xFF6EC1FF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    fun push(level: Float) {
        levels[head] = level.coerceIn(0f, 1f)
        head = (head + 1) % count
        invalidate()
    }

    fun clear() {
        levels.fill(0f)
        head = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val slot = w / count
        val bar = (slot * 0.55f).coerceAtLeast(2f * density)
        val minH = 3f * density
        paint.color = barColor
        for (i in 0 until count) {
            val level = levels[(head + i) % count]
            val bh = minH + (h - minH) * level
            val cx = slot * i + slot / 2f
            // older samples fade out towards the left
            paint.alpha = (60 + 195 * (i / (count - 1f))).toInt().coerceIn(0, 255)
            rect.set(cx - bar / 2f, (h - bh) / 2f, cx + bar / 2f, (h + bh) / 2f)
            canvas.drawRoundRect(rect, bar / 2f, bar / 2f, paint)
        }
    }
}
