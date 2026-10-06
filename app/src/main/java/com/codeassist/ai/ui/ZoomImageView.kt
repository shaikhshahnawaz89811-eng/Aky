package com.codeassist.ai.ui

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** Full-screen image preview with fit-to-screen and pinch zoom. */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private val imageTransform = Matrix()
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                imageTransform.postScale(
                    detector.scaleFactor,
                    detector.scaleFactor,
                    detector.focusX,
                    detector.focusY
                )
                imageMatrix = imageTransform
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        contentDescription = "Pinch to zoom"
    }

    fun fitImage() {
        post {
            val image = drawable ?: return@post
            if (width == 0 || height == 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) {
                return@post
            }
            val scale = min(
                width.toFloat() / image.intrinsicWidth,
                height.toFloat() / image.intrinsicHeight
            )
            val dx = (width - image.intrinsicWidth * scale) / 2f
            val dy = (height - image.intrinsicHeight * scale) / 2f
            imageTransform.setScale(scale, scale)
            imageTransform.postTranslate(dx, dy)
            imageMatrix = imageTransform
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }
}