package com.codeassist.ai.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.codeassist.ai.R
import java.util.concurrent.Executors
import kotlin.math.max

/** Decode chat images off the UI thread and downsample before displaying them. */
object ImageLoader {
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context, view: ImageView, uriText: String) {
        val appContext = context.applicationContext
        view.tag = uriText
        view.setImageResource(R.drawable.ic_image)
        executor.execute {
            val bitmap = try {
                val uri = Uri.parse(uriText)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                appContext.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val target = max(320, max(view.width, view.height).coerceAtMost(1600))
                var sample = 1
                while (bounds.outWidth / (sample * 2) > target ||
                    bounds.outHeight / (sample * 2) > target
                ) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                appContext.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            } catch (_: Exception) {
                null
            }
            main.post {
                if (view.tag == uriText && bitmap != null) {
                    view.setImageBitmap(bitmap)
                    if (view is ZoomImageView) view.fitImage()
                }
            }
        }
    }
}