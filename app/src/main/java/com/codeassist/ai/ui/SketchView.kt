package com.codeassist.ai.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.codeassist.ai.R
import com.codeassist.ai.ai.Sketch
import com.codeassist.ai.ai.SvgPath
import com.codeassist.ai.ai.ViewXform
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * T4: draws a [Sketch.Drawing] (100 x 100 canvas units) as a square under the chat text. No touch handling: it only shows the
 * drawing the model described and the app checked. Default colours come from the app theme; a shape or the drawing can carry its
 * own colours (Sketch Studio stage 1: path, poly and ellipse shapes, fill / stroke / width / opacity, background).
 *
 * Sketch Studio stage 2: with [interactive] = true (only the studio screen sets it) the view also takes pinch-zoom, one-finger pan
 * and double-tap-to-reset ([ViewXform]); [renderBitmap] draws the same picture into a PNG-sized bitmap. In the chat the view stays
 * non-interactive, so it never steals a scroll from the message list.
 */
class SketchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var drawing: Sketch.Drawing? = null
    private val density = resources.displayMetrics.density

    /** Biggest side of the square in dp (the chat keeps 360, the studio screen asks for more). */
    var maxSideDp: Int = 360

    /** Text shown on the empty canvas (studio only); null hides the empty view like the chat does. */
    var emptyHint: String? = null

    /** Called after the user zoomed or panned (the studio shows a "reset" hint when the picture is not at 1x). */
    var onTransformChanged: (() -> Unit)? = null

    var interactive: Boolean = false
        set(value) {
            field = value
            if (!value) xf.reset()
            invalidate()
        }

    private val xf = ViewXform()
    private val sideNow: Float get() = min(width, height).toFloat()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            xf.zoomAt(detector.scaleFactor, detector.focusX, detector.focusY, sideNow)
            invalidate()
            onTransformChanged?.invoke()
            return true
        }
    })

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            xf.reset()
            invalidate()
            onTransformChanged?.invoke()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            xf.pan(-distanceX, -distanceY, sideNow)
            invalidate()
            onTransformChanged?.invoke()
            return true
        }
    })

    /** True when the picture is zoomed or moved away from the start position. */
    val isZoomed: Boolean get() = !xf.isIdentity

    /** Current zoom factor, 1 = whole picture. */
    val zoomLevel: Float get() = xf.scale

    fun resetZoom() {
        xf.reset()
        invalidate()
        onTransformChanged?.invoke()
    }

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

    private val baseBg = bg.color
    private val baseFill = fill.color
    private val baseStroke = stroke.color
    private val baseStrokeWidth = stroke.strokeWidth
    private val baseText = label.color

    /** Colours, line width and opacity of one shape (defaults when the shape has no style). [u] = pixels per canvas unit. */
    private fun applyStyle(s: Sketch.Shape, u: Float) {
        val st = s.style
        val op = st?.opacity ?: 1f
        fill.color = st?.fill ?: baseFill
        fill.alpha = (Color.alpha(fill.color) * op).toInt().coerceIn(0, 255)
        stroke.color = st?.stroke ?: baseStroke
        stroke.alpha = (Color.alpha(stroke.color) * op).toInt().coerceIn(0, 255)
        val sw = st?.strokeWidth
        stroke.strokeWidth = if (sw != null) sw * u else baseStrokeWidth
        val textFill = st?.fill
        label.color = if (s.kind == Sketch.Kind.TEXT && textFill != null && textFill != 0) textFill else baseText
    }

    private fun buildPath(segs: List<SvgPath.Seg>, u: Float): Path {
        val p = Path()
        for (sg in segs) {
            val v = sg.v
            when (sg.op) {
                'M' -> p.moveTo(v[0] * u, v[1] * u)
                'L' -> p.lineTo(v[0] * u, v[1] * u)
                'C' -> p.cubicTo(v[0] * u, v[1] * u, v[2] * u, v[3] * u, v[4] * u, v[5] * u)
                'Q' -> p.quadTo(v[0] * u, v[1] * u, v[2] * u, v[3] * u)
                'Z' -> p.close()
                else -> Unit
            }
        }
        return p
    }

    /** [json] is [Sketch.toJson]; an unreadable one hides the view. */
    fun setSketch(json: String?) {
        drawing = Sketch.fromJson(json)
        visibility = if (drawing == null) GONE else VISIBLE
        requestLayout()
        invalidate()
    }

    /** Studio use: shows [d] (or the empty canvas when null) and never hides the view. */
    fun setDrawing(d: Sketch.Drawing?) {
        drawing = d
        visibility = VISIBLE
        requestLayout()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** The drawing as a square bitmap of [px] x [px] pixels (what the PNG export writes). Same look as the preview, no border. */
    fun renderBitmap(px: Int): Bitmap {
        val size = px.coerceIn(64, 4096)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // the preview is laid out at 360 dp at most: draw at that size and scale up, so text sizes match the preview
        val ref = 360f * density
        c.scale(size / ref, size / ref)
        drawAll(c, drawing, ref, false)
        return bmp
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxSide = (maxSideDp * density).toInt()
        val w = min(MeasureSpec.getSize(widthMeasureSpec), maxSide)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (interactive) {
            canvas.save()
            canvas.translate(xf.tx, xf.ty)
            canvas.scale(xf.scale, xf.scale)
            drawAll(canvas, drawing, side, true)
            canvas.restore()
        } else {
            drawAll(canvas, drawing, side, true)
        }
    }

    /** Draws [d] (null = empty canvas, only when [emptyHint] is set) into a square of [side] pixels at the canvas origin. */
    private fun drawAll(canvas: Canvas, d: Sketch.Drawing?, side: Float, withBorder: Boolean) {
        val round = 12f * density
        if (d == null) {
            if (emptyHint == null) return
            bg.color = baseBg
            canvas.drawRoundRect(RectF(0f, 0f, side, side), round, round, bg)
            if (withBorder) canvas.drawRoundRect(RectF(0f, 0f, side, side), round, round, border)
            label.color = ContextCompat.getColor(context, R.color.text3)
            drawFitted(canvas, emptyHint.orEmpty(), side / 2f, side / 2f, side - 24f * density, 4.2f * side / Sketch.SIZE)
            label.color = baseText
            return
        }
        bg.color = d.background ?: baseBg
        canvas.drawRoundRect(RectF(0f, 0f, side, side), round, round, bg)
        if (withBorder) canvas.drawRoundRect(RectF(0f, 0f, side, side), round, round, border)
        val u = side / Sketch.SIZE
        val pad = 0f
        for (s in d.shapes) {
            applyStyle(s, u)
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
                Sketch.Kind.ELLIPSE -> {
                    val r = RectF((s.a - s.c) * u, (s.b - s.d) * u, (s.a + s.c) * u, (s.b + s.d) * u)
                    canvas.drawOval(r, fill)
                    canvas.drawOval(r, stroke)
                    if (s.label.isNotEmpty()) drawFitted(canvas, s.label, s.a * u, s.b * u, s.c * u * 1.8f, 3.4f * u)
                }
                Sketch.Kind.PATH -> {
                    val segs = s.path
                    if (segs != null) {
                        val p = buildPath(segs, u)
                        canvas.drawPath(p, fill)
                        canvas.drawPath(p, stroke)
                    }
                }
                Sketch.Kind.POLY -> {
                    val pts = s.points
                    if (pts != null && pts.size >= 4) {
                        val p = Path()
                        p.moveTo(pts[0] * u, pts[1] * u)
                        var i = 2
                        while (i + 1 < pts.size) {
                            p.lineTo(pts[i] * u, pts[i + 1] * u)
                            i += 2
                        }
                        if (s.closed) {
                            p.close()
                            canvas.drawPath(p, fill)
                        }
                        canvas.drawPath(p, stroke)
                    }
                }
                Sketch.Kind.LINE -> {
                    canvas.drawLine(s.a * u, s.b * u, s.c * u, s.d * u, stroke)
                    if (s.arrow) arrowHead(canvas, s.a * u, s.b * u, s.c * u, s.d * u, u)
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

    private fun arrowHead(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, u: Float) {
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val len = 3.2f * u
        val spread = Math.PI / 7
        for (side in intArrayOf(-1, 1)) {
            val a = angle + Math.PI + side * spread
            canvas.drawLine(x2, y2, x2 + (len * cos(a)).toFloat(), y2 + (len * sin(a)).toFloat(), stroke)
        }
    }
}
