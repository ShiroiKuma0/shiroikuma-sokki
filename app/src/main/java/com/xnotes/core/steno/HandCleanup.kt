package com.xnotes.core.steno

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/**
 * The one correction 速記 writing gets: the hand steadied, and nothing else. The writing keeps
 * its own slant, size and shapes — no element is recognized or redrawn. Every sample keeps its
 * index, so the result is a per-sample move ([StenoFix]) and the stroke's shading, which comes
 * from its pressure, is untouched.
 *
 * The path is smoothed over arc length, piece by piece between its corners, so the wobble of a
 * long line goes but a corner stays sharp; each piece keeps its two ends exactly. Small loops
 * (knots) are left exactly as written: the 速記 pen redraws them itself.
 */
object HandCleanup {

    /** How strongly the hand is steadied, 0..1. */
    data class Params(val steady: Double = 0.6)

    /** The unit assumed when a page has no lines to measure one from, in page px. */
    const val FALLBACK_UNIT = 15.0

    /** Widest smoothing, in units, at full steadiness. */
    const val STEADY_SIGMA = 0.06

    /** Turning, in degrees, that makes a corner the smoothing must not round off. */
    const val CORNER_DEGREES = 55.0

    /** The reach, in units, over which a corner's turning is measured. */
    const val CORNER_REACH = 0.2


    /** The corrected positions of the samples ([rx], [ry]). */
    fun clean(rx: DoubleArray, ry: DoubleArray, frame: LineFrame?, p: Params = Params()): Pair<DoubleArray, DoubleArray> {
        val n = rx.size
        if (n < 3) return rx.copyOf() to ry.copyOf()
        val unit = frame?.unit ?: FALLBACK_UNIT
        var x = rx.copyOf()
        var y = ry.copyOf()
        if (p.steady > 0.0) {
            val s = steady(x, y, unit, p.steady)
            x = s.first; y = s.second
        }
        return x to y
    }

    /** The correction layer for a stroke ([rx], [ry]): the steadied hand, as one stretch. */
    fun fix(rx: DoubleArray, ry: DoubleArray, frame: LineFrame?, p: Params = Params()): StenoFix {
        val (cx, cy) = clean(rx, ry, frame, p)
        return StenoFix.of(rx, ry, cx, cy)
    }

    // --- 1. steady ---

    private fun steady(x: DoubleArray, y: DoubleArray, unit: Double, strength: Double): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        // Lengths and corners are read off a lightly averaged copy of the path: a tremor faster than
        // the samples would otherwise turn every zigzag into a corner and inflate the length the
        // smoothing is measured in.
        val ax = average(x, PRE_AVERAGE)
        val ay = average(y, PRE_AVERAGE)
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(ax[i] - ax[i - 1], ay[i] - ay[i - 1])
        val loops = smallLoops(x, y, cum, unit)
        val cuts = (corners(ax, ay, cum, unit).toList() + loops.flatMap { listOf(it.first, it.last) }).distinct().sorted().toIntArray()
        val sigma = STEADY_SIGMA * unit * strength.coerceIn(0.0, 1.0)
        if (sigma <= 1e-9) return x to y
        val ox = x.copyOf()
        val oy = y.copyOf()
        for (k in 0 until cuts.size - 1) {
            // A piece inside a knot keeps the hand's own points.
            if (loops.any { cuts[k] >= it.first && cuts[k + 1] <= it.last }) continue
            smoothPiece(x, y, cum, cuts[k], cuts[k + 1], sigma, ox, oy)
        }
        return ox to oy
    }

    /** Widest loop, in units, left alone as a knot. */
    const val LOOP_SIZE = 0.35

    /** How far a knot turns, at least. */
    const val LOOP_TURN = 1.7 * PI

    /**
     * The small loops of the stroke: stretches that turn a full circle within [LOOP_SIZE] units.
     * Read over a short chord, as [com.xnotes.core.stroke.StenoEngine] reads its knots, so writing
     * zoomed in (a fraction of a px per sample) still turns as drawn.
     */
    private fun smallLoops(x: DoubleArray, y: DoubleArray, cum: DoubleArray, unit: Double): List<IntRange> {
        val n = x.size
        val size = LOOP_SIZE * unit
        val chord = size * 0.15
        val head = DoubleArray(n)
        var a = 0.0
        var known = false
        var b = 0
        for (k in 0 until n) {
            if (b < k) b = k
            while (b < n - 1 && hypot(x[b] - x[k], y[b] - y[k]) < chord) b++
            if (hypot(x[b] - x[k], y[b] - y[k]) >= 0.5 * chord) {
                a = kotlin.math.atan2(y[b] - y[k], x[b] - x[k])
                if (!known) { known = true; for (m in 0 until k) head[m] = a }
            }
            if (k == 0) { head[0] = a; continue }
            var d = a - head[k - 1]
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            head[k] = head[k - 1] + d
        }
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < n) {
            var minX = x[i]; var maxX = x[i]; var minY = y[i]; var maxY = y[i]
            var j = i + 1
            var found = -1
            while (j < n && cum[j] - cum[i] <= PI * size) {
                minX = minOf(minX, x[j]); maxX = max(maxX, x[j]); minY = minOf(minY, y[j]); maxY = max(maxY, y[j])
                if (maxX - minX > size || maxY - minY > size) break
                if (abs(head[j] - head[i]) >= LOOP_TURN) { found = j; break }
                j++
            }
            if (found < 0) { i++; continue }
            // Widen by a chord either side, so the smoothing next to the knot can't pull it apart.
            var from = i
            while (from > 0 && hypot(x[from - 1] - x[i], y[from - 1] - y[i]) < chord) from--
            var to = found
            while (to < n - 1 && hypot(x[to + 1] - x[found], y[to + 1] - y[found]) < chord) to++
            out += from..to
            i = to + 1
        }
        return out
    }

    /** Samples either side the lengths and corners are averaged over. */
    const val PRE_AVERAGE = 3

    /** [v] averaged over [r] samples either side (fewer at the ends, so the ends stay put). */
    private fun average(v: DoubleArray, r: Int): DoubleArray {
        val n = v.size
        return DoubleArray(n) { i ->
            val k = minOf(r, i, n - 1 - i)
            var sum = 0.0
            for (j in i - k..i + k) sum += v[j]
            sum / (2 * k + 1)
        }
    }

    /** The sample indices that bound the pieces: both ends and every sharp corner between. */
    private fun corners(x: DoubleArray, y: DoubleArray, cum: DoubleArray, unit: Double): IntArray {
        val n = x.size
        val reach = CORNER_REACH * unit
        val turn = DoubleArray(n)
        var a = 0
        var b = 0
        for (i in 0 until n) {
            while (a < i && cum[i] - cum[a + 1] >= reach) a++
            if (b < i) b = i
            while (b < n - 1 && cum[b] - cum[i] < reach) b++
            if (a == i || b == i) continue
            val ux = x[i] - x[a]; val uy = y[i] - y[a]
            val vx = x[b] - x[i]; val vy = y[b] - y[i]
            val lu = hypot(ux, uy); val lv = hypot(vx, vy)
            if (lu < 1e-9 || lv < 1e-9) continue
            turn[i] = acos(((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1.0, 1.0)) * 180.0 / PI
        }
        val out = ArrayList<Int>()
        out += 0
        var i = 1
        while (i < n - 1) {
            if (turn[i] >= CORNER_DEGREES) {
                // The sharpest point of this run of turning is the corner.
                var j = i
                var best = i
                while (j < n - 1 && turn[j] >= CORNER_DEGREES) { if (turn[j] > turn[best]) best = j; j++ }
                if (best > out.last()) out += best
                i = j
            } else i++
        }
        if (out.last() != n - 1) out += n - 1
        return out.toIntArray()
    }

    /**
     * Gaussian smoothing over arc length of samples [from]..[to], kept within the piece, then bent
     * back so the piece's two ends land exactly where they were: the corner they meet stays put.
     */
    private fun smoothPiece(
        x: DoubleArray, y: DoubleArray, cum: DoubleArray, from: Int, to: Int, sigma: Double,
        ox: DoubleArray, oy: DoubleArray,
    ) {
        if (to - from < 2) return
        val reach = 3.0 * sigma
        val sx = DoubleArray(to - from + 1)
        val sy = DoubleArray(to - from + 1)
        var lo = from
        var hi = from
        for (i in from..to) {
            while (lo < i && cum[i] - cum[lo] > reach) lo++
            if (hi < i) hi = i
            while (hi < to && cum[hi + 1] - cum[i] <= reach) hi++
            var w = 0.0; var ax = 0.0; var ay = 0.0
            for (k in lo..hi) {
                val d = (cum[k] - cum[i]) / sigma
                // Weighted by the travel each sample stands for, so a cluster of samples where the pen
                // slowed counts no more than the same length drawn quickly.
                val step = 0.5 * ((if (k < to) cum[k + 1] - cum[k] else 0.0) + (if (k > from) cum[k] - cum[k - 1] else 0.0))
                val wk = exp(-0.5 * d * d) * max(step, 1e-6)
                w += wk; ax += wk * x[k]; ay += wk * y[k]
            }
            sx[i - from] = ax / w
            sy[i - from] = ay / w
        }
        val len = cum[to] - cum[from]
        val ex0 = x[from] - sx[0]; val ey0 = y[from] - sy[0]
        val ex1 = x[to] - sx[to - from]; val ey1 = y[to] - sy[to - from]
        for (i in from..to) {
            val t = if (len > 1e-9) (cum[i] - cum[from]) / len else 0.0
            ox[i] = sx[i - from] + ex0 + (ex1 - ex0) * t
            oy[i] = sy[i - from] + ey0 + (ey1 - ey0) * t
        }
    }
}
