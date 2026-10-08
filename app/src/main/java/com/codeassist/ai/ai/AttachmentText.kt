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
 *  - images               -> JPEG bytes for Gemini; for Qwen: on-device OCR text + rough object labels as plain text
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
                            skipped.add(a.name + " (image: Gemma 4 sirf text padhta hai; OCR setting off hai)")
                        } else if (left < 200) {
                            skipped.add(a.name + " (OCR text ke liye context mein jagah nahi)")
                        } else {
                            val recognized = analyzeImage(ctx, a, left - 100)
                            if (recognized.isNullOrBlank()) {
                                ConvKpi.inc("ocr_failed")
                                skipped.add(a.name + " (on-device analysis mein na text mila na koi cheez pehchani)")
                            } else {
                                ConvKpi.inc("ocr_success")
                                val block = "--- Photo analysis (on-device): " + a.name + " ---\n" +
                                    recognized + "\n--- end of photo analysis ---\n\n"
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

    /**
     * What the offline model gets instead of pixels: text found in the photo (bundled ML Kit Latin OCR) and the
     * few most confident object / scene labels (bundled ML Kit image labeling, about 400 everyday classes). Both run
     * on the phone. This is a rough description, not sight: the note at the end tells the model so, because a 1.5B
     * model that is not told will happily "recognise" a face from a label like "Person".
     */
    private fun analyzeImage(ctx: Context, a: Attachment, maxChars: Int): String? {
        var bitmap: Bitmap? = null
        return try {
            val decoded = decodeBitmap(ctx, a)
            bitmap = decoded
            if (decoded == null) {
                null
            } else {
                val image = InputImage.fromBitmap(decoded, 0)
                val text = ocrText(image)
                val labels = imageLabels(image)
                if (text.isBlank() && labels.isEmpty()) {
                    null
                } else {
                    val sb = StringBuilder()
                    if (labels.isNotEmpty()) {
                        sb.append("Detected in the photo (rough on-device guess): ").append(labels.joinToString(", ")).append('\n')
                    }
                    if (text.isNotBlank()) {
                        // keep room for the closing note below: the OCR text is what gets cut when the budget is small
                        sb.append("Text visible in the photo:\n")
                            .append(text.trim().take((maxChars - 420).coerceAtLeast(60))).append('\n')
                    }
                    sb.append(
                        "Note: you cannot see the photo itself. Answer only from the lines above, " +
                            "never guess who a person is, and say so if the lines are not enough."
                    )
                    sb.toString().take(maxChars.coerceAtLeast(0))
                }
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun ocrText(image: InputImage): String {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            Tasks.await(client.process(image), 20, TimeUnit.SECONDS).text
        } catch (_: Exception) {
            ""
        } finally {
            client.close()
        }
    }

    /** Object labels need an extra ML Kit library; kept as a stub so it can be added back later. */
    private fun imageLabels(@Suppress("UNUSED_PARAMETER") image: InputImage): List<String> = emptyList()

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
