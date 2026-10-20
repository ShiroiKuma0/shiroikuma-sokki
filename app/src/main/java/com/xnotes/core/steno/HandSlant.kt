package com.xnotes.core.steno

/**
 * The slant the hand habitually writes at (fork), read over the last strokes rather than from each
 * one: a single element's downstrokes lean however its form leans (the book's own range from −28°
 * to +31°), so only many strokes together tell the hand's habit. The catalogue reads writing
 * sheared from this habit to the book's slant.
 */
object HandSlant {

    /** Strokes remembered. */
    private const val KEEP = 40

    /** Downstroke, in units, needed before the habit is trusted over a stroke's own slant. One
     *  element's downstrokes lean however its form leans, so a single stroke is never enough. */
    private const val ENOUGH = 4.0

    /** Strokes needed, for the same reason. */
    private const val MIN_STROKES = 5

    private val seen = ArrayDeque<Pair<Double, Double>>()

    /** Remembers a written stroke ([x], [y], page px) of the given [unit]. */
    @Synchronized
    fun add(x: DoubleArray, y: DoubleArray, unit: Double) {
        val k = HandCleanup.slantOf(x, y, unit) ?: return
        seen.addLast(k to HandCleanup.slantRun(x, y, unit))
        while (seen.size > KEEP) seen.removeFirst()
    }

    /** The habit (forward lean per unit of height): the length-weighted median, or null while too
     *  little has been written to tell. */
    @Synchronized
    fun estimate(): Double? {
        val total = seen.sumOf { it.second }
        if (total < ENOUGH || seen.size < MIN_STROKES) return null
        var acc = 0.0
        for ((k, w) in seen.sortedBy { it.first }) {
            acc += w
            if (acc >= total / 2) return k
        }
        return null
    }

    @Synchronized
    fun clear() = seen.clear()
}
