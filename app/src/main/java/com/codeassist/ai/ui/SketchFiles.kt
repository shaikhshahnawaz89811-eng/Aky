package com.codeassist.ai.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.codeassist.ai.ai.Sketch
import com.codeassist.ai.ai.SketchDesigns
import java.io.File

/**
 * Sketch Studio stage 2: a drawing leaves the app as a real .svg or .png FILE (not text). The file is written to
 * cache/sketch_export and handed to the share sheet through a FileProvider (authority = package name + ".files", see
 * AndroidManifest and res/xml/file_paths.xml). Nothing is written outside the app's own cache. Files older than a day are removed
 * each time a new one is written. PNG encoding runs on the calling thread (a 1080 px picture is a short job).
 */
object SketchFiles {
    const val PNG_SIDE = 1080
    private const val DIR = "sketch_export"
    private const val KEEP_MS = 24L * 60 * 60 * 1000

    private fun dir(ctx: Context): File {
        val d = File(ctx.cacheDir, DIR)
        d.mkdirs()
        return d
    }

    private fun sweep(d: File) {
        val limit = System.currentTimeMillis() - KEEP_MS
        d.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    /** Writes the SVG text of [d] to a file named after [d]'s title and returns it. */
    fun writeSvg(ctx: Context, d: Sketch.Drawing): File {
        val folder = dir(ctx)
        sweep(folder)
        val f = File(folder, SketchDesigns.fileName(d.title, "svg"))
        f.writeText(Sketch.toSvg(d), Charsets.UTF_8)
        return f
    }

    /** Renders [d] as a [PNG_SIDE] square PNG file and returns it. */
    fun writePng(ctx: Context, d: Sketch.Drawing): File {
        val folder = dir(ctx)
        sweep(folder)
        val view = SketchView(ctx)
        view.setDrawing(d)
        val bmp = view.renderBitmap(PNG_SIDE)
        val f = File(folder, SketchDesigns.fileName(d.title, "png"))
        try {
            f.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        } finally {
            bmp.recycle()
        }
        return f
    }

    /** Opens the share sheet for [file]; [mime] is "image/svg+xml" or "image/png". */
    fun share(ctx: Context, file: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share " + file.extension.uppercase()))
    }

    /** Write + share in one step; a failure becomes a short toast instead of a crash. */
    fun shareSvg(ctx: Context, d: Sketch.Drawing) = safely(ctx) { share(ctx, writeSvg(ctx, d), "image/svg+xml", d.title) }

    fun sharePng(ctx: Context, d: Sketch.Drawing) = safely(ctx) { share(ctx, writePng(ctx, d), "image/png", d.title) }

    private fun safely(ctx: Context, job: () -> Unit) {
        try {
            job()
        } catch (e: Exception) {
            Toast.makeText(ctx, "File nahi ban payi: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }
}
