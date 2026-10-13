package com.codeassist.ai.ai

import android.content.Context
import com.codeassist.ai.data.Store

/**
 * Phase 4A: the phone side of the memory. The list ([MemoryBook]) is kept as JSON, encrypted with the Keystore key of
 * [SecureStore] (audit Sec 9.5: "Keystore-backed"), in one SharedPreferences value that is not part of any backup
 * (`allowBackup=false`). If the Keystore key is ever lost (app data restored on another phone) the list reads as empty
 * instead of failing. If the Keystore itself refuses to encrypt, the list is kept as plain JSON rather than lost; the
 * value then starts with "p:" instead of "e:".
 *
 * Every change goes through here, so every change is counted for the KPI screen and a delete is verified by reading the
 * stored value back (not the cache) and building the prompt again: "Fact delete karo, agla jawab usse na bole".
 */
object MemoryStore {
    private var cache: List<MemoryItem>? = null

    fun init(ctx: Context) {
        Store.init(ctx)
        ConvKpi.init(ctx)
    }

    // ---------- sealing ----------

    private fun seal(json: String): String =
        try {
            "e:" + SecureStore.encrypt(json)
        } catch (_: Throwable) {
            "p:$json"
        }

    private fun unseal(blob: String): String? = when {
        blob.startsWith("e:") -> SecureStore.decrypt(blob.substring(2))
        blob.startsWith("p:") -> blob.substring(2)
        else -> null
    }

    /** What is really stored, read fresh from disk (not the cache). */
    private fun readStored(): List<MemoryItem>? {
        val blob = Store.memoryBlob ?: return null
        return MemoryBook.unpack(unseal(blob))
    }

    // ---------- the list ----------

    /** The live items (expired ones are dropped and removed from disk). */
    @Synchronized
    fun all(now: Long = System.currentTimeMillis()): List<MemoryItem> {
        val loaded = cache ?: load().also { cache = it }
        val live = MemoryBook.alive(loaded, now)
        if (live.size != loaded.size) save(live)
        return live
    }

    private fun load(): List<MemoryItem> {
        val blob = Store.memoryBlob
        if (blob == null) {
            // first start after Phase 4A: move the old "kind|text" facts over once, then empty them
            val legacy = Store.userFacts
            val moved = MemoryBook.fromLegacy(legacy)
            if (legacy.isNotEmpty()) {
                save(moved)
                Store.userFacts = emptyList()
            }
            return moved
        }
        return MemoryBook.unpack(unseal(blob)) ?: emptyList()
    }

    @Synchronized
    fun save(items: List<MemoryItem>) {
        cache = items
        Store.memoryBlob = seal(MemoryBook.pack(items))
    }

    // ---------- the chat ----------

    /** T2: the user's language choice ("Hinglish me batao"), or null when there is none. */
    fun replyLanguage(now: Long = System.currentTimeMillis()): String? = MemoryBook.replyLanguage(all(now), now)

    /** The few relevant facts for a model prompt, or "". [userText] is the message being answered. */
    fun promptBlock(userText: String): String = MemoryBook.promptBlock(all(), userText, System.currentTimeMillis())

    /**
     * Facts the user stated about themselves in a normal message ("mera naam ... hai"). Skipped in Private mode.
     * Only the clear patterns of [LocalMemory.extract]; nothing is guessed.
     */
    fun autoLearn(text: String, now: Long = System.currentTimeMillis()) {
        val found = LocalMemory.extract(text)
        if (found.isEmpty()) return
        if (Store.memoryPrivate) {
            ConvKpi.inc("mem_private_blocked")
            return
        }
        val items = found.map { line ->
            MemoryBook.make(LocalMemory.kindOf(line), LocalMemory.readable(line), MemoryBook.LONG, MemoryBook.SRC_AUTO, now)
        }
        save(MemoryBook.add(all(now), items, now))
        ConvKpi.inc("mem_added_auto", items.size.toLong())
    }

    /** A memory command typed or spoken in the chat, or null for an ordinary message. */
    fun command(text: String, now: Long = System.currentTimeMillis()): Tier0.Result? {
        val r = MemoryCommands.handle(text, all(now), now, Store.memoryPrivate) ?: return null
        val items = r.items
        if (items != null) save(items)
        val priv = r.privateMode
        if (priv != null) Store.memoryPrivate = priv
        ConvKpi.inc("mem_" + r.event)
        return Tier0.Result("Yaad", r.reply)
    }

    // ---------- the Memory screen ----------

    /** Deletes one item and checks that it is really gone from disk and from the prompt. Returns false if the check failed. */
    @Synchronized
    fun delete(id: String): Boolean {
        val now = System.currentTimeMillis()
        val item = MemoryBook.find(all(now), id) ?: return true
        save(MemoryBook.delete(all(now), id))
        ConvKpi.inc("mem_deleted")
        val ok = verifyGone(item)
        ConvKpi.inc(if (ok) "mem_delete_ok" else "mem_delete_fail")
        return ok
    }

    /** Read the stored value back and build the prompt again: the deleted words must be in neither. */
    fun verifyGone(item: MemoryItem): Boolean {
        val stored = readStored() ?: emptyList()
        if (stored.any { it.id == item.id || it.text == item.text }) return false
        val block = MemoryBook.promptBlock(stored, item.text, System.currentTimeMillis())
        return !block.contains(item.text)
    }

    /** Error text for the user, or null when the new words were saved. */
    @Synchronized
    fun edit(id: String, newText: String): String? {
        val now = System.currentTimeMillis()
        return when (val r = MemoryBook.editText(all(now), id, newText, now)) {
            is MemoryBook.Edit.Refused -> r.reason
            is MemoryBook.Edit.Ok -> {
                save(r.items)
                ConvKpi.inc("mem_edited")
                null
            }
        }
    }

    @Synchronized
    fun promote(id: String) {
        val now = System.currentTimeMillis()
        save(MemoryBook.promote(all(now), id))
        ConvKpi.inc("mem_promoted")
    }

    /** Deletes everything. Returns how many items were removed; the stored value is checked to be empty. */
    @Synchronized
    fun forgetAll(): Int {
        val n = all().size
        save(emptyList())
        ConvKpi.inc("mem_forget_all")
        ConvKpi.inc(if ((readStored() ?: listOf<MemoryItem>()).isEmpty()) "mem_delete_ok" else "mem_delete_fail")
        return n
    }

    @Synchronized
    fun forgetRecent(minutes: Int): Int {
        val now = System.currentTimeMillis()
        val (kept, removed) = MemoryBook.forgetSince(all(now), now - minutes * 60_000L)
        if (removed > 0) save(kept)
        ConvKpi.inc("mem_forget_recent")
        return removed
    }

    fun exportText(stamp: String): String {
        ConvKpi.inc("mem_export")
        return MemoryBook.export(all(), System.currentTimeMillis(), stamp)
    }
}
