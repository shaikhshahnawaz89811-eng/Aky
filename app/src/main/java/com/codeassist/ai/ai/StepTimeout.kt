package com.codeassist.ai.ai

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Audit Sec 9.6 (Phase 3B-2): "per-node timeout". Runs one blocking piece of work (a web request) on its own thread and
 * gives the caller back control after at most [call]'s `timeoutMs`, whatever the work does. A coroutine timeout alone
 * is not enough for a blocking socket read: it cannot interrupt it. This helper waits in short slices, so it also
 * notices a Stop request within about [POLL_MS] and can report progress while it waits.
 *
 * Pure JVM (no Android class), so the rules are unit-tested.
 */
object StepTimeout {
    sealed class Outcome<out T> {
        class Done<T>(val value: T) : Outcome<T>()

        /** The work did not finish in time. Its thread is interrupted and abandoned; a late answer is never used. */
        object TimedOut : Outcome<Nothing>()

        /** [call]'s `stopped` returned true while waiting. */
        object Stopped : Outcome<Nothing>()

        class Crashed(val error: Throwable) : Outcome<Nothing>()
    }

    const val POLL_MS = 100L

    /**
     * [onTick] gets the milliseconds waited so far, about every [pollMs]; it runs on the calling thread and must be
     * quick. The work itself ([block]) runs on a daemon thread, so an abandoned request can never keep the app alive.
     */
    fun <T> call(
        timeoutMs: Long,
        pollMs: Long = POLL_MS,
        stopped: () -> Boolean = { false },
        onTick: (Long) -> Unit = {},
        block: () -> T
    ): Outcome<T> {
        val task = FutureTask<T>(Callable { block() })
        val worker = Thread(task, "step-timeout")
        worker.isDaemon = true
        worker.start()
        val started = System.nanoTime()
        while (true) {
            val waited = (System.nanoTime() - started) / 1_000_000L
            val slice = minOf(pollMs, maxOf(1L, timeoutMs - waited))
            try {
                return Outcome.Done(task.get(slice, TimeUnit.MILLISECONDS))
            } catch (_: TimeoutException) {
                // not finished yet: look at stop and the clock below
            } catch (e: ExecutionException) {
                return Outcome.Crashed(e.cause ?: e)
            } catch (_: InterruptedException) {
                task.cancel(true)
                Thread.currentThread().interrupt()
                return Outcome.Stopped
            }
            if (stopped()) {
                task.cancel(true)
                return Outcome.Stopped
            }
            val now = (System.nanoTime() - started) / 1_000_000L
            if (now >= timeoutMs) {
                task.cancel(true)
                return Outcome.TimedOut
            }
            onTick(now)
        }
    }
}
