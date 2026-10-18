package com.codeassist.ai.search

/**
 * Net Search Agent, stage S2: the gate of audit 6.1 steps 3 to 5, as one pure function. Everything it needs from the phone
 * (settings, key, budget, connection) comes in through [Inputs], so the rules are tested on the JVM and the Android side
 * ([SearchRunner.decide]) only fills the form in.
 *
 * Three answers:
 *  - [Decision.Skip]: no search, the message goes the normal way (nothing changes for the user).
 *  - [Decision.Say]: the app answers with one line and no model (no key, no internet, credits used up, a private number in the question).
 *  - [Decision.Run]: search, then write the answer from the sources.
 *
 * A line is only said when the user clearly asked for the net (they said "search karo" or similar, or the question is about the
 * latest news): a soft cue that cannot be served quietly falls back to a normal chat answer instead of blocking the user.
 */
object WebGate {
    enum class KeyState { NONE, OK, INVALID }

    class Inputs(
        val text: String,
        /** The previous AI message of this chat came from a net search. */
        val prevWeb: Boolean,
        val hasAttachments: Boolean,
        val viaVoice: Boolean,
        /** Settings > Net search switch. */
        val enabled: Boolean,
        val key: KeyState,
        /** The provider said (432 / 433) that its plan is used up, and the month has not changed. */
        val planExhausted: Boolean,
        val remainingToday: Int,
        val dailyCap: Int,
        val online: Boolean
    )

    sealed class Decision {
        /** No search. [why] is for the KPI: disabled, attachments, voice, no_intent, kind, no_key, unavailable, blocked. */
        class Skip(val why: String) : Decision()

        /** Search for [query] (already checked by [QueryGuard]). [numberStripped]: a phone-like number was removed from it. */
        class Run(val plan: SearchPlan, val query: String, val numberStripped: Boolean) : Decision()

        /** One line from the app, no model. [kpi] is the counter to raise. [error] makes the message look like a failed reply. */
        class Say(val text: String, val note: String, val kpi: String, val error: Boolean) : Decision()
    }

    const val NOTE = "Net search"

    const val NO_KEY_TEXT =
        "Net search ke liye Tavily key chahiye. Settings > Voice and AI > Net search mein key daalo " +
            "(free account tavily.com par banta hai)."

    const val EXHAUSTED_TEXT =
        "Tavily ka credit ya plan limit is mahine ke liye khatam ho gaya hai, isliye net search band hai. " +
            "Naya mahina shuru hone par ya naya plan lene par wapas chalega."

    const val OFFLINE_TEXT = "Internet nahi mil raha, isliye net search nahi ho sakta."

    /** Added for latest-news questions: the model's own memory is not a source for "latest". */
    const val NEWS_NOT_FROM_MEMORY =
        " Latest jaankari bina net ke bharosemand nahi hoti, isliye main ise apni yaad se nahi bataunga."

    fun capText(cap: Int): String =
        "Aaj ka net-search credit limit ($cap) poora ho gaya. Kal dobara chalega, ya Settings > Net search mein limit badha do."

    fun decide(i: Inputs): Decision {
        if (!i.enabled) return Decision.Skip("disabled")
        if (i.hasAttachments) return Decision.Skip("attachments")
        if (i.viaVoice) return Decision.Skip("voice")
        val plan = SearchIntent.detect(i.text, i.prevWeb, false) ?: return Decision.Skip("no_intent")
        if (!SearchPlanner.supported(plan)) return Decision.Skip("kind")

        val loud = plan.explicit || SearchPlanner.isNews(plan) || plan.freshness != Freshness.ANY

        when (i.key) {
            KeyState.NONE ->
                return if (plan.explicit) Decision.Say(NO_KEY_TEXT, NOTE + " · key nahi hai", "web_no_key", false) else Decision.Skip("no_key")
            KeyState.INVALID ->
                return if (plan.explicit) {
                    Decision.Say(SearchFailure(FailureKind.BAD_KEY).userMessage(), NOTE + " · key galat", "web_bad_key", true)
                } else {
                    Decision.Skip("no_key")
                }
            KeyState.OK -> Unit
        }

        val unavailable: Decision.Say? = when {
            i.planExhausted -> Decision.Say(EXHAUSTED_TEXT, NOTE + " · plan khatam", "web_plan_exhausted", false)
            i.remainingToday < 1 -> Decision.Say(capText(i.dailyCap), NOTE + " · aaj ki limit", "web_cap_reached", false)
            !i.online -> Decision.Say(
                OFFLINE_TEXT + (if (SearchPlanner.isNews(plan)) NEWS_NOT_FROM_MEMORY else ""),
                NOTE + " · offline", "web_offline", true
            )
            else -> null
        }
        if (unavailable != null) return if (loud) unavailable else Decision.Skip("unavailable")

        return when (val prepared = QueryGuard.prepare(plan.query)) {
            is QueryGuard.Prepared.Blocked ->
                if (plan.explicit) Decision.Say(QueryGuard.BLOCKED_MESSAGE, NOTE + " · query roki", "web_blocked_query", false)
                else Decision.Skip("blocked")
            is QueryGuard.Prepared.Ok -> Decision.Run(plan, prepared.query, prepared.stripped)
        }
    }
}
