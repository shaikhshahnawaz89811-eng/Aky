package com.codeassist.ai.ai

import com.google.gson.Gson

/**
 * Audit Sec 9.8 "Proactive engine" (Phase 4B): the rules that decide whether the assistant may offer a suggestion on its own.
 * Pure Kotlin, JVM-tested; [ProactiveRunner] reads the phone and shows the result on the Home screen.
 *
 * What it is: a small card on the Home screen, shown only while the app is open ("notification-first" and background work are not
 * built: no WorkManager, no notification channel exists in this app yet). Every suggestion comes from real data on the phone
 * (battery level, the torch the assistant itself switched on), nothing is made up, and it works with no internet.
 *
 * The rules (all tested):
 *  - Off / Low (1 a day) / Normal (2 a day). Never more than [MAX_PER_DAY], whatever the setting.
 *  - Not between 22:00 and 08:00 (quiet hours), at least 3 hours between two suggestions, the same kind at most every 6 hours.
 *  - A kind the user muted ("Ye mat bolo") never comes back until they switch it on again in Settings.
 *  - A suggestion counts against the day when it is SHOWN, not when the user acts on it.
 */
object Proactive {
    const val MAX_PER_DAY = 2
    const val QUIET_FROM_HOUR = 22
    const val QUIET_TO_HOUR = 8
    const val MIN_GAP_MS = 3L * 60L * 60L * 1000L
    const val KIND_COOLDOWN_MS = 6L * 60L * 60L * 1000L
    const val LOW_BATTERY_PERCENT = 20
    const val TORCH_LEFT_ON_MS = 10L * 60L * 1000L

    const val KIND_BATTERY = "battery"
    const val KIND_TORCH = "torch"
    val KINDS = listOf(KIND_BATTERY, KIND_TORCH)

    val LEVELS = listOf("Off", "Low", "Normal")

    fun kindLabel(kind: String): String = when (kind) {
        KIND_BATTERY -> "Battery low"
        KIND_TORCH -> "Torch on reh gayi"
        else -> kind
    }

    /** [ref] is whatever the action needs (the activity-log entry of the torch), [actionId] says which action it is. */
    class Candidate(val kind: String, val text: String, val actionLabel: String?, val actionId: String?, val ref: String? = null)

    data class State(
        val day: String = "",
        val shown: Int = 0,
        val lastShownAt: Long = 0L,
        val kindLast: Map<String, Long> = emptyMap(),
        val muted: List<String> = emptyList()
    )

    /** [pick] is null when nothing may be shown; [why] then says what stopped it (for the KPI): off, budget, quiet, gap, cooldown, muted, none. */
    class Choice(val pick: Candidate?, val why: String)

    fun perDay(level: String): Int = when (level) {
        "Normal" -> MAX_PER_DAY
        "Low" -> 1
        else -> 0
    }.coerceAtMost(MAX_PER_DAY)

    /** The local day as a string; [offsetMs] is the time zone offset of the phone. */
    fun dayKey(now: Long, offsetMs: Int): String = "d" + Math.floorDiv(now + offsetMs, 86_400_000L)

    fun hourOf(now: Long, offsetMs: Int): Int = Math.floorMod(Math.floorDiv(now + offsetMs, 3_600_000L), 24L).toInt()

    fun isQuiet(hour: Int): Boolean = hour >= QUIET_FROM_HOUR || hour < QUIET_TO_HOUR

    fun shownToday(st: State, now: Long, offsetMs: Int): Int = if (st.day == dayKey(now, offsetMs)) st.shown else 0

    fun pick(cands: List<Candidate>, st: State, level: String, now: Long, offsetMs: Int): Choice {
        if (cands.isEmpty()) return Choice(null, "none")
        val budget = perDay(level)
        if (budget == 0) return Choice(null, "off")
        if (shownToday(st, now, offsetMs) >= budget) return Choice(null, "budget")
        if (isQuiet(hourOf(now, offsetMs))) return Choice(null, "quiet")
        if (st.lastShownAt > 0L && now - st.lastShownAt < MIN_GAP_MS) return Choice(null, "gap")
        val open = cands.filter { it.kind !in st.muted }
        if (open.isEmpty()) return Choice(null, "muted")
        val fresh = open.filter { c ->
            val last = st.kindLast[c.kind] ?: 0L
            last == 0L || now - last >= KIND_COOLDOWN_MS
        }
        if (fresh.isEmpty()) return Choice(null, "cooldown")
        return Choice(fresh[0], "")
    }

    /** The state after [c] was shown. */
    fun afterShown(st: State, c: Candidate, now: Long, offsetMs: Int): State {
        val day = dayKey(now, offsetMs)
        val count = if (st.day == day) st.shown + 1 else 1
        return st.copy(day = day, shown = count, lastShownAt = now, kindLast = st.kindLast + (c.kind to now))
    }

    fun mute(st: State, kind: String): State = if (kind in st.muted) st else st.copy(muted = st.muted + kind)

    /** Replaces the muted kinds (the Settings dialog). */
    fun setMuted(st: State, kinds: List<String>): State = st.copy(muted = kinds.filter { it in KINDS }.distinct())

    // ---------- candidates from real data ----------

    fun batteryCandidate(percent: Int, charging: Boolean): Candidate? {
        if (charging || percent < 0 || percent > LOW_BATTERY_PERCENT) return null
        return Candidate(
            KIND_BATTERY,
            "Battery sirf $percent% hai aur charge nahi ho rahi. Charger lagao, ya battery saver chalu karo?",
            "Battery saver kholo", "battery_saver"
        )
    }

    /** [onForMs]: how long ago the assistant switched the torch on; [entryId]: that activity-log entry (its Undo switches it off). */
    fun torchCandidate(onForMs: Long, entryId: String): Candidate? {
        if (onForMs < TORCH_LEFT_ON_MS) return null
        val min = onForMs / 60_000L
        return Candidate(KIND_TORCH, "Torch $min minute se on hai. Band kar dun?", "Band karo", "torch_off", entryId)
    }

    // ---------- storage ----------

    private val gson = Gson()

    fun pack(st: State): String = gson.toJson(st)

    /** An empty state when [json] is missing or damaged. */
    fun unpack(json: String?): State {
        if (json.isNullOrBlank()) return State()
        return try {
            val raw = gson.fromJson(json, State::class.java) ?: return State()
            // Gson fills fields without running Kotlin defaults: normalise anything that may be null
            State(
                raw.day ?: "",
                raw.shown,
                raw.lastShownAt,
                raw.kindLast ?: emptyMap(),
                raw.muted ?: emptyList()
            )
        } catch (_: Throwable) {
            State()
        }
    }
}
