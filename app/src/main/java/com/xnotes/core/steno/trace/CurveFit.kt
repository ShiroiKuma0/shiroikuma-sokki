package com.xnotes.core.steno.trace

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/** A cubic Bézier: (x0, y0, x1, y1, x2, y2, x3, y3). */
typealias Cubic = DoubleArray

/** A polyline as parallel coordinate arrays. */
class Poly(val x: DoubleArray, val y: DoubleArray) {
    val size: Int get() = x.size

    fun arc(): DoubleArray {
        val d = DoubleArray(size)
        for (i in 1 until size) d[i] = d[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        return d
    }

    fun slice(a: Int, b: Int) = Poly(x.copyOfRange(a, b + 1), y.copyOfRange(a, b + 1))

    /** Resampled at equal arc length [step]. */
    fun resampled(step: Double): Poly {
        val d = arc()
        if (size < 2 || d.last() <= 0) return this
        val n = (d.last() / step).toInt() + 1
        val ox = DoubleArray(n)
        val oy = DoubleArray(n)
        var j = 0
        for (k in 0 until n) {
            val t = k * step
            while (j < size - 2 && d[j + 1] < t) j++
            val seg = d[j + 1] - d[j]
            val f = if (seg > 0) ((t - d[j]) / seg).coerceIn(0.0, 1.0) else 0.0
            ox[k] = x[j] + f * (x[j + 1] - x[j])
            oy[k] = y[j] + f * (y[j + 1] - y[j])
        }
        return Poly(ox, oy)
    }

    /** Gaussian-smoothed with the ends held (reflected padding, so the ends do not drift). */
    fun smoothed(sigma: Double): Poly {
        if (size < 5 || sigma < 0.3) return this
        return Poly(gauss(x, sigma), gauss(y, sigma))
    }

    companion object {
        fun gauss(v: DoubleArray, sigma: Double, hold: Boolean = true): DoubleArray {
            val n = v.size
            val r = (3 * sigma).toInt().coerceAtLeast(1)
            val k = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
            val ks = k.sum()
            fun at(i: Int): Double = when {
                !hold -> v[i.coerceIn(0, n - 1)]
                i < 0 -> 2 * v[0] - v[(-i).coerceAtMost(n - 1)]
                i >= n -> 2 * v[n - 1] - v[(2 * (n - 1) - i).coerceAtLeast(0)]
                else -> v[i]
            }
            return DoubleArray(n) { i ->
                var s = 0.0
                for (j in -r..r) s += k[j + r] * at(i + j)
                s / ks
            }
        }
    }
}

/**
 * The clean form of a centre-line route: corners kept only where the line truly reverses, straight
 * stretches made exact lines, and everything else the fewest cubic Béziers within a tight
 * tolerance (Schneider, Graphics Gems 1990). All lengths are in fractions of [step], the writing
 * step in px, so the same rules hold at any scan resolution.
 */
class CurveFit(private val step: Double) {

    class Result(val curves: List<Cubic>, val breaks: List<Int>)

    fun fit(route: Poly): Result {
        if (route.size < 2) return Result(emptyList(), emptyList())
        if (route.size < 4) return Result(listOf(line(route.x[0], route.y[0], route.x.last(), route.y.last())), emptyList())
        val look = route.smoothed(3.0)
        val corners = corners(look, 0.09 * step, CORNER_TURN)
        var cs = (listOf(0) + corners + listOf(route.size - 1)).distinct().sorted()
        // Fair each corner-to-corner run on its own, ends pinned, so corners stay sharp.
        val px = route.x.copyOf()
        val py = route.y.copyOf()
        for ((a, b) in cs.zipWithNext()) {
            if (b - a < 8) continue
            val s = route.slice(a, b).smoothed(4.0)
            s.x.copyInto(px, a)
            s.y.copyInto(py, a)
        }
        val p = Poly(px, py)
        var runs = straightRuns(p)
        runs = dropShallowNeighbours(p, runs)
        runs = trimRuns(p, runs)
        for ((a, b) in runs) project(p, a, b)
        val ends = runs.flatMap { listOf(it.first, it.second) }.toSet()
        cs = mergeClose(p, (cs + ends).distinct().sorted(), ends)
        val isRun = runs.filter { it.first in cs && it.second in cs }.toSet()
        val curves = ArrayList<Cubic>()
        val pieces = cs.zipWithNext()
        for ((idx, piece) in pieces.withIndex()) {
            val (a, b) = piece
            if (b <= a) continue
            val q = p.slice(a, b)
            if (piece in isRun || q.size < 3) {
                curves += line(q.x[0], q.y[0], q.x.last(), q.y.last())
                continue
            }
            val k = min(4, q.size - 1)
            var t1 = unit(q.x[k] - q.x[0], q.y[k] - q.y[0])
            var t2 = unit(q.x[q.size - 1 - k] - q.x.last(), q.y[q.size - 1 - k] - q.y.last())
            if (idx > 0 && pieces[idx - 1] in isRun) {
                val (ra, rb) = pieces[idx - 1]
                t1 = unit(p.x[rb] - p.x[ra], p.y[rb] - p.y[ra])
            }
            if (idx + 1 < pieces.size && pieces[idx + 1] in isRun) {
                val (ra, rb) = pieces[idx + 1]
                t2 = unit(p.x[ra] - p.x[rb], p.y[ra] - p.y[rb])
            }
            fitCubic(q.x, q.y, 0, q.size - 1, t1, t2, TOLERANCE * step, curves)
        }
        return Result(curves, cs.drop(1).dropLast(1))
    }

    // --- corners and straight runs ---

    private fun corners(p: Poly, win: Double, minTurn: Double): List<Int> {
        val d = p.arc()
        val out = ArrayList<Pair<Double, Int>>()
        for (i in 0 until p.size) {
            val a = d.indexOfFirstAtLeast(d[i] - win)
            val b = d.indexOfFirstAtLeast(d[i] + win)
            if (a >= i || b >= p.size || b <= i) continue
            val t = turn(p.x[i] - p.x[a], p.y[i] - p.y[a], p.x[b] - p.x[i], p.y[b] - p.y[i])
            if (t > minTurn) out += t to i
        }
        val keep = ArrayList<Int>()
        for ((_, i) in out.sortedByDescending { it.first }) {
            if (keep.all { abs(d[i] - d[it]) > 2 * win }) keep += i
        }
        return keep.sorted()
    }

    private fun straightRuns(p: Poly): MutableList<Pair<Int, Int>> {
        val d = p.arc()
        val tol = 0.028 * step
        val minLen = 0.55 * step
        val runs = ArrayList<Pair<Int, Int>>()
        var i = 0
        val n = p.size
        while (i < n - 3) {
            var j = i + 3
            while (j < n && isStraight(p, i, j, tol)) j++
            j--
            if (d[j] - d[i] >= minLen) {
                runs += i to j
                i = j
            } else i++
        }
        return runs
    }

    /** Every point within [tol] of one line, moving one way along it (no turning back). */
    private fun isStraight(p: Poly, a: Int, b: Int, tol: Double): Boolean {
        val (mx, my, dx, dy) = line(p, a, b)
        var prevT = Double.NaN
        var fwd = true
        var bwd = true
        for (i in a..b) {
            val ox = p.x[i] - mx
            val oy = p.y[i] - my
            if (abs(-dy * ox + dx * oy) >= tol) return false
            val t = dx * ox + dy * oy
            if (!prevT.isNaN()) {
                if (t - prevT < -0.5) fwd = false
                if (t - prevT > 0.5) bwd = false
            }
            prevT = t
        }
        return fwd || bwd
    }

    /** Two runs meeting at a shallow angle are a kink the print does not have: keep the longer. */
    private fun dropShallowNeighbours(p: Poly, runs: MutableList<Pair<Int, Int>>): MutableList<Pair<Int, Int>> {
        val d = p.arc()
        var changed = true
        while (changed) {
            changed = false
            for (k in 0 until runs.size - 1) {
                val (a, b) = runs[k]
                val (c, e) = runs[k + 1]
                if (c - b > 3) continue
                val v1 = unit(p.x[b] - p.x[a], p.y[b] - p.y[a])
                val v2 = unit(p.x[e] - p.x[c], p.y[e] - p.y[c])
                if (acos((v1[0] * v2[0] + v1[1] * v2[1]).coerceIn(-1.0, 1.0)) < 0.5) {
                    runs.removeAt(if (d[b] - d[a] < d[e] - d[c]) k else k + 1)
                    changed = true
                    break
                }
            }
        }
        return runs
    }

    /** The ends a run shares with a curve pulled back, so the curve has room to blend into it. */
    private fun trimRuns(p: Poly, runs: List<Pair<Int, Int>>): MutableList<Pair<Int, Int>> {
        val d = p.arc()
        val out = ArrayList<Pair<Int, Int>>()
        for ((a, b) in runs) {
            val a2 = if (a == 0) a else d.indexOfFirstAtLeast(d[a] + 0.1 * step)
            val b2 = if (b == p.size - 1) b else (d.indexOfFirstAtLeast(d[b] - 0.1 * step)).coerceAtMost(p.size - 1)
            if (a2 < b2 && d[b2] - d[a2] > 0.3 * step) out += a2 to b2
        }
        return out
    }

    /** Points [a]..[b] moved onto their total-least-squares line. */
    private fun project(p: Poly, a: Int, b: Int) {
        val (mx, my, dx, dy) = line(p, a, b)
        for (i in a..b) {
            val t = dx * (p.x[i] - mx) + dy * (p.y[i] - my)
            p.x[i] = mx + t * dx
            p.y[i] = my + t * dy
        }
    }

    /** Breakpoints closer than a sliver merged; a run's end wins over a corner. */
    private fun mergeClose(p: Poly, cs: List<Int>, ends: Set<Int>): List<Int> {
        val d = p.arc()
        val keep = ArrayList<Int>()
        keep += cs.first()
        for (i in cs.drop(1)) {
            val last = keep.last()
            if (d[i] - d[last] < 0.12 * step) {
                if (i == cs.last()) {
                    if (last != cs.first() && last !in ends) keep[keep.size - 1] = i
                    continue
                }
                if (i in ends && last !in ends && last != cs.first()) keep[keep.size - 1] = i
                continue
            }
            keep += i
        }
        if (keep.last() != cs.last()) keep += cs.last()
        return keep
    }

    // --- Schneider ---

    private fun fitCubic(x: DoubleArray, y: DoubleArray, first: Int, last: Int, t1: DoubleArray, t2: DoubleArray, tol: Double, out: MutableList<Cubic>) {
        val n = last - first + 1
        if (n == 2) {
            val dist = hypot(x[last] - x[first], y[last] - y[first]) / 3
            out += doubleArrayOf(x[first], y[first], x[first] + t1[0] * dist, y[first] + t1[1] * dist, x[last] + t2[0] * dist, y[last] + t2[1] * dist, x[last], y[last])
            return
        }
        var u = chord(x, y, first, last)
        var c = generate(x, y, first, last, u, t1, t2)
        var (err, split) = maxError(x, y, first, last, c, u)
        if (err <= tol) {
            out += c
            return
        }
        if (err < tol * 4) {
            repeat(20) {
                u = reparameterize(x, y, first, last, c, u)
                c = generate(x, y, first, last, u, t1, t2)
                val e = maxError(x, y, first, last, c, u)
                err = e.first
                split = e.second
                if (err <= tol) {
                    out += c
                    return
                }
            }
        }
        val s = split.coerceIn(first + 1, last - 1)
        val tc = unit(x[s - 1] - x[s + 1], y[s - 1] - y[s + 1])
        fitCubic(x, y, first, s, t1, tc, tol, out)
        fitCubic(x, y, s, last, doubleArrayOf(-tc[0], -tc[1]), t2, tol, out)
    }

    private fun chord(x: DoubleArray, y: DoubleArray, first: Int, last: Int): DoubleArray {
        val u = DoubleArray(last - first + 1)
        for (i in 1 until u.size) u[i] = u[i - 1] + hypot(x[first + i] - x[first + i - 1], y[first + i] - y[first + i - 1])
        val total = u.last()
        if (total > 0) for (i in u.indices) u[i] /= total
        return u
    }

    private fun generate(x: DoubleArray, y: DoubleArray, first: Int, last: Int, u: DoubleArray, t1: DoubleArray, t2: DoubleArray): Cubic {
        var c00 = 0.0; var c01 = 0.0; var c11 = 0.0; var x0 = 0.0; var x1 = 0.0
        val ax = x[first]; val ay = y[first]; val bx = x[last]; val by = y[last]
        for (i in u.indices) {
            val t = u[i]
            val b0 = (1 - t) * (1 - t) * (1 - t)
            val b1 = 3 * (1 - t) * (1 - t) * t
            val b2 = 3 * (1 - t) * t * t
            val b3 = t * t * t
            val a1x = t1[0] * b1; val a1y = t1[1] * b1
            val a2x = t2[0] * b2; val a2y = t2[1] * b2
            c00 += a1x * a1x + a1y * a1y
            c01 += a1x * a2x + a1y * a2y
            c11 += a2x * a2x + a2y * a2y
            val tx = x[first + i] - (ax * (b0 + b1) + bx * (b2 + b3))
            val ty = y[first + i] - (ay * (b0 + b1) + by * (b2 + b3))
            x0 += a1x * tx + a1y * ty
            x1 += a2x * tx + a2y * ty
        }
        val det = c00 * c11 - c01 * c01
        val seg = hypot(bx - ax, by - ay)
        var al = if (abs(det) > 1e-12) (x0 * c11 - x1 * c01) / det else seg / 3
        var ar = if (abs(det) > 1e-12) (c00 * x1 - c01 * x0) / det else seg / 3
        if (al < 1e-6 * seg || ar < 1e-6 * seg) {
            al = seg / 3
            ar = seg / 3
        }
        return doubleArrayOf(ax, ay, ax + t1[0] * al, ay + t1[1] * al, bx + t2[0] * ar, by + t2[1] * ar, bx, by)
    }

    private fun maxError(x: DoubleArray, y: DoubleArray, first: Int, last: Int, c: Cubic, u: DoubleArray): Pair<Double, Int> {
        var worst = 0.0
        var at = (first + last) / 2
        for (i in 1 until u.size - 1) {
            val p = point(c, u[i])
            val e = hypot(p[0] - x[first + i], p[1] - y[first + i])
            if (e > worst) {
                worst = e
                at = first + i
            }
        }
        return worst to at
    }

    private fun reparameterize(x: DoubleArray, y: DoubleArray, first: Int, last: Int, c: Cubic, u: DoubleArray): DoubleArray {
        val out = u.copyOf()
        for (i in 1 until u.size - 1) {
            val t = u[i]
            val p = point(c, t)
            val d1 = deriv(c, t)
            val d2 = deriv2(c, t)
            val dx = p[0] - x[first + i]
            val dy = p[1] - y[first + i]
            val num = dx * d1[0] + dy * d1[1]
            val den = d1[0] * d1[0] + d1[1] * d1[1] + dx * d2[0] + dy * d2[1]
            out[i] = if (abs(den) > 1e-12) (t - num / den).coerceIn(0.0, 1.0) else t
        }
        return out
    }

    companion object {
        /** Fitting tolerance, in steps. */
        const val TOLERANCE = 0.022

        /** A corner is a turn sharper than this (radians) — a reversal, not a bend. */
        const val CORNER_TURN = 2.3

        fun line(ax: Double, ay: Double, bx: Double, by: Double): Cubic =
            doubleArrayOf(ax, ay, ax + (bx - ax) / 3, ay + (by - ay) / 3, ax + 2 * (bx - ax) / 3, ay + 2 * (by - ay) / 3, bx, by)

        fun point(c: Cubic, t: Double): DoubleArray {
            val s = 1 - t
            val b0 = s * s * s; val b1 = 3 * s * s * t; val b2 = 3 * s * t * t; val b3 = t * t * t
            return doubleArrayOf(b0 * c[0] + b1 * c[2] + b2 * c[4] + b3 * c[6], b0 * c[1] + b1 * c[3] + b2 * c[5] + b3 * c[7])
        }

        fun deriv(c: Cubic, t: Double): DoubleArray {
            val s = 1 - t
            return doubleArrayOf(
                3 * s * s * (c[2] - c[0]) + 6 * s * t * (c[4] - c[2]) + 3 * t * t * (c[6] - c[4]),
                3 * s * s * (c[3] - c[1]) + 6 * s * t * (c[5] - c[3]) + 3 * t * t * (c[7] - c[5]),
            )
        }

        fun deriv2(c: Cubic, t: Double): DoubleArray {
            val s = 1 - t
            return doubleArrayOf(
                6 * s * (c[4] - 2 * c[2] + c[0]) + 6 * t * (c[6] - 2 * c[4] + c[2]),
                6 * s * (c[5] - 2 * c[3] + c[1]) + 6 * t * (c[7] - 2 * c[5] + c[3]),
            )
        }

        /** [curves] sampled to a polyline, [perCurve] points each. */
        fun sample(curves: List<Cubic>, perCurve: Int = 24): Poly {
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            for ((k, c) in curves.withIndex()) {
                for (i in (if (k == 0) 0 else 1)..perCurve) {
                    val p = point(c, i.toDouble() / perCurve)
                    xs += p[0]
                    ys += p[1]
                }
            }
            return Poly(xs.toDoubleArray(), ys.toDoubleArray())
        }

        internal fun unit(dx: Double, dy: Double): DoubleArray {
            val n = hypot(dx, dy)
            return if (n > 0) doubleArrayOf(dx / n, dy / n) else doubleArrayOf(0.0, 0.0)
        }

        internal fun turn(ax: Double, ay: Double, bx: Double, by: Double): Double {
            var t = atan2(by, bx) - atan2(ay, ax)
            while (t > Math.PI) t -= 2 * Math.PI
            while (t < -Math.PI) t += 2 * Math.PI
            return abs(t)
        }

        /** Total-least-squares line through points [a]..[b]: centroid and unit direction. */
        internal fun line(p: Poly, a: Int, b: Int): DoubleArray {
            val n = b - a + 1
            var mx = 0.0; var my = 0.0
            for (i in a..b) { mx += p.x[i]; my += p.y[i] }
            mx /= n; my /= n
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0
            for (i in a..b) {
                val dx = p.x[i] - mx; val dy = p.y[i] - my
                sxx += dx * dx; sxy += dx * dy; syy += dy * dy
            }
            val ang = 0.5 * atan2(2 * sxy, sxx - syy)
            return doubleArrayOf(mx, my, kotlin.math.cos(ang), kotlin.math.sin(ang))
        }

        private fun DoubleArray.indexOfFirstAtLeast(v: Double): Int {
            var lo = 0
            var hi = size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (this[mid] < v) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
