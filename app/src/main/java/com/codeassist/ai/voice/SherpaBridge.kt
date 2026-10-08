package com.codeassist.ai.voice

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Talks to the sherpa-onnx Kotlin API (com.k2fsa.sherpa.onnx) through reflection, on purpose:
 * the library ships as an AAR that is NOT on Maven, so the project must still compile and run when the
 * AAR is missing. Without it [isPresent] is false, the offline option says so, and nothing else changes.
 *
 * It drives an OfflineRecognizer with a NeMo CTC model (AI4Bharat IndicConformer exports are exactly that).
 * Vosk / Kaldi models are a different format and cannot be loaded by this.
 */
object SherpaBridge {

    private const val PKG = "com.k2fsa.sherpa.onnx."

    /** True when the sherpa-onnx classes are in the APK (checked without running any native code). */
    fun isPresent(): Boolean = try {
        Class.forName(PKG + "OfflineRecognizer", false, SherpaBridge::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }

    /** The model was unloaded (idle timeout / delete) between getting the handle and using it. */
    class Released : IllegalStateException("offline model unloaded")

    class Handle internal constructor(
        private val recognizer: Any,
        private val createStream: Method,
        private val acceptWaveform: Method,
        private val decodeMethod: Method,
        private val getResult: Method,
        private val getText: Method,
        private val streamRelease: Method?,
        private val recognizerRelease: Method?
    ) {
        private var released = false

        @Synchronized
        fun decode(samples: FloatArray, sampleRate: Int): String {
            if (released) throw Released()
            var stream: Any? = null
            try {
                stream = if (createStream.parameterTypes.isEmpty()) {
                    createStream.invoke(recognizer)
                } else {
                    createStream.invoke(recognizer, "")
                }
                acceptWaveform.invoke(stream, samples, sampleRate)
                decodeMethod.invoke(recognizer, stream)
                val result = getResult.invoke(recognizer, stream)
                return ((getText.invoke(result) as? String) ?: "").trim()
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            } finally {
                if (stream != null) {
                    try {
                        streamRelease?.invoke(stream)
                    } catch (_: Throwable) {
                        // nothing to do
                    }
                }
            }
        }

        @Synchronized
        fun release() {
            if (released) return
            released = true
            try {
                recognizerRelease?.invoke(recognizer)
            } catch (_: Throwable) {
                // nothing to do
            }
        }
    }

    /** Loads the model (takes a second or two: run off the main thread). Throws with a readable message. */
    fun create(modelPath: String, tokensPath: String, threads: Int): Handle {
        try {
            val nemo = newObject("OfflineNemoEncDecCtcModelConfig")
            set(nemo, "model", modelPath)

            val modelConfig = newObject("OfflineModelConfig")
            set(modelConfig, "nemo", nemo)
            set(modelConfig, "tokens", tokensPath)
            set(modelConfig, "numThreads", threads)
            set(modelConfig, "debug", false)
            set(modelConfig, "provider", "cpu")

            val config = newObject("OfflineRecognizerConfig")
            set(config, "modelConfig", modelConfig)

            val recognizerClass = Class.forName(PKG + "OfflineRecognizer")
            val configClass = config.javaClass
            val ctor = recognizerClass.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 2 && p[1] == configClass
            } ?: throw NoSuchMethodException("OfflineRecognizer(assetManager, config)")
            val recognizer = ctor.newInstance(null, config)

            val streamClass = Class.forName(PKG + "OfflineStream")
            val createStream = recognizerClass.methods.firstOrNull {
                it.name == "createStream" && it.parameterTypes.isEmpty()
            } ?: recognizerClass.methods.firstOrNull {
                it.name == "createStream" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            } ?: throw NoSuchMethodException("OfflineRecognizer.createStream")
            val accept = streamClass.methods.firstOrNull {
                it.name == "acceptWaveform" && it.parameterTypes.size == 2
            } ?: throw NoSuchMethodException("OfflineStream.acceptWaveform")
            val decodeMethod = recognizerClass.methods.firstOrNull {
                it.name == "decode" && it.parameterTypes.size == 1 && it.parameterTypes[0] == streamClass
            } ?: throw NoSuchMethodException("OfflineRecognizer.decode")
            val getResult = recognizerClass.methods.firstOrNull {
                it.name == "getResult" && it.parameterTypes.size == 1 && it.parameterTypes[0] == streamClass
            } ?: throw NoSuchMethodException("OfflineRecognizer.getResult")
            val getText = getResult.returnType.methods.firstOrNull {
                it.name == "getText" && it.parameterTypes.isEmpty()
            } ?: throw NoSuchMethodException("OfflineRecognizerResult.getText")
            val streamRelease = streamClass.methods.firstOrNull { it.name == "release" && it.parameterTypes.isEmpty() }
            val recognizerRelease = recognizerClass.methods.firstOrNull { it.name == "release" && it.parameterTypes.isEmpty() }

            return Handle(recognizer, createStream, accept, decodeMethod, getResult, getText, streamRelease, recognizerRelease)
        } catch (t: Throwable) {
            throw IllegalStateException(describe(unwrap(t)), unwrap(t))
        }
    }

    private fun unwrap(t: Throwable): Throwable =
        if (t is InvocationTargetException) (t.targetException ?: t) else t

    private fun describe(t: Throwable): String = when (t) {
        is ClassNotFoundException, is NoClassDefFoundError ->
            "sherpa-onnx library is app mein nahi hai (app/libs mein AAR chahiye, BUILD_NOTES dekho)."
        is UnsatisfiedLinkError, is ExceptionInInitializerError ->
            "sherpa-onnx ki native library is phone par load nahi hui (" + (t.message ?: "unknown") + ")."
        is NoSuchMethodException ->
            "Ye sherpa-onnx version app ke code se match nahi karta (" + (t.message ?: "") + "). Doosra AAR version try karo."
        is OutOfMemoryError ->
            "Model load karne ko RAM kam padi. Baaki apps band karke try karo."
        else ->
            "Model load nahi hua: " + (t.message ?: t.javaClass.simpleName) +
                ". Model NeMo CTC format ka hona chahiye (model.int8.onnx + tokens.txt)."
    }

    private fun newObject(simpleName: String): Any =
        Class.forName(PKG + simpleName).getDeclaredConstructor().newInstance()

    private fun set(target: Any, property: String, value: Any) {
        val name = "set" + property.substring(0, 1).uppercase() + property.substring(1)
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 }
            ?: throw NoSuchMethodException(target.javaClass.simpleName + "." + name)
        method.invoke(target, value)
    }
}
