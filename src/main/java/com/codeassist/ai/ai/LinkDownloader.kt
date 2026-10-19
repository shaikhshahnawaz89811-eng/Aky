package com.codeassist.ai.ai

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.codeassist.ai.search.DownloadGuard
import com.codeassist.ai.search.LinkFmt
import com.codeassist.ai.search.LinkResolver
import com.codeassist.ai.search.WebCard
import java.io.File

/**
 * Net Search Agent, stage S3: the only place where a file is fetched for a link card (audit section 3: "the agent never auto-downloads,
 * never auto-installs"). Two taps are needed: Download on the card, then Download in the confirm dialog that shows the file name,
 * size, type, site and the publisher's checksum if there is one. The fetch is done by Android's DownloadManager (notification, resume),
 * into the app's own downloads folder: no storage permission, and nothing is opened or installed afterwards.
 */
object LinkDownloader {
    private const val SPACE_MARGIN = 100L * 1024 * 1024

    private fun count(key: String) {
        try {
            ConvKpi.inc(key)
        } catch (_: Exception) {
            // the report is a convenience, never a reason to stop a download
        }
    }

    private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

    /** The words of the confirm dialog. Pure text: the facts come from the card, not from the model. */
    fun confirmText(card: WebCard, metered: Boolean): String {
        val sb = StringBuilder()
        sb.append(card.fileName ?: card.title).append("\n")
        sb.append("Size: ").append(card.sizeBytes?.let { LinkFmt.size(it) } ?: "pata nahi").append("\n")
        sb.append("Type: ").append(card.mime ?: "pata nahi").append("\n")
        sb.append("Site: ").append(card.domain).append("\n")
        sb.append("Checksum: ").append(card.checksum ?: "nahi mila").append("\n")
        if (card.runnable) sb.append("\nYe chalne wali file hai. Download hone ke baad bhi main ise install nahi karunga: kholne se pehle source par bharosa kar lo.\n")
        if (metered) sb.append("\nAbhi mobile data (metered) par ho: download data kharch karega.\n")
        sb.append("\nCard par \u201Cchecked\u201D ka matlab safe hona nahi hai.")
        return sb.toString()
    }

    /** Called when the Download button of a file card is tapped: checks the address again, then asks. */
    fun ask(context: Context, card: WebCard) {
        count("web_download_tap")
        val rule = DownloadGuard.checkUrl(card.url)
        if (!card.downloadable || rule != null) {
            count("web_download_blocked")
            toast(context, "Ye address download ke liye theek nahi laga" + (if (rule != null) " (" + rule.detail + ")" else "") + ".")
            return
        }
        val app = context.applicationContext
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir == null) {
            toast(context, "Download folder nahi mila.")
            return
        }
        val size = card.sizeBytes
        if (size != null && freeBytes(dir) < size + SPACE_MARGIN) {
            toast(context, "Storage kam hai: " + LinkFmt.size(size + SPACE_MARGIN) + " khaali jagah chahiye.")
            return
        }
        val metered = try {
            (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
        } catch (_: Exception) {
            false
        }
        try {
            AlertDialog.Builder(context)
                .setTitle("Download karein?")
                .setMessage(confirmText(card, metered))
                .setPositiveButton(if (metered) "Download (mobile data)" else "Download") { _, _ -> enqueue(app, card, dir, metered) }
                .setNegativeButton("Cancel") { _, _ -> count("web_download_cancel") }
                .show()
        } catch (_: Exception) {
            toast(context, "Confirm box nahi khul paaya, download nahi kiya.")
        }
    }

    private fun enqueue(app: Context, card: WebCard, dir: File, allowMetered: Boolean) {
        try {
            val name = uniqueName(dir, LinkResolver.safeFileName(card.fileName ?: card.url))
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(card.url))
                .setTitle(name)
                .setDescription("CodeAssistAI download")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(allowMetered)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name)
                .addRequestHeader("User-Agent", "CodeAssistAI")
            dm.enqueue(request)
            count("web_download_started")
            toast(app, "Download shuru ho gaya, notification mein dikhega. Install tum khud karna.")
        } catch (e: Exception) {
            count("web_download_failed")
            toast(app, "Download shuru nahi hua: " + (e.message ?: "unknown error"))
        }
    }

    /** `name.apk` -> `name.apk` when free, else `name-1.apk`, `name-2.apk` ... (DownloadManager does not rename a file that exists). */
    fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val ext = LinkResolver.extensionOf(name)
        val base = if (ext != null) name.dropLast(ext.length + 1) else name
        val dot = if (ext != null) ".$ext" else ""
        var i = 1
        while (i < 1000) {
            val candidate = "$base-$i$dot"
            if (!File(dir, candidate).exists()) return candidate
            i++
        }
        return base + "-" + System.currentTimeMillis() + dot
    }

    private fun freeBytes(dir: File): Long =
        try {
            StatFs(dir.absolutePath).availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
}
