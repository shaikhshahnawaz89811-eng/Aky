package com.codeassist.ai.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Audit Sec 9.6 / Gap F2: plans must survive a process kill. Every plan, with the state of every node, is kept in a
 * small JSON file in app-private storage and rewritten (temp file + rename) on every state change, so the file
 * always shows what had really started when the process died. [TaskEngine.recover] reads it after a restart.
 *
 * The audit suggests Room. This app has no Room dependency and the file stays tiny (at most [MAX_PLANS] plans),
 * so a JSON file, written the same way as [ActivityLog], keeps the build free of a new library and a kapt / KSP plugin.
 */
object TaskQueue {
    private const val FILE_NAME = "task_plans.json"
    private const val MAX_PLANS = 20

    /** Finished plans are dropped after a day. Plans that still have work to do are kept until they are handled. */
    private const val KEEP_FINISHED_MS = 24 * 60 * 60 * 1000L

    private val gson = Gson()
    private var file: File? = null
    private var plans: ArrayList<TaskGraph.Plan> = ArrayList()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, FILE_NAME)
        file = f
        try {
            if (f.exists()) {
                val type = object : TypeToken<ArrayList<TaskGraph.Plan>>() {}.type
                val loaded: ArrayList<TaskGraph.Plan>? = gson.fromJson(f.readText(), type)
                if (loaded != null) plans = loaded
            }
        } catch (_: Throwable) {
            // an unreadable file must never stop the app: start with no plans (the file is overwritten on the next save)
            plans = ArrayList()
        }
    }

    /** Adds or replaces [plan] (matched by id) and writes the file. */
    @Synchronized
    fun save(plan: TaskGraph.Plan) {
        val i = plans.indexOfFirst { it.id == plan.id }
        if (i >= 0) plans[i] = plan else plans.add(plan)
        trim(System.currentTimeMillis())
        write()
    }

    @Synchronized
    fun get(id: String): TaskGraph.Plan? = plans.firstOrNull { it.id == id }

    /** Newest first. */
    @Synchronized
    fun all(): List<TaskGraph.Plan> = plans.sortedByDescending { it.createdAt }

    @Synchronized
    fun count(): Int = plans.size

    @Synchronized
    fun clear() {
        plans.clear()
        write()
    }

    private fun trim(now: Long) {
        val iter = plans.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            if (TaskGraph.isFinished(p) && now - p.createdAt > KEEP_FINISHED_MS) iter.remove()
        }
        while (plans.size > MAX_PLANS) {
            // drop the oldest finished plan first; if every plan is still open, drop the oldest one
            val finished = plans.filter { TaskGraph.isFinished(it) }.minByOrNull { it.createdAt }
            val victim = finished ?: plans.minByOrNull { it.createdAt } ?: break
            plans.remove(victim)
        }
    }

    private fun write() {
        val f = file ?: return
        try {
            val json = gson.toJson(plans)
            val tmp = File(f.parentFile, FILE_NAME + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) {
                f.writeText(json)
                tmp.delete()
            }
        } catch (_: Throwable) {
            // best effort, like the activity log: never crash a chat because the queue file could not be written
        }
    }
}
