package com.codeassist.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi

/**
 * Android 13+ glue for the on-device recognizer's language packs: which ones are installed, and a request to
 * download one. The choice of language lives in [WakeLang]; this file only talks to Android.
 * Nothing here records or sends audio. All callbacks arrive on the main thread.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object WakePacks {
    class State(val installed: List<String>, val pending: List<String>, val supported: List<String>)

    private fun intentFor(app: Context, language: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }

    /** [done] gets the pack lists, or null when Android cannot tell (the caller then carries on as before). */
    fun query(recognizer: SpeechRecognizer, app: Context, language: String, done: (State?) -> Unit) {
        try {
            recognizer.checkRecognitionSupport(
                intentFor(app, language),
                app.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        done(
                            State(
                                installed = recognitionSupport.installedOnDeviceLanguages.toList(),
                                pending = recognitionSupport.pendingOnDeviceLanguages.toList(),
                                supported = recognitionSupport.supportedOnDeviceLanguages.toList()
                            )
                        )
                    }

                    override fun onError(error: Int) {
                        done(null)
                    }
                }
            )
        } catch (_: Throwable) {
            done(null)
        }
    }

    /** Ask Android to download the pack for [language]. True = the request was accepted (it finishes later, needs network). */
    fun download(recognizer: SpeechRecognizer, app: Context, language: String): Boolean =
        try {
            recognizer.triggerModelDownload(intentFor(app, language))
            true
        } catch (_: Throwable) {
            false
        }
}
