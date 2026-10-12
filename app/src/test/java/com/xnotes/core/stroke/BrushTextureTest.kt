package com.xnotes.core.stroke

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 筆 brush's texture: deterministic, solid when wet, streaked when dry, never outside the outline. */
class BrushTextureTest {

    private fun ink(speed: Double, n: Int = 150, dry: Double = 0.5, x0: Double = 10.0): BrushInk {
        val xs = DoubleArray(n) { x0 + it * 2.0 }
        val ys = DoubleArray(n) { 50.0 }
        val ps = DoubleArray(n) { 0.8 }
        val ts = DoubleArray(n) { it * 2.0 / speed }
        return BrushEngine.buildInk(
            xs, ys, ps, ts, baseWidth = 120.0, pressureEnabled = true, m = 0.03, gamma = 0.5,
            entry = 0.5, harai = 0.5, pressureLow = 0.0, pressureHigh = 1.0, speedScale = 1.0,
            smoothScale = 1.0, finished = true, dry = dry, textured = true,
        )
    }

    @Test fun theSameStrokeGetsTheSameStreaks() {
        val a = ink(0.5)
        val b = ink(0.5)
        assertEquals(a.strips.size, b.strips.size)
        for (i in a.strips.indices) assertArrayEquals(a.strips[i].left, b.strips[i].left, 0f)
    }

    @Test fun aSaveRoundingDoesNotMoveTheSeed() {
        assertEquals(BrushTexture.seedOf(101.234, 57.891), BrushTexture.seedOf(101.23, 57.89))
    }

    /** How much ink a stroke lays down: opacity summed over every hair point. */
    private fun inkOf(k: BrushInk) = k.strips.sumOf { st -> st.alphas.sumOf { it.toDouble() } }

    @Test fun fastStrokesLayDownLessInkThanSlowOnes() {
        assertTrue(inkOf(ink(2.0)) < inkOf(ink(0.1)))
    }

    @Test fun aDrierBrushLaysDownLessInkWhenFast() {
        assertTrue(inkOf(ink(4.0, dry = 0.0)) > inkOf(ink(4.0, dry = 1.0)))
    }

    @Test fun everyHairStaysInsideTheOutline() {
        val k = ink(1.0)
        val y = k.outline.centerline[1]
        val maxHalf = k.outline.halfWidths.max()
        for (st in k.strips) for (j in 0 until st.pointCount) {
            assertTrue(kotlin.math.abs(st.left[2 * j + 1] - y) <= maxHalf + 0.01f)
            assertTrue(kotlin.math.abs(st.right[2 * j + 1] - y) <= maxHalf + 0.01f)
        }
    }

    @Test fun liftingOutOfABallDragsHairsWithinTheBounds() {
        // Write along x, rest, then lift off moving up: faint hairs trail out of the ball upward, and
        // the outline — what bounds and redraws use — still holds every one of them.
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ps = ArrayList<Double>(); val ts = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until 120) { xs += i * 3.0; ys += 200.0; ps += 0.8; ts += t; t += 3.0 }
        for (k in 1..12) { xs += 357.0; ys += 200.0; ps += 0.85; ts += t; t += 10.0 }
        for (k in 1..6) { xs += 357.0; ys += 200.0 - k * 2.0; ps += 0.6; ts += t; t += 3.0 }
        val k = BrushEngine.buildInk(
            xs.toDoubleArray(), ys.toDoubleArray(), ps.toDoubleArray(), ts.toDoubleArray(), baseWidth = 120.0,
            pressureEnabled = true, m = 0.03, gamma = 1.0, entry = 0.5, harai = 0.5, pressureLow = 0.0,
            pressureHigh = 1.0, speedScale = 1.0, smoothScale = 1.0, finished = true, dry = 0.5, textured = true,
        )
        val o = k.outline
        var ball = 0f
        for (i in 0 until o.pointCount) if (kotlin.math.abs(o.centerline[2 * i] - 357f) < 2f && kotlin.math.abs(o.centerline[2 * i + 1] - 200f) < 2f) ball = maxOf(ball, o.halfWidths[i])
        var above = false
        for (st in k.strips) for (j in 0 until st.pointCount) {
            val x = (st.left[2 * j] + st.right[2 * j]) / 2f
            val y = (st.left[2 * j + 1] + st.right[2 * j + 1]) / 2f
            if (y < 200f - ball && kotlin.math.abs(x - 357f) < ball) above = true
            var inside = false
            for (i in 0 until o.pointCount) {
                if (kotlin.math.hypot(x - o.centerline[2 * i], y - o.centerline[2 * i + 1]) <= o.halfWidths[i] + 0.5f) { inside = true; break }
            }
            assertTrue(inside)
        }
        assertTrue(above)
    }

    @Test fun anUntexturedStrokeHasNoStrips() {
        val n = 40
        val g = BrushEngine.buildInk(
            DoubleArray(n) { it * 2.0 }, DoubleArray(n), DoubleArray(n) { 0.8 }, DoubleArray(n) { it * 4.0 },
            baseWidth = 120.0, pressureEnabled = true, m = 0.03, gamma = 0.5, entry = 0.5, harai = 0.5,
            pressureLow = 0.0, pressureHigh = 1.0, speedScale = 1.0, smoothScale = 1.0, finished = false,
            dry = 0.5, textured = false,
        )
        assertTrue(g.strips.isEmpty())
    }
}
