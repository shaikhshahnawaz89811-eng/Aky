package com.codeassist.ai.ai

/**
 * Phase 4A: what the chat does with a spoken or typed memory command. Pure Kotlin, JVM-tested; [MemoryStore] applies the result
 * to the phone. These messages never reach a model, so they work every time and offline.
 *
 *  - "yaad rakho ki ..."  -> saved as a note. A number is kept for a day only (audit "number yaad rakhna"), unless the words say
 *    "hamesha"; OTP / password / PIN / card / Aadhaar / PAN are refused ([MemoryGuard]).
 *  - "tum mere baare mein kya jaante ho"  -> the list.
 *  - "bhool jao"  -> everything is deleted.
 *  - "pichhle 10 minute bhool jao"  -> only what was saved in the last 10 minutes is deleted.
 *  - "private mode on" / "private mode off"  -> nothing new is saved while it is on.
 */
object MemoryCommands {
    /**
     * [items] is the new list (null = nothing changed), [privateMode] the new switch (null = unchanged), [event] a short
     * word for the KPI counters.
     */
    class Result(val reply: String, val items: List<MemoryItem>?, val privateMode: Boolean?, val event: String)

    const val MAX_FORGET_MINUTES = 24 * 60

    const val PRIVATE_BLOCKED_REPLY =
        "Private mode on hai, isliye kuch save nahi karunga. Chalu karne ke liye bolo \"private mode off\"."

    private val privateRx = Regex(
        "(?i)^\\s*private\\s+mode\\s+(on|off)(?:\\s+kar(?:o|\\s+do|\\s+de))?\\s*[.!]?\\s*$"
    )

    private val forgetRecentHi = Regex(
        "(?i)^\\s*(?:pichhle|pichle|pichhli|pichli|last)\\s+(\\d{1,4})\\s*(minute|minutes|min|mins|ghante|ghanta|ghantey|hour|hours)\\s+" +
            "(?:ki\\s+|ka\\s+|ke\\s+)?(?:saari\\s+|sab\\s+)?(?:yaad\\s+|memory\\s+)?" +
            "(?:bhool\\s+jao|bhula\\s+do|hata\\s+do|mita\\s+do|delete\\s+karo|clear\\s+karo)\\s*[.!]?\\s*$"
    )
    private val forgetRecentEn = Regex(
        "(?i)^\\s*forget\\s+(?:the\\s+)?last\\s+(\\d{1,4})\\s*(minute|minutes|min|mins|hour|hours)\\s*[.!]?\\s*$"
    )

    /** "hamesha yaad rakho ki ..." (the order the reply of a temporary note suggests). */
    private val leadAlwaysRx = Regex("(?i)^\\s*(?:hamesha\\s+(?:ke\\s+liye\\s+)?|always\\s+)")

    /** Only the exact phrase: a plain "hamesha" or "always" inside a sentence ("main hamesha late hoon") is part of the note. */
    private val alwaysRx = Regex("(?i)\\b(?:hamesha\\s+ke\\s+liye|permanently)\\b[\\s,:-]*(?:ki\\s+)?")

    fun handle(text: String, items: List<MemoryItem>, now: Long, privateMode: Boolean): Result? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 240) return null

        privateRx.matchEntire(t)?.let { m ->
            return if (m.groupValues[1].lowercase() == "on") {
                Result(
                    "Private mode on: ab kuch naya save nahi hoga. Jo pehle se yaad hai wo abhi bhi use hoga. " +
                        "Band karne ke liye bolo \"private mode off\".",
                    null, true, "private_on"
                )
            } else {
                Result("Private mode off: ab main phir se yaad rakh sakta hoon.", null, false, "private_off")
            }
        }

        val recent = forgetRecentHi.matchEntire(t) ?: forgetRecentEn.matchEntire(t)
        if (recent != null) {
            val n = recent.groupValues[1].toInt()
            val unit = recent.groupValues[2].lowercase()
            val minutes = if (unit.startsWith("min")) n else n * 60
            if (minutes < 1 || minutes > MAX_FORGET_MINUTES) {
                return Result("Ek baar mein 24 ghante (1440 minute) tak ka hi bhula sakta hoon.", null, null, "forget_recent_refused")
            }
            val (kept, removed) = MemoryBook.forgetSince(items, now - minutes * 60_000L)
            val words = if (minutes % 60 == 0 && minutes >= 60) (minutes / 60).toString() + " ghante" else "$minutes minute"
            return if (removed == 0) {
                Result("Pichhle $words mein maine kuch save nahi kiya tha.", null, null, "forget_recent_none")
            } else {
                Result("Theek hai, pichhle $words ki $removed yaad hata di.", kept, null, "forget_recent")
            }
        }

        var forceAlways = false
        var parsed: LocalMemory.Cmd? = null
        val lead = leadAlwaysRx.find(t)
        if (lead != null) {
            val p = LocalMemory.parse(t.substring(lead.range.last + 1))
            if (p is LocalMemory.Cmd.Remember) {
                parsed = p
                forceAlways = true
            }
        }
        if (parsed == null) parsed = LocalMemory.parse(t)

        return when (val c = parsed ?: return null) {
            is LocalMemory.Cmd.Forget ->
                if (items.isEmpty()) Result("Abhi mujhe aapke baare mein kuch yaad hi nahi tha.", null, null, "forget_none")
                else Result("Theek hai, maine sab bhula diya (" + items.size + " yaad).", emptyList(), null, "forget_all")
            is LocalMemory.Cmd.Show -> show(items, now)
            is LocalMemory.Cmd.Incomplete -> Result(LocalMemory.INCOMPLETE_REPLY, null, null, "incomplete")
            is LocalMemory.Cmd.Remember -> remember(c.note, items, now, privateMode, forceAlways)
        }
    }

    private fun show(items: List<MemoryItem>, now: Long): Result {
        val live = MemoryBook.alive(items, now)
        if (live.isEmpty()) {
            return Result(
                "Abhi mujhe aapke baare mein kuch yaad nahi hai. Bolo \"yaad rakho ki ...\" ya \"mera naam ... hai\".",
                null, null, "show"
            )
        }
        val lines = live.joinToString("\n") { "• " + it.text + (if (it.layer == MemoryBook.WORK) " (abhi ke liye)" else "") }
        return Result(
            "Mujhe aapke baare mein ye yaad hai:\n$lines\n\nEk-ek hatane ya badalne ke liye Settings > Memory kholo. Sab bhulana ho toh bolo \"bhool jao\".",
            null, null, "show"
        )
    }

    private fun remember(rawNote: String, items: List<MemoryItem>, now: Long, privateMode: Boolean, forceAlways: Boolean): Result {
        if (privateMode) return Result(PRIVATE_BLOCKED_REPLY, null, null, "private_blocked")
        val always = forceAlways || alwaysRx.containsMatchIn(rawNote)
        val note = MemoryBook.clean(rawNote.replace(alwaysRx, ""))
        if (note.length < MemoryBook.MIN_TEXT) return Result(LocalMemory.INCOMPLETE_REPLY, null, null, "incomplete")

        val verdict = MemoryGuard.check(note)
        if (verdict.kind == MemoryGuard.Kind.NEVER) return Result(MemoryGuard.NEVER_MESSAGE, null, null, "blocked")

        val temporary = verdict.kind == MemoryGuard.Kind.TEMPORARY && !always
        val layer = if (temporary) MemoryBook.WORK else MemoryBook.LONG
        val item = MemoryBook.make("note:" + note.lowercase().take(40), note, layer, MemoryBook.SRC_SAID, now)
        val updated = MemoryBook.add(items, listOf(item), now)
        val digits = if (verdict.kind == MemoryGuard.Kind.TEMPORARY) MemoryGuard.readback(note) else null
        val readback = if (digits != null) " Number dobara padh raha hoon: $digits." else ""
        return if (temporary) {
            Result(
                "Theek hai, abhi ke liye (24 ghante) yaad rakha: $note.$readback Hamesha ke liye chahiye toh bolo \"hamesha yaad rakho ki ...\".",
                updated, null, "working"
            )
        } else {
            Result("Theek hai, hamesha ke liye yaad rakh liya: $note.$readback", updated, null, "said")
        }
    }
}
