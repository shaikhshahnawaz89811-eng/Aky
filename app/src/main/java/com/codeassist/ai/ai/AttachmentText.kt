package com.codeassist.ai.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.codeassist.ai.data.AttachKind
import com.codeassist.ai.data.Attachment
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Turns picked attachments into something a model can actually use:
 *  - text / source files  -> inlined as text (both brains)
 *  - images               -> JPEG bytes for Gemini, optional local OCR for Qwen
 *  - ZIP / PDF / binaries -> reported as skipped, never silently dropped
 */
object AttachmentText {
    class Payload(val text: String, val images: List<GeminiClient.Image>, val skipped: List<String>)

    private val textExtensions = setOf(
        "txt", "md", "markdown", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "json", "xml", "html",
        "htm", "css", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "swift", "php", "rb", "sh", "bat",
        "gradle", "properties", "yml", "yaml", "toml", "ini", "cfg", "csv", "tsv", "log", "sql", "dart", "lua"
    )

    fun read(
        ctx: Context,
        attachments: List<Attachment>,
        allowImages: Boolean,
        maxChars: Int,
        ocrImages: Boolean = false
    ): Payload {
        val text = StringBuilder()
        val images = ArrayList<GeminiClient.Image>()
        val skipped = ArrayList<String>()
        var left = maxChars.coerceAtLeast(0)

        for (a in attachments) {
            when {
                a.kind == AttachKind.IMAGE -> {
                    if (!allowImages) {
                        if (!ocrImages) {
                            skipped.add(a.name + " (image: Qwen2.5 sirf text padhta hai; OCR setting off hai)")
                        } else if (left < 200) {
                            skipped.add(a.name + " (OCR text ke liye context mein jagah nahi)")
                        } else {
                            val recognized = readImageText(ctx, a, left - 100)
                            if (recognized.isNullOrBlank()) {
                                ConvKpi.inc("ocr_failed")
                                skipped.add(a.name + " (on-device OCR mein readable text nahi mila)")
                            } else {
                                ConvKpi.inc("ocr_success")
                                val block = "--- Screenshot OCR: " + a.name + " ---\n" +
                                    recognized + "\n--- end OCR ---\n\n"
                                text.append(block)
                                left -= block.length
                            }
                        }
                    } else {
                        val img = loadImage(ctx, a)
                        if (img != null) images.add(img) else skipped.add(a.name + " (image khul nahi payi)")
                    }
                }
                a.kind == AttachKind.ZIP -> skipped.add(a.name + " (ZIP abhi padhi nahi jaati)")
                isTextLike(a) -> {
                    if (left < 200) {
                        skipped.add(a.name + " (context mein jagah nahi)")
                    } else {
                        val body = readText(ctx, a, left)
                        if (body == null) {
                            skipped.add(a.name + " (file padhi nahi gayi)")
                        } else {
                            val block = "--- File: " + a.name + " ---\n" + body + "\n--- end of " + a.name + " ---\n\n"
                            text.append(block)
                            left -= block.length
                        }
                    }
                }
                else -> skipped.add(a.name + " (ye file type abhi support nahi)")
            }
        }
        return Payload(text.toString(), images, skipped)
    }

    private fun isTextLike(a: Attachment): Boolean {
        val ext = a.name.substringAfterLast('.', "").lowercase()
        return a.mime.startsWith("text/") || ext in textExtensions ||
            a.mime == "application/json" || a.mime == "application/xml"
    }

    private fun readText(ctx: Context, a: Attachment, maxChars: Int): String? {
        return try {
            ctx.contentResolver.openInputStream(Uri.parse(a.uri))?.use { stream ->
                val reader = InputStreamReader(stream, Charsets.UTF_8)
                val buf = CharArray(8192)
                val sb = StringBuilder()
                var nulls = 0
                while (sb.length < maxChars) {
                    val n = reader.read(buf, 0, minOf(buf.size, maxChars - sb.length))
                    if (n < 0) break
                    for (i in 0 until n) if (buf[i] == '\u0000') nulls++
                    sb.append(buf, 0, n)
                }
                if (nulls > 8) null else sb.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadImage(ctx: Context, a: Attachment): GeminiClient.Image? {
        return try {
            val bmp = decodeBitmap(ctx, a) ?: return null
            val out = ByteArrayOutputStream()
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            } finally {
                bmp.recycle()
            }
            GeminiClient.Image("image/jpeg", out.toByteArray())
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Bundled ML Kit Latin OCR stays on-device; its text is only added to the local Qwen prompt. */
    private fun readImageText(ctx: Context, a: Attachment, maxChars: Int): String? {
        var bitmap: Bitmap? = null
        var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
        return try {
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer = client
            bitmap = decodeBitmap(ctx, a) ?: return null
            val task = client.process(InputImage.fromBitmap(bitmap, 0))
            Tasks.await(task, 20, TimeUnit.SECONDS).text.take(maxChars.coerceAtLeast(0))
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            recognizer?.close()
            bitmap?.recycle()
        }
    }

    private fun decodeBitmap(ctx: Context, a: Attachment): Bitmap? {
        val uri = Uri.parse(a.uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
