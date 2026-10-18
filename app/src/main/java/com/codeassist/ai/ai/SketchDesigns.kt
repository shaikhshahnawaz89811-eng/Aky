package com.codeassist.ai.ai

import java.util.UUID

/**
 * Sketch Studio, stage 2 (14 Oct 2026): the pure logic behind the studio screen. No Android types, so the JVM tests cover all
 * of it ([com.codeassist.ai.ai.SketchDesignsTest]); the screen itself is `sketch/SketchStudioActivity`.
 *
 *  - [Design] + the list operations (add, update, rename, duplicate, delete): the "designs" the user keeps outside any chat;
 *  - [SketchHistory]: undo / redo of one open design (the JSON strings of [Sketch.toJson], newest last);
 *  - [ViewXform]: pinch-zoom and pan of the square canvas (clamped so the drawing never leaves the view);
 *  - [fileName]: a safe name for an exported .svg / .png.
 *
 * A design with a blank [Design.json] is an empty canvas (just created, or undone back to the start).
 */
object SketchDesigns {
    const val MAX_DESIGNS = 60
    const val MAX_TITLE = 40
    const val UNTITLED = "Naya design"
    const val COPY_WORD = "copy"

    /** One saved design. [json] is [Sketch.toJson] or "" for an empty canvas. Times are epoch milliseconds. */
    data class Design(
        val id: String,
        val title: String,
        val json: String = "",
        val created: Long = 0L,
        val updated: Long = 0L,
        /**
         * Stage 3: [KIND_SKETCH] (the default, also for every design saved before stage 3), [KIND_HTML] (a web page) or
         * [KIND_HTML3D] (a web page with the Three.js library). Nullable on purpose: Gson fills a field that is missing in old
         * saved data with null, not with the Kotlin default, so read it with [kindOf].
         */
        val kind: String? = null,
        /** The page of a web design (the [json] of such a design stays empty). Nullable for the same reason, read it with [htmlOf]. */
        val html: String? = null
    )

    const val KIND_SKETCH = "sketch"
    const val KIND_HTML = "html"
    const val KIND_HTML3D = "html3d"

    fun kindOf(d: Design): String = when (d.kind) {
        KIND_HTML -> KIND_HTML
        KIND_HTML3D -> KIND_HTML3D
        else -> KIND_SKETCH
    }

    fun isWeb(d: Design): Boolean = kindOf(d) != KIND_SKETCH

    fun is3d(d: Design): Boolean = kindOf(d) == KIND_HTML3D

    fun htmlOf(d: Design): String = d.html.orEmpty()

    /** What undo / redo and the model work on: the page for a web design, the sketch JSON otherwise ("" = empty). */
    fun contentOf(d: Design): String = if (isWeb(d)) htmlOf(d) else d.json

    /** The short kind name shown in the list ("</>" for a page, "3D" for a Three.js page, "" for a drawing). */
    fun badge(d: Design): String = when (kindOf(d)) {
        KIND_HTML -> "</>"
        KIND_HTML3D -> "3D"
        else -> ""
    }

    /** Trimmed, one-line title of at most [MAX_TITLE] characters; a blank one becomes [UNTITLED]. */
    fun cleanTitle(raw: String?): String {
        val flat = (raw ?: "").map { if (it.isISOControl()) ' ' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return UNTITLED
        return if (flat.length > MAX_TITLE) flat.take(MAX_TITLE).trimEnd() else flat
    }

    fun newDesign(title: String?, json: String, now: Long, id: String = UUID.randomUUID().toString()): Design =
        Design(id = id, title = cleanTitle(title), json = json, created = now, updated = now)

    /** An empty web design ([KIND_HTML] or [KIND_HTML3D]); anything else gives a normal sketch design. */
    fun newWebDesign(title: String?, kind: String, now: Long, id: String = UUID.randomUUID().toString()): Design {
        val k = if (kind == KIND_HTML || kind == KIND_HTML3D) kind else KIND_SKETCH
        return Design(id = id, title = cleanTitle(title), json = "", created = now, updated = now, kind = if (k == KIND_SKETCH) null else k, html = null)
    }

    fun find(list: List<Design>, id: String?): Design? = if (id == null) null else list.firstOrNull { it.id == id }

    /** Newest change first (the order the list screen shows). */
    fun sorted(list: List<Design>): List<Design> = list.sortedByDescending { it.updated }

    /** The list with [d] added, or null when [MAX_DESIGNS] is reached or the id already exists. */
    fun add(list: List<Design>, d: Design): List<Design>? {
        if (list.size >= MAX_DESIGNS) return null
        if (list.any { it.id == d.id }) return null
        return list + d
    }

    /**
     * New content for design [id] (also bumps the change time): the sketch JSON, or the page of a web design.
     * Unknown id: the list comes back unchanged.
     */
    fun update(list: List<Design>, id: String, json: String, now: Long): List<Design> =
        list.map {
            if (it.id != id) it
            else if (isWeb(it)) it.copy(html = json, updated = now)
            else it.copy(json = json, updated = now)
        }

    fun rename(list: List<Design>, id: String, title: String?, now: Long): List<Design> =
        list.map { if (it.id == id) it.copy(title = cleanTitle(title), updated = now) else it }

    fun delete(list: List<Design>, id: String): List<Design> = list.filterNot { it.id == id }

    /** "House copy", "House copy 2", ... the first title not used yet; the base is cut so the result fits [MAX_TITLE]. */
    fun copyTitle(list: List<Design>, title: String): String {
        val taken = list.map { it.title }.toSet()
        var n = 1
        while (true) {
            val suffix = " " + COPY_WORD + (if (n == 1) "" else " $n")
            val base = cleanTitle(title).take(MAX_TITLE - suffix.length).trimEnd()
            val candidate = base + suffix
            if (candidate !in taken) return candidate
            n++
        }
    }

    /** A copy of design [id] (new id, new times), or null when the id is unknown or the list is full. */
    fun duplicate(list: List<Design>, id: String, now: Long, newId: String = UUID.randomUUID().toString()): List<Design>? {
        val src = find(list, id) ?: return null
        return add(list, src.copy(id = newId, title = copyTitle(list, src.title), created = now, updated = now))
    }

    /** True when [json] is an empty canvas or a drawing the app can read back. */
    fun readable(json: String): Boolean = json.isBlank() || Sketch.fromJson(json) != null

    /** A file name such as "house.svg": lower-case letters, digits and "_" only, at most 30 characters before the dot. */
    fun fileName(title: String?, ext: String): String {
        val cleaned = (title ?: "").lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("").replace(Regex("_+"), "_").trim('_').take(30).trim('_')
        val base = if (cleaned.isEmpty()) "sketch" else cleaned
        return base + "." + ext.trimStart('.').lowercase()
    }
}

/**
 * Undo / redo of the open design. The first entry is the state the design had when it was opened ([initial], "" = empty canvas).
 * [push] after an undo drops the redo tail. At most [limit] states are kept; the oldest is forgotten first.
 */
class SketchHistory(initial: String, private val limit: Int = 30) {
    private val items = ArrayList<String>().apply { add(initial) }
    private var pos = 0

    val current: String get() = items[pos]
    val canUndo: Boolean get() = pos > 0
    val canRedo: Boolean get() = pos < items.size - 1
    val size: Int get() = items.size

    /** Records a new state. False (nothing changes) when it equals the current one. */
    fun push(json: String): Boolean {
        if (json == current) return false
        while (items.size > pos + 1) items.removeAt(items.size - 1)
        items.add(json)
        if (items.size > limit.coerceAtLeast(2)) items.removeAt(0)
        pos = items.size - 1
        return true
    }

    /** The previous state, or null at the start. */
    fun undo(): String? {
        if (!canUndo) return null
        pos--
        return items[pos]
    }

    /** The next state, or null at the end. */
    fun redo(): String? {
        if (!canRedo) return null
        pos++
        return items[pos]
    }
}

/**
 * Zoom and pan of a square canvas of [side] pixels. The drawing is drawn as translate([tx], [ty]) then scale([scale]) from its
 * top-left corner. Scale stays between 1 and [MAX_SCALE]; the translation is clamped so the scaled drawing always covers the
 * whole view (at scale 1 there is no room to move at all).
 */
class ViewXform {
    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 8f
    }

    var scale = 1f
        private set
    var tx = 0f
        private set
    var ty = 0f
        private set

    val isIdentity: Boolean get() = scale == 1f && tx == 0f && ty == 0f

    fun reset() {
        scale = 1f
        tx = 0f
        ty = 0f
    }

    /** Multiplies the scale by [factor] and keeps the canvas point under (fx, fy) where it is (as far as the clamps allow). */
    fun zoomAt(factor: Float, fx: Float, fy: Float, side: Float) {
        if (factor.isNaN() || factor <= 0f || side <= 0f) return
        val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = next / scale
        tx = fx - (fx - tx) * ratio
        ty = fy - (fy - ty) * ratio
        scale = next
        clamp(side)
    }

    /** Moves the drawing by (dx, dy) pixels. */
    fun pan(dx: Float, dy: Float, side: Float) {
        tx += dx
        ty += dy
        clamp(side)
    }

    /** Pulls the translation back so the scaled drawing still covers the view. */
    fun clamp(side: Float) {
        val minT = side - side * scale
        tx = tx.coerceIn(minT, 0f)
        ty = ty.coerceIn(minT, 0f)
    }

    /** Canvas coordinate (0..side) under the view pixel [p] on one axis, [t] = tx or ty. */
    fun toCanvas(p: Float, t: Float): Float = (p - t) / scale
}
