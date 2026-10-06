package com.codeassist.ai.ai

import com.codeassist.ai.data.Store

/** One-line status text for the header and the composer chip. */
object BrainStatus {
    fun chipLabel(): String = if (Store.brainProvider == "gemini") "Gemini" else "Phi-4 mini"

    fun line(): String {
        if (Store.brainProvider == "gemini") {
            if (Store.geminiKey.isNullOrBlank()) return "Gemini · API key chahiye"
            return "Gemini · " + Store.geminiModel.ifBlank { GeminiClient.FALLBACK_MODEL }
        }
        return when (Modules.phase) {
            Modules.Phase.LOADED -> "Phi-4 mini · on-device · loaded"
            Modules.Phase.LOADING -> "Phi-4 mini · load ho raha hai"
            Modules.Phase.UNLOADED -> "Phi-4 mini · on-device"
            Modules.Phase.DOWNLOADING, Modules.Phase.IMPORTING -> "Phi-4 mini · taiyaar ho raha hai"
            else -> "Phi-4 mini · import nahi hui"
        }
    }
}
