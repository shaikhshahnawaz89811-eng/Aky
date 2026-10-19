package com.codeassist.ai.ai

/**
 * Sketch Studio, stage 3 (14 Oct 2026): the layer panel of the studio screen, as pure functions on a [Sketch.Drawing].
 * A "layer" is one top-level shape (a group is the usual one). Every operation returns a NEW drawing (the old one is not changed)
 * or null when it cannot be done, so the screen can push the result into the undo history like any other change.
 *
 * The panel lists the front layer first (the last shape of the drawing is drawn on top). [Row.index] is the index in
 * [Sketch.Drawing.shapes], so the screen never has to convert between the two orders.
 */
object SketchLayers {
    class Row(val index: Int, val title: String, val hidden: Boolean, val isGroup: Boolean, val children: Int)

    private fun kindName(k: Sketch.Kind): String = when (k) {
        Sketch.Kind.RECT -> "rect"
        Sketch.Kind.TRI -> "triangle"
        Sketch.Kind.CIRCLE -> "circle"
        Sketch.Kind.LINE -> "line"
        Sketch.Kind.TEXT -> "text"
        Sketch.Kind.PATH -> "path"
        Sketch.Kind.POLY -> "polygon"
        Sketch.Kind.ELLIPSE -> "ellipse"
        Sketch.Kind.GROUP -> "layer"
    }

    /** "sky (5)" for a group, "rect · wall" / "circle" for a plain shape. */
    fun title(s: Sketch.Shape): String {
        if (s.kind == Sketch.Kind.GROUP) {
            val name = if (s.label.isEmpty()) "layer" else s.label
            return name + " (" + (s.children?.size ?: 0) + ")"
        }
        return kindName(s.kind) + (if (s.label.isEmpty()) "" else " \u00B7 " + s.label)
    }

    /** The rows of the panel, front layer first. */
    fun rows(d: Sketch.Drawing): List<Row> =
        d.shapes.indices.reversed().map { i ->
            val s = d.shapes[i]
            Row(i, title(s), s.hidden, s.kind == Sketch.Kind.GROUP, s.children?.size ?: 0)
        }

    private fun withShapes(d: Sketch.Drawing, shapes: List<Sketch.Shape>): Sketch.Drawing =
        Sketch.Drawing(d.title, shapes, d.background, d.bgGrad)

    private fun valid(d: Sketch.Drawing, i: Int): Boolean = i >= 0 && i < d.shapes.size

    /** Show or hide layer [i]. */
    fun toggleHidden(d: Sketch.Drawing, i: Int): Sketch.Drawing? {
        if (!valid(d, i)) return null
        val list = d.shapes.toMutableList()
        list[i] = list[i].withHidden(!list[i].hidden)
        return withShapes(d, list)
    }

    /** One step to the front (drawn later). Null at the front already. */
    fun moveForward(d: Sketch.Drawing, i: Int): Sketch.Drawing? {
        if (!valid(d, i) || i >= d.shapes.size - 1) return null
        val list = d.shapes.toMutableList()
        val t = list[i]
        list[i] = list[i + 1]
        list[i + 1] = t
        return withShapes(d, list)
    }

    /** One step to the back (drawn earlier). Null at the back already. */
    fun moveBack(d: Sketch.Drawing, i: Int): Sketch.Drawing? {
        if (!valid(d, i) || i <= 0) return null
        val list = d.shapes.toMutableList()
        val t = list[i]
        list[i] = list[i - 1]
        list[i - 1] = t
        return withShapes(d, list)
    }

    /** Removes layer [i]. Null when it is the last one: a drawing without shapes is not a valid sketch (undo or "Naya" instead). */
    fun remove(d: Sketch.Drawing, i: Int): Sketch.Drawing? {
        if (!valid(d, i) || d.shapes.size <= 1) return null
        val list = d.shapes.toMutableList()
        list.removeAt(i)
        return withShapes(d, list)
    }

    /** A copy of layer [i] right in front of it. Null when the shape or point limits of the format would be passed. */
    fun duplicate(d: Sketch.Drawing, i: Int): Sketch.Drawing? {
        if (!valid(d, i)) return null
        val list = d.shapes.toMutableList()
        list.add(i + 1, d.shapes[i])
        val next = withShapes(d, list)
        if (Sketch.shapeCount(next) > Sketch.MAX_SHAPES_V2) return null
        if (next.shapes.sumOf { Sketch.pointsOf(it) } > Sketch.MAX_POINTS) return null
        return next
    }

    /** Gives a group a new name (at most 24 characters). Null when [i] is not a group. */
    fun renameGroup(d: Sketch.Drawing, i: Int, name: String): Sketch.Drawing? {
        if (!valid(d, i) || d.shapes[i].kind != Sketch.Kind.GROUP) return null
        val clean = name.replace(Regex("\\s+"), " ").trim().take(24).trimEnd()
        val list = d.shapes.toMutableList()
        list[i] = list[i].withLabel(clean)
        return withShapes(d, list)
    }

    /** Puts the plain shape [i] into a new group called [name]. Null for a group (no group inside a group) or a bad index. */
    fun groupOne(d: Sketch.Drawing, i: Int, name: String): Sketch.Drawing? {
        if (!valid(d, i) || d.shapes[i].kind == Sketch.Kind.GROUP) return null
        val clean = name.replace(Regex("\\s+"), " ").trim().take(24).trimEnd()
        val list = d.shapes.toMutableList()
        list[i] = Sketch.Shape(Sketch.Kind.GROUP, 0f, 0f, 0f, 0f, clean, children = listOf(d.shapes[i]))
        return withShapes(d, list)
    }

    /** True when every layer is hidden (nothing is drawn; the screen warns instead of showing an empty square). */
    fun allHidden(d: Sketch.Drawing): Boolean = d.shapes.all { it.hidden }
}
