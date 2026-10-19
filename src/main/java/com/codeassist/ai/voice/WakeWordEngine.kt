package com.codeassist.ai.voice

/**
 * A wake-word detector. The app only depends on this interface, so the stand-in below
 * ([VadGatedWakeWord]) can be replaced by a real keyword-spotting engine later (audit PDF Sec 9.1:
 * "on-device keyword-spotting engine, benchmark FAR / FRR") without touching the service or the screens.
 * All listener methods are called on the main thread.
 */
interface WakeWordEngine {
    interface Listener {
        /** [heard] = what the recognizer returned, [remainder] = the words after the phrase (may be empty). */
        fun onWake(heard: String, remainder: String)

        /** The engine cannot work (permission gone, no on-device recognizer, language pack missing). */
        fun onUnavailable(reason: String)
    }

    fun start()

    fun stop()
}
