package com.codeassist.ai.voice

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

/**
 * Can this phone run the wake word the way the audit asks (nothing leaves the device)?
 *
 * The wake engine feeds short bursts of ambient speech to a speech recognizer. If that recognizer were the
 * normal online one, every noise burst in the room would go to a server. So the wake word is only offered
 * when Android has a recognizer that works strictly on the device (API 33+, language pack installed).
 */
object WakeSupport {
    class Check(val ok: Boolean, val reason: String)

    fun check(context: Context): Check {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return Check(
                false,
                "Wake word ke liye Android 13 ya naya chahiye. Purane Android par sirf online recognizer milta hai, " +
                    "aur wo kamre ki har awaaz server ko bhejta. Isliye yahan wake word band hai. Mic button / tap-to-talk chalta rahega."
            )
        }
        val onDevice = try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context.applicationContext)
        } catch (_: Throwable) {
            false
        }
        if (!onDevice) {
            return Check(
                false,
                "Is phone par on-device speech recognizer nahi mila. Google app update karo aur Settings mein " +
                    "offline speech pack (English) download karo, phir dobara try karo."
            )
        }
        return Check(true, "")
    }
}
