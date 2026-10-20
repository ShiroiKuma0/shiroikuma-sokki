package com.xnotes.format

import com.xnotes.core.steno.FixKind
import com.xnotes.core.steno.FixSegment
import com.xnotes.core.steno.StenoFix

/**
 * The `"steno_fix"` key of a 速記 stroke (fork): its correction layer, written only when the stroke
 * has one. A build that doesn't know the key skips it and shows the writing as it was written.
 *
 * ```
 * "steno_fix": {
 *   "segs": [[from, to, "clean", 0, "glyph-id", "reading", raise, checked], ...],
 *   "d": [dx0, dy0, dx1, dy1, ...]
 * }
 * ```
 * A segment's glyph and reading are written only for an element, and its raise and checked flag
 * only when set; the moves are rounded to 0.01 px, as the samples are.
 */
internal object StenoFixJson {

    fun write(j: JsonWrite, fix: StenoFix) {
        j.name("steno_fix").beginObject()
        j.name("segs").beginArray()
        for (s in fix.segments) {
            j.beginArray()
            j.value(s.from).value(s.to).value(s.kind.id).value(if (s.raw) 1 else 0)
            val tail = s.raise != 0.0 || s.checked
            if (s.glyph != null || s.reading != null || tail) {
                if (s.glyph != null) j.value(s.glyph) else j.nullValue()
                if (s.reading != null) j.value(s.reading) else j.nullValue()
            }
            if (tail) j.value(round2(s.raise)).value(if (s.checked) 1 else 0)
            j.endArray()
        }
        j.endArray()
        j.name("d").beginArray()
        for (i in 0 until fix.size) j.value(round2(fix.dx[i].toDouble())).value(round2(fix.dy[i].toDouble()))
        j.endArray()
        j.endObject()
    }

    /** The layer at the reader, or null when it is malformed (the stroke then shows as written). */
    fun read(p: JsonPull): StenoFix? {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) { p.skipValue(); return null }
        val segs = ArrayList<FixSegment>()
        var d: DoubleArray? = null
        var ok = true
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "segs" -> {
                    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) { p.skipValue(); ok = false; continue }
                    p.beginArray()
                    while (p.hasNext()) readSegment(p)?.let { segs += it } ?: run { ok = false }
                    p.endArray()
                }
                "d" -> {
                    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) { p.skipValue(); ok = false; continue }
                    val out = ArrayList<Double>()
                    p.beginArray()
                    while (p.hasNext()) {
                        if (p.peek() == JsonPull.Token.NUMBER) out += p.nextDouble() else { p.skipValue(); ok = false }
                    }
                    p.endArray()
                    d = out.toDoubleArray()
                }
                else -> p.skipValue()
            }
        }
        p.endObject()
        val moves = d ?: return null
        if (!ok || segs.isEmpty() || moves.size % 2 != 0) return null
        val n = moves.size / 2
        if (segs.any { it.from < 0 || it.to >= n || it.from > it.to }) return null
        return StenoFix(segs, FloatArray(n) { moves[2 * it].toFloat() }, FloatArray(n) { moves[2 * it + 1].toFloat() })
    }

    private fun readSegment(p: JsonPull): FixSegment? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) { p.skipValue(); return null }
        val f = ArrayList<Any?>()
        p.beginArray()
        while (p.hasNext()) {
            f += when (p.peek()) {
                JsonPull.Token.NUMBER -> p.nextDouble()
                JsonPull.Token.STRING -> p.nextString()
                JsonPull.Token.NULL -> { p.nextNull(); null }
                else -> { p.skipValue(); null }
            }
        }
        p.endArray()
        if (f.size < 4) return null
        val from = (f[0] as? Double)?.toInt() ?: return null
        val to = (f[1] as? Double)?.toInt() ?: return null
        return FixSegment(
            from, to,
            kind = FixKind.fromId(f[2] as? String),
            raw = (f[3] as? Double ?: 0.0) != 0.0,
            glyph = f.getOrNull(4) as? String,
            reading = f.getOrNull(5) as? String,
            raise = f.getOrNull(6) as? Double ?: 0.0,
            checked = (f.getOrNull(7) as? Double ?: 0.0) != 0.0,
        )
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
