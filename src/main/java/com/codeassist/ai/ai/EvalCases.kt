package com.codeassist.ai.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 3C, audit Sec 12.1 / Appendix A: the labelled Hinglish multi-intent set and the rule that scores one answer.
 * The set is `assets/eval_multi.json` (30 cases: independent steps, "pehle ... phir" dependencies, and cases where the right
 * answer is ONE question and no tool call). [EvalRunner] asks the brain each case and parses the answer exactly like the
 * chat does, but never runs a tool; this file only decides right or wrong.
 *
 * Pure Kotlin + Gson, JVM-tested. A case is right when:
 *  - clarify case: no tool call, and the answer is a question;
 *  - any other case: the plan passes [ToolSpecs.check], has exactly the expected tools (order does not matter), every expected
 *    argument matches by meaning (numbers as numbers, true / false, text without case), and every `after` link is exactly
 *    the expected one (a missing link and an extra link are both wrong: the link decides what is skipped when a step fails).
 */
object EvalCases {
    class Expect(val tool: String, val args: Map<String, String>, val after: List<Int>)

    class Case(
        val id: String,
        val group: String,
        val text: String,
        val clarify: Boolean,
        val expect: List<Expect>
    )

    class Verdict(val ok: Boolean, val why: String)

    /** The cases of an `eval_multi.json` text. Throws [IllegalArgumentException] with a short reason when the file is malformed. */
    fun parse(json: String): List<Case> {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("eval file JSON nahi hai")
        }
        if (!root.isJsonObject) throw IllegalArgumentException("eval file object nahi hai")
        val arr = root.asJsonObject.get("cases")
        if (arr == null || !arr.isJsonArray) throw IllegalArgumentException("eval file mein cases nahi hain")
        val out = ArrayList<Case>()
        val seen = HashSet<String>()
        for (el in arr.asJsonArray) {
            val o = el.asJsonObject
            val id = str(o, "id") ?: throw IllegalArgumentException("case bina id ke")
            if (!seen.add(id)) throw IllegalArgumentException("id dohra: $id")
            val text = str(o, "text") ?: throw IllegalArgumentException("$id: text nahi")
            val clarify = o.get("clarify")?.let { it.isJsonPrimitive && it.asBoolean } ?: false
            val expect = ArrayList<Expect>()
            val ea = o.get("expect")
            if (ea != null && ea.isJsonArray) {
                for (x in ea.asJsonArray) expect.add(expectOf(id, x.asJsonObject))
            }
            if (clarify && expect.isNotEmpty()) throw IllegalArgumentException("$id: clarify case mein expect nahi hona chahiye")
            if (!clarify && expect.isEmpty()) throw IllegalArgumentException("$id: expect khaali hai")
            out.add(Case(id, str(o, "group") ?: "multi", text, clarify, expect))
        }
        return out
    }

    private fun str(o: JsonObject, key: String): String? {
        val e = o.get(key) ?: return null
        return if (e.isJsonPrimitive) e.asString else null
    }

    private fun expectOf(id: String, o: JsonObject): Expect {
        val tool = str(o, "tool") ?: throw IllegalArgumentException("$id: tool nahi")
        if (ToolSpecs.find(tool) == null) throw IllegalArgumentException("$id: tool '$tool' maujood nahi")
        val args = LinkedHashMap<String, String>()
        val a = o.get("args")
        if (a != null && a.isJsonObject) {
            for ((k, v) in a.asJsonObject.entrySet()) args[k] = v.asString
        }
        val after = ArrayList<Int>()
        val af: JsonElement? = o.get("after")
        if (af != null && af.isJsonArray) for (x in af.asJsonArray) after.add(x.asInt)
        return Expect(tool, args, after)
    }

    // ---------- scoring ----------

    /** [tasks] are the calls the brain wrote (already numbered t1, t2 ...), [reply] its plain text. */
    fun judge(case: Case, tasks: List<PlanTask>, reply: String): Verdict {
        if (case.clarify) {
            if (tasks.isNotEmpty()) return Verdict(false, "tool call kiya, ek sawaal poochna tha")
            if (!reply.contains('?')) return Verdict(false, "sawaal nahi poocha")
            return Verdict(true, "")
        }
        if (tasks.isEmpty()) return Verdict(false, "koi tool call nahi")
        val problem = ToolSpecs.check(tasks)
        if (problem != null) return Verdict(false, "plan refused: $problem")
        if (tasks.size != case.expect.size) {
            return Verdict(false, tasks.size.toString() + " call mile, " + case.expect.size + " chahiye the")
        }

        // assign every expected call to an unused actual call with the same tool and matching arguments
        val used = BooleanArray(tasks.size)
        val matched = IntArray(case.expect.size) { -1 }
        for ((i, ex) in case.expect.withIndex()) {
            var found = -1
            for ((j, t) in tasks.withIndex()) {
                if (used[j] || t.tool != ex.tool) continue
                if (argsMatch(ex, t)) {
                    found = j
                    break
                }
            }
            if (found < 0) return Verdict(false, ex.tool + " sahi arguments ke saath nahi mila")
            used[found] = true
            matched[i] = found
        }

        // the links: expected step i waits for the steps named in its `after` (1-based, in expected order)
        for ((i, ex) in case.expect.withIndex()) {
            val want = HashSet<String>()
            for (n in ex.after) {
                if (n < 1 || n > case.expect.size) return Verdict(false, "case galat: after $n")
                want.add(tasks[matched[n - 1]].id)
            }
            val have = tasks[matched[i]].dependsOn.toSet()
            if (want != have) {
                return Verdict(
                    false,
                    ex.tool + ": `after` " + (if (want.isEmpty()) "nahi hona chahiye tha" else "chahiye tha") +
                        " (mila: " + (if (have.isEmpty()) "kuch nahi" else have.joinToString(",")) + ")"
                )
            }
        }
        return Verdict(true, "")
    }

    private fun argsMatch(ex: Expect, t: PlanTask): Boolean {
        for ((k, want) in ex.args) {
            val got = t.args[k] ?: return false
            if (!valueMatches(k, want, got)) return false
        }
        return true
    }

    private fun valueMatches(key: String, want: String, got: String): Boolean {
        val spec = ToolSpecs.all.flatMap { it.params }.firstOrNull { it.name == key }
        return when (spec?.type) {
            "integer" -> {
                val a = ToolSpecs.intArg(mapOf(key to want), key)
                val b = ToolSpecs.intArg(mapOf(key to got), key)
                a != null && a == b
            }
            "boolean" -> {
                val a = ToolSpecs.boolArg(mapOf(key to want), key)
                a != null && a == ToolSpecs.boolArg(mapOf(key to got), key)
            }
            else -> when (key) {
                "number" -> ToolSpecs.cleanNumber(want) == ToolSpecs.cleanNumber(got)
                "name" -> got.trim().lowercase().contains(want.trim().lowercase())
                else -> got.trim().equals(want.trim(), ignoreCase = true)
            }
        }
    }

    // ---------- report ----------

    class Totals(val total: Int, val ok: Int, val byGroup: Map<String, Pair<Int, Int>>) {
        val percent: Int get() = if (total == 0) 0 else Math.round(ok * 100.0 / total).toInt()
    }

    fun totals(cases: List<Case>, verdicts: List<Verdict>): Totals {
        val by = LinkedHashMap<String, Pair<Int, Int>>()
        var ok = 0
        for ((i, c) in cases.withIndex()) {
            val v = verdicts.getOrNull(i) ?: continue
            val old = by[c.group] ?: Pair(0, 0)
            by[c.group] = Pair(old.first + 1, old.second + (if (v.ok) 1 else 0))
            if (v.ok) ok++
        }
        val total = by.values.sumOf { it.first }
        return Totals(total, ok, by)
    }

    /** The Phase 3 exit rule: at least 85 % of a full set (30 cases) right. */
    const val EXIT_PERCENT = 85
    const val EXIT_MIN_CASES = 30

    fun exitReached(t: Totals): Boolean = t.total >= EXIT_MIN_CASES && t.ok * 100 >= EXIT_PERCENT * t.total
}
