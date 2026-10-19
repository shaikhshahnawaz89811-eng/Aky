package com.codeassist.ai.search

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the shipped labelled set (assets/eval_search.json) through the router, like EvalCasesTest does for the multi-intent set. */
class SearchEvalAssetTest {
    private fun assetText(): String {
        val a = File("src/main/assets/eval_search.json")
        val b = File("app/src/main/assets/eval_search.json")
        return (if (a.exists()) a else b).readText(Charsets.UTF_8)
    }

    private class Labelled(val id: String, val text: String, val prevWeb: Boolean, val forced: Boolean, val expect: List<String>?)

    private fun load(): List<Labelled> {
        val root = JsonParser.parseString(assetText()).asJsonObject
        val out = ArrayList<Labelled>()
        for (e in root.getAsJsonArray("cases")) {
            val o = e.asJsonObject
            val expectEl = o.get("expect")
            val expect = if (expectEl == null || expectEl.isJsonNull) null else expectEl.asJsonArray.map { it.asString }
            out.add(Labelled(o.get("id").asString, o.get("text").asString, o.get("prevWeb").asBoolean, o.get("forced").asBoolean, expect))
        }
        return out
    }

    @Test fun theShippedSetIsBigEnoughAndHasUniqueIds() {
        val cases = load()
        assertTrue(cases.size >= 40)
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        assertTrue(cases.count { it.expect == null } >= 10)
    }

    @Test fun everyLabelIsAKnownKind() {
        val known = SearchKind.values().map { it.name }.toSet()
        for (c in load()) for (k in c.expect.orEmpty()) assertTrue(c.id + " " + k, k in known)
    }

    @Test fun theRouterAgreesWithEveryLabel() {
        val wrong = ArrayList<String>()
        for (c in load()) {
            val got = SearchIntent.detect(c.text, c.prevWeb, c.forced)?.kinds?.map { it.name }
            if (got != c.expect) wrong.add(c.id + " '" + c.text + "' expected " + c.expect + " got " + got)
        }
        assertEquals("labels the router does not meet: " + wrong, 0, wrong.size)
    }
}
