package com.xnotes.core.steno.glyph

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Words composed from catalogue elements by rule, never traced (白い熊, 2026-10-13).
 *
 * - **Shading** means a: a shaded element swells to three times the hairline on its downstrokes.
 * - Each element starts where the previous one ended; consecutive elements meet with a tangent
 *   (Hermite) bridge, so no hand-traced junction exists.
 * - **A hairline continuing out of a shaded stroke shares that stroke's outer edge**: it starts
 *   (3·hair − hair)/2 off the shaded stroke's centre line, on the side it leaves towards, so it
 *   copies the thick stroke's edge and adds nothing to its width.
 * - A **straight hairline** (vlasové t, v, z) keeps its own slant and runs from the previous exit to
 *   where the next element sits correctly: it stops so the next element's top lands on the line the
 *   hairline itself reaches (s hanging from 2.5 in “dát se”).
 * - A **closed** element (s) is entered at the point whose tangent best continues the line coming in.
 * - **An element ending in a straight line is left at a sharp corner** (白い熊, 2026-10-17): t's
 *   stem meets the o rising out of it in a literal v, never a rounded bridge.
 * - **A straight hairline runs straight onto the next element's hook** (白い熊, 2026-10-17): it
 *   meets the hook where the hook's direction is its own, and the hook before that point is
 *   dropped — o into t turns only clockwise, never back the other way first.
 *
 * All in writing units: x from the word's start, y up from the base line.
 */
object Composer {

    /** One element of a word: [shaded] carries an a; [raise] lifts it (in steps) for i, y, u. */
    class Part(val shape: GlyphShape, val shaded: Boolean = false, val raise: Double = 0.0)

    /** A composed stroke: centre line and per-point ink width, in units. */
    class Ink(val x: DoubleArray, val y: DoubleArray, val w: DoubleArray) {
        val size: Int get() = x.size
    }

    private const val STEP = 0.01
    private const val GAP = 0.14
    private const val EDGE_GAP = 0.06

    /** The length, in units, an element's end must run straight to be left at a corner. */
    private const val STRAIGHT_END = 0.15

    fun compose(parts: List<Part>): Ink = composeMarked(parts).first

    /**
     * As [compose], with where each part lies in the ink: part k spans ink points
     * bounds[k]..bounds[k + 1] (each joint split at the middle of its bridge).
     *
     * [onLines]: every element sits at its own textbook height (plus its raise) and the bridge
     * takes up the difference — writing redrawn from a hand, where each element belongs between its
     * lines, not wherever the one before happened to end. An element after a straight hairline
     * still hangs from the hairline's end: there the hairline itself sets the height.
     */
    fun composeMarked(parts: List<Part>, onLines: Boolean = false): Pair<Ink, IntArray> {
        if (parts.isEmpty()) return Ink(DoubleArray(0), DoubleArray(0), DoubleArray(0)) to IntArray(1)
        val placed = ArrayList<Pts>()
        // sharp[k]: part k meets the one before it directly, at a corner or along a tangent.
        val sharp = BooleanArray(parts.size)
        var afterHairline = false
        var prev: Pts? = null
        for ((k, part) in parts.withIndex()) {
            var p = Pts.of(part.shape).also { it.shade(part.shaded) }
            p.lift(part.raise)
            val next = parts.getOrNull(k + 1)
            if (prev == null) {
                p.translate(-p.x[0], 0.0)
            } else {
                val inDir = prev.endTangent()
                if (p.closed) p = p.enteredAlong(inDir)
                var start = doubleArrayOf(prev.x.last(), prev.y.last())
                // Leaving a shaded stroke — the previous element ends on it, or closes onto it (d's
                // loop closing onto its stem): start on its outer edge, not its centre line.
                val thick = prev.shadedNear(start[0], start[1], 0.2)
                if (thick >= 0 && p.w[0] < 1.5 * HAIR) {
                    val t = prev.tangentAround(thick)
                    var dir = p.startTangent()
                    if (p.isStraight() && dir[1] < 0) dir = doubleArrayOf(-dir[0], -dir[1])
                    var n = doubleArrayOf(-t[1], t[0])
                    if (n[0] * dir[0] + n[1] * dir[1] < 0) n = doubleArrayOf(-n[0], -n[1])
                    val off = (prev.w[thick] - p.w[0]) / 2
                    start = doubleArrayOf(prev.x[thick] + n[0] * off, prev.y[thick] + n[1] * off)
                }
                if (p.isStraight()) {
                    // Keep the slant, run from the exit to where the next element sits right.
                    var d = p.startTangent()
                    if (d[1] < 0) d = doubleArrayOf(-d[0], -d[1])
                    val reach = p.y.maxOrNull()!!.coerceAtLeast(p.y[0])
                    val rise = next?.let { nx -> Pts.of(nx.shape).let { it.y.max() - it.y[0] } } ?: 0.0
                    val target = reach - rise
                    val len = if (abs(d[1]) > 1e-6) (target - start[1]) / d[1] else hypot(p.x.last() - p.x[0], p.y.last() - p.y[0])
                    p = Pts.line(start, d, max(0.1, len), p.w[0])
                } else if (onLines && !afterHairline) {
                    p.translate(start[0] - p.x[0], 0.0)
                } else {
                    p.translate(start[0] - p.x[0], start[1] - p.y[0])
                }
                if (prev.isStraight() && !p.isStraight() && !p.closed) {
                    // The hairline runs straight onto the hook, where the hook turns to its direction.
                    val s0x = prev.x[0]
                    val s0y = prev.y[0]
                    val d = prev.startTangent()
                    val e = p.tangentEntry(d)
                    if (e > 0 && abs(d[1]) > 1e-3) {
                        val len = (p.y[e] - s0y) / d[1]
                        if (len > 0.1) {
                            p.translate(s0x + d[0] * len - p.x[e], 0.0)
                            p = p.from(e)
                            placed[placed.size - 1] = Pts.line(doubleArrayOf(s0x, s0y), d, len, prev.w[0])
                            sharp[k] = true
                        }
                    }
                }
                if (prev.endsStraight() && !p.closed) sharp[k] = true
            }
            afterHairline = prev != null && p.isStraight()
            placed += p
            prev = p
        }
        // Join them, each pair with a tangent bridge.
        var acc = placed[0]
        val bounds = IntArray(placed.size + 1)
        for (i in 1 until placed.size) {
            val a = acc
            val b = placed[i]
            if (sharp[i]) {
                // Straight on: the corner (or the tangent point) is the joint itself.
                val same = hypot(b.x[0] - a.x.last(), b.y[0] - a.y.last()) < 1e-6
                val from = if (same) 1 else 0
                acc = Pts(
                    a.x + b.x.copyOfRange(from, b.size), a.y + b.y.copyOfRange(from, b.size),
                    a.w + b.w.copyOfRange(from, b.size), false,
                )
                bounds[i] = a.size - 1
                continue
            }
            val gap = if (abs(a.w.last() - b.w[0]) > 0.5 * HAIR) EDGE_GAP else GAP
            val (joined, mid) = Pts.join(a, b, gap)
            acc = joined
            bounds[i] = mid
        }
        bounds[placed.size] = acc.size - 1
        return Ink(acc.x, acc.y, acc.w) to bounds
    }

    private const val HAIR = GlyphShape.HAIR

    /** A working polyline with widths. */
    private class Pts(var x: DoubleArray, var y: DoubleArray, var w: DoubleArray, val closed: Boolean) {
        val size get() = x.size

        fun translate(dx: Double, dy: Double) {
            for (i in x.indices) { x[i] += dx; y[i] += dy }
        }

        fun lift(dy: Double) = translate(0.0, dy)

        /** Shaded: 3× hair on downstrokes (or the print's own swell, where it has one); else hair. */
        fun shade(on: Boolean) {
            if (!on) { for (i in w.indices) w[i] = HAIR; return }
            val down = DoubleArray(size) { i ->
                val a = y[max(0, i - 2)]
                val b = y[min(size - 1, i + 2)]
                if (b - a < -0.004) 1.0 else 0.0
            }
            val sm = smooth(down, 6.0)
            for (i in w.indices) w[i] = HAIR * (1.0 + 2.0 * max(sm[i], (w[i] / HAIR - 1.0) / 2.0).coerceIn(0.0, 1.0))
        }

        /** The shaded point (wider than 1.5 hair) nearest ([px], [py]) within [r], or -1. */
        fun shadedNear(px: Double, py: Double, r: Double): Int {
            var best = -1
            var bd = r
            for (i in x.indices) {
                if (w[i] <= 1.5 * HAIR) continue
                val d = hypot(x[i] - px, y[i] - py)
                if (d < bd) { bd = d; best = i }
            }
            return best
        }

        fun tangentAround(i: Int): DoubleArray {
            val a = max(0, i - 3)
            val b = min(size - 1, i + 3)
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val n = hypot(dx, dy).coerceAtLeast(1e-9)
            return doubleArrayOf(dx / n, dy / n)
        }

        fun startTangent(): DoubleArray = tangentAt(0, 1)
        fun endTangent(): DoubleArray = tangentAt(size - 1, -1)

        private fun tangentAt(i: Int, dirSign: Int): DoubleArray {
            var j = i
            var len = 0.0
            while (len < 0.12 && j + dirSign in 0 until size) {
                len += hypot(x[j + dirSign] - x[j], y[j + dirSign] - y[j])
                j += dirSign
            }
            val dx = (x[j] - x[i]) * dirSign
            val dy = (y[j] - y[i]) * dirSign
            val n = hypot(dx, dy).coerceAtLeast(1e-9)
            return doubleArrayOf(dx / n, dy / n)
        }

        /** Whether it ends in a straight line: its last [STRAIGHT_END] units on their chord. */
        fun endsStraight(): Boolean {
            if (size < 3) return false
            var j = size - 1
            var len = 0.0
            while (j > 0 && len < STRAIGHT_END) { len += hypot(x[j] - x[j - 1], y[j] - y[j - 1]); j-- }
            if (len < STRAIGHT_END * 0.8) return false
            val dx = x.last() - x[j]
            val dy = y.last() - y[j]
            val n = hypot(dx, dy).coerceAtLeast(1e-9)
            for (i in j..size - 1) if (abs((x[i] - x[j]) * dy - (y[i] - y[j]) * dx) / n > 0.01) return false
            return true
        }

        /**
         * Where, within its first half, its direction first turns clockwise onto [d] (a line coming
         * in) — the point a straight line runs onto its hook; -1 when it never does.
         */
        fun tangentEntry(d: DoubleArray): Int {
            var len = 0.0
            val total = (1 until size).sumOf { hypot(x[it] - x[it - 1], y[it] - y[it - 1]) }
            var i = 0
            var wasLeft = false
            while (i < size - 2 && len < total / 2) {
                val tx = x[i + 2] - x[i]
                val ty = y[i + 2] - y[i]
                val tn = hypot(tx, ty).coerceAtLeast(1e-9)
                val t = doubleArrayOf(tx / tn, ty / tn)
                val cross = d[0] * t[1] - d[1] * t[0]
                val dot = d[0] * t[0] + d[1] * t[1]
                if (cross > 0) wasLeft = true
                else if (wasLeft && dot > 0) return i
                len += hypot(x[i + 1] - x[i], y[i + 1] - y[i])
                i++
            }
            return -1
        }

        /** It from point [i] on. */
        fun from(i: Int): Pts = Pts(x.copyOfRange(i, size), y.copyOfRange(i, size), w.copyOfRange(i, size), false)

        fun isStraight(): Boolean {
            if (closed || size < 3) return false
            val dx = x.last() - x[0]
            val dy = y.last() - y[0]
            val n = hypot(dx, dy)
            if (n < 0.5) return false
            var worst = 0.0
            for (i in x.indices) worst = max(worst, abs((x[i] - x[0]) * dy - (y[i] - y[0]) * dx) / n)
            return worst < 0.03 * n
        }

        /** This closed path restarted at the point whose direction best continues [dir]. */
        fun enteredAlong(dir: DoubleArray): Pts {
            val m = size - 1 // last repeats the first
            var best = 0
            var bestDot = -2.0
            for (i in 0 until m) {
                val dx = x[(i + 1) % m] - x[i]
                val dy = y[(i + 1) % m] - y[i]
                val n = hypot(dx, dy).coerceAtLeast(1e-9)
                val d = (dx * dir[0] + dy * dir[1]) / n
                if (d > bestDot) { bestDot = d; best = i }
            }
            val idx = IntArray(m + 1) { (best + it) % m }
            return Pts(DoubleArray(m + 1) { x[idx[it]] }, DoubleArray(m + 1) { y[idx[it]] }, DoubleArray(m + 1) { w[idx[it]] }, true)
        }

        companion object {
            fun of(s: GlyphShape): Pts {
                val p = s.paths.maxByOrNull { it.size } ?: return Pts(DoubleArray(0), DoubleArray(0), DoubleArray(0), false)
                return Pts(p.x.copyOf(), p.y.copyOf(), DoubleArray(p.size) { GlyphShape.width(p.shade[it]) }, p.closed)
            }

            fun line(start: DoubleArray, d: DoubleArray, len: Double, w: Double): Pts {
                val n = max(2, (len / STEP).toInt())
                return Pts(
                    DoubleArray(n + 1) { start[0] + d[0] * len * it / n },
                    DoubleArray(n + 1) { start[1] + d[1] * len * it / n },
                    DoubleArray(n + 1) { w }, false,
                )
            }

            /** [a] then [b], both trimmed by [gap] at the joint and bridged with matching tangents;
             *  with the index of the bridge's middle. */
            fun join(a: Pts, b: Pts, gap: Double): Pair<Pts, Int> {
                val a2 = a.trimEnd(gap)
                val b2 = b.trimStart(gap)
                val t0 = a2.endTangent()
                val t1 = b2.startTangent()
                val p0x = a2.x.last(); val p0y = a2.y.last()
                val p1x = b2.x[0]; val p1y = b2.y[0]
                val l = hypot(p1x - p0x, p1y - p0y)
                val n = max(4, (l / STEP).toInt())
                val bx = DoubleArray(n - 1)
                val by = DoubleArray(n - 1)
                val bw = DoubleArray(n - 1)
                for (k in 1 until n) {
                    val s = k.toDouble() / n
                    val h00 = 2 * s * s * s - 3 * s * s + 1
                    val h10 = s * s * s - 2 * s * s + s
                    val h01 = -2 * s * s * s + 3 * s * s
                    val h11 = s * s * s - s * s
                    bx[k - 1] = h00 * p0x + h10 * l * t0[0] + h01 * p1x + h11 * l * t1[0]
                    by[k - 1] = h00 * p0y + h10 * l * t0[1] + h01 * p1y + h11 * l * t1[1]
                    bw[k - 1] = a2.w.last() + (b2.w[0] - a2.w.last()) * s
                }
                return Pts(a2.x + bx + b2.x, a2.y + by + b2.y, a2.w + bw + b2.w, false) to (a2.size + (n - 1) / 2)
            }

            private fun smooth(v: DoubleArray, sigma: Double): DoubleArray {
                val r = (3 * sigma).toInt()
                return DoubleArray(v.size) { i ->
                    var s = 0.0
                    var ws = 0.0
                    for (j in -r..r) {
                        val k = (i + j).coerceIn(0, v.size - 1)
                        val g = kotlin.math.exp(-j * j / (2 * sigma * sigma))
                        s += g * v[k]; ws += g
                    }
                    s / ws
                }
            }
        }

        fun trimEnd(len: Double): Pts {
            var acc = 0.0
            var i = size - 1
            while (i > 1 && acc < len) { acc += hypot(x[i] - x[i - 1], y[i] - y[i - 1]); i-- }
            return Pts(x.copyOfRange(0, i + 1), y.copyOfRange(0, i + 1), w.copyOfRange(0, i + 1), false)
        }

        fun trimStart(len: Double): Pts {
            var acc = 0.0
            var i = 0
            while (i < size - 2 && acc < len) { acc += hypot(x[i + 1] - x[i], y[i + 1] - y[i]); i++ }
            return Pts(x.copyOfRange(i, size), y.copyOfRange(i, size), w.copyOfRange(i, size), closed)
        }
    }
}
