package com.codeassist.ai.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Audit PDF Sec 9.6 / 11.3: every phone action the assistant takes is written here (what, when, risk
 * tier) and, where Android allows it, can be undone. Stored as a small JSON file in app-private storage.
 *
 * Undo is honest about its limits: torch is exact; alarm / timer undo sends a dismiss request to the
 * Clock app, which some OEM clocks ignore, so the reply tells the user to double-check there.
 */
object ActivityLog {
    data class Entry(
        val id: String,
        val time: Long,
        val tool: String,
        val tier: String,
        val summary: String,
        val undo: String?,
        var undone: Boolean = false
    )

    private const val FILE_NAME = "activity_log.json"
    private const val MAX_ENTRIES = 200

    private val gson = Gson()
    private var file: File? = null
    private var items: ArrayList<Entry> = ArrayList()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, FILE_NAME)
        file = f
        try {
            if (f.exists()) {
                val type = object : TypeToken<ArrayList<Entry>>() {}.type
                val loaded: ArrayList<Entry>? = gson.fromJson(f.readText(), type)
                if (loaded != null) items = loaded
            }
        } catch (_: Throwable) {
            items = ArrayList()
        }
    }

    @Synchronized
    fun add(tool: String, tier: String, summary: String, undo: String?): Entry {
        val e = Entry(UUID.randomUUID().toString(), System.currentTimeMillis(), tool, tier, summary, undo)
        items.add(e)
        while (items.size > MAX_ENTRIES) items.removeAt(0)
        save()
        return e
    }

    /** Newest first. */
    @Synchronized
    fun all(): List<Entry> = items.asReversed().toList()

    @Synchronized
    fun get(id: String): Entry? = items.firstOrNull { it.id == id }

    /** [id] may hold several entry ids separated by commas (one reply that ran several undoable tools). */
    @Synchronized
    fun isUndone(id: String): Boolean {
        val parts = id.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { get(it)?.undone == true }
    }

    @Synchronized
    fun clear() {
        items.clear()
        save()
    }

    @Synchronized
    private fun markUndone(id: String) {
        get(id)?.undone = true
        save()
    }

    private fun save() {
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, FILE_NAME + ".tmp")
            tmp.writeText(gson.toJson(items))
            if (!tmp.renameTo(f)) {
                f.writeText(gson.toJson(items))
                tmp.delete()
            }
        } catch (_: Throwable) {
            // the log is best-effort; never crash a chat because of it
        }
    }

    /** Runs the undo for entry [id] (or for each id of a comma list) and returns the message(s) for the user. */
    fun undo(ctx: Context, id: String): String {
        if (id.contains(',')) {
            val out = ArrayList<String>()
            for (p in id.split(',')) {
                val one = p.trim()
                if (one.isNotEmpty()) out.add(undoOne(ctx, one))
            }
            return out.joinToString("\n")
        }
        return undoOne(ctx, id)
    }

    private fun undoOne(ctx: Context, id: String): String {
        val e = get(id) ?: return "Ye action log mein nahi mili."
        val token = e.undo ?: return "Is action ko wapas nahi kiya ja sakta."
        if (e.undone) return "Ye pehle hi wapas ho chuka hai."
        val app = ctx.applicationContext
        val reply: String = when {
            token == "torch:on" || token == "torch:off" -> {
                val err = Tier0.setTorch(app, token == "torch:on")
                if (err != null) return err
                if (token == "torch:on") "Torch on kar di." else "Torch off kar di."
            }
            token.startsWith("alarm:") -> {
                val label = token.removePrefix("alarm:")
                val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
                    .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                val ok = startSafely(app, intent)
                if (!ok) {
                    startSafely(app, Intent(AlarmClock.ACTION_SHOW_ALARMS))
                    return "Clock app dismiss request nahi leta. Alarms list khol di, wahan se khud hata do."
                }
                "Alarm hatane ki request Clock app ko bhej di. Clock app mein ek baar check kar lena."
            }
            token == "timer" -> {
                val ok = Build.VERSION.SDK_INT >= 26 &&
                    startSafely(app, Intent(AlarmClock.ACTION_DISMISS_TIMER))
                if (!ok) {
                    if (Build.VERSION.SDK_INT >= 26) startSafely(app, Intent(AlarmClock.ACTION_SHOW_TIMERS))
                    return "Clock app timer dismiss nahi karta. Timers list khol di, wahan se khud rok do."
                }
                "Timer rokne ki request Clock app ko bhej di. Clock app mein ek baar check kar lena."
            }
            else -> return "Is action ka undo supported nahi hai."
        }
        markUndone(id)
        return reply
    }

    private fun startSafely(ctx: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
