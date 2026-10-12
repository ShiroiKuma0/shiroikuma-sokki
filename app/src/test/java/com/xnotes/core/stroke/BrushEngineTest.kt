package com.xnotes.core.stroke

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 筆 brush: deterministic, swells under a held press, and sweeps out only when it leaves moving. */
class BrushEngineTest {

    private fun build(
        xs: DoubleArray,
        ys: DoubleArray,
        ps: DoubleArray,
        ts: DoubleArray,
        finished: Boolean = true,
        harai: Double = 0.5,
        entry: Double = 0.0,
    ) = BrushEngine.build(
        xs, ys, ps, ts,
        baseWidth = 80.0, pressureEnabled = true, m = 0.03, gamma = 1.8, entry = entry, harai = harai,
        pressureLow = 0.0, pressureHigh = 1.0, speedScale = 1.0, smoothScale = 1.0, finished = finished,
    )

    /** A straight stroke along x at [speed] px/ms, [n] samples 2 px apart, at constant [pressure]. */
    private fun line(n: Int, speed: Double, pressure: Double = 0.8): Array<DoubleArray> {
        val xs = DoubleArray(n) { it * 2.0 }
        val ys = DoubleArray(n)
        val ps = DoubleArray(n) { pressure }
        val ts = DoubleArray(n) { it * 2.0 / speed }
        return arrayOf(xs, ys, ps, ts)
    }

    @Test fun sameSamplesBuildTheSameInk() {
        val (xs, ys, ps, ts) = line(60, 0.5)
        val a = build(xs, ys, ps, ts)
        val b = build(xs.copyOf(), ys.copyOf(), ps.copyOf(), ts.copyOf())
        assertArrayEquals(a.halfWidths, b.halfWidths, 0f)
        assertArrayEquals(a.centerline, b.centerline, 0f)
    }

    /** The widest the ink is at points of its centreline within [r] of ([x], [y]). */
    private fun widthNear(g: StrokeGeometry, x: Double, y: Double, r: Double): Float {
        var w = 0f
        for (i in 0 until g.pointCount) {
            if (kotlin.math.hypot(g.centerline[2 * i] - x, g.centerline[2 * i + 1] - y) <= r) w = maxOf(w, g.halfWidths[i])
        }
        return w
    }

    /** How far the centreline reaches past ([x], [y]) along the unit direction ([dx], [dy]). */
    private fun reachPast(g: StrokeGeometry, x: Double, y: Double, dx: Double, dy: Double): Double {
        var far = 0.0
        for (i in 0 until g.pointCount) far = maxOf(far, (g.centerline[2 * i] - x) * dx + (g.centerline[2 * i + 1] - y) * dy)
        return far
    }

    @Test fun everySampleIsInTheInk() {
        // Each sample is a footprint of the brush; the round ends only add to them.
        val (xs, ys, ps, ts) = line(40, 0.5)
        assertTrue(build(xs, ys, ps, ts).pointCount >= 40)
    }

    @Test fun neverWiderThanTheBrush() {
        val (xs, ys, ps, ts) = line(80, 0.05, pressure = 1.0)
        val g = build(xs, ys, ps, ts, entry = 1.0)
        for (h in g.halfWidths) assertTrue(h <= 40.0f + 1e-4f)
    }

    @Test fun aPressInPlacePoolsTheInk() {
        // Land light and bear down without moving: the ink spreads under the resting brush.
        val n = 30
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        val ps = DoubleArray(n) { 0.2 + 0.7 * it / (n - 1) }
        val ts = DoubleArray(n) { it * 12.0 }
        val g = build(xs, ys, ps, ts, finished = false)
        val landed = build(xs.copyOf(3), ys.copyOf(3), ps.copyOf(3), ts.copyOf(3), finished = false)
        assertTrue(g.halfWidths.max() > landed.halfWidths.max() * 2)
    }

    @Test fun aTremblingPressPoolsLikeAStillOne() {
        // A pen pressed hard in place trembles a px or two per report: that is still a rest, not travel.
        val n = 60
        val still = build(DoubleArray(n), DoubleArray(n), DoubleArray(n) { 0.9 }, DoubleArray(n) { it * 4.0 }, finished = false)
        val xs = DoubleArray(n) { if (it % 2 == 0) 0.0 else 1.5 }
        val ys = DoubleArray(n) { if (it % 3 == 0) 0.0 else 1.0 }
        val shaky = build(xs, ys, DoubleArray(n) { 0.9 }, DoubleArray(n) { it * 4.0 }, finished = false)
        assertEquals(still.halfWidths.max(), shaky.halfWidths.max(), still.halfWidths.max() * 0.1f)
    }

    @Test fun aStopEndsRound() {
        // Write along x and come to rest: the end keeps its width and closes in the round of its last
        // footprint, with no point and no ball past it.
        val moving = 120
        val n = moving + 15
        val xs = DoubleArray(n) { if (it < moving) it * 2.0 else (moving - 1) * 2.0 }
        val ys = DoubleArray(n)
        val ps = DoubleArray(n) { 0.8 }
        val ts = DoubleArray(n) { if (it < moving) it * 4.0 else (moving - 1) * 4.0 + (it - moving + 1) * 10.0 }
        val g = build(xs, ys, ps, ts)
        val stop = (moving - 1) * 2.0
        val body = widthNear(g, stop - 4.0, 0.0, 2.0) // the width the brush arrived at the stop with
        val end = widthNear(g, stop, 0.0, 1.0)
        assertTrue(end > body * 0.8f)
        assertTrue(g.halfWidths.max() < body * 1.3f)
        val reach = reachPast(g, stop, 0.0, 1.0, 0.0)
        assertTrue(reach > end * 0.8 && reach < end * 1.2)
    }

    @Test fun aLiftTwitchLeavesTheEndRound() {
        // Resting at the end, then coming off the paper, the pen hooks back a few px the way it came:
        // still a round end.
        val moving = 120
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ps = ArrayList<Double>(); val ts = ArrayList<Double>()
        for (i in 0 until moving) { xs += i * 2.0; ys += 0.0; ps += 0.8; ts += i * 4.0 }
        var t = (moving - 1) * 4.0
        for (k in 1..15) { xs += (moving - 1) * 2.0; ys += 0.0; ps += 0.8; t += 10.0; ts += t }
        for (k in 1..8) { xs += (moving - 1) * 2.0 - k * 1.0; ys += k * 0.6; ps += 0.7; t += 3.0; ts += t }
        val g = build(xs.toDoubleArray(), ys.toDoubleArray(), ps.toDoubleArray(), ts.toDoubleArray())
        val stop = (moving - 1) * 2.0
        val end = widthNear(g, stop, 0.0, 1.0)
        assertTrue(end > widthNear(g, moving * 1.0, 0.0, 2.0) * 0.6f)
        assertTrue(reachPast(g, stop, 0.0, 1.0, 0.0) > end * 0.8)
    }

    @Test fun aHookEndsInAPoint() {
        // Down, then a sharp turn and a short fast run up-left: the run is a flick, narrowing to a point.
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ps = ArrayList<Double>(); val ts = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until 150) { xs += 0.0; ys += i * 3.0; ps += 0.8; ts += t; t += 4.0 }
        for (k in 1..30) { xs += -k * 2.5; ys += 447.0 - k * 2.5; ps += 0.8; ts += t; t += 2.0 }
        val g = build(xs.toDoubleArray(), ys.toDoubleArray(), ps.toDoubleArray(), ts.toDoubleArray())
        assertTrue(g.halfWidths.last() < g.halfWidths[100] * 0.25f)
    }

    @Test fun aFastStrokeStopsInABall() {
        // Write briskly and stop: the body runs thin with the speed, the stop presses into a ball
        // wider than it, and the body flows into the ball rather than meeting it at a neck.
        val moving = 100
        val n = moving + 20
        val xs = DoubleArray(n) { if (it < moving) it * 4.0 else (moving - 1) * 4.0 }
        val ys = DoubleArray(n)
        val ps = DoubleArray(n) { 0.8 }
        val ts = DoubleArray(n) { if (it < moving) it * 2.0 else (moving - 1) * 2.0 + (it - moving + 1) * 10.0 }
        val g = build(xs, ys, ps, ts)
        val stop = (moving - 1) * 4.0
        val body = widthNear(g, stop / 2, 0.0, 2.0)
        val ball = widthNear(g, stop, 0.0, 2.0)
        assertTrue(ball > body * 1.3f)
        // No neck: from the body to the ball the width only grows.
        var last = 0f
        for (x in (stop / 2).toInt()..stop.toInt() step 4) {
            val w = widthNear(g, x.toDouble(), 0.0, 2.0)
            assertTrue(w >= last - 0.5f)
            last = w
        }
    }

    @Test fun aHookIntoARestEndsInABall() {
        // Down, a sharp turn up-left, and the hand comes to rest before it lifts: the brush stopped
        // there, so the hook ends in its ball, not a flicked point.
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ps = ArrayList<Double>(); val ts = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until 150) { xs += 0.0; ys += i * 3.0; ps += 0.8; ts += t; t += 4.0 }
        for (k in 1..30) { xs += -k * 2.5; ys += 447.0 - k * 2.5; ps += 0.8; ts += t; t += 2.0 }
        for (k in 1..10) { xs += -75.0; ys += 372.0; ps += 0.85; ts += t; t += 10.0 }
        val g = build(xs.toDoubleArray(), ys.toDoubleArray(), ps.toDoubleArray(), ts.toDoubleArray())
        assertTrue(widthNear(g, -75.0, 372.0, 2.0) > widthNear(g, 0.0, 300.0, 2.0))
    }

    @Test fun slowingIntoTheLiftEndsThick() {
        // Decelerate and lift without resting: a stop all the same — the end stays thick, no point.
        val n = 160
        val xs = DoubleArray(n); val ys = DoubleArray(n); val ps = DoubleArray(n) { 0.85 }; val ts = DoubleArray(n)
        var x = 0.0
        var t = 0.0
        for (i in 0 until n) {
            val v = if (i < 100) 1.0 else 1.0 - 0.8 * (i - 100) / 59.0 // px/ms, easing to 0.2
            xs[i] = x; ts[i] = t
            x += 2.0; t += 2.0 / v
        }
        val g = build(xs, ys, ps, ts)
        assertTrue(widthNear(g, xs[n - 1], 0.0, 1.0) >= widthNear(g, xs[80], 0.0, 1.0))
    }

    @Test fun theWidthNeverSteps() {
        // The hand jumps from a light to a hard press mid-stroke: the brush spreads into it gradually.
        val n = 200
        val (xs, ys, _, ts) = line(n, 0.5)
        val ps = DoubleArray(n) { if (it < n / 2) 0.3 else 0.95 }
        val g = build(xs, ys, ps, ts)
        for (i in 1 until g.pointCount) {
            if (g.centerline[2 * i] < 150f || g.centerline[2 * i] > 340f) continue // the landing and the round end are their own
            val ds = kotlin.math.hypot(g.centerline[2 * i] - g.centerline[2 * i - 2], g.centerline[2 * i + 1] - g.centerline[2 * i - 1])
            assertTrue(g.halfWidths[i] - g.halfWidths[i - 1] <= 1.25 * 0.25 * ds) // the round footprints add a touch
        }
    }

    @Test fun theLandingOpensFromItsTip() {
        // Setting straight off, the brush lands on its tip and spreads as it travels.
        val (xs, ys, ps, ts) = line(120, 0.8, pressure = 0.9)
        val g = build(xs, ys, ps, ts, finished = false)
        assertTrue(g.halfWidths[2] < g.halfWidths[80] * 0.6f)
    }
}
