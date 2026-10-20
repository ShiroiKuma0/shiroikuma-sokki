package com.xnotes.core.steno

/**
 * Where the hand habitually puts the base line and the 1 line (fork). A hand writing “between the
 * lines” lands its bottoms a little above the base line and its tops a little under the 1 line;
 * the textbook's elements touch both. Read over the last strokes — the bottoms and tops of their
 * turns near each line — the two heights map the hand onto the book's, so every element is read,
 * and redrawn, at the textbook's size.
 */
object HandSize {

    /** Turns remembered, at each line. */
    private const val KEEP = 60

    /** Turns needed at each line before the habit is trusted. */
    private const val ENOUGH = 3

    /** How far, in units, a turn must swing either side to count. */
    private const val SWING = 0.1

    private val lows = ArrayDeque<Double>()
    private val highs = ArrayDeque<Double>()

    /** Remembers a written stroke: [h] its heights above its base line, in units. */
    @Synchronized
    fun add(h: DoubleArray) {
        for (k in turns(h)) {
            val v = h[k]
            val isLow = (k == 0 || h[k] <= h[k - 1]) && (k == h.size - 1 || h[k] <= h[k + 1])
            if (isLow && v in -0.5..0.6) lows.addLast(v)
            if (!isLow && v in 0.6..1.4) highs.addLast(v)
        }
        while (lows.size > KEEP) lows.removeFirst()
        while (highs.size > KEEP) highs.removeFirst()
    }

    /** The hand's base line and 1 line, in units, or null while too little has been written. */
    @Synchronized
    fun estimate(): Pair<Double, Double>? {
        if (lows.size < ENOUGH || highs.size < ENOUGH) return null
        val lo = lows.sorted()[lows.size / 2]
        val hi = highs.sorted()[highs.size / 2]
        return if (hi - lo in 0.5..1.6) lo to hi else null
    }

    @Synchronized
    fun clear() { lows.clear(); highs.clear() }

    /** The indices where [h] turns (and its ends), each swinging at least [SWING] to both sides. */
    private fun turns(h: DoubleArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        val n = h.size
        if (n < 3) return out
        // Walk the extremes, keeping only swings of SWING or more.
        var last = 0
        var dir = 0
        for (k in 1 until n) {
            val d = h[k] - h[last]
            if (dir >= 0 && d > 0 || dir <= 0 && d < 0) {
                if (dir == 0 && kotlin.math.abs(d) >= SWING) { dir = if (d > 0) 1 else -1; out += 0 }
                if (dir != 0) last = k
                continue
            }
            if (kotlin.math.abs(d) >= SWING) {
                out += last
                dir = if (d > 0) 1 else -1
                last = k
            }
            i++
        }
        if (dir != 0) out += last
        return out.distinct()
    }
}
