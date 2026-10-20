package com.xnotes.core.steno

/** What a stretch of a corrected stroke was given. */
enum class FixKind(val id: String) {
    /** The hand's own shape, steadied and set to the slant and the lines (no recognition). */
    CLEAN("clean"),

    /** Recognized as a catalogue element and redrawn in its textbook form. */
    GLYPH("glyph"),

    /** Replaced by hand: a part of the line written over with the right element. */
    REPLACED("replaced");

    companion object {
        fun fromId(id: String?): FixKind = entries.firstOrNull { it.id == id } ?: CLEAN
    }
}

/**
 * One stretch of a corrected stroke: samples [from]..[to] (inclusive). [raw] shows the stretch as
 * it was written rather than corrected. [glyph] and [reading] name the catalogue element a [FixKind.GLYPH]
 * or [FixKind.REPLACED] stretch became. [raise] is how far, in steps, the element sits above (or
 * below) its own height — half a step carries a vowel. [checked] marks a stretch the user has
 * reviewed, so the review queue passes it by.
 */
data class FixSegment(
    val from: Int,
    val to: Int,
    val kind: FixKind = FixKind.CLEAN,
    val raw: Boolean = false,
    val glyph: String? = null,
    val reading: String? = null,
    val raise: Double = 0.0,
    val checked: Boolean = false,
)

/**
 * The correction layer of a 速記 stroke. The stroke's own samples are never touched; this holds,
 * for every sample, how far the corrected writing moved it ([dx], [dy], in page px), and the
 * stretches it was divided into. Holding the moves rather than positions is what lets the layer
 * ride along with the stroke: moving it changes nothing here, a rotation or resize turns the moves
 * with it ([transformed]), and a partial erase keeps the moves of what survives ([slice]).
 *
 * Immutable; every edit makes a new one.
 */
class StenoFix(
    val segments: List<FixSegment>,
    val dx: FloatArray,
    val dy: FloatArray,
) {
    val size: Int get() = dx.size

    /** Whether any stretch is shown as written. */
    val anyRaw: Boolean get() = segments.any { it.raw }

    /** Whether every stretch is shown as written. */
    val allRaw: Boolean get() = segments.all { it.raw }

    /** Whether sample [i] is shown as written. */
    fun isRaw(i: Int): Boolean = segments.firstOrNull { i in it.from..it.to }?.raw ?: true

    /** The index of the stretch holding sample [i], or -1. */
    fun segmentIndexAt(i: Int): Int = segments.indexOfFirst { i in it.from..it.to }

    /** This layer with stretch [index] shown as written ([raw]) or corrected. */
    fun withRaw(index: Int, raw: Boolean): StenoFix =
        StenoFix(segments.mapIndexed { k, s -> if (k == index) s.copy(raw = raw) else s }, dx, dy)

    /** This layer with every stretch shown as written ([raw]) or corrected. */
    fun withAllRaw(raw: Boolean): StenoFix = StenoFix(segments.map { it.copy(raw = raw) }, dx, dy)

    /** The layer of samples `[from, to)`, as a stroke cut out of this one carries it. */
    fun slice(from: Int, to: Int): StenoFix? {
        if (to <= from) return null
        val segs = segments.mapNotNull { s ->
            val a = maxOf(s.from, from)
            val b = minOf(s.to, to - 1)
            if (a > b) null else s.copy(from = a - from, to = b - from)
        }
        if (segs.isEmpty()) return null
        return StenoFix(segs, dx.copyOfRange(from, to), dy.copyOfRange(from, to))
    }

    /** The layer after the stroke went through the linear part `[a c; b d]` of a transform. */
    fun transformed(a: Double, b: Double, c: Double, d: Double): StenoFix {
        val nx = FloatArray(size)
        val ny = FloatArray(size)
        for (i in 0 until size) {
            nx[i] = (a * dx[i] + c * dy[i]).toFloat()
            ny[i] = (b * dx[i] + d * dy[i]).toFloat()
        }
        return StenoFix(segments, nx, ny)
    }

    companion object {
        /** A layer moving every sample of an [n]-sample stroke to ([x], [y]), as one [kind] stretch. */
        fun of(rawX: DoubleArray, rawY: DoubleArray, x: DoubleArray, y: DoubleArray, kind: FixKind = FixKind.CLEAN): StenoFix {
            val n = rawX.size
            return StenoFix(
                listOf(FixSegment(0, n - 1, kind)),
                FloatArray(n) { (x[it] - rawX[it]).toFloat() },
                FloatArray(n) { (y[it] - rawY[it]).toFloat() },
            )
        }
    }
}
