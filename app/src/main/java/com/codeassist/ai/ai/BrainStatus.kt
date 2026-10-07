package com.codeassist.ai.ai

import com.codeassist.ai.data.Store

/** One-line status text for the header and the composer chip. */
object BrainStatus {
    fun chipLabel(): String = if (Store.brainProvider == "gemini") "Gemini" else "Qwen2.5 1.5B"

    fun line(): String {
        if (Store.brainProvider == "gemini") {
            if (Store.geminiKey.isNullOrBlank()) return "Gemini · API key chahiye"
            return "Gemini · " + Store.geminiModel.ifBlank { GeminiClient.FALLBACK_MODEL }
        }
        return when (Modules.phase) {
            Modules.Phase.LOADED -> "Qwen2.5 1.5B · on-device · loaded"
            Modules.Phase.LOADING -> "Qwen2.5 1.5B · load ho raha hai"
            Modules.Phase.UNLOADED -> "Qwen2.5 1.5B · on-device"
            Modules.Phase.DOWNLOADING, Modules.Phase.IMPORTING -> "Qwen2.5 1.5B · taiyaar ho raha hai"
            else -> "Qwen2.5 1.5B · import nahi hua"
        }
    }
}
