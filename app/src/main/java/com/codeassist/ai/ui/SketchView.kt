package com.codeassist.ai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.codeassist.ai.R
import com.codeassist.ai.ai.Sketch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * T4: draws a [Sketch.Drawing] (100 x 100 canvas units) as a square under the chat text. No touch handling: it only shows the
 * drawing the model described and the app checked. Colours come from the app theme.
 */
class SketchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var drawing: Sketch.Drawing? = null
    private val density = resources.displayMetrics.density

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.card)
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ContextCompat.getColor(context, R.color.card_stroke_strong)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent_dim)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text1)
    }

    /** [json] is [Sketch.toJson]; an unreadable one hides the view. */
    fun setSketch(json: String?) {
        drawing = Sketch.fromJson(json)
        visibility = if (drawing == null) GONE else VISIBLE
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxSide = (360 * density).toInt()
        val w = min(MeasureSpec.getSize(widthMeasureSpec), maxSide)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = drawing ?: return
        val side = min(width, height).toFloat()
        val round = 12f * density
        canvas.drawRoundRect(RectF(0f, 0f, side, side), round, round, bg)
        canvas.drawRoundRect(RectF(0f, 0f, side, side), round, round, border)
        val u = side / Sketch.SIZE
        val pad = 0f
        for (s in d.shapes) {
            when (s.kind) {
                Sketch.Kind.RECT -> {
                    val r = RectF(pad + s.a * u, pad + s.b * u, pad + (s.a + s.c) * u, pad + (s.b + s.d) * u)
                    canvas.drawRoundRect(r, 3f * u * 0.4f, 3f * u * 0.4f, fill)
                    canvas.drawRoundRect(r, 3f * u * 0.4f, 3f * u * 0.4f, stroke)
                    if (s.label.isNotEmpty()) drawFitted(canvas, s.label, r.centerX(), r.centerY(), r.width() - 4f * density, 3.6f * u)
                }
                Sketch.Kind.TRI -> {
                    val p = Path()
                    p.moveTo((s.a + s.c / 2) * u, s.b * u)
                    p.lineTo((s.a + s.c) * u, (s.b + s.d) * u)
                    p.lineTo(s.a * u, (s.b + s.d) * u)
                    p.close()
                    canvas.drawPath(p, fill)
                    canvas.drawPath(p, stroke)
                    if (s.label.isNotEmpty()) drawFitted(canvas, s.label, (s.a + s.c / 2) * u, (s.b + s.d * 0.7f) * u, s.c * u * 0.6f, 3.4f * u)
                }
                Sketch.Kind.CIRCLE -> {
                    canvas.drawCircle(s.a * u, s.b * u, s.c * u, fill)
                    canvas.drawCircle(s.a * u, s.b * u, s.c * u, stroke)
                    if (s.label.isNotEmpty()) drawFitted(canvas, s.label, s.a * u, s.b * u, s.c * u * 1.8f, 3.4f * u)
                }
                Sketch.Kind.LINE -> {
                    canvas.drawLine(s.a * u, s.b * u, s.c * u, s.d * u, stroke)
                    if (s.arrow) arrowHead(canvas, s.a * u, s.b * u, s.c * u, s.d * u)
                    if (s.label.isNotEmpty()) drawFitted(canvas, s.label, (s.a + s.c) / 2 * u, (s.b + s.d) / 2 * u - 2f * u, 30f * u, 3.2f * u)
                }
                Sketch.Kind.TEXT -> drawFitted(canvas, s.label, s.a * u, s.b * u, side - 8f * density, 4.2f * u)
            }
        }
    }

    /** One line of text centred on (cx, cy), shrunk and then cut with "..." so it stays inside [maxWidth]. */
    private fun drawFitted(canvas: Canvas, text: String, cx: Float, cy: Float, maxWidth: Float, wantedSize: Float) {
        var size = wantedSize.coerceAtLeast(9f * density)
        label.textSize = size
        var shown = text
        while (label.measureText(shown) > maxWidth && size > 8f * density) {
            size -= density
            label.textSize = size
        }
        while (label.measureText(shown) > maxWidth && shown.length > 2) shown = shown.dropLast(2).trimEnd() + "\u2026"
        val y = cy - (label.descent() + label.ascent()) / 2f
        canvas.drawText(shown, cx, y, label)
    }

    private fun arrowHead(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val len = 3.2f * (min(width, height) / Sketch.SIZE)
        val spread = Math.PI / 7
        for (side in intArrayOf(-1, 1)) {
            val a = angle + Math.PI + side * spread
            canvas.drawLine(x2, y2, x2 + (len * cos(a)).toFloat(), y2 + (len * sin(a)).toFloat(), stroke)
        }
    }
}
