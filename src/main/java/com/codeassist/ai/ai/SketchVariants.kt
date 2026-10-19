package com.codeassist.ai.ai

/**
 * Sketch Studio, stage 3 (14 Oct 2026): "design variations" - three different drawings from ONE request. The phone model is
 * called three times in a row with the same request and a different layout idea each time ([hint]); the studio shows the
 * answers side by side and the user keeps one. This file is the pure part: the hints, the prompts and the choice of which
 * answers to show. The calls themselves are in [SketchGen.runVariants].
 */
object SketchVariants {
    const val COUNT = 3

    private val HINTS = listOf(
        "Layout idea 1: simple and bold. Few big shapes, a clear centre, flat colours.",
        "Layout idea 2: rich and detailed. Many small shapes, a gradient or two, and 2 or 3 layers (groups).",
        "Layout idea 3: a different mood and composition from a plain version. Other colours, a dark or bright bg, and tilt or scale something with rot / sc."
    )

    /** The layout idea for variation [i] (0 to [COUNT] - 1). */
    fun hint(i: Int): String = HINTS[i.coerceIn(0, HINTS.size - 1)]

    fun label(i: Int): String = "Option " + (i + 1)

    /** The user message of variation [i]: the normal request prompt plus its layout idea. */
    fun prompt(request: String, i: Int): String = Sketch.userPrompt(request) + "\n" + hint(i)

    class Candidate(val index: Int, val drawing: Sketch.Drawing, val json: String)

    /** The candidates without repeats (same JSON twice shows one), in the order they were made. */
    fun distinct(list: List<Candidate>): List<Candidate> {
        val seen = HashSet<String>()
        return list.filter { seen.add(it.json) }
    }
}
