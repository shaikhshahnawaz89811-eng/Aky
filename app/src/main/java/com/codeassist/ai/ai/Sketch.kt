package com.codeassist.ai.ai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

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
 * Sketch Studio, stage 1 (14 Oct 2026): the "shapes" kind is extended, old documents still read exactly as before:
 *  - new shapes: "path" (SVG path data, see [SvgPath]), "poly" (polygon / polyline), "ellipse";
 *  - optional per-shape style: "fill", "stroke" (#RGB / #RRGGBB / a few names / "none"), "sw" (line width), "op" (opacity);
 *  - optional "bg" background colour of the whole drawing;
 *  - "v":2 raises the shape limit from 40 to 120 (documents without "v" keep the old limit);
 *  - revisions: [revisePrompt] hands the model the current JSON plus one change request.
 *
 * Sketch Studio, stage 3 (14 Oct 2026), all optional and backward compatible:
 *  - arcs in paths (A / a, see [SvgPath]);
 *  - gradients: `"grad":"#RRGGBB #RRGGBB 90"` (linear, angle in degrees, 0 = left to right, 90 = top to bottom) or
 *    `"grad":"#RRGGBB #RRGGBB radial"` as the fill of a shape, and `"bggrad"` for the whole background;
 *  - transforms per shape: `"dx"`, `"dy"` (move), `"rot"` (turn, degrees) and `"sc"` (scale), all around the centre of the shape;
 *  - groups (layers, one level): `{"t":"group","l":"sky","shapes":[...]}`; a group takes the same transform keys and `"op"`;
 *  - `"hide":true` hides a shape or a group (it stays in the file).
 * The chat prompt ([systemPrompt]) still does not offer these (small context); the studio prompt does ([studioSystemPrompt]).
 * NOT supported: real 3D (see [HtmlSandbox] for web pages / Three.js, which are a different kind of design, not part of this format).
 *
 * Pure Kotlin (Gson only), no Android types: the JVM tests cover everything except the drawing itself.
 */
object Sketch {
    const val SIZE = 100f
    /** Limit for documents without "v":2 (every sketch saved before the Studio upgrade). */
    const val MAX_SHAPES = 40
    const val MAX_SHAPES_V2 = 120
    const val VERSION = 2
    /** All path segments and polygon points of one drawing together. */
    const val MAX_POINTS = 800
    const val POLY_MAX_POINTS = 60
    /** Longest JSON the app reads (a model that runs on is cut off long before this). */
    const val MAX_JSON = 30_000
    /** A revision sends the current JSON back to the model; a bigger drawing cannot be edited by the phone model. */
    const val MAX_REVISE_JSON = 3_500
    const val MAX_ITEMS = 8
    /** Shapes inside ONE group; the limit for the whole drawing (children included) is [MAX_SHAPES_V2]. */
    const val MAX_GROUP_CHILDREN = 60
    private const val MAX_LABEL = 24
    private const val MAX_TITLE = 40

    enum class Kind(val key: String) {
        RECT("rect"), TRI("tri"), CIRCLE("circle"), LINE("line"), TEXT("text"),
        PATH("path"), POLY("poly"), ELLIPSE("ellipse"), GROUP("group")
    }

    /**
     * A two-colour gradient. [angle] is in degrees for a linear one: 0 = left to right, 90 = top to bottom (screen coordinates).
     * The colours are opaque ARGB ints.
     */
    class Gradient(val radial: Boolean, val from: Int, val to: Int, val angle: Float) {
        /** x0, y0, x1, y1 of the gradient line over [b] (minX, minY, maxX, maxY): through the centre, long enough to span the box. */
        fun line(b: FloatArray): FloatArray {
            val cx = (b[0] + b[2]) / 2f
            val cy = (b[1] + b[3]) / 2f
            val w = b[2] - b[0]
            val h = b[3] - b[1]
            val rad = Math.toRadians(angle.toDouble())
            val dx = Math.cos(rad).toFloat()
            val dy = Math.sin(rad).toFloat()
            val half = (Math.abs(dx) * w + Math.abs(dy) * h) / 2f
            return floatArrayOf(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half)
        }

        /** Radius of the radial form over [b]: half of the longer side (never below 0.1). */
        fun radius(b: FloatArray): Float = Math.max(0.1f, Math.max(b[2] - b[0], b[3] - b[1]) / 2f)

        /** The JSON / prompt text: "#RRGGBB #RRGGBB 90" or "#RRGGBB #RRGGBB radial". */
        fun text(): String = Sketch.colorText(from) + " " + Sketch.colorText(to) + " " + (if (radial) "radial" else SvgPath.fmt(angle))
    }

    /** Move ([dx], [dy] canvas units), turn ([rot] degrees, clockwise on screen) and scale ([scale]) around the shape's centre. */
    class Xform(val dx: Float, val dy: Float, val rot: Float, val scale: Float) {
        fun isIdentity(): Boolean = dx == 0f && dy == 0f && rot == 0f && scale == 1f
    }

    /** Optional look of one shape. Colours are ARGB ints; fill 0 means "none". A null field means "use the default". */
    class Style(val fill: Int?, val stroke: Int?, val strokeWidth: Float?, val opacity: Float?, val grad: Gradient? = null) {
        fun isEmpty(): Boolean = fill == null && stroke == null && strokeWidth == null && opacity == null && grad == null
    }

    /**
     * One shape; the four numbers mean (canvas units 0..100, origin top-left):
     * RECT / TRI: x, y, width, height (TRI: the apex is the top middle); CIRCLE: centre x, centre y, radius, unused;
     * LINE: x1, y1, x2, y2 ([arrow] = arrow head at the second end); TEXT: centre x, centre y, unused, unused;
     * ELLIPSE: centre x, centre y, radius x, radius y. PATH uses [path], POLY uses [points] (x0,y0,x1,y1,...) and [closed];
     * for those two the four numbers are unused.
     */
    class Shape(
        val kind: Kind,
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val label: String = "",
        val arrow: Boolean = false,
        val path: List<SvgPath.Seg>? = null,
        val points: FloatArray? = null,
        val closed: Boolean = true,
        val style: Style? = null,
        /** GROUP only: the shapes of the layer, drawn in order (no group inside a group). */
        val children: List<Shape>? = null,
        val xform: Xform? = null,
        /** True = not drawn and not exported, but kept in the file. */
        val hidden: Boolean = false
    ) {
        /** The same shape with [xform] and [hidden] set (used by the reader after the shape itself was read). */
        fun withExtras(xform: Xform?, hidden: Boolean): Shape =
            Shape(kind, a, b, c, d, label, arrow, path, points, closed, style, children, xform, hidden)

        /** The same shape with [hidden] changed (layer panel). */
        fun withHidden(hidden: Boolean): Shape = withExtras(xform, hidden)

        /** The same shape with a new name / label. */
        fun withLabel(label: String): Shape = Shape(kind, a, b, c, d, label, arrow, path, points, closed, style, children, xform, hidden)
    }

    /** [background] is an ARGB colour, or null for the app's default card colour. */
    class Drawing(val title: String, val shapes: List<Shape>, val background: Int? = null, val bgGrad: Gradient? = null)

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
        if (json.length > MAX_JSON) return Parsed.Bad("the answer is too long (more than $MAX_JSON characters)")
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
        return numOf(e)
    }

    private fun numOf(e: JsonElement): Float? {
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
        val version = num(obj, "v")?.toInt() ?: 1
        if (version < 2 && arr.size() > MAX_SHAPES) {
            return Parsed.Bad("at most $MAX_SHAPES shapes (add \"v\":2 for up to $MAX_SHAPES_V2)")
        }
        if (arr.size() > MAX_SHAPES_V2) return Parsed.Bad("at most $MAX_SHAPES_V2 shapes")
        val bgRaw = str(obj, "bg")
        var background: Int? = null
        if (bgRaw.isNotEmpty()) {
            background = parseColor(bgRaw) ?: return Parsed.Bad("bad color '$bgRaw' for bg (use #RRGGBB)")
        }
        val bgGradRaw = str(obj, "bggrad")
        var bgGrad: Gradient? = null
        if (bgGradRaw.isNotEmpty()) {
            bgGrad = parseGradient(bgGradRaw) ?: return Parsed.Bad("bad gradient '$bgGradRaw' for bggrad (use \"#RRGGBB #RRGGBB 90\")")
        }
        val out = ArrayList<Shape>()
        var points = 0
        var total = 0
        for (e in arr) {
            if (!e.isJsonObject) return Parsed.Bad("every shape must be an object")
            val s = shapeFromJson(e.asJsonObject)
            when (s) {
                is String -> return Parsed.Bad(s)
                is Shape -> {
                    points += pointsOf(s)
                    if (points > MAX_POINTS) return Parsed.Bad("the drawing is too complex (more than $MAX_POINTS path points)")
                    total += 1 + (s.children?.size ?: 0)
                    if (version < 2 && total > MAX_SHAPES) return Parsed.Bad("at most $MAX_SHAPES shapes (add \"v\":2 for up to $MAX_SHAPES_V2)")
                    if (total > MAX_SHAPES_V2) return Parsed.Bad("at most $MAX_SHAPES_V2 shapes (groups count their shapes too)")
                    out.add(s)
                }
            }
        }
        return Parsed.Ok(Drawing(title, out, background, bgGrad), "shapes")
    }

    // ---------- colours and styles ----------

    private val NAMED_COLORS: Map<String, Int> = mapOf(
        "white" to 0xFFFFFFFF.toInt(), "black" to 0xFF000000.toInt(), "red" to 0xFFE53935.toInt(),
        "green" to 0xFF43A047.toInt(), "blue" to 0xFF1E88E5.toInt(), "yellow" to 0xFFFDD835.toInt(),
        "orange" to 0xFFFB8C00.toInt(), "gray" to 0xFF9E9E9E.toInt(), "grey" to 0xFF9E9E9E.toInt(),
        "cyan" to 0xFF00E5FF.toInt(), "purple" to 0xFF8E24AA.toInt(), "pink" to 0xFFEC407A.toInt(),
        "brown" to 0xFF6D4C41.toInt()
    )

    /** #RGB, #RRGGBB, a few colour names, or none / transparent (= 0). Null when it is none of these. */
    fun parseColor(raw: String): Int? {
        val t = raw.trim().lowercase()
        if (t == "none" || t == "transparent") return 0
        val named = NAMED_COLORS[t]
        if (named != null) return named
        if (!t.startsWith("#")) return null
        val hex = t.substring(1)
        val full = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            else -> return null
        }
        if (!full.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return 0xFF000000.toInt() or full.toInt(16)
    }

    /** The colour as the text kept in the JSON / SVG: "none" for 0, otherwise #RRGGBB (alpha is not kept). */
    fun colorText(argb: Int): String =
        if (argb == 0) "none" else String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    /** A [Style] (possibly empty) or a String with the reason it is refused. */
    private fun styleFrom(o: JsonObject): Any {
        val fillRaw = str(o, "fill")
        val strokeRaw = str(o, "stroke")
        var fill: Int? = null
        var stroke: Int? = null
        if (fillRaw.isNotEmpty()) fill = parseColor(fillRaw) ?: return "bad color '$fillRaw' for fill (use #RRGGBB)"
        if (strokeRaw.isNotEmpty()) stroke = parseColor(strokeRaw) ?: return "bad color '$strokeRaw' for stroke (use #RRGGBB)"
        var sw: Float? = null
        if (o.has("sw")) {
            sw = num(o, "sw") ?: return "sw must be a number"
            if (sw < 0.1f || sw > 6f) return "sw must be between 0.1 and 6"
        }
        var op: Float? = null
        if (o.has("op")) {
            op = num(o, "op") ?: return "op must be a number"
            if (op < 0f || op > 1f) return "op must be between 0 and 1"
        }
        val gradRaw = str(o, "grad")
        var grad: Gradient? = null
        if (gradRaw.isNotEmpty()) {
            grad = parseGradient(gradRaw) ?: return "bad gradient '$gradRaw' (use \"#RRGGBB #RRGGBB 90\" or \"#RRGGBB #RRGGBB radial\")"
        }
        return Style(fill, stroke, sw, op, grad)
    }

    /**
     * "#RRGGBB #RRGGBB", then optionally an angle in degrees (-360..360) or the word radial. Separators: spaces, commas or ">".
     * Null when it is not that. Colours must be real colours (no "none").
     */
    fun parseGradient(raw: String): Gradient? {
        val toks = raw.trim().split(Regex("[\\s,>]+")).filter { it.isNotEmpty() }
        if (toks.size < 2 || toks.size > 3) return null
        val c1 = parseColor(toks[0]) ?: return null
        val c2 = parseColor(toks[1]) ?: return null
        if (c1 == 0 || c2 == 0) return null
        if (toks.size == 2) return Gradient(false, c1, c2, 90f)
        val third = toks[2].lowercase()
        if (third == "radial") return Gradient(true, c1, c2, 0f)
        val angle = third.toFloatOrNull() ?: return null
        if (angle.isNaN() || angle.isInfinite() || angle < -360f || angle > 360f) return null
        return Gradient(false, c1, c2, angle)
    }

    /** Transform keys of one shape: null when none is written, a String with the reason when one is wrong. */
    private fun xformFrom(o: JsonObject): Any? {
        if (!o.has("dx") && !o.has("dy") && !o.has("rot") && !o.has("sc")) return null
        val dx = if (o.has("dx")) (num(o, "dx") ?: return "dx must be a number") else 0f
        val dy = if (o.has("dy")) (num(o, "dy") ?: return "dy must be a number") else 0f
        val rot = if (o.has("rot")) (num(o, "rot") ?: return "rot must be a number") else 0f
        val sc = if (o.has("sc")) (num(o, "sc") ?: return "sc must be a number") else 1f
        if (dx < -100f || dx > 100f || dy < -100f || dy > 100f) return "dx and dy must be between -100 and 100"
        if (rot < -360f || rot > 360f) return "rot must be between -360 and 360 (degrees)"
        if (sc < 0.1f || sc > 5f) return "sc must be between 0.1 and 5"
        val x = Xform(dx, dy, rot, sc)
        return if (x.isIdentity()) null else x
    }

    /** Path segments plus polygon points of [s] and of its children (the budget [MAX_POINTS] counts them all). */
    fun pointsOf(s: Shape): Int {
        var n = (s.path?.size ?: 0) + (s.points?.size ?: 0) / 2
        s.children?.forEach { n += pointsOf(it) }
        return n
    }

    /** Shapes of a drawing, a group's shapes included (the group itself counts as one). */
    fun shapeCount(d: Drawing): Int = d.shapes.sumOf { 1 + (it.children?.size ?: 0) }

    /** minX, minY, maxX, maxY of [s] in canvas units (the pivot of its transform and the box of its gradient). */
    fun bounds(s: Shape): FloatArray {
        return when (s.kind) {
            Kind.RECT, Kind.TRI -> floatArrayOf(s.a, s.b, s.a + s.c, s.b + s.d)
            Kind.CIRCLE -> floatArrayOf(s.a - s.c, s.b - s.c, s.a + s.c, s.b + s.c)
            Kind.ELLIPSE -> floatArrayOf(s.a - s.c, s.b - s.d, s.a + s.c, s.b + s.d)
            Kind.LINE -> floatArrayOf(Math.min(s.a, s.c), Math.min(s.b, s.d), Math.max(s.a, s.c), Math.max(s.b, s.d))
            Kind.TEXT -> floatArrayOf(s.a, s.b, s.a, s.b)
            Kind.PATH -> {
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                for (seg in s.path ?: emptyList()) {
                    var i = 0
                    while (i + 1 < seg.v.size) {
                        minX = Math.min(minX, seg.v[i])
                        maxX = Math.max(maxX, seg.v[i])
                        minY = Math.min(minY, seg.v[i + 1])
                        maxY = Math.max(maxY, seg.v[i + 1])
                        i += 2
                    }
                }
                if (minX > maxX) floatArrayOf(0f, 0f, SIZE, SIZE) else floatArrayOf(minX, minY, maxX, maxY)
            }
            Kind.POLY -> {
                val p = s.points ?: FloatArray(0)
                if (p.size < 2) floatArrayOf(0f, 0f, SIZE, SIZE) else {
                    var minX = p[0]
                    var maxX = p[0]
                    var minY = p[1]
                    var maxY = p[1]
                    var i = 2
                    while (i + 1 < p.size) {
                        minX = Math.min(minX, p[i])
                        maxX = Math.max(maxX, p[i])
                        minY = Math.min(minY, p[i + 1])
                        maxY = Math.max(maxY, p[i + 1])
                        i += 2
                    }
                    floatArrayOf(minX, minY, maxX, maxY)
                }
            }
            Kind.GROUP -> {
                val kids = (s.children ?: emptyList()).filter { !it.hidden }
                if (kids.isEmpty()) floatArrayOf(0f, 0f, SIZE, SIZE) else {
                    val first = bounds(kids[0])
                    var minX = first[0]
                    var minY = first[1]
                    var maxX = first[2]
                    var maxY = first[3]
                    for (k in kids.drop(1)) {
                        val b = bounds(k)
                        minX = Math.min(minX, b[0])
                        minY = Math.min(minY, b[1])
                        maxX = Math.max(maxX, b[2])
                        maxY = Math.max(maxY, b[3])
                    }
                    floatArrayOf(minX, minY, maxX, maxY)
                }
            }
        }
    }

    /** Centre of [bounds] (x, y): the pivot of a turn or a scale. */
    fun pivot(s: Shape): FloatArray {
        val b = bounds(s)
        return floatArrayOf((b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f)
    }

    /** Polygon points from [[x,y],...], a flat [x,y,x,y,...] list or the text "x,y x,y". A FloatArray or a reason String. */
    private fun polyPoints(o: JsonObject): Any {
        val e = o.get("pts") ?: o.get("points") ?: return "a poly needs 'pts'"
        val nums = ArrayList<Float>()
        val tooMany = "a poly has at most $POLY_MAX_POINTS points"
        if (e.isJsonArray) {
            for (item in e.asJsonArray) {
                if (item.isJsonArray) {
                    val pair = item.asJsonArray
                    if (pair.size() != 2) return "every poly point must be [x,y]"
                    for (v in pair) nums.add(numOf(v) ?: return "poly points must be numbers")
                } else {
                    nums.add(numOf(item) ?: return "poly points must be numbers")
                }
                if (nums.size > POLY_MAX_POINTS * 2) return tooMany
            }
        } else if (e.isJsonPrimitive) {
            for (tok in e.asString.trim().split(Regex("[\\s,]+"))) {
                if (tok.isEmpty()) continue
                val f = tok.toFloatOrNull()
                if (f == null || f.isNaN() || f.isInfinite()) return "poly points must be numbers"
                nums.add(f)
                if (nums.size > POLY_MAX_POINTS * 2) return tooMany
            }
        } else {
            return "pts must be a list"
        }
        if (nums.size % 2 != 0) return "poly pts need pairs of x and y"
        val out = FloatArray(nums.size)
        for (i in nums.indices) {
            val v = nums[i]
            if (v < -5f || v > SIZE + 5f) return "poly points must be between 0 and 100"
            out[i] = v.coerceIn(0f, SIZE)
        }
        return out
    }

    private fun isTrue(o: JsonObject, key: String): Boolean =
        o.get(key)?.let { it.isJsonPrimitive && it.asString.equals("true", ignoreCase = true) } ?: false

    /** A [Shape] (with transform, hide flag and, for a group, its shapes), or a String with the reason it is refused. */
    private fun shapeFromJson(o: JsonObject, inGroup: Boolean = false): Any {
        val xo = xformFrom(o)
        if (xo is String) return xo
        val xf: Xform? = xo as Xform?
        val hide = isTrue(o, "hide")
        val t = str(o, "t").lowercase().ifEmpty { str(o, "type").lowercase() }
        val base: Any = if (t == Kind.GROUP.key) groupFromJson(o, inGroup) else baseShape(o)
        if (base is String) return base
        val sh = base as Shape
        return if (xf == null && !hide) sh else sh.withExtras(xf, hide)
    }

    private fun groupFromJson(o: JsonObject, inGroup: Boolean): Any {
        if (inGroup) return "a group cannot hold another group"
        val arr = if (o.get("shapes")?.isJsonArray == true) o.getAsJsonArray("shapes") else return "a group needs 'shapes' (1 to $MAX_GROUP_CHILDREN)"
        if (arr.size() == 0 || arr.size() > MAX_GROUP_CHILDREN) return "a group needs 1 to $MAX_GROUP_CHILDREN shapes"
        val styleOr = styleFrom(o)
        if (styleOr is String) return styleOr
        val st: Style? = (styleOr as Style).takeUnless { it.isEmpty() }
        val kids = ArrayList<Shape>()
        for (e in arr) {
            if (!e.isJsonObject) return "every shape must be an object"
            val k = shapeFromJson(e.asJsonObject, true)
            when (k) {
                is String -> return k
                is Shape -> kids.add(k)
            }
        }
        val name = clip(str(o, "l").ifEmpty { str(o, "label") }.ifEmpty { str(o, "name") }, MAX_LABEL)
        return Shape(Kind.GROUP, 0f, 0f, 0f, 0f, name, style = st, children = kids)
    }

    /** One non-group shape, or a String with the reason it is refused. */
    private fun baseShape(o: JsonObject): Any {
        val t = str(o, "t").lowercase().ifEmpty { str(o, "type").lowercase() }
        val label = clip(str(o, "l").ifEmpty { str(o, "label") }, MAX_LABEL)
        val arrow = o.get("arrow")?.let { it.isJsonPrimitive && it.asString.equals("true", ignoreCase = true) } ?: false
        val kind = Kind.values().firstOrNull { it.key == t } ?: return "unknown shape type '$t'"
        val styleOr = styleFrom(o)
        if (styleOr is String) return styleOr
        val st: Style? = (styleOr as Style).takeUnless { it.isEmpty() }
        if (kind == Kind.PATH) {
            val d = str(o, "d")
            if (d.isEmpty()) return "a path shape needs 'd'"
            val p = SvgPath.parse(d)
            return when (p) {
                is SvgPath.Parsed.Bad -> "path: " + p.reason
                is SvgPath.Parsed.Ok -> Shape(kind, 0f, 0f, 0f, 0f, "", false, p.segs, null, true, st)
            }
        }
        if (kind == Kind.POLY) {
            val pts = polyPoints(o)
            if (pts is String) return pts
            val arr = pts as FloatArray
            val closed = o.get("closed")?.let { !(it.isJsonPrimitive && it.asString.equals("false", ignoreCase = true)) } ?: true
            if (closed && arr.size / 2 < 3) return "a closed poly needs 3 to $POLY_MAX_POINTS points"
            if (arr.size / 2 < 2) return "a poly needs at least 2 points"
            return Shape(kind, 0f, 0f, 0f, 0f, "", false, null, arr, closed, st)
        }
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
                else Shape(kind, xf, yf, w, h, label, style = st)
            }
            Kind.CIRCLE -> {
                val r = num(o, "r") ?: return "missing 'r'"
                if (r < 1f || r > SIZE / 2) "r must be between 1 and 50" else Shape(kind, xf, yf, r, 0f, label, style = st)
            }
            Kind.ELLIPSE -> {
                val rx = num(o, "rx") ?: return "missing 'rx'"
                val ry = num(o, "ry") ?: return "missing 'ry'"
                if (rx < 1f || rx > SIZE / 2 || ry < 1f || ry > SIZE / 2) "rx and ry must be between 1 and 50"
                else Shape(kind, xf, yf, rx, ry, label, style = st)
            }
            Kind.LINE -> {
                val x2 = coord(o, "x2")
                if (x2 is String) return x2
                val y2 = coord(o, "y2")
                if (y2 is String) return y2
                Shape(kind, xf, yf, x2 as Float, y2 as Float, label, arrow, style = st)
            }
            Kind.TEXT -> if (label.isEmpty()) "a text shape needs 'l'" else Shape(kind, xf, yf, 0f, 0f, label, style = st)
            Kind.PATH, Kind.POLY, Kind.GROUP -> "internal: path, poly and group are read above"
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

    /** The drawing as the JSON kept in the message ([com.codeassist.ai.data.Message.sketch]). Always "kind":"shapes", "v":2. */
    fun toJson(d: Drawing): String {
        val root = JsonObject()
        root.addProperty("kind", "shapes")
        root.addProperty("v", VERSION)
        root.addProperty("title", d.title)
        if (d.background != null) root.addProperty("bg", colorText(d.background))
        if (d.bgGrad != null) root.addProperty("bggrad", d.bgGrad.text())
        val arr = JsonArray()
        for (s in d.shapes) arr.add(shapeJson(s))
        root.add("shapes", arr)
        return root.toString()
    }

    private fun shapeJson(s: Shape): JsonObject {
        val o = JsonObject()
        o.addProperty("t", s.kind.key)
        if (s.kind != Kind.PATH && s.kind != Kind.POLY && s.kind != Kind.GROUP) {
            o.addProperty("x", s.a)
            o.addProperty("y", s.b)
        }
        when (s.kind) {
            Kind.RECT, Kind.TRI -> {
                o.addProperty("w", s.c)
                o.addProperty("h", s.d)
            }
            Kind.CIRCLE -> o.addProperty("r", s.c)
            Kind.ELLIPSE -> {
                o.addProperty("rx", s.c)
                o.addProperty("ry", s.d)
            }
            Kind.LINE -> {
                o.addProperty("x2", s.c)
                o.addProperty("y2", s.d)
                if (s.arrow) o.addProperty("arrow", true)
            }
            Kind.PATH -> o.addProperty("d", SvgPath.toD(s.path ?: emptyList()))
            Kind.POLY -> {
                val pts = JsonArray()
                val p = s.points ?: FloatArray(0)
                var i = 0
                while (i + 1 < p.size) {
                    val pair = JsonArray()
                    pair.add(p[i])
                    pair.add(p[i + 1])
                    pts.add(pair)
                    i += 2
                }
                o.add("pts", pts)
                if (!s.closed) o.addProperty("closed", false)
            }
            Kind.GROUP -> {
                val kids = JsonArray()
                for (k in s.children ?: emptyList()) kids.add(shapeJson(k))
                o.add("shapes", kids)
            }
            Kind.TEXT -> Unit
        }
        if (s.label.isNotEmpty()) o.addProperty("l", s.label)
        val st = s.style
        if (st != null) {
            if (st.fill != null) o.addProperty("fill", colorText(st.fill))
            if (st.grad != null) o.addProperty("grad", st.grad.text())
            if (st.stroke != null) o.addProperty("stroke", colorText(st.stroke))
            if (st.strokeWidth != null) o.addProperty("sw", st.strokeWidth)
            if (st.opacity != null) o.addProperty("op", st.opacity)
        }
        val xf = s.xform
        if (xf != null) {
            if (xf.dx != 0f) o.addProperty("dx", xf.dx)
            if (xf.dy != 0f) o.addProperty("dy", xf.dy)
            if (xf.rot != 0f) o.addProperty("rot", xf.rot)
            if (xf.scale != 1f) o.addProperty("sc", xf.scale)
        }
        if (s.hidden) o.addProperty("hide", true)
        return o
    }

    /** The stored JSON back as a drawing; null when it is not a valid sketch (the chat then just shows the text). */
    fun fromJson(json: String?): Drawing? {
        if (json.isNullOrBlank()) return null
        val p = parse(json)
        return if (p is Parsed.Ok) p.drawing else null
    }

    // ---------- export ----------

    private const val DEFAULT_BG = 0xFF151D28.toInt()
    private const val DEFAULT_FILL = 0x336EC1FF
    private const val DEFAULT_STROKE = 0xFF6EC1FF.toInt()
    private const val DEFAULT_TEXT = 0xFFE8EEF5.toInt()

    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun n(f: Float): String = SvgPath.fmt(f)

    /** Fill as an SVG attribute pair: the colour (alpha of the default fill goes into fill-opacity, as SVG has no #AARRGGBB). */
    private fun fillAttr(argb: Int): String {
        if (argb == 0) return "fill=\"none\""
        val alpha = (argb ushr 24) and 0xFF
        val base = "fill=\"" + String.format(Locale.US, "#%06X", argb and 0xFFFFFF) + "\""
        return if (alpha == 0xFF) base else base + " fill-opacity=\"" + n(alpha / 255f) + "\""
    }

    /** Gradient definitions collected while the SVG body is written; they go into one <defs> block in front of it. */
    private class SvgCtx {
        val defs = StringBuilder()
        var next = 0
    }

    private fun hex(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    /** Writes the gradient into [ctx] (user space = canvas units, same box as the Android drawing) and returns its id. */
    private fun gradientDef(ctx: SvgCtx, g: Gradient, b: FloatArray): String {
        ctx.next++
        val id = "g" + ctx.next
        if (g.radial) {
            ctx.defs.append("<radialGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" cx=\"")
                .append(n((b[0] + b[2]) / 2f)).append("\" cy=\"").append(n((b[1] + b[3]) / 2f)).append("\" r=\"").append(n(g.radius(b))).append("\">")
            ctx.defs.append("<stop offset=\"0\" stop-color=\"").append(hex(g.from)).append("\"/><stop offset=\"1\" stop-color=\"")
                .append(hex(g.to)).append("\"/></radialGradient>\n")
        } else {
            val l = g.line(b)
            ctx.defs.append("<linearGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" x1=\"").append(n(l[0]))
                .append("\" y1=\"").append(n(l[1])).append("\" x2=\"").append(n(l[2])).append("\" y2=\"").append(n(l[3])).append("\">")
            ctx.defs.append("<stop offset=\"0\" stop-color=\"").append(hex(g.from)).append("\"/><stop offset=\"1\" stop-color=\"")
                .append(hex(g.to)).append("\"/></linearGradient>\n")
        }
        return id
    }

    private fun paint(s: Shape, defaultFill: Int?, ctx: SvgCtx, strokeToo: Boolean = true): String {
        val st = s.style
        val sb = StringBuilder()
        val grad = st?.grad
        if (grad != null && defaultFill != null) {
            sb.append("fill=\"url(#").append(gradientDef(ctx, grad, bounds(s))).append(")\"")
        } else {
            val fill = st?.fill ?: defaultFill
            sb.append(if (fill == null) "fill=\"none\"" else fillAttr(fill))
        }
        if (strokeToo) {
            val stroke = st?.stroke ?: DEFAULT_STROKE
            if (stroke == 0) {
                sb.append(" stroke=\"none\"")
            } else {
                sb.append(" stroke=\"").append(hex(stroke)).append("\"")
                sb.append(" stroke-width=\"").append(n(st?.strokeWidth ?: 0.5f)).append("\"")
                sb.append(" stroke-linecap=\"round\" stroke-linejoin=\"round\"")
            }
        }
        val op = st?.opacity
        if (op != null && op < 1f) sb.append(" opacity=\"").append(n(op)).append("\"")
        return sb.toString()
    }

    /** The SVG `transform` text of [xf] (move, then turn, then scale, the last two around the shape's centre). */
    private fun xformAttr(s: Shape, xf: Xform): String {
        val p = pivot(s)
        val parts = ArrayList<String>()
        if (xf.dx != 0f || xf.dy != 0f) parts.add("translate(" + n(xf.dx) + " " + n(xf.dy) + ")")
        if (xf.rot != 0f) parts.add("rotate(" + n(xf.rot) + " " + n(p[0]) + " " + n(p[1]) + ")")
        if (xf.scale != 1f) parts.add("translate(" + n(p[0]) + " " + n(p[1]) + ") scale(" + n(xf.scale) + ") translate(" + n(-p[0]) + " " + n(-p[1]) + ")")
        return parts.joinToString(" ")
    }

    /**
     * The drawing as a standalone SVG document (viewBox 0 0 100 100). It looks like the in-app preview: same default
     * colours, same background. Only the shapes the sketch format has are written; nothing is invented. Hidden shapes are not
     * written. A group becomes a `<g>`; a transform becomes a `transform` attribute; a gradient a `<linearGradient>` /
     * `<radialGradient>` in `<defs>`.
     */
    fun toSvg(d: Drawing): String {
        val ctx = SvgCtx()
        val body = StringBuilder()
        for (s in d.shapes) svgShape(body, s, ctx)
        val bgPaint = if (d.bgGrad != null) {
            "fill=\"url(#" + gradientDef(ctx, d.bgGrad, floatArrayOf(0f, 0f, SIZE, SIZE)) + ")\""
        } else {
            fillAttr(d.background ?: DEFAULT_BG)
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\" width=\"600\" height=\"600\">\n")
        sb.append("<title>").append(xml(d.title)).append("</title>\n")
        if (ctx.defs.isNotEmpty()) sb.append("<defs>\n").append(ctx.defs).append("</defs>\n")
        sb.append("<rect width=\"100\" height=\"100\" rx=\"4\" ").append(bgPaint).append("/>\n")
        sb.append(body)
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun svgShape(sb: StringBuilder, s: Shape, ctx: SvgCtx) {
        if (s.hidden) return
        val xf = s.xform
        if (s.kind == Kind.GROUP) {
            sb.append("<g")
            if (xf != null) sb.append(" transform=\"").append(xformAttr(s, xf)).append("\"")
            val op = s.style?.opacity
            if (op != null && op < 1f) sb.append(" opacity=\"").append(n(op)).append("\"")
            if (s.label.isNotEmpty()) sb.append(" id=\"").append(xml(layerId(s.label))).append("\"")
            sb.append(">\n")
            for (k in s.children ?: emptyList()) svgShape(sb, k, ctx)
            sb.append("</g>\n")
            return
        }
        if (xf != null) sb.append("<g transform=\"").append(xformAttr(s, xf)).append("\">\n")
        svgPlain(sb, s, ctx)
        if (xf != null) sb.append("</g>\n")
    }

    /** An id-safe layer name ("Sky 1" -> "sky_1"). */
    private fun layerId(name: String): String {
        val c = name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("").trim('_')
        return if (c.isEmpty()) "layer" else "layer_$c"
    }

    private fun svgPlain(sb: StringBuilder, s: Shape, ctx: SvgCtx) {
        when (s.kind) {
            Kind.RECT -> {
                sb.append("<rect x=\"").append(n(s.a)).append("\" y=\"").append(n(s.b)).append("\" width=\"").append(n(s.c))
                    .append("\" height=\"").append(n(s.d)).append("\" rx=\"1.2\" ").append(paint(s, DEFAULT_FILL, ctx)).append("/>\n")
                if (s.label.isNotEmpty()) text(sb, s.label, s.a + s.c / 2, s.b + s.d / 2, 3.6f, s)
            }
            Kind.TRI -> {
                sb.append("<polygon points=\"").append(n(s.a + s.c / 2)).append(',').append(n(s.b)).append(' ')
                    .append(n(s.a + s.c)).append(',').append(n(s.b + s.d)).append(' ')
                    .append(n(s.a)).append(',').append(n(s.b + s.d)).append("\" ").append(paint(s, DEFAULT_FILL, ctx)).append("/>\n")
                if (s.label.isNotEmpty()) text(sb, s.label, s.a + s.c / 2, s.b + s.d * 0.7f, 3.4f, s)
            }
            Kind.CIRCLE -> {
                sb.append("<circle cx=\"").append(n(s.a)).append("\" cy=\"").append(n(s.b)).append("\" r=\"").append(n(s.c))
                    .append("\" ").append(paint(s, DEFAULT_FILL, ctx)).append("/>\n")
                if (s.label.isNotEmpty()) text(sb, s.label, s.a, s.b, 3.4f, s)
            }
            Kind.ELLIPSE -> {
                sb.append("<ellipse cx=\"").append(n(s.a)).append("\" cy=\"").append(n(s.b)).append("\" rx=\"").append(n(s.c))
                    .append("\" ry=\"").append(n(s.d)).append("\" ").append(paint(s, DEFAULT_FILL, ctx)).append("/>\n")
                if (s.label.isNotEmpty()) text(sb, s.label, s.a, s.b, 3.4f, s)
            }
            Kind.LINE -> {
                sb.append("<line x1=\"").append(n(s.a)).append("\" y1=\"").append(n(s.b)).append("\" x2=\"").append(n(s.c))
                    .append("\" y2=\"").append(n(s.d)).append("\" ").append(paint(s, null, ctx)).append("/>\n")
                if (s.arrow) arrowHead(sb, s)
                if (s.label.isNotEmpty()) text(sb, s.label, (s.a + s.c) / 2, (s.b + s.d) / 2 - 2f, 3.2f, s)
            }
            Kind.PATH -> sb.append("<path d=\"").append(SvgPath.toD(s.path ?: emptyList())).append("\" ")
                .append(paint(s, DEFAULT_FILL, ctx)).append("/>\n")
            Kind.POLY -> {
                val p = s.points ?: FloatArray(0)
                val pts = StringBuilder()
                var i = 0
                while (i + 1 < p.size) {
                    if (pts.isNotEmpty()) pts.append(' ')
                    pts.append(n(p[i])).append(',').append(n(p[i + 1]))
                    i += 2
                }
                sb.append(if (s.closed) "<polygon" else "<polyline").append(" points=\"").append(pts).append("\" ")
                    .append(paint(s, if (s.closed) DEFAULT_FILL else null, ctx)).append("/>\n")
            }
            Kind.TEXT -> text(sb, s.label, s.a, s.b, 4.2f, s)
            Kind.GROUP -> Unit // written by svgShape
        }
    }

    private fun text(sb: StringBuilder, label: String, cx: Float, cy: Float, size: Float, s: Shape) {
        val color = if (s.kind == Kind.TEXT) (s.style?.fill ?: DEFAULT_TEXT) else DEFAULT_TEXT
        sb.append("<text x=\"").append(n(cx)).append("\" y=\"").append(n(cy)).append("\" font-size=\"").append(n(size))
            .append("\" text-anchor=\"middle\" dominant-baseline=\"central\" font-family=\"sans-serif\" fill=\"")
            .append(String.format(Locale.US, "#%06X", color and 0xFFFFFF)).append("\">").append(xml(label)).append("</text>\n")
    }

    private fun arrowHead(sb: StringBuilder, s: Shape) {
        val angle = Math.atan2((s.d - s.b).toDouble(), (s.c - s.a).toDouble())
        val len = 3.2f
        val spread = Math.PI / 7
        val stroke = s.style?.stroke ?: DEFAULT_STROKE
        if (stroke == 0) return
        for (side in intArrayOf(-1, 1)) {
            val a = angle + Math.PI + side * spread
            val x = s.c + (len * Math.cos(a)).toFloat()
            val y = s.d + (len * Math.sin(a)).toFloat()
            sb.append("<line x1=\"").append(n(s.c)).append("\" y1=\"").append(n(s.d)).append("\" x2=\"").append(n(x))
                .append("\" y2=\"").append(n(y)).append("\" stroke=\"").append(String.format(Locale.US, "#%06X", stroke and 0xFFFFFF))
                .append("\" stroke-width=\"").append(n(s.style?.strokeWidth ?: 0.5f)).append("\" stroke-linecap=\"round\"/>\n")
        }
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
            "\"x\":10,\"y\":88,\"x2\":90,\"y2\":88},{\"t\":\"text\",\"x\":50,\"y\":96,\"l\":\"House\"}]} " + DETAIL_HELP + labels
    }

    /** What a detailed picture may use (stage 1). Kept short: the phone model has a small context. */
    const val DETAIL_HELP =
        "For a detailed picture write kind shapes with \"v\":2 (up to 120 shapes) and use more shape types: " +
            "{\"t\":\"path\",\"d\":\"M10 80 L30 40 Q50 20 70 40 L90 80 Z\"} (SVG path, commands M L H V C S Q T Z, no arcs), " +
            "{\"t\":\"poly\",\"pts\":[[10,10],[40,10],[25,30]]}, {\"t\":\"ellipse\",\"x\":50,\"y\":50,\"rx\":20,\"ry\":10}. " +
            "Any shape may add \"fill\":\"#RRGGBB\" (or \"none\"), \"stroke\":\"#RRGGBB\", \"sw\":0.6 (line width), \"op\":0.8 (opacity); " +
            "the picture may add \"bg\":\"#RRGGBB\" (background). "

    // ---------- changing a drawing that is already there ----------

    fun reviseSystemPrompt(lang: String): String =
        systemPrompt(lang) +
            " You are now EDITING a drawing: the user message holds the current JSON and ONE change request. " +
            "Answer with the FULL updated JSON of kind shapes with \"v\":2. Change only what was asked and keep every other shape exactly as it is."

    /**
     * What only the studio screen offers the model (stage 3). Kept out of the chat prompt on purpose: the phone model's context
     * is small and the chat path stays exactly as it was.
     */
    const val ADVANCED_HELP =
        "Also allowed: arcs in a path (A rx ry rot large sweep x y); a gradient fill \"grad\":\"#RRGGBB #RRGGBB 90\" " +
            "(angle in degrees, 90 = top to bottom) or \"grad\":\"#RRGGBB #RRGGBB radial\"; \"bggrad\":\"#RRGGBB #RRGGBB 90\" for the background; " +
            "move / turn / scale a shape around its centre with \"dx\":5,\"dy\":-3,\"rot\":30,\"sc\":1.5; " +
            "layers: {\"t\":\"group\",\"l\":\"sky\",\"shapes\":[ ...shapes... ]} (a group may take dx, dy, rot, sc, op; no group inside a group); " +
            "\"hide\":true hides a shape or a group. "

    /** The studio's system prompt for a NEW drawing: the chat prompt plus [ADVANCED_HELP]. */
    fun studioSystemPrompt(lang: String): String = systemPrompt(lang) + " " + ADVANCED_HELP

    /** The studio's system prompt for an EDIT: [reviseSystemPrompt] plus [ADVANCED_HELP]. */
    fun studioReviseSystemPrompt(lang: String): String = reviseSystemPrompt(lang) + " " + ADVANCED_HELP

    fun revisePrompt(currentJson: String, request: String): String =
        "Current drawing JSON: " + currentJson + "\nChange request: " + request.trim().take(300)

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

    private val editWords = setOf(
        "badlo", "badal", "badalo", "change", "hata", "hatao", "hatado", "jodo", "jod", "add", "remove", "bada", "badi",
        "chhota", "chhoti", "chota", "choti", "bigger", "smaller", "dark", "darker", "light", "lighter", "color", "colour",
        "rang", "kar", "karo", "make", "set", "delete", "replace", "thick", "patla", "mota", "moti", "background"
    )

    /**
     * A change request for the drawing that is already on screen ("background dark kar do", "darwaza bada karo"): only when
     * the last AI reply was a drawing, the message is short, not a question and not a new drawing order.
     */
    fun isRevision(text: String, lastReplyHasSketch: Boolean): Boolean {
        if (!lastReplyHasSketch) return false
        val t = text.trim()
        if (t.isEmpty() || t.length > 200 || t.endsWith("?") || t.contains("```")) return false
        if (isRequest(t)) return false
        val ws = words(t)
        if (ws.isEmpty() || ws.size > 24) return false
        return ws.any { it in editWords }
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
