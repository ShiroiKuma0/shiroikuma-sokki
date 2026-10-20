package com.xnotes.core.steno.glyph

import com.xnotes.core.steno.trace.CurveFit
import com.xnotes.core.steno.trace.Cubic
import com.xnotes.core.steno.trace.Poly
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One-tap fixes to an element's clean form (fork): a single segment — one fitted curve — made an
 * exact straight line, bent into a curve that flows on from its neighbours, or its shading turned
 * on or off. The rest of the form is left exactly as it was: its curves untouched, and its samples'
 * shading carried over curve by curve.
 */
object ShapeEdit {

    enum class Fix { STRAIGHT, CURVE, SHADED }

    /** A segment: curve [curve] of path [path]. */
    data class Seg(val path: Int, val curve: Int)

    /** The segment nearest to ([x], [y]) (units), when it lies within [reach] units; else null. */
    fun hit(s: GlyphShape, x: Double, y: Double, reach: Double = 0.3): Seg? {
        var best: Seg? = null
        var bd = reach
        for ((pi, p) in s.paths.withIndex()) for ((ci, c) in p.curves.withIndex()) {
            for (k in 0..DENSE) {
                val q = CurveFit.point(c, k.toDouble() / DENSE)
                val d = hypot(q[0] - x, q[1] - y)
                if (d < bd) { bd = d; best = Seg(pi, ci) }
            }
        }
        return best
    }

    /** Whether segment [seg] is mostly shaded. */
    fun isShaded(s: GlyphShape, seg: Seg): Boolean {
        val p = s.paths.getOrNull(seg.path) ?: return false
        val (own, _) = owners(p)
        var n = 0
        var sum = 0.0
        for (i in 0 until p.size) if (own[i] == seg.curve) { n++; sum += p.shade[i] }
        return n > 0 && sum / n > 0.5
    }

    /** [s] with [fix] applied to [seg]. */
    fun apply(s: GlyphShape, seg: Seg, fix: Fix): GlyphShape {
        val p = s.paths.getOrNull(seg.path) ?: return s
        val c = p.curves.getOrNull(seg.curve) ?: return s
        val curves = p.curves.map { it.copyOf() }.toMutableList()
        var shadeTo: Float? = null
        when (fix) {
            Fix.STRAIGHT -> curves[seg.curve] = CurveFit.line(c[0], c[1], c[6], c[7])
            Fix.CURVE -> curves[seg.curve] = flowing(p, seg.curve)
            Fix.SHADED -> shadeTo = if (isShaded(s, seg)) 0f else 1f
        }
        val np = rebuild(p, curves, if (shadeTo != null) seg.curve else -1, shadeTo ?: 0f)
        return GlyphShape(s.paths.mapIndexed { i, q -> if (i == seg.path) np else q })
    }

    /**
     * Curve [k] of [p] redrawn between its own two ends as a Hermite curve whose tangents are its
     * neighbours' at the joints (its own where it has none), so it flows on from them.
     */
    private fun flowing(p: GlyphPath, k: Int): Cubic {
        val c = p.curves[k]
        val n = p.curves.size
        val prev = if (k > 0) p.curves[k - 1] else if (p.closed && n > 1) p.curves[n - 1] else null
        val next = if (k < n - 1) p.curves[k + 1] else if (p.closed && n > 1) p.curves[0] else null
        val chord = hypot(c[6] - c[0], c[7] - c[1])
        val t0 = (prev?.let { endTangent(it) } ?: startTangent(c)).let { CurveFit.unit(it[0], it[1]) }
        val t1 = (next?.let { startTangent(it) } ?: endTangent(c)).let { CurveFit.unit(it[0], it[1]) }
        val h = chord / 3
        return doubleArrayOf(
            c[0], c[1],
            c[0] + t0[0] * h, c[1] + t0[1] * h,
            c[6] - t1[0] * h, c[7] - t1[1] * h,
            c[6], c[7],
        )
    }

    private fun startTangent(c: Cubic): DoubleArray {
        val d = CurveFit.deriv(c, 0.0)
        return if (hypot(d[0], d[1]) > 1e-9) d else doubleArrayOf(c[6] - c[0], c[7] - c[1])
    }

    private fun endTangent(c: Cubic): DoubleArray {
        val d = CurveFit.deriv(c, 1.0)
        return if (hypot(d[0], d[1]) > 1e-9) d else doubleArrayOf(c[6] - c[0], c[7] - c[1])
    }

    /** Which curve each sample of [p] lies on, and how far along it (0..1). */
    internal fun owners(p: GlyphPath): Pair<IntArray, DoubleArray> {
        val dk = ArrayList<Int>()
        val dt = ArrayList<Double>()
        val dx = ArrayList<Double>()
        val dy = ArrayList<Double>()
        for ((k, c) in p.curves.withIndex()) for (i in 0..DENSE) {
            val t = i.toDouble() / DENSE
            val q = CurveFit.point(c, t)
            dk += k; dt += t; dx += q[0]; dy += q[1]
        }
        val own = IntArray(p.size)
        val at = DoubleArray(p.size)
        if (dk.isEmpty()) return own to at
        // Samples run along the path in order, so the search moves forward with them — a loop
        // passing over itself is never mistaken for its other turn.
        var j = 0
        for (i in 0 until p.size) {
            var bd = Double.MAX_VALUE
            var bj = j
            for (q in max(0, j - 8)..min(dk.size - 1, j + 3 * DENSE)) {
                val d = hypot(dx[q] - p.x[i], dy[q] - p.y[i])
                if (d < bd) { bd = d; bj = q }
            }
            j = bj
            own[i] = dk[bj]
            at[i] = dt[bj]
        }
        return own to at
    }

    /**
     * [p] with [curves] in place of its own: sampled afresh at its old spacing, each new sample
     * shaded as the old sample at the same place on the same curve was — or [shade] all along
     * curve [shadeCurve], softened into its neighbours at the joints.
     */
    private fun rebuild(p: GlyphPath, curves: List<Cubic>, shadeCurve: Int, shade: Float): GlyphPath {
        val (own, at) = owners(p)
        val total = (1 until p.size).sumOf { hypot(p.x[it] - p.x[it - 1], p.y[it] - p.y[it - 1]) }
        val spacing = if (p.size > 1 && total > 0) total / (p.size - 1) else 0.01
        val byCurve = Array(curves.size) { k -> (0 until p.size).filter { own[it] == k } }
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val ss = ArrayList<Float>()
        for ((k, c) in curves.withIndex()) {
            val len = CurveFit.sample(listOf(c), DENSE).let { q -> (1 until q.size).sumOf { hypot(q.x[it] - q.x[it - 1], q.y[it] - q.y[it - 1]) } }
            val m = max(4, ceil(len / spacing).toInt())
            for (i in (if (k == 0) 0 else 1)..m) {
                val t = i.toDouble() / m
                val q = CurveFit.point(c, t)
                xs += q[0]; ys += q[1]
                ss += if (k == shadeCurve) shade else {
                    val old = byCurve.getOrNull(k)
                    if (old.isNullOrEmpty()) 0f else p.shade[old.minBy { abs(at[it] - t) }]
                }
            }
        }
        var shades = ss.toFloatArray()
        if (shadeCurve >= 0) {
            // Soften the step at the joints over about 0.04 units, as the print's own swelling is.
            val sm = Poly.gauss(DoubleArray(shades.size) { shades[it].toDouble() }, 0.04 / spacing, hold = false)
            shades = FloatArray(sm.size) { sm[it].toFloat().coerceIn(0f, 1f) }
        }
        return GlyphPath(curves, xs.toDoubleArray(), ys.toDoubleArray(), shades, p.closed)
    }

    private const val DENSE = 48
}
