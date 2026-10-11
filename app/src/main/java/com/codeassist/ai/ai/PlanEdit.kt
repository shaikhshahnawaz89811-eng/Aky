package com.codeassist.ai.ai

/**
 * Audit Sec 9.6 "Plan edits" / Appendix A #20 (Phase 3C): right after a multi-step reply the user takes one step back:
 * "petrol wala kaam hata do", "torch wala kaam hata do", "call wala kaam cancel karo".
 *
 * Pure grammar and rules (JVM-tested); the phone side is [PlanEditRunner] and [TaskEngine.applyEdit]. Same promise as
 * [Correction]: deterministic, no model is asked, and when the words do not clearly name a step of THAT plan, nothing
 * is guessed.
 *
 *  - It only applies to a message that comes right after a plan reply (the caller checks that) and that is short (12 words
 *    at most) and has a remove phrase ("hata do", "cancel karo", "rehne do", "mat karo" ...) plus a name of the step.
 *  - The name is a tool word (torch, timer, alarm, call, battery, time, date, app, mausam). "pehla / doosra" picks one when
 *    the plan has the same tool twice. A name that is no tool of the plan ("petrol": no such tool exists) is answered
 *    honestly: no such step, and what the plan has.
 *  - A step that has not run yet is cancelled and the steps that wait for it are skipped. A step that already ran is taken
 *    back through its Undo when it has one (torch, alarm, timer); an answer that was only read out (time, battery) has
 *    nothing to take back.
 */
object PlanEdit {
    /** How long after the plan an edit is still understood. Same as the confirmation window. */
    const val WINDOW_MS = PolicyGate.PENDING_TTL_MS

    private const val MAX_WORDS = 12

    /** [tool] is null when the words name no tool ([subject] then holds what was said, e.g. "petrol"). [ordinal] 0 = not said. */
    class Request(val tool: String?, val subject: String, val ordinal: Int)

    enum class Action { CANCEL_NODE, UNDO_DONE, NOTHING_TO_UNDO, NOT_RUN, BUSY }

    sealed class Pick {
        class Found(val node: TaskGraph.Node) : Pick()
        object NotFound : Pick()
        class Ambiguous(val count: Int) : Pick()
    }

    private val removePhrases: List<List<String>> = listOf(
        "cancel kar do", "cancel kar de", "cancel karo", "hata dijiye", "hata dena", "hata do", "hata de", "hatao",
        "rehne do", "rehne de", "rahne do", "chhod do", "chod do", "nikal do", "delete kar do", "delete karo",
        "remove karo", "mat karo", "mat lagao", "cancel", "remove"
    ).map { it.split(' ') }.sortedByDescending { it.size }

    private val fillers = setOf(
        "wala", "wali", "wale", "waala", "kaam", "kam", "task", "ka", "ki", "ke", "ko", "se", "plan", "ye", "yeh", "wo", "woh",
        "usko", "isko", "mera", "meri", "please", "plz", "pls", "bhi", "nahi", "nahin", "arre", "are", "ruko", "wait", "sorry",
        "aur", "toh", "to", "hi", "step", "karna", "karne"
    )

    private val ordinals: Map<String, Int> = mapOf(
        "pehla" to 1, "pehle" to 1, "pehli" to 1, "first" to 1, "1st" to 1,
        "doosra" to 2, "dusra" to 2, "doosre" to 2, "dusre" to 2, "doosri" to 2, "second" to 2, "2nd" to 2,
        "teesra" to 3, "teesre" to 3, "third" to 3, "3rd" to 3,
        "chautha" to 4, "chauthe" to 4, "fourth" to 4, "4th" to 4
    )

    private val toolWords: Map<String, String> = mapOf(
        "torch" to "torch_set", "flashlight" to "torch_set", "flash" to "torch_set", "टॉर्च" to "torch_set",
        "timer" to "timer_set", "टाइमर" to "timer_set",
        "alarm" to "alarm_set", "alaram" to "alarm_set", "अलार्म" to "alarm_set",
        "call" to "call_dial", "dial" to "call_dial", "phone" to "call_dial", "कॉल" to "call_dial",
        "battery" to "battery_level", "बैटरी" to "battery_level",
        "time" to "time_now", "samay" to "time_now", "समय" to "time_now",
        "date" to "date_today", "tarikh" to "date_today", "tareekh" to "date_today", "तारीख" to "date_today",
        "app" to "app_open", "open" to "app_open", "kholo" to "app_open",
        "mausam" to "weather_now", "weather" to "weather_now", "मौसम" to "weather_now"
    )

    /** Null = this message is not "take a step of the plan away". */
    fun parse(raw: String): Request? {
        val t = Tier0.clean(raw)
        if (t.isEmpty()) return null
        var words: List<String> = t.split(' ')
        if (words.size > MAX_WORDS) return null

        var matched = false
        for (phrase in removePhrases) {
            val i = indexOfPhrase(words, phrase)
            if (i >= 0) {
                words = words.subList(0, i) + words.subList(i + phrase.size, words.size)
                matched = true
                break
            }
        }
        if (!matched) return null

        var ordinal = 0
        var tool: String? = null
        val subject = ArrayList<String>()
        for (w in words) {
            val ord = ordinals[w]
            if (ord != null) {
                if (ordinal == 0) ordinal = ord
                continue
            }
            if (w in fillers) continue
            if (tool == null) tool = toolWords[w]
            subject.add(w)
        }
        if (subject.isEmpty()) return null
        return Request(tool, subject.joinToString(" "), ordinal)
    }

    private fun indexOfPhrase(words: List<String>, phrase: List<String>): Int {
        if (phrase.size > words.size) return -1
        for (i in 0..(words.size - phrase.size)) {
            var ok = true
            for (j in phrase.indices) {
                if (words[i + j] != phrase[j]) {
                    ok = false
                    break
                }
            }
            if (ok) return i
        }
        return -1
    }

    /** What taking [n] away means right now. */
    fun actionFor(n: TaskGraph.Node): Action = when (n.state) {
        TaskGraph.State.PENDING, TaskGraph.State.WAITING_CONFIRM -> Action.CANCEL_NODE
        TaskGraph.State.COMPLETED -> if (n.undoEntryId != null) Action.UNDO_DONE else Action.NOTHING_TO_UNDO
        TaskGraph.State.FAILED, TaskGraph.State.SKIPPED, TaskGraph.State.CANCELLED -> Action.NOT_RUN
        TaskGraph.State.RUNNING -> Action.BUSY
    }

    /** Finds the step the request means in [plan], or says why not. */
    fun pick(plan: TaskGraph.Plan, req: Request): Pick {
        val tool = req.tool ?: return Pick.NotFound
        val matches = plan.nodes.filter { it.tool == tool }
        if (matches.isEmpty()) return Pick.NotFound
        if (req.ordinal > 0) {
            return if (req.ordinal <= matches.size) Pick.Found(matches[req.ordinal - 1]) else Pick.NotFound
        }
        if (matches.size == 1) return Pick.Found(matches[0])
        val removable = matches.filter {
            val a = actionFor(it)
            a == Action.CANCEL_NODE || a == Action.UNDO_DONE
        }
        return if (removable.size == 1) Pick.Found(removable[0]) else Pick.Ambiguous(matches.size)
    }

    /** "Torch (ho gaya), Call (tap ka wait)": what the plan has, for the "no such step" answer. */
    fun listing(plan: TaskGraph.Plan): String =
        plan.nodes.joinToString(", ") { TaskGraph.label(it.tool) + " (" + stateWord(it.state) + ")" }

    fun stateWord(s: TaskGraph.State): String = when (s) {
        TaskGraph.State.PENDING -> "baaki"
        TaskGraph.State.RUNNING -> "chal raha hai"
        TaskGraph.State.WAITING_CONFIRM -> "tap ka wait"
        TaskGraph.State.COMPLETED -> "ho gaya"
        TaskGraph.State.FAILED -> "nahi hua"
        TaskGraph.State.CANCELLED -> "cancel"
        TaskGraph.State.SKIPPED -> "chhoda"
    }
}
