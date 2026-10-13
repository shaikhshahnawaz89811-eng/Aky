package com.codeassist.ai.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * T4 (test chat 13 Oct 2026, report mistake 6): "AI ko sketch banane ki skills do" (no training).
 *
 * A skill here is a TOOL plus a FORMAT, not training: the model writes one small JSON object, the app checks it and
 * draws it ([com.codeassist.ai.ui.SketchView]). Three kinds, easiest first for a small model:
 *  - "flowchart": the model gives only the labels of 2 to 8 boxes; the app lays out boxes and arrows (snake order);
 *  - "wireframe": an app screen; the model gives parts like "header:Chat", "list", "input:Message", "button:Send";
 *  - "shapes": a simple picture from rect / tri / circle / line / text, on a 100 x 100 canvas.
 *
 * Why not a normal entry in [ToolSpecs]: the tool prompt of the phone model is full (3,319 of 3,400 characters, PLAN.md) and a
 * task-engine result is text only, it cannot carry a drawing. So a sketch request has its own path in
 * [ChatRunner.runSketch]; nothing is added to the tool prompt.
 *
 * Pure Kotlin (Gson only), no Android types: the JVM tests cover everything except the drawing itself.
 */
object Sketch {
    const val SIZE = 100f
    const val MAX_SHAPES = 40
    const val MAX_ITEMS = 8
    private const val MAX_LABEL = 24
    private const val MAX_TITLE = 40

    enum class Kind(val key: String) { RECT("rect"), TRI("tri"), CIRCLE("circle"), LINE("line"), TEXT("text") }

    /**
     * One shape; the four numbers mean (canvas units 0..100, origin top-left):
     * RECT / TRI: x, y, width, height (TRI: the apex is the top middle); CIRCLE: centre x, centre y, radius, unused;
     * LINE: x1, y1, x2, y2 ([arrow] = arrow head at the second end); TEXT: centre x, centre y, unused, unused.
     */
    class Shape(
        val kind: Kind,
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val label: String = "",
        val arrow: Boolean = false
    )

    class Drawing(val title: String, val shapes: List<Shape>)

    sealed class Parsed {
        class Ok(val drawing: Drawing, val kind: String) : Parsed()
        class Bad(val reason: String) : Parsed()
    }

    // ---------- reading what the model wrote ----------

    /** The first JSON object in [raw] (fences and chatter around it are ignored), or null. */
    fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    fun parse(raw: String): Parsed {
        val json = extractJson(raw) ?: return Parsed.Bad("no JSON object in the answer")
        val obj: JsonObject = try {
            JsonParser.parseString(json).asJsonObject
        } catch (_: Throwable) {
            return Parsed.Bad("the JSON is not valid")
        }
        val title = clip(str(obj, "title").ifBlank { "Sketch" }, MAX_TITLE)
        val declared = str(obj, "kind").lowercase()
        val kind = when {
            declared == "flowchart" || declared == "wireframe" || declared == "shapes" -> declared
            obj.has("shapes") -> "shapes"
            obj.has("items") -> "flowchart"
            else -> return Parsed.Bad("kind must be flowchart, wireframe or shapes")
        }
        return when (kind) {
            "flowchart" -> {
                val items = items(obj, MAX_ITEMS)
                if (items.size < 2) Parsed.Bad("a flowchart needs 2 to $MAX_ITEMS items")
                else Parsed.Ok(flowchart(title, items), kind)
            }
            "wireframe" -> {
                val items = items(obj, MAX_ITEMS)
                if (items.isEmpty()) Parsed.Bad("a wireframe needs 1 to $MAX_ITEMS items") else Parsed.Ok(wireframe(title, items), kind)
            }
            else -> shapes(obj, title)
        }
    }

    private fun str(o: JsonObject, key: String): String {
        val e = o.get(key) ?: return ""
        return if (e.isJsonPrimitive) e.asString.trim() else ""
    }

    private fun items(o: JsonObject, max: Int): List<String> {
        val arr: JsonArray = if (o.get("items")?.isJsonArray == true) o.getAsJsonArray("items") else return emptyList()
        val out = ArrayList<String>()
        for (e in arr) {
            if (!e.isJsonPrimitive) continue
            val s = clip(e.asString.trim().replace(Regex("\\s+"), " "), MAX_LABEL)
            if (s.isNotEmpty()) out.add(s)
            if (out.size >= max) break
        }
        return out
    }

    private fun clip(s: String, max: Int): String = if (s.length > max) s.take(max - 1).trimEnd() + "\u2026" else s

    private fun num(o: JsonObject, key: String): Float? {
        val e = o.get(key) ?: return null
        if (!e.isJsonPrimitive) return null
        val f = try {
            e.asFloat
        } catch (_: Throwable) {
            return null
        }
        return if (f.isNaN() || f.isInfinite()) null else f
    }

    private fun coord(o: JsonObject, key: String): Any {
        val f = num(o, key) ?: return "missing '$key'"
        if (f < -5f || f > SIZE + 5f) return "'$key' must be between 0 and 100"
        return f.coerceIn(0f, SIZE)
    }

    private fun shapes(obj: JsonObject, title: String): Parsed {
        val arr = if (obj.get("shapes")?.isJsonArray == true) obj.getAsJsonArray("shapes") else return Parsed.Bad("shapes must be a list")
        if (arr.size() == 0) return Parsed.Bad("shapes is empty")
        if (arr.size() > MAX_SHAPES) return Parsed.Bad("at most $MAX_SHAPES shapes")
        val out = ArrayList<Shape>()
        for (e in arr) {
            if (!e.isJsonObject) return Parsed.Bad("every shape must be an object")
            val s = shapeFromJson(e.asJsonObject)
            when (s) {
                is String -> return Parsed.Bad(s)
                is Shape -> out.add(s)
            }
        }
        return Parsed.Ok(Drawing(title, out), "shapes")
    }

    /** A [Shape], or a String with the reason it is refused. */
    private fun shapeFromJson(o: JsonObject): Any {
        val t = str(o, "t").lowercase().ifEmpty { str(o, "type").lowercase() }
        val label = clip(str(o, "l").ifEmpty { str(o, "label") }, MAX_LABEL)
        val arrow = o.get("arrow")?.let { it.isJsonPrimitive && it.asString.equals("true", ignoreCase = true) } ?: false
        val kind = Kind.values().firstOrNull { it.key == t } ?: return "unknown shape type '$t'"
        val x = coord(o, "x")
        if (x is String) return x
        val y = coord(o, "y")
        if (y is String) return y
        val xf = x as Float
        val yf = y as Float
        return when (kind) {
            Kind.RECT, Kind.TRI -> {
                val w = num(o, "w") ?: return "missing 'w'"
                val h = num(o, "h") ?: return "missing 'h'"
                if (w <= 0f || h <= 0f || w > SIZE || h > SIZE) "w and h must be between 1 and 100"
                else Shape(kind, xf, yf, w, h, label)
            }
            Kind.CIRCLE -> {
                val r = num(o, "r") ?: return "missing 'r'"
                if (r < 1f || r > SIZE / 2) "r must be between 1 and 50" else Shape(kind, xf, yf, r, 0f, label)
            }
            Kind.LINE -> {
                val x2 = coord(o, "x2")
                if (x2 is String) return x2
                val y2 = coord(o, "y2")
                if (y2 is String) return y2
                Shape(kind, xf, yf, x2 as Float, y2 as Float, label, arrow)
            }
            Kind.TEXT -> if (label.isEmpty()) "a text shape needs 'l'" else Shape(kind, xf, yf, 0f, 0f, label)
        }
    }

    // ---------- templates ----------

    private fun titleShape(t: String): Shape = Shape(Kind.TEXT, 50f, 6f, 0f, 0f, t)

    /**
     * Boxes in snake order, at most 3 per row: row 1 left to right, row 2 right to left, so the arrow between two rows is
     * a short vertical line. 2 to 8 [items].
     */
    fun flowchart(title: String, items: List<String>): Drawing {
        val n = items.size
        val cols = minOf(n, 3)
        val rows = (n + cols - 1) / cols
        val gap = 8f
        val boxW = (90f - (cols - 1) * gap) / cols
        val boxH = 14f
        val rowGap = if (rows > 1) minOf(18f, (80f - rows * boxH) / (rows - 1)) else 0f
        val out = ArrayList<Shape>()
        out.add(titleShape(title))
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val row = i / cols
            // snake: odd rows run right to left, so the box that starts a row sits under the box that ended the row above
            val col = if (row % 2 == 1) cols - 1 - (i % cols) else i % cols
            xs[i] = 5f + col * (boxW + gap)
            ys[i] = 16f + row * (boxH + rowGap)
            out.add(Shape(Kind.RECT, xs[i], ys[i], boxW, boxH, items[i]))
        }
        for (i in 0 until n - 1) {
            val sameRow = i / cols == (i + 1) / cols
            if (sameRow) {
                val right = xs[i + 1] > xs[i]
                val fromX = if (right) xs[i] + boxW else xs[i]
                val toX = if (right) xs[i + 1] else xs[i + 1] + boxW
                out.add(Shape(Kind.LINE, fromX, ys[i] + boxH / 2, toX, ys[i + 1] + boxH / 2, "", true))
            } else {
                val cx = xs[i] + boxW / 2
                out.add(Shape(Kind.LINE, cx, ys[i] + boxH, xs[i + 1] + boxW / 2, ys[i + 1], "", true))
            }
        }
        return Drawing(title, out)
    }

    private class Part(val type: String, val label: String)

    private fun partOf(item: String): Part {
        val i = item.indexOf(':')
        if (i > 0) {
            val type = item.substring(0, i).trim().lowercase()
            if (type in setOf("header", "list", "input", "button", "text", "image")) return Part(type, item.substring(i + 1).trim())
        }
        val whole = item.trim().lowercase()
        if (whole in setOf("header", "list", "input", "button", "image")) return Part(whole, "")
        return Part("text", item.trim())
    }

    private fun partHeight(type: String): Float = when (type) {
        "header" -> 10f
        "list" -> 26f
        "input" -> 9f
        "button" -> 9f
        "image" -> 20f
        else -> 8f
    }

    private fun partLabel(p: Part): String = when {
        p.label.isNotEmpty() -> p.label
        p.type == "header" -> "Header"
        p.type == "list" -> "List"
        p.type == "input" -> "Input"
        p.type == "button" -> "Button"
        p.type == "image" -> "Image"
        else -> ""
    }

    /** An app screen: a phone frame with the parts stacked top to bottom (scaled down when they do not fit). */
    fun wireframe(title: String, items: List<String>): Drawing {
        val parts = items.map { partOf(it) }
        val gap = 2f
        val total = parts.sumOf { partHeight(it.type).toDouble() }.toFloat() + gap * (parts.size - 1)
        val avail = 78f
        val scale = if (total > avail) avail / total else 1f
        val out = ArrayList<Shape>()
        out.add(titleShape(title))
        out.add(Shape(Kind.RECT, 25f, 12f, 50f, 85f, ""))
        var y = 15f
        for (p in parts) {
            val h = partHeight(p.type) * scale
            out.add(Shape(Kind.RECT, 28f, y, 44f, h, partLabel(p)))
            y += h + gap * scale
        }
        return Drawing(title, out)
    }

    // ---------- storing and reading back ----------

    /** The drawing as the JSON kept in the message ([com.codeassist.ai.data.Message.sketch]). Always "kind":"shapes". */
    fun toJson(d: Drawing): String {
        val root = JsonObject()
        root.addProperty("kind", "shapes")
        root.addProperty("title", d.title)
        val arr = JsonArray()
        for (s in d.shapes) {
            val o = JsonObject()
            o.addProperty("t", s.kind.key)
            o.addProperty("x", s.a)
            o.addProperty("y", s.b)
            when (s.kind) {
                Kind.RECT, Kind.TRI -> {
                    o.addProperty("w", s.c)
                    o.addProperty("h", s.d)
                }
                Kind.CIRCLE -> o.addProperty("r", s.c)
                Kind.LINE -> {
                    o.addProperty("x2", s.c)
                    o.addProperty("y2", s.d)
                    if (s.arrow) o.addProperty("arrow", true)
                }
                Kind.TEXT -> Unit
            }
            if (s.label.isNotEmpty()) o.addProperty("l", s.label)
            arr.add(o)
        }
        root.add("shapes", arr)
        return root.toString()
    }

    /** The stored JSON back as a drawing; null when it is not a valid sketch (the chat then just shows the text). */
    fun fromJson(json: String?): Drawing? {
        if (json.isNullOrBlank()) return null
        val p = parse(json)
        return if (p is Parsed.Ok) p.drawing else null
    }

    // ---------- what the model is asked ----------

    fun systemPrompt(lang: String): String {
        val labels = if (lang == LocalMemory.LANG_HINGLISH) "Write the labels in simple Roman Hinglish." else "Write the labels in English."
        return "You turn a drawing request into ONE JSON object and output nothing else (no explanation, no code fence). " +
            "Pick the kind that fits. " +
            "flowchart (steps or a process; 2 to 8 short items in order): " +
            "{\"kind\":\"flowchart\",\"title\":\"Login flow\",\"items\":[\"Start\",\"Enter password\",\"Check\",\"Home\"]} " +
            "wireframe (an app screen; item types: header, list, input, button, text, image): " +
            "{\"kind\":\"wireframe\",\"title\":\"Chat screen\",\"items\":[\"header:Chat\",\"list\",\"input:Type here\",\"button:Send\"]} " +
            "shapes (a simple picture; canvas is 100 by 100, (0,0) is top-left; rect and tri: x,y is the top-left corner; " +
            "circle: x,y is the centre): " +
            "{\"kind\":\"shapes\",\"title\":\"House\",\"shapes\":[{\"t\":\"rect\",\"x\":25,\"y\":50,\"w\":50,\"h\":35,\"l\":\"wall\"}," +
            "{\"t\":\"tri\",\"x\":20,\"y\":25,\"w\":60,\"h\":25},{\"t\":\"circle\",\"x\":82,\"y\":15,\"r\":8},{\"t\":\"line\"," +
            "\"x\":10,\"y\":88,\"x2\":90,\"y2\":88},{\"t\":\"text\",\"x\":50,\"y\":96,\"l\":\"House\"}]} " + labels
    }

    fun userPrompt(request: String): String = "Request: " + request.trim().take(300)

    fun retryNote(reason: String): String =
        "\n\n(Your last answer was rejected: $reason. Answer again with ONE valid JSON object only.)"

    fun kindLabel(kind: String): String = when (kind) {
        "flowchart" -> "flowchart"
        "wireframe" -> "screen wireframe"
        else -> "chitra"
    }
}

/**
 * Which messages are about drawing. A direct order ("ek sketch banao: ghar", "login ka flowchart banao") starts a drawing;
 * talk ABOUT the feature ("AI ko sketch banane ki skills deni hain") gets one short question instead, because it is not clear
 * what the user wants drawn (the old reply listed 5 training steps).
 */
object SketchIntent {
    /** Caption of the clarifying reply; the next message is read against it ([clarify]). */
    const val CLARIFY_NOTE = "Sketch sawaal"

    private val splitter = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private fun words(s: String): List<String> = s.lowercase().split(splitter).filter { it.isNotEmpty() }

    private val drawWords = setOf("sketch", "drawing", "draw", "diagram", "flowchart", "wireframe", "mockup", "naksha", "nakshe", "chitra")
    private val makeWords = setOf("banao", "bana", "banado", "bnao", "dikhao", "dikha", "nikalo", "draw", "create", "make", "show", "do", "de")
    private val featureWords = setOf(
        "skill", "skills", "feature", "features", "tool", "tools", "train", "training", "seekh", "seekhna", "sikhana", "sikha",
        "banane", "banana", "ability", "hunar", "kaise", "kyun", "kya"
    )
    private val trainWords = setOf("train", "training", "skills", "skill", "hunar")

    /** A direct order to draw something: short, no question, no talk about the feature. */
    fun isRequest(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 200 || t.endsWith("?") || t.contains("```")) return false
        val ws = words(t)
        if (ws.size < 2 || ws.size > 16) return false
        if (ws.none { it in drawWords }) return false
        if (ws.any { it in featureWords }) return false
        return ws.any { it in makeWords }
    }

    /** Talk about giving the AI a drawing skill: a sketch word together with a skill / feature word. */
    fun isFeatureTalk(text: String): Boolean {
        val ws = words(text)
        if (ws.size < 3 || ws.size > 40) return false
        if (ws.none { it in drawWords }) return false
        return ws.any { it in setOf("skill", "skills", "feature", "features", "banane", "ability", "hunar", "tool") }
    }

    const val CLARIFY_REPLY =
        "Sketch se aapka matlab kya hai: drawing, flowchart / diagram, app screen ka wireframe, ya code ka khaka? " +
            "Is app me ye bina training ke chalta hai: likho \"sketch banao: ghar\" ya \"login ka flowchart banao\" " +
            "(model ek chhota JSON likhta hai, app usse chitra banati hai). Batao, pehle kaun sa chahiye?"

    const val NO_TRAINING_REPLY =
        "Theek hai, koi training nahi. Skill = tool + format: model sirf ek chhota JSON likhta hai " +
            "(flowchart, wireframe ya simple shapes), app use check karke draw karti hai. " +
            "Abhi try karo: \"sketch banao: ghar\" ya \"chat screen ka wireframe banao\"."

    /** The fixed answer for a message about the feature (null = not about it). [lastNote] is the caption of the last AI reply. */
    fun clarify(text: String, lastNote: String?): String? {
        val ws = words(text)
        if (lastNote == CLARIFY_NOTE && ws.any { it in trainWords } && ws.size <= 30) return NO_TRAINING_REPLY
        if (isFeatureTalk(text)) return CLARIFY_REPLY
        return null
    }
}
