package com.codeassist.ai.ai

import com.google.gson.Gson
import java.util.UUID

/**
 * One remembered thing. [layer] is [MemoryBook.LONG] (until the user deletes it) or [MemoryBook.WORK] (a day). [createdAt] is 0
 * for facts that were saved before Phase 4A (their time is unknown, so "pichhle 10 minute bhool jao" never touches them).
 */
data class MemoryItem(
    val id: String,
    val kind: String,
    val text: String,
    val layer: String,
    val createdAt: Long,
    val expiresAt: Long,
    val source: String
)

/**
 * Audit Sec 9.5 (Phase 4A): the layers and the rules of the user's memory, without any Android type, so every rule is
 * unit-tested on the JVM. Storage and encryption are in [MemoryStore].
 *
 *  - LONG ("Hamesha"): name, city, likes, "yaad rakho ki ..." notes. Same kind replaces the older one (a new name replaces the old).
 *  - WORK ("Abhi ke liye"): temporary things such as a phone number said in passing; gone after 24 hours.
 *  - What goes to the model is a small, relevant part ([promptFacts]), not the whole list.
 */
object MemoryBook {
    const val LONG = "long"
    const val WORK = "work"
    const val SRC_AUTO = "auto"
    const val SRC_SAID = "said"
    const val SRC_EDIT = "edit"
    const val SRC_OLD = "old"

    const val MAX_ITEMS = 40
    const val PROMPT_MAX = 6
    const val MIN_TEXT = 3
    const val MAX_TEXT = 140
    const val WORK_TTL_MS = 24L * 60L * 60L * 1000L

    const val PROMPT_HEADER = "Known about the user (use only when relevant, do not repeat unprompted):"

    private val coreKinds = setOf("name", "lives")
    private val gson = Gson()

    fun make(kind: String, text: String, layer: String, source: String, now: Long): MemoryItem =
        MemoryItem(
            UUID.randomUUID().toString().take(8),
            kind.replace('|', ' ').replace('\n', ' ').take(60),
            clean(text),
            layer,
            now,
            if (layer == WORK) now + WORK_TTL_MS else 0L,
            source
        )

    /** One line, single spaces, at most [MAX_TEXT] characters. */
    fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)

    fun alive(items: List<MemoryItem>, now: Long): List<MemoryItem> =
        items.filter { it.expiresAt == 0L || it.expiresAt > now }

    /** Adds [incoming]: same kind replaces; when the book is full the oldest automatic item goes first, then the oldest. */
    fun add(items: List<MemoryItem>, incoming: List<MemoryItem>, now: Long): List<MemoryItem> {
        val out = ArrayList(alive(items, now))
        for (inc in incoming) {
            out.removeAll { it.kind == inc.kind }
            out.add(inc)
        }
        while (out.size > MAX_ITEMS) {
            val victim = out.filter { it.source == SRC_AUTO }.minByOrNull { it.createdAt }
                ?: out.minByOrNull { it.createdAt }
                ?: break
            out.removeAll { it.id == victim.id }
        }
        return out
    }

    fun find(items: List<MemoryItem>, id: String): MemoryItem? = items.firstOrNull { it.id == id }

    fun delete(items: List<MemoryItem>, id: String): List<MemoryItem> = items.filter { it.id != id }

    sealed class Edit {
        class Ok(val items: List<MemoryItem>) : Edit()
        class Refused(val reason: String) : Edit()
    }

    /** Changes the words of one item. The same never-store rule applies as when saving. */
    fun editText(items: List<MemoryItem>, id: String, newText: String, now: Long): Edit {
        val old = find(items, id) ?: return Edit.Refused("Ye yaad ab mili nahi.")
        val t = clean(newText)
        if (t.length < MIN_TEXT) return Edit.Refused("Kam se kam $MIN_TEXT akshar likho.")
        if (MemoryGuard.check(t).kind == MemoryGuard.Kind.NEVER) return Edit.Refused(MemoryGuard.NEVER_MESSAGE)
        return Edit.Ok(items.map { if (it.id == old.id) it.copy(text = t, source = SRC_EDIT) else it })
    }

    /** "Abhi ke liye" -> "Hamesha": the item no longer expires. */
    fun promote(items: List<MemoryItem>, id: String): List<MemoryItem> =
        items.map { if (it.id == id) it.copy(layer = LONG, expiresAt = 0L) else it }

    /** Removes everything saved at or after [sinceMs]. Items from before Phase 4A (createdAt 0) stay. */
    fun forgetSince(items: List<MemoryItem>, sinceMs: Long): Pair<List<MemoryItem>, Int> {
        val kept = items.filter { it.createdAt == 0L || it.createdAt < sinceMs }
        return Pair(kept, items.size - kept.size)
    }

    fun search(items: List<MemoryItem>, query: String): List<MemoryItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items
        return items.filter { it.text.lowercase().contains(q) }
    }

    // ---------- what the model gets ----------

    private fun words(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]{3,}").findAll(text.lowercase()).map { it.value }.toSet()

    /**
     * Audit "retrieval: keyword + recency, top 3 to 5": name and city always (they are short and almost always useful), then the
     * items that share words with [userText], then the newest, up to [PROMPT_MAX] in all. A deleted or expired item is never here.
     */
    fun promptFacts(items: List<MemoryItem>, userText: String, now: Long): List<MemoryItem> {
        // a name saved by the old rule from "mera naam jante hoo" ("Jante") is never told to the model
        // the language choice has its own line in the system prompt ([Prompts.languageLine]); it is not a "fact" to use when relevant
        val live = alive(items, now).filterNot {
            (it.kind == "name" && LocalMemory.isWrongName(it.text)) || it.kind == LocalMemory.KIND_REPLY_LANGUAGE
        }
        val core = live.filter { it.kind in coreKinds }.sortedByDescending { it.createdAt }
        val asked = words(userText)
        val rest = live.filter { it.kind !in coreKinds }.sortedWith(
            compareByDescending<MemoryItem> { item -> words(item.text).count { it in asked } }
                .thenByDescending { it.createdAt }
        )
        return (core + rest).take(PROMPT_MAX)
    }

    /** T2: the user's language choice ("Hinglish me batao"): [LocalMemory.LANG_HINGLISH], [LocalMemory.LANG_ENGLISH] or null. */
    fun replyLanguage(items: List<MemoryItem>, now: Long): String? {
        val item = alive(items, now).filter { it.kind == LocalMemory.KIND_REPLY_LANGUAGE }.maxByOrNull { it.createdAt } ?: return null
        return LocalMemory.languageOfFact(item.text)
    }

    fun promptBlock(items: List<MemoryItem>, userText: String, now: Long): String {
        val facts = promptFacts(items, userText, now)
        if (facts.isEmpty()) return ""
        val sb = StringBuilder(PROMPT_HEADER)
        for (f in facts) sb.append("\n- ").append(f.text)
        return sb.toString()
    }

    // ---------- showing ----------

    fun ageWords(createdAt: Long, now: Long): String {
        if (createdAt <= 0L) return "purani yaad"
        val min = ((now - createdAt) / 60_000L).coerceAtLeast(0L)
        return when {
            min < 1 -> "abhi"
            min < 60 -> "$min minute pehle"
            min < 24 * 60 -> (min / 60).toString() + " ghante pehle"
            else -> (min / (24 * 60)).toString() + " din pehle"
        }
    }

    fun sourceWords(source: String): String = when (source) {
        SRC_AUTO -> "tumhari baat se pakda"
        SRC_SAID -> "tumne kaha"
        SRC_EDIT -> "tumne badla"
        else -> "purani yaad"
    }

    /** "2 ghante baaki" for a working item, null for a long one. */
    fun leftWords(item: MemoryItem, now: Long): String? {
        if (item.expiresAt == 0L) return null
        val min = ((item.expiresAt - now) / 60_000L).coerceAtLeast(0L)
        return if (min < 60) "$min minute baaki" else (min / 60).toString() + " ghante baaki"
    }

    /** Plain text of the whole book for the Export button. [stamp] is the date text the caller wants in the first line. */
    fun export(items: List<MemoryItem>, now: Long, stamp: String): String {
        val live = alive(items, now)
        val sb = StringBuilder("CodeAssistAI memory export · ").append(stamp).append('\n')
        val long = live.filter { it.layer == LONG }
        val work = live.filter { it.layer == WORK }
        sb.append("\nHamesha ke liye (").append(long.size).append("):\n")
        if (long.isEmpty()) sb.append("- (khaali)\n")
        for (i in long) sb.append("- ").append(i.text).append(" (").append(sourceWords(i.source)).append(" · ").append(ageWords(i.createdAt, now)).append(")\n")
        sb.append("\nAbhi ke liye, 24 ghante (").append(work.size).append("):\n")
        if (work.isEmpty()) sb.append("- (khaali)\n")
        for (i in work) sb.append("- ").append(i.text).append(" (").append(leftWords(i, now)).append(")\n")
        return sb.toString()
    }

    // ---------- storage format ----------

    fun pack(items: List<MemoryItem>): String = gson.toJson(items)

    /** Null when [json] is not a memory list (damaged). */
    fun unpack(json: String?): List<MemoryItem>? {
        if (json.isNullOrBlank()) return null
        return try {
            val arr = gson.fromJson(json, Array<MemoryItem>::class.java) ?: return null
            arr.filter { it.id.isNotBlank() && it.text.isNotBlank() && (it.layer == LONG || it.layer == WORK) }
        } catch (_: Throwable) {
            null
        }
    }

    /** The "kind|text" lines Phase 2 kept: they become LONG items of unknown age. */
    fun fromLegacy(lines: List<String>): List<MemoryItem> =
        lines.mapNotNull { line ->
            val text = clean(LocalMemory.readable(line))
            val kind = if (line.contains('|')) LocalMemory.kindOf(line).take(60) else "note:" + text.lowercase().take(40)
            if (text.length < MIN_TEXT) null
            else MemoryItem(UUID.randomUUID().toString().take(8), kind.ifBlank { "note" }, text, LONG, 0L, 0L, SRC_OLD)
        }
}
