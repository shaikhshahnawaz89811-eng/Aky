package com.codeassist.ai.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tier-0 "fast path": a handful of phone actions that run instantly on the device, without any LLM.
 * Every pattern is anchored to a short, clearly-intended phrase so ordinary chat (for example a
 * question about "time complexity") is never hijacked. Anything that does not match returns null
 * and goes to the selected brain.
 */
object Tier0 {
    /**
     * [tier] is the risk tier from the audit (T0 read-only, T1 reversible on-device, T2 affects others).
     * [undo] is a token understood by [ActivityLog.undo], or null when the action cannot be undone.
     */
    class Result(
        val title: String,
        val reply: String,
        val tier: String = "T0",
        val undo: String? = null,
        /** false when nothing happened (error, missing app, needs more input): not logged, not counted as an action. */
        val ok: Boolean = true
    )

    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"

    private val devanagari = listOf(
        "कितने बजे" to "kitne baje", "बजे" to "baje", "अलार्म" to "alarm", "टाइमर" to "timer",
        "बैटरी" to "battery", "टॉर्च" to "torch", "फ्लैशलाइट" to "flashlight",
        "सुबह" to "subah", "शाम" to "shaam", "रात" to "raat", "दोपहर" to "dopahar",
        "कल" to "kal", "आज" to "aaj", "लगा दो" to "laga do", "लगाओ" to "lagao", "सेट करो" to "set karo",
        "खोल दो" to "khol do", "खोलो" to "kholo", "चालू" to "chalu", "बंद" to "band",
        "करो" to "karo", "कर दो" to "kar do", "मिनट" to "minute", "घंटे" to "ghante", "घंटा" to "ghanta",
        "सेकंड" to "second", "समय" to "samay", "तारीख" to "tarikh",
        "डेढ़" to "dedh", "डेढ" to "dedh", "ढाई" to "dhai", "साढ़े" to "saade", "साढे" to "saade",
        "सवा" to "sava", "पौने" to "paune"
    )

    private val numWords: Map<String, Int> = mapOf(
        "ek" to 1, "one" to 1, "एक" to 1, "do" to 2, "two" to 2, "दो" to 2,
        "teen" to 3, "three" to 3, "तीन" to 3, "char" to 4, "chaar" to 4, "four" to 4, "चार" to 4,
        "paanch" to 5, "panch" to 5, "five" to 5, "पांच" to 5, "पाँच" to 5,
        "chhe" to 6, "chhah" to 6, "che" to 6, "six" to 6, "छह" to 6, "छः" to 6, "छे" to 6,
        "saat" to 7, "seven" to 7, "सात" to 7, "aath" to 8, "ath" to 8, "eight" to 8, "आठ" to 8,
        "nau" to 9, "nine" to 9, "नौ" to 9, "das" to 10, "ten" to 10, "दस" to 10,
        "gyarah" to 11, "gyaarah" to 11, "eleven" to 11, "ग्यारह" to 11,
        "barah" to 12, "baarah" to 12, "twelve" to 12, "बारह" to 12
    )
    private val numAlt: String =
        "\\d{1,2}|" + numWords.keys.sortedByDescending { it.length }.joinToString("|")

    fun handle(ctx: Context, raw: String): Result? {
        val t = normalize(raw)
        if (t.isEmpty() || t.split(' ').size > 12) return null
        return time(t) ?: date(t) ?: battery(ctx, t) ?: torch(ctx, t) ?: timer(ctx, t)
            ?: alarm(ctx, t) ?: dial(ctx, t) ?: openApp(ctx, t)
    }

    private fun normalize(raw: String): String {
        var s = raw.lowercase(Locale.ROOT)
        for ((from, to) in devanagari) s = s.replace(from, to)
        val sb = StringBuilder()
        for (ch in s) {
            if (ch in '\u0966'..'\u096F') sb.append('0' + (ch - '\u0966')) else sb.append(ch)
        }
        s = sb.toString()
        s = s.replace(Regex("[^\\p{L}\\p{M}\\p{N}:.+ ]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    // ---------- time / date / battery ----------

    private val timePhrases = Regex(
        "^(abhi )?(kitne baje|kitna time|time|samay|current time|what time is it|what s the time|tell me the time)" +
            "( kya)?( hua| hai| hain| hue| ho raha| batao| bata do| bataiye)*( please)?$"
    )

    private fun time(t: String): Result? {
        if (!timePhrases.matches(t)) return null
        return runTime()
    }

    fun runTime(): Result {
        val hm = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())
        return Result("Time", "Abhi $hm hua hai.")
    }

    private val datePhrases = Regex(
        "^(aaj ki |aaj )?(date|tarikh|tareekh)( kya)?( hai| hain| batao| bata do)*$|" +
            "^aaj kaun sa din( hai)?$|^what s (the )?(today s )?date$|^today s date$|^what day is it( today)?$"
    )

    private fun date(t: String): Result? {
        if (!datePhrases.matches(t)) return null
        return runDate()
    }

    fun runDate(): Result {
        val d = SimpleDateFormat("d MMMM yyyy, EEEE", Locale.ENGLISH).format(Date())
        return Result("Date", "Aaj $d hai.")
    }

    private val batteryPhrases = Regex(
        "^(battery|charge|charging)( level| percent| percentage| kitni| kitna| kya| status)*( hai| hain| batao| bata do)*$|" +
            "^how much battery( is left)?$"
    )

    private fun battery(ctx: Context, t: String): Result? {
        if (!batteryPhrases.matches(t)) return null
        return runBattery(ctx)
    }

    fun runBattery(ctx: Context): Result {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0 || level > 100) return Result("Battery", "Battery level abhi padh nahi paya.", ok = false)
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Result("Battery", "Battery $level% hai" + (if (charging) " (charge ho rahi hai)." else "."))
    }

    // ---------- torch ----------

    private val onWords = setOf("on", "chalu", "jalao", "jala", "start")
    private val offWords = setOf("off", "band", "bujhao", "bujha", "stop")

    private fun torch(ctx: Context, t: String): Result? {
        val words = t.split(' ')
        if (words.size > 5) return null
        if (words.none { it == "torch" || it == "flashlight" || it == "flash" }) return null
        val on = words.any { it in onWords }
        val off = words.any { it in offWords }
        if (on == off) return null
        return runTorch(ctx, on)
    }

    fun runTorch(ctx: Context, on: Boolean): Result {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Result("Torch", "Is phone mein flash / torch nahi mila.", ok = false)
            cm.setTorchMode(id, on)
            Result(
                "Torch", if (on) "Torch on kar di." else "Torch off kar di.",
                "T1", if (on) "torch:off" else "torch:on"
            )
        } catch (e: Exception) {
            Result("Torch", "Torch control nahi ho paya: " + (e.message ?: e.javaClass.simpleName), ok = false)
        }
    }

    /** Used by the Undo button. Returns null on success, or a user-facing error message. */
    fun setTorch(ctx: Context, on: Boolean): String? {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "Is phone mein flash / torch nahi mila."
            cm.setTorchMode(id, on)
            null
        } catch (e: Exception) {
            "Torch control nahi ho paya: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- timer / alarm (Clock app intents) ----------

    private val durationRegex = Regex(
        "(\\d{1,4}) ?(seconds?|secs?|minutes?|mins?|mint|ghanta|ghante|ghanton|hours?|hrs?)" + E
    )

    private fun timer(ctx: Context, t: String): Result? {
        if (!t.contains("timer")) return null
        val m = durationRegex.find(t) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        val unit = m.groupValues[2]
        val seconds = when {
            unit.startsWith("sec") -> n
            unit.startsWith("h") || unit.startsWith("ghant") -> n * 3600
            else -> n * 60
        }
        if (seconds <= 0 || seconds > 24 * 3600) return null
        return runTimer(ctx, seconds)
    }

    fun runTimer(ctx: Context, seconds: Int): Result {
        if (seconds <= 0 || seconds > 24 * 3600) {
            return Result("Timer", "Timer 1 second se 24 ghante ke beech hona chahiye.", ok = false)
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "CodeAssist timer")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(
            ctx, intent, "Timer",
            durationLabel(seconds) + " ka timer start kar diya.",
            "Is phone ka Clock app timer intent support nahi karta.",
            "T1", "timer"
        )
    }

    private fun durationLabel(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add("$h ghanta")
        if (m > 0) parts.add("$m minute")
        if (s > 0) parts.add("$s second")
        return parts.joinToString(" ")
    }

    private class Clock(val hour: Int, val minute: Int, val cue: String?)

    private fun parseClock(t: String): Clock? {
        Regex(B + "(dedh|dhai|dhaai) baje" + E).find(t)?.let {
            return if (it.groupValues[1] == "dedh") Clock(1, 30, null) else Clock(2, 30, null)
        }
        Regex(B + "saade ($numAlt) baje" + E).find(t)?.let { m ->
            val n = num(m.groupValues[1]) ?: return null
            return Clock(n, 30, null)
        }
        Regex(B + "(sava|sawa) ($numAlt) baje" + E).find(t)?.let { m ->
            val n = num(m.groupValues[2]) ?: return null
            return Clock(n, 15, null)
        }
        Regex(B + "paune ($numAlt) baje" + E).find(t)?.let { m ->
            val n = num(m.groupValues[1]) ?: return null
            return Clock(if (n <= 1) 12 else n - 1, 45, null)
        }
        Regex(B + "(\\d{1,2})[:.](\\d{2}) ?(am|pm)?" + E).find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 24 || min > 59) return null
            return Clock(h, min, m.groupValues[3].ifEmpty { null })
        }
        Regex(B + "($numAlt) ?(baje|bje|am|pm|oclock|o clock)" + E).find(t)?.let { m ->
            val h = num(m.groupValues[1]) ?: return null
            if (h > 24) return null
            val tail = m.groupValues[2]
            return Clock(h, 0, if (tail == "am" || tail == "pm") tail else null)
        }
        return null
    }

    private fun num(s: String): Int? = s.toIntOrNull() ?: numWords[s]

    private fun cueWord(t: String): String? = when {
        Regex(B + "(subah|savere|morning)" + E).containsMatchIn(t) -> "am"
        Regex(B + "(dopahar|afternoon|noon)" + E).containsMatchIn(t) -> "dopahar"
        Regex(B + "(raat|night|midnight)" + E).containsMatchIn(t) -> "raat"
        Regex(B + "(shaam|sham|evening)" + E).containsMatchIn(t) -> "pm"
        else -> null
    }

    private fun minutesUntil(now: Calendar, hour24: Int, minute: Int): Int {
        val target = now.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, hour24)
        target.set(Calendar.MINUTE, minute)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return ((target.timeInMillis - now.timeInMillis) / 60000L).toInt()
    }

    private fun alarm(ctx: Context, t: String): Result? {
        if (!t.contains("alarm")) return null
        val clock = parseClock(t) ?: return null
        val now = Calendar.getInstance()
        var h = clock.hour
        val m = clock.minute
        if (h <= 12) {
            val cue = clock.cue ?: cueWord(t)
            h = when (cue) {
                "am" -> if (h == 12) 0 else h
                "pm" -> if (h == 12) 12 else h + 12
                "raat" -> if (h == 12) 0 else if (h in 1..4) h else h + 12
                "dopahar" -> if (h == 12) 12 else if (h in 1..6) h + 12 else h
                else -> {
                    val am = h % 12
                    val pm = h % 12 + 12
                    if (minutesUntil(now, am, m) <= minutesUntil(now, pm, m)) am else pm
                }
            }
        }
        h %= 24
        return runAlarm(ctx, h, m, t.contains("kal") || t.contains("tomorrow"))
    }

    /** [h] is already 24-hour. [saidTomorrow] only guards the Clock app's "next occurrence" rule below. */
    fun runAlarm(ctx: Context, h: Int, m: Int, saidTomorrow: Boolean): Result {
        val now = Calendar.getInstance()
        val shown = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(
            (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
            }.time
        )
        // The Clock app always rings at the next occurrence of this time.
        val todayTarget = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val ringsToday = todayTarget.after(now)
        if (saidTomorrow && ringsToday) {
            return Result(
                "Alarm",
                "Clock app agla $shown hi set karta hai, aur wo aaj hi aa raha hai. " +
                    "Kal ke liye ye alarm aaj raat bol dena.",
                ok = false
            )
        }
        // unique label per time, so Undo can dismiss exactly this alarm (see ActivityLog.undo)
        val label = "CodeAssist alarm " + String.format(Locale.US, "%02d:%02d", h, m)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val day = if (ringsToday) "aaj" else "kal"
        return launch(
            ctx, intent, "Alarm",
            "Alarm $shown ($day) ke liye Clock app mein set kar diya.",
            "Is phone ka Clock app alarm intent support nahi karta.",
            "T1", "alarm:" + label
        )
    }

    // ---------- dial / open app ----------

    private val dialRegex = Regex(
        "^(call|dial|phone) (\\+?[0-9 ]{6,16})$|^(\\+?[0-9 ]{6,16}) (ko )?(call|dial)( karo| kar do| kardo| lagao)?$"
    )

    private fun dial(ctx: Context, t: String): Result? {
        val m = dialRegex.find(t) ?: return null
        val number = (m.groupValues[2].ifEmpty { m.groupValues[3] }).replace(" ", "")
        if (number.length < 6) return null
        return runDial(ctx, number)
    }

    fun runDial(ctx: Context, number: String): Result {
        return launch(
            ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")), "Call",
            "Dialer mein $number khol diya. Call aap khud dabayenge.",
            "Is phone mein dialer nahi mila.",
            "T2", null // T2: the dialer only opens; the user's own tap on Call is the explicit confirmation
        )
    }

    private val openPrefix = Regex("^(open|launch|start|kholo|khol) (.+)$")
    private val openSuffix = Regex("^(.+?) (kholo|khol do|khol|open karo|open kar do|open|chalu karo|start karo|launch karo)$")

    private class AppInfo(val label: String, val norm: String, val pkg: String)

    private fun openApp(ctx: Context, t: String): Result? {
        var name = openPrefix.find(t)?.groupValues?.get(2) ?: openSuffix.find(t)?.groupValues?.get(1) ?: return null
        name = name.replace(Regex("^(the |app )+"), "").replace(Regex("( app| ko| please)+$"), "").trim()
        if (name.length < 2) return null
        return openByName(ctx, name)
    }

    /** Structured entry (brain tool call): an unknown app name is an answer, not "let the brain handle it". */
    fun runOpenApp(ctx: Context, rawName: String): Result {
        val name = normalize(rawName)
        if (name.length < 2) return Result("Open app", "App ka naam samajh nahi aaya.", ok = false)
        return openByName(ctx, name) ?: Result("Open app", "\"" + rawName.trim() + "\" naam ka app nahi mila.", ok = false)
    }

    private fun openByName(ctx: Context, name: String): Result? {
        val pm = ctx.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), normalize(it.loadLabel(pm).toString()), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
        val exact = apps.filter { it.norm == name }
        val matches = when {
            exact.isNotEmpty() -> exact
            apps.any { it.norm.startsWith(name) } -> apps.filter { it.norm.startsWith(name) }
            else -> apps.filter { it.norm.contains(name) }
        }
        if (matches.isEmpty()) return null // not an app request: let the brain answer
        if (matches.size > 1) {
            val names = matches.take(4).joinToString(", ") { it.label }
            return Result("Open app", "Kaun sa app? $names. Poora naam bolo.", ok = false)
        }
        val app = matches[0]
        val launchIntent = pm.getLaunchIntentForPackage(app.pkg)
            ?: return Result("Open app", app.label + " abhi khul nahi sakta.", ok = false)
        return launch(ctx, launchIntent, "Open app", app.label + " khol diya.", app.label + " nahi khula.", "T0", null)
    }

    private fun launch(
        ctx: Context, intent: Intent, title: String, ok: String, missing: String,
        tier: String = "T1", undo: String? = null
    ): Result {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            Result(title, ok, tier, undo)
        } catch (_: ActivityNotFoundException) {
            Result(title, missing, ok = false)
        } catch (e: SecurityException) {
            Result(title, "Permission nahi mili: " + (e.message ?: ""), ok = false)
        } catch (e: Exception) {
            Result(title, "Ho nahi paya: " + (e.message ?: e.javaClass.simpleName), ok = false)
        }
    }
}
