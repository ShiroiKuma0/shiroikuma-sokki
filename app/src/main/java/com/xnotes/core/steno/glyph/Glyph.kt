package com.xnotes.core.steno.glyph

import com.xnotes.core.steno.trace.Cubic

/** What kind of element a glyph is — the catalogue groups by it. */
enum class GlyphCategory(val id: String) {
    CONSONANT("consonant"),
    HAIRLINE("hairline"),
    CLUSTER("cluster"),
    SYMBOL("symbol"),
    PREFIX("prefix"),
    ENDING("ending"),
    MARK("mark"),
    OWN("own");

    companion object {
        fun fromId(id: String?): GlyphCategory = entries.firstOrNull { it.id == id } ?: OWN
    }
}

/**
 * One continuous pen path of an element, in writing units: x from the element's left edge, y up
 * from the base line, one unit being one step (base line to the line above it). [curves] are the
 * clean Béziers; [x]/[y] sample them densely for drawing and matching, and [shade] says per sample
 * how far the print swelled there (0 the hairline, 1 three times it) — where a written a shades it.
 */
class GlyphPath(
    val curves: List<Cubic>,
    val x: DoubleArray,
    val y: DoubleArray,
    val shade: FloatArray,
    val closed: Boolean,
) {
    val size: Int get() = x.size
}

/** An element's clean form: its paths in writing order. */
class GlyphShape(val paths: List<GlyphPath>) {

    val isEmpty: Boolean get() = paths.all { it.size == 0 }

    val minX: Double get() = paths.minOfOrNull { p -> p.x.minOrNull() ?: 0.0 } ?: 0.0
    val maxX: Double get() = paths.maxOfOrNull { p -> p.x.maxOrNull() ?: 0.0 } ?: 0.0
    val minY: Double get() = paths.minOfOrNull { p -> p.y.minOrNull() ?: 0.0 } ?: 0.0
    val maxY: Double get() = paths.maxOfOrNull { p -> p.y.maxOrNull() ?: 0.0 } ?: 0.0

    companion object {
        /** The hairline's width, in units; shaded ink is three times it. */
        const val HAIR = 0.05

        /** Ink width at shade [s] (0..1), in units. */
        fun width(s: Float): Double = HAIR * (1.0 + 2.0 * s.coerceIn(0f, 1f))
    }
}

/**
 * Where an element was traced: a label for the scan (its file name), the PDF page when it was one,
 * and the step and base line marked on it. The crop kept beside the glyph is described by
 * [cropBase]/[cropStep] in its own pixels, so the detail view can lay the print under the form.
 */
class GlyphSource(
    val label: String,
    val page: Int?,
    val cropBase: Double,
    val cropStep: Double,
    val cropLeft: Double,
)

/** A catalogue element. */
data class Glyph(
    val id: String,
    val reading: String,
    val category: GlyphCategory,
    val shape: GlyphShape,
    val source: GlyphSource?,
    /** False for seeded forms not yet looked at; only reviewed ones are used for correction. */
    val reviewed: Boolean = true,
    val note: String = "",
    val created: Long = 0L,
    val modified: Long = 0L,
    /** The user's own writings of it, as assigned in the review: polylines in units from the
     *  writing's start. Recognition matches against these as well as the clean form. */
    val examples: List<GlyphExample> = emptyList(),
    /** Written only at the start of a word (z vlasové). */
    val atStart: Boolean = false,
    /** The heights its bottom may sit at, in units above the base line, when it has fixed places
     *  (z vlasové: −0.5 or 2); empty when it sits where its form does, raised as the hand raises it. */
    val places: List<Double> = emptyList(),
)

/**
 * One writing of an element by the user, in units: x from its start, y up from its start, read as
 * the recognizer reads writing (at the book's slant, the hand's size mapped onto the book's). [y0]
 * is the height its start stood at above the base line (NaN for writings taught before it was
 * kept), so it is matched on its lines like the clean form. [origin] says where it was written:
 * [DRILL], or `note:<created>` for a note's writing, which the corpus gate leaves out when it
 * scores that very note.
 */
class GlyphExample(val x: DoubleArray, val y: DoubleArray, val y0: Double = Double.NaN, val origin: String = "") {
    companion object {
        /** Written on the catalogue's drill pad. */
        const val DRILL = "drill"

        /** The origin of writing in a note created at [created] (epoch ms). */
        fun ofNote(created: Long?): String = if (created == null) "" else "note:$created"
    }
}
