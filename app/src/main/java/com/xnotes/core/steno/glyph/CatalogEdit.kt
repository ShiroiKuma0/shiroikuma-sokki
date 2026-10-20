package com.xnotes.core.steno.glyph

/**
 * Merging and splitting catalogue elements (fork). Two elements that are one shape in the hand
 * merge into one, which keeps everything either was recognized by; one element that is really two
 * splits, the user's writings of it parted between them.
 */
object CatalogEdit {

    /**
     * [from] merged into [into]: [into] keeps its name and clean form, and takes [from]'s writings
     * and [from]'s clean form as further examples, so whatever matched [from] now matches it.
     */
    fun merge(from: Glyph, into: Glyph): Glyph {
        val form = from.shape.paths.maxByOrNull { it.size }?.takeIf { it.size >= 2 }?.let { p ->
            GlyphExample(DoubleArray(p.size) { p.x[it] - p.x[0] }, DoubleArray(p.size) { p.y[it] - p.y[0] }, p.y[0])
        }
        return into.copy(examples = into.examples + from.examples + listOfNotNull(form))
    }

    /**
     * [g] split in two: the writings at [take] go to a new element [newId] named [reading], the rest
     * stay. The new element's clean form is drawn from the first writing it takes, started at the
     * height [g]'s own form starts at; with none taken, it begins as a copy of [g]'s form.
     * Returns (what is left of [g], the new element).
     */
    fun split(g: Glyph, take: Set<Int>, newId: String, reading: String, category: GlyphCategory): Pair<Glyph, Glyph> {
        val taken = g.examples.filterIndexed { i, _ -> i in take }
        val kept = g.examples.filterIndexed { i, _ -> i !in take }
        val shape = taken.firstOrNull()?.let { e ->
            val y0 = g.shape.paths.maxByOrNull { it.size }?.y?.firstOrNull() ?: 0.0
            StenoEdit.shapeOf(e.x, DoubleArray(e.y.size) { e.y[it] + y0 })
        } ?: g.shape
        val made = Glyph(
            id = newId, reading = reading, category = category, shape = shape, source = if (taken.isEmpty()) g.source else null,
            reviewed = true, note = "", examples = taken,
        )
        return g.copy(examples = kept) to made
    }

    /** [g] without its writing [index]. */
    fun withoutExample(g: Glyph, index: Int): Glyph = g.copy(examples = g.examples.filterIndexed { i, _ -> i != index })

    /** Writing [index] of [from] moved to [to]: (new [from], new [to]). */
    fun moveExample(from: Glyph, index: Int, to: Glyph): Pair<Glyph, Glyph> {
        val e = from.examples.getOrNull(index) ?: return from to to
        return withoutExample(from, index) to to.copy(examples = to.examples + e)
    }
}
