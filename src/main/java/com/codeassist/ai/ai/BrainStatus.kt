package com.codeassist.ai.ai

import com.codeassist.ai.data.Store

/** One-line status text for the header and the composer chip. */
object BrainStatus {
    fun chipLabel(): String = if (Store.brainProvider == "gemini") "Gemini" else "Gemma 4 E2B"

    fun line(): String {
        if (Store.brainProvider == "gemini") {
            if (Store.geminiKey.isNullOrBlank()) return "Gemini · API key chahiye"
            return "Gemini · " + Store.geminiModel.ifBlank { GeminiClient.FALLBACK_MODEL }
        }
        return when (Modules.phase) {
            Modules.Phase.LOADED -> "Gemma 4 E2B · on-device · loaded"
            Modules.Phase.LOADING -> "Gemma 4 E2B · load ho raha hai"
            Modules.Phase.UNLOADED -> "Gemma 4 E2B · on-device"
            Modules.Phase.DOWNLOADING, Modules.Phase.IMPORTING -> "Gemma 4 E2B · taiyaar ho raha hai"
            else -> "Gemma 4 E2B · import nahi hua"
        }
    }
}
