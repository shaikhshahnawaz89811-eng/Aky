package com.codeassist.ai.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.codeassist.ai.ai.ConvKpi
import com.codeassist.ai.data.Store

/**
 * Wake-word hit-rate test (audit PDF Phase 2 exit: "wake-word FAR / FRR measured").
 *
 * The user says the wake phrase N times; each trial gets a few seconds. A trial that hears the phrase is a hit,
 * one that times out is a miss, so misses / trials = the false-reject rate for the current phrase and
 * sensitivity. It runs its own copy of the engine ([VadGatedWakeWord] in standalone mode) and tells
 * [WakeCoordinator] it is busy, so the hands-free service lets go of the microphone meanwhile.
 *
 * Results are saved after every trial (`waketest_trials`, `waketest_hits`, and the same per sensitivity level).
 * The false-accept side is not tested here: it comes from the hours of real listening in the KPI report.
 * All calls and callbacks are on the main thread.
 */
class WakeTest(
    context: Context,
    private val total: Int,
    private val ui: Ui
) {
    interface Ui {
        /** Trial [index] (1-based) starts: ask the user to say the phrase now. */
        fun onTrial(index: Int, total: Int, phrase: String)

        /** Trial [index] finished. [heard] is what the recognizer returned for a hit, "" for a miss. */
        fun onResult(index: Int, hit: Boolean, heard: String)

        fun onDone(hits: Int, total: Int)

        /** The engine cannot run (no on-device recognizer, no permission ...). The test stops. */
        fun onError(reason: String)
    }

    companion object {
        private const val LEAD_MS = 1200L
        private const val TRIAL_MS = 9000L
        private const val REST_MS = 2200L
    }

    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var engine: VadGatedWakeWord? = null
    private var index = 0
    private var hits = 0
    private var running = false
    private var settled = true

    fun start() {
        if (running) return
        running = true
        ConvKpi.init(app)
        WakeCoordinator.setBusy(this, true)
        main.postDelayed({ nextTrial() }, LEAD_MS)
    }

    /** Stop now. Trials already finished stay counted. */
    fun cancel() {
        if (!running) return
        finish(false)
    }

    private fun nextTrial() {
        if (!running) return
        if (index >= total) {
            finish(true)
            return
        }
        index += 1
        settled = false
        ui.onTrial(index, total, Store.wakePhrase)
        val e = VadGatedWakeWord(app, object : WakeWordEngine.Listener {
            override fun onWake(heard: String, remainder: String) {
                settle(true, heard)
            }

            override fun onUnavailable(reason: String) {
                if (!running) return
                stopEngine()
                running = false
                WakeCoordinator.setBusy(this@WakeTest, false)
                main.removeCallbacksAndMessages(null)
                ui.onError(reason)
            }
        }, true)
        engine = e
        e.start()
        main.postDelayed({ settle(false, "") }, TRIAL_MS)
    }

    private fun settle(hit: Boolean, heard: String) {
        if (!running || settled) return
        settled = true
        main.removeCallbacksAndMessages(null)
        stopEngine()
        val key = Store.wakeSensitivity
        ConvKpi.inc("waketest_trials")
        ConvKpi.inc("waketest_trials_$key")
        if (hit) {
            hits += 1
            ConvKpi.inc("waketest_hits")
            ConvKpi.inc("waketest_hits_$key")
        }
        ui.onResult(index, hit, heard)
        main.postDelayed({ nextTrial() }, REST_MS)
    }

    private fun stopEngine() {
        try {
            engine?.stop()
        } catch (_: Throwable) {
            // already stopped
        }
        engine = null
    }

    private fun finish(completed: Boolean) {
        val wasRunning = running
        running = false
        main.removeCallbacksAndMessages(null)
        stopEngine()
        WakeCoordinator.setBusy(this, false)
        if (wasRunning && completed) ui.onDone(hits, total)
    }
}
