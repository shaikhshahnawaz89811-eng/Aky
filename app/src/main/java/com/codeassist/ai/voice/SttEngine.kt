package com.codeassist.ai.voice

import android.content.Intent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

/**
 * The five calls [VoiceController] makes on a speech recognizer. The names match
 * android.speech.SpeechRecognizer on purpose, so the controller code did not have to change shape:
 *  - [PlatformStt]  = the Android / Google speech service (default, exactly as before)
 *  - [OfflineStt]   = the optional on-device sherpa-onnx model (Settings > Voice and AI > Offline speech model)
 * Every method is called on the main thread; the listener is always called on the main thread.
 */
interface SttEngine {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
}

/** Thin pass-through to the Android recognizer: no behaviour of its own. */
class PlatformStt(private val recognizer: SpeechRecognizer) : SttEngine {
    override fun setRecognitionListener(listener: RecognitionListener) = recognizer.setRecognitionListener(listener)
    override fun startListening(intent: Intent) = recognizer.startListening(intent)
    override fun stopListening() = recognizer.stopListening()
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()
}
