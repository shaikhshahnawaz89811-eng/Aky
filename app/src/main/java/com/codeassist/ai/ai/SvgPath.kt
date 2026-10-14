package com.codeassist.ai.ai

/**
 * Sketch Studio, stage 1: reads the path data of an SVG `<path d="...">` (the model writes it, the app checks it).
 *
 * Supported: M m L l H h V v C c S s Q q T t Z z, with implicit repeats ("M0 0 10 10 20 0"). NOT supported: the arc
 * commands A / a (they are refused with a clear reason so the model can redraw with C or Q curves).
 *
 * The result is normalised to absolute M / L / C / Q / Z segments in canvas units (0..100, see [Sketch.SIZE]), so the
 * Android drawing code and the SVG export need no parser of their own. Pure Kotlin, no Android types.
 */
object SvgPath {
    const val MAX_LENGTH = 800
    const val MAX_SEGMENTS = 80

    /** [op] is one of M, L, C, Q, Z; [v] holds 2 (M, L), 6 (C), 4 (Q) or 0 (Z) absolute numbers. */
    class Seg(val op: Char, val v: FloatArray)

    sealed class Parsed {
        class Ok(val segs: List<Seg>) : Parsed()
        class Bad(val reason: String) : Parsed()
    }

    private const val COMMANDS = "MmLlHhVvCcSsQqTtZz"
    private const val LIMIT_LOW = -5f
    private const val LIMIT_HIGH = 105f

    private fun isDigit(c: Char): Boolean = c in '0'..'9'
    private fun isLetter(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'

    private fun arity(op: Char): Int = when (op) {
        'M', 'L', 'T' -> 2
        'H', 'V' -> 1
        'C' -> 6
        'S', 'Q' -> 4
        else -> 0
    }

    private class Reader(val s: String) {
        var i = 0

        fun skipSep() {
            while (i < s.length && (s[i] == ' ' || s[i] == ',' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        /** The next number, or null when there is none at this position. */
        fun number(): Float? {
            skipSep()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var digits = 0
            while (i < s.length && isDigit(s[i])) {
                i++
                digits++
            }
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && isDigit(s[i])) {
                    i++
                    digits++
                }
            }
            if (digits == 0) {
                i = start
                return null
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                var j = i + 1
                if (j < s.length && (s[j] == '-' || s[j] == '+')) j++
                var expDigits = 0
                while (j < s.length && isDigit(s[j])) {
                    j++
                    expDigits++
                }
                if (expDigits > 0) i = j
            }
            val f = s.substring(start, i).toFloatOrNull() ?: return null
            return if (f.isNaN() || f.isInfinite()) null else f
        }
    }

    private fun checked(v: Float): Float? = if (v < LIMIT_LOW || v > LIMIT_HIGH) null else v.coerceIn(0f, Sketch.SIZE)

    fun parse(d: String): Parsed {
        val s = d.trim()
        if (s.isEmpty()) return Parsed.Bad("path is empty")
        if (s.length > MAX_LENGTH) return Parsed.Bad("path is longer than $MAX_LENGTH characters")
        val r = Reader(s)
        val out = ArrayList<Seg>()
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var lastCx = 0f
        var lastCy = 0f
        var prev = ' '
        var cmd = ' '
        val rangeMsg = "path coordinates must be between 0 and 100"
        while (true) {
            r.skipSep()
            if (r.i >= s.length) break
            val ch = s[r.i]
            if (isLetter(ch)) {
                if (COMMANDS.indexOf(ch) < 0) {
                    return if (ch == 'A' || ch == 'a') Parsed.Bad("arc commands (A) are not supported, use C or Q curves")
                    else Parsed.Bad("unknown path command '$ch'")
                }
                cmd = ch
                r.i++
                if (ch == 'Z' || ch == 'z') {
                    if (out.isEmpty()) return Parsed.Bad("path must start with M")
                    out.add(Seg('Z', FloatArray(0)))
                    if (out.size > MAX_SEGMENTS) return Parsed.Bad("path has more than $MAX_SEGMENTS segments")
                    cx = sx
                    cy = sy
                    prev = 'Z'
                    continue
                }
            } else {
                if (cmd == ' ') return Parsed.Bad("path must start with M")
                if (cmd == 'Z' || cmd == 'z') return Parsed.Bad("a number after Z needs a new command")
            }
            val rel = cmd in 'a'..'z'
            val up = cmd.uppercaseChar()
            if (out.isEmpty() && up != 'M') return Parsed.Bad("path must start with M")
            val n = arity(up)
            val a = FloatArray(n)
            for (k in 0 until n) {
                a[k] = r.number() ?: return Parsed.Bad("bad or missing number in the path (position ${r.i})")
            }
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            when (up) {
                'M' -> {
                    val x = a[0] + ox
                    val y = a[1] + oy
                    out.add(Seg('M', floatArrayOf(checked(x) ?: return Parsed.Bad(rangeMsg), checked(y) ?: return Parsed.Bad(rangeMsg))))
                    cx = x
                    cy = y
                    sx = x
                    sy = y
                    prev = 'M'
                    // numbers that follow the first pair are implicit line-tos
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> {
                    val x = a[0] + ox
                    val y = a[1] + oy
                    out.add(Seg('L', floatArrayOf(checked(x) ?: return Parsed.Bad(rangeMsg), checked(y) ?: return Parsed.Bad(rangeMsg))))
                    cx = x
                    cy = y
                    prev = 'L'
                }
                'H' -> {
                    val x = a[0] + ox
                    out.add(Seg('L', floatArrayOf(checked(x) ?: return Parsed.Bad(rangeMsg), checked(cy) ?: return Parsed.Bad(rangeMsg))))
                    cx = x
                    prev = 'L'
                }
                'V' -> {
                    val y = a[0] + oy
                    out.add(Seg('L', floatArrayOf(checked(cx) ?: return Parsed.Bad(rangeMsg), checked(y) ?: return Parsed.Bad(rangeMsg))))
                    cy = y
                    prev = 'L'
                }
                'C' -> {
                    val x1 = a[0] + ox
                    val y1 = a[1] + oy
                    val x2 = a[2] + ox
                    val y2 = a[3] + oy
                    val x = a[4] + ox
                    val y = a[5] + oy
                    out.add(Seg('C', floatArrayOf(
                        checked(x1) ?: return Parsed.Bad(rangeMsg), checked(y1) ?: return Parsed.Bad(rangeMsg),
                        checked(x2) ?: return Parsed.Bad(rangeMsg), checked(y2) ?: return Parsed.Bad(rangeMsg),
                        checked(x) ?: return Parsed.Bad(rangeMsg), checked(y) ?: return Parsed.Bad(rangeMsg)
                    )))
                    lastCx = x2
                    lastCy = y2
                    cx = x
                    cy = y
                    prev = 'C'
                }
                'S' -> {
                    // first control point = reflection of the last cubic control point (or the current point)
                    val x1 = if (prev == 'C') 2 * cx - lastCx else cx
                    val y1 = if (prev == 'C') 2 * cy - lastCy else cy
                    val x2 = a[0] + ox
                    val y2 = a[1] + oy
                    val x = a[2] + ox
                    val y = a[3] + oy
                    out.add(Seg('C', floatArrayOf(
                        checked(x1) ?: return Parsed.Bad(rangeMsg), checked(y1) ?: return Parsed.Bad(rangeMsg),
                        checked(x2) ?: return Parsed.Bad(rangeMsg), checked(y2) ?: return Parsed.Bad(rangeMsg),
                        checked(x) ?: return Parsed.Bad(rangeMsg), checked(y) ?: return Parsed.Bad(rangeMsg)
                    )))
                    lastCx = x2
                    lastCy = y2
                    cx = x
                    cy = y
                    prev = 'C'
                }
                'Q' -> {
                    val x1 = a[0] + ox
                    val y1 = a[1] + oy
                    val x = a[2] + ox
                    val y = a[3] + oy
                    out.add(Seg('Q', floatArrayOf(
                        checked(x1) ?: return Parsed.Bad(rangeMsg), checked(y1) ?: return Parsed.Bad(rangeMsg),
                        checked(x) ?: return Parsed.Bad(rangeMsg), checked(y) ?: return Parsed.Bad(rangeMsg)
                    )))
                    lastCx = x1
                    lastCy = y1
                    cx = x
                    cy = y
                    prev = 'Q'
                }
                else -> { // 'T': control point = reflection of the last quadratic control point (or the current point)
                    val x1 = if (prev == 'Q') 2 * cx - lastCx else cx
                    val y1 = if (prev == 'Q') 2 * cy - lastCy else cy
                    val x = a[0] + ox
                    val y = a[1] + oy
                    out.add(Seg('Q', floatArrayOf(
                        checked(x1) ?: return Parsed.Bad(rangeMsg), checked(y1) ?: return Parsed.Bad(rangeMsg),
                        checked(x) ?: return Parsed.Bad(rangeMsg), checked(y) ?: return Parsed.Bad(rangeMsg)
                    )))
                    lastCx = x1
                    lastCy = y1
                    cx = x
                    cy = y
                    prev = 'Q'
                }
            }
            if (out.size > MAX_SEGMENTS) return Parsed.Bad("path has more than $MAX_SEGMENTS segments")
        }
        if (out.size < 2) return Parsed.Bad("a path needs a move (M) and at least one line or curve")
        return Parsed.Ok(out)
    }

    /** Number without a trailing ".0", at most 2 decimals. */
    fun fmt(f: Float): String {
        val r = Math.round(f * 100f) / 100f
        return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
    }

    /** The normalised segments as one `d` string (absolute commands, single spaces). */
    fun toD(segs: List<Seg>): String {
        val sb = StringBuilder()
        for (s in segs) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s.op)
            for ((i, v) in s.v.withIndex()) {
                sb.append(if (i == 0) "" else " ").append(fmt(v))
            }
        }
        return sb.toString()
    }
}
