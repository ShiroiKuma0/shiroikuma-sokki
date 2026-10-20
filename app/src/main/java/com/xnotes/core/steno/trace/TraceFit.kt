package com.xnotes.core.steno.trace

import com.xnotes.core.steno.glyph.GlyphPath
import com.xnotes.core.steno.glyph.GlyphShape
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The writing lines marked on a scan: the base line's y and one step, in the raster's pixels. */
class TraceFrame(val baseY: Double, val stepPx: Double)

/**
 * A trace over a printed element → the element's clean form.
 *
 * 1. The print is read as ink at a fixed working scale (one step = [WORK_STEP] px): levels
 *    stretched, blurred a little, thresholded, specks and pinholes removed.
 * 2. Its centre line is thinned out and freed of short spurs, and becomes a graph.
 * 3. Each traced stroke picks its route through that graph ([SkeletonGraph.route]), so the result
 *    lies on the print, in the pen's order, whatever the hand did.
 * 4. The route is fitted with clean curves ([CurveFit]).
 * 5. The extremes near a writing line are set exactly onto it, and the shading is read from the
 *    print's width, on downstrokes only.
 *
 * Every length is in steps, so a small box and a large one give the same element.
 */
object TraceFit {

    const val WORK_STEP = 110.0

    /** Line heights, in steps, that an element's top or bottom snaps to. */
    private val LEVELS = doubleArrayOf(0.0, 0.5, 1.0, 2.5, -1.0, -1.67)
    private const val SNAP_TOL = 0.15

    /** A fitted element and where its x = 0 lies, in the input raster's pixels. */
    class Fitted(val shape: GlyphShape, val leftPx: Double)

    /** [traces] (points in [gray]'s pixels, each a DoubleArray(x, y)) over the print in [gray]. */
    fun fit(gray: Gray, frame: TraceFrame, traces: List<List<DoubleArray>>): GlyphShape? = fitPlaced(gray, frame, traces)?.shape

    /** As [fit], with the element's placement on the raster. */
    fun fitPlaced(gray: Gray, frame: TraceFrame, traces: List<List<DoubleArray>>): Fitted? {
        if (frame.stepPx <= 1.0 || traces.isEmpty()) return null
        val k = WORK_STEP / frame.stepPx
        val s = WORK_STEP
        val g = gray.scaled(k).normalized()
        val base = frame.baseY * k

        val ink = g.blurred(0.0175 * s).below(115f)
            .withoutSpecks((0.03 * s * s).toInt())
            .withHolesFilled((0.008 * s * s).toInt())
        val widthInk = g.blurred(0.007 * s).below(125f).withoutSpecks((0.03 * s * s).toInt())
        val edt = widthInk.distanceToPaper()
        val inkEdt = ink.distanceToPaper()
        val sk = Thinning.pruneSpurs(Thinning.thin(ink), (0.14 * s).toInt())
        val graph = SkeletonGraph(sk)
        if (graph.size == 0) return null

        class Raw(val curves: List<Cubic>, val poly: Poly, val shade: FloatArray, val closed: Boolean)
        val raws = ArrayList<Raw>()
        for (trace in traces) {
            if (trace.size < 2) continue
            val t = Poly(DoubleArray(trace.size) { trace[it][0] * k }, DoubleArray(trace.size) { trace[it][1] * k })
                .resampled(0.03 * s).smoothed(2.0)
            if (t.size < 2) continue
            val route = graph.route(
                List(t.size) { doubleArrayOf(t.x[it], t.y[it]) },
                stepPx = 0.03 * s, radius = 0.4 * s, sigma = 0.08 * s, detourLimit = (0.5 * s).toInt(), extendMax = 0.3 * s,
            )
            if (route.size < 2) continue
            val r = toInkEnds(Poly(DoubleArray(route.size) { route[it][0] }, DoubleArray(route.size) { route[it][1] }), ink, inkEdt, 0.25 * s)
                .resampled(1.0).smoothed(0.035 * s)
            val fit = CurveFit(s).fit(r)
            if (fit.curves.isEmpty()) continue
            val c = CurveFit.sample(fit.curves, 48).resampled(1.0)
            val closed = hypot(r.x[0] - r.x.last(), r.y[0] - r.y.last()) < 0.1 * r.arc().last()
            raws += Raw(fit.curves, c, shading(c, r, edt, g.w, g.h), closed)
        }
        if (raws.isEmpty()) return null

        // Units: y up from the base line, extremes near a line set onto it; x from the left edge.
        val ys = raws.flatMap { r -> r.poly.y.map { (base - it) / s } }
        val yTop = ys.max()
        val yBot = ys.min()
        val (scale, shift) = snap(yTop, yBot)
        val left = raws.minOf { r -> r.poly.x.min() }
        fun ux(px: Double) = (px - left) / s
        fun uy(py: Double) = ((base - py) / s - yBot) * scale + yBot + shift
        val paths = raws.map { r ->
            GlyphPath(
                curves = r.curves.map { cv -> DoubleArray(8) { i -> if (i % 2 == 0) ux(cv[i]) else uy(cv[i]) } },
                x = DoubleArray(r.poly.size) { ux(r.poly.x[it]) },
                y = DoubleArray(r.poly.size) { uy(r.poly.y[it]) },
                shade = r.shade,
                closed = r.closed,
            )
        }
        return Fitted(GlyphShape(paths), left / k)
    }

    /**
     * [r] with each end carried on along its direction to where the printed ink ends, less half
     * the stroke's width there: the centre of the print's round end. A skeleton stops short of a
     * stroke's end, and further still where the end touches a ruled dash; the ink itself does not.
     */
    private fun toInkEnds(r: Poly, ink: Mask, edt: FloatArray, maxLen: Double): Poly {
        if (r.size < 10) return r
        fun march(ex: Double, ey: Double, bx: Double, by: Double): DoubleArray? {
            var dx = ex - bx
            var dy = ey - by
            val n = hypot(dx, dy)
            if (n == 0.0) return null
            dx /= n
            dy /= n
            val half = edt[ey.toInt().coerceIn(0, ink.h - 1) * ink.w + ex.toInt().coerceIn(0, ink.w - 1)].toDouble()
            var t = 0.0
            while (t < maxLen + half && ink[(ex + dx * (t + 0.5)).toInt(), (ey + dy * (t + 0.5)).toInt()]) t += 0.5
            val go = t - half
            if (go <= 1.0) return null
            return doubleArrayOf(ex + dx * go, ey + dy * go)
        }
        val k = minOf(8, r.size - 1)
        val head = march(r.x[0], r.y[0], r.x[k], r.y[k])
        val tail = march(r.x.last(), r.y.last(), r.x[r.size - 1 - k], r.y[r.size - 1 - k])
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        head?.let { xs += it[0]; ys += it[1] }
        for (i in 0 until r.size) { xs += r.x[i]; ys += r.y[i] }
        tail?.let { xs += it[0]; ys += it[1] }
        return Poly(xs.toDoubleArray(), ys.toDoubleArray())
    }

    /** The scale about the bottom and the shift that put the element's extremes on the lines. */
    internal fun snap(top: Double, bot: Double): Pair<Double, Double> {
        val lt = LEVELS.minBy { abs(it - top) }
        val lb = LEVELS.minBy { abs(it - bot) }
        val nearTop = abs(top - lt) < SNAP_TOL
        val nearBot = abs(bot - lb) < SNAP_TOL
        return when {
            nearTop && nearBot && lt != lb && top > bot -> (lt - lb) / (top - bot) to (lb - bot)
            nearBot -> 1.0 to (lb - bot)
            nearTop -> 1.0 to (lt - top)
            else -> 1.0 to 0.0
        }
    }

    /** Per point of [c], how far the print swells there (0..1), on downstrokes only. */
    private fun shading(c: Poly, route: Poly, edt: FloatArray, w: Int, h: Int): FloatArray {
        val n = c.size
        val wr = DoubleArray(route.size) { i ->
            val x0 = route.x[i].toInt()
            val y0 = route.y[i].toInt()
            var m = 0f
            for (yy in max(0, y0 - 3)..min(h - 1, y0 + 3)) for (xx in max(0, x0 - 3)..min(w - 1, x0 + 3)) m = max(m, edt[yy * w + xx])
            2.0 * m
        }
        // Width at each curve point: from the nearest route point.
        val wc = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            var best = Double.MAX_VALUE
            var bi = j
            val lo = max(0, j - 40)
            val hi = min(route.size - 1, j + 400)
            for (q in lo..hi) {
                val d = hypot(route.x[q] - c.x[i], route.y[q] - c.y[i])
                if (d < best) {
                    best = d
                    bi = q
                }
            }
            j = bi
            wc[i] = wr[bi]
        }
        val med = median(wc, 15)
        val p95 = med.sorted()[(0.95 * (n - 1)).toInt()]
        for (i in med.indices) med[i] = min(med[i], p95)
        val sm = Poly.gauss(med, 14.0, hold = false)
        val hair = max(1e-3, sm.sorted()[(0.2 * (n - 1)).toInt()])
        val sh = DoubleArray(n) { ((sm[it] / hair - 1.35) / 0.55).coerceIn(0.0, 1.0) }
        val ys = Poly.gauss(c.y, 4.0, hold = false)
        val down = DoubleArray(n) { i ->
            val a = ys[max(0, i - 1)]
            val b = ys[min(n - 1, i + 1)]
            if ((b - a) / 2 > 0.15) 1.0 else 0.0
        }
        val dm = Poly.gauss(down, 3.0, hold = false)
        for (i in 0 until n) sh[i] *= dm[i]
        val mx = DoubleArray(n) { i -> var m = 0.0; for (q in max(0, i - 4)..min(n - 1, i + 4)) m = max(m, sh[q]); m }
        val out = Poly.gauss(mx, 10.0, hold = false)
        return FloatArray(n) { out[it].toFloat().coerceIn(0f, 1f) }
    }

    private fun median(v: DoubleArray, size: Int): DoubleArray {
        val r = size / 2
        return DoubleArray(v.size) { i ->
            val win = DoubleArray(min(v.size - 1, i + r) - max(0, i - r) + 1) { v[max(0, i - r) + it] }
            win.sort()
            win[win.size / 2]
        }
    }
}
