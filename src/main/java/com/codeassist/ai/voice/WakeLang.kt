package com.codeassist.ai.voice

/**
 * Which recognizer language the wake word uses, and which language pack to ask Android to download.
 * Pure Kotlin (no Android classes) so every choice is unit-tested on the JVM ([WakeLangTest]).
 *
 * Why this exists: the on-device recognizer (Android 13+) keeps its own language packs. The "offline speech" pack
 * the Google app shows is not always the same thing, so "English (India)" can be on in Settings and the recognizer
 * still answers error 12 / 13 for `en-IN`. A short wake phrase like "Jarvis" is recognized fine by any English pack,
 * so the order is: `en-IN`, `en-US`, `en-GB`, then any other installed English pack. A Devanagari phrase only
 * ever uses `hi-IN`.
 */
object WakeLang {
    private val ENGLISH = listOf("en-IN", "en-US", "en-GB")
    private val HINDI = listOf("hi-IN")

    fun isDevanagari(phrase: String): Boolean = phrase.any { it in '\u0900'..'\u097F' }

    /** Languages to try, best first. */
    fun candidates(phrase: String): List<String> = if (isDevanagari(phrase)) HINDI else ENGLISH

    /** "en_IN" and " EN-in " both become "en-in", so tags from different Android versions compare equal. */
    fun norm(tag: String): String = tag.trim().replace('_', '-').lowercase()

    /**
     * First of [candidates] that is in [installed] and not in [skip] (languages that already failed).
     * If none is, another region of the same language (en-AU, en-CA ...) is accepted. Null = nothing usable installed.
     */
    fun pickInstalled(candidates: List<String>, installed: Collection<String>, skip: Set<String> = emptySet()): String? {
        val have = installed.map { norm(it) }.toSet()
        val skipN = skip.map { norm(it) }.toSet()
        for (c in candidates) {
            val n = norm(c)
            if (n in have && n !in skipN) return c
        }
        val family = candidates.firstOrNull()?.let { norm(it).substringBefore('-') } ?: return null
        for (tag in installed) {
            val n = norm(tag)
            if (n.substringBefore('-') == family && n !in skipN) return tag.trim().replace('_', '-')
        }
        return null
    }

    /** First candidate whose pack is already being downloaded ([pending]); then there is nothing to trigger. */
    fun pickPending(candidates: List<String>, pending: Collection<String>, skip: Set<String> = emptySet()): String? {
        val wait = pending.map { norm(it) }.toSet()
        val skipN = skip.map { norm(it) }.toSet()
        return candidates.firstOrNull { norm(it) in wait && norm(it) !in skipN }
    }

    /**
     * Language to ask Android to download: the first candidate the recognizer lists as [supported] on the device.
     * If Android gives no list at all, the first candidate is tried. Null = no candidate can be installed.
     */
    fun pickDownload(candidates: List<String>, supported: Collection<String>, skip: Set<String> = emptySet()): String? {
        val skipN = skip.map { norm(it) }.toSet()
        val open = candidates.filter { norm(it) !in skipN }
        if (open.isEmpty()) return null
        if (supported.isEmpty()) return open.first()
        val sup = supported.map { norm(it) }.toSet()
        return open.firstOrNull { norm(it) in sup }
    }

    /** Name for messages. */
    fun label(tag: String): String = when (norm(tag)) {
        "en-in" -> "English (India)"
        "en-us" -> "English (US)"
        "en-gb" -> "English (UK)"
        "hi-in" -> "Hindi (India)"
        else -> tag
    }
}
