package com.xnotes.core.steno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan

/** The 速記 hand clean-up: steadies without rounding corners or knots, and sets the slant. */
class HandCleanupTest {

    private val frame = LineFrame(64.0) // unit 15 px, base lines at multiples of 64
    private val base = 128.0

    /** A downstroke from the inner line to the base line, leaning [deg] forward, with tremor. */
    private fun downstroke(deg: Double, tremor: Double = 0.0): Pair<DoubleArray, DoubleArray> {
        val n = 60
        val k = tan(deg * PI / 180)
        val xs = DoubleArray(n) { i -> val h = 15.0 * (1 - i / (n - 1.0)); 100 + k * h + tremor * sin(i * 2.1) * sin(PI * i / (n - 1.0)) }
        val ys = DoubleArray(n) { i -> base - 15.0 * (1 - i / (n - 1.0)) }
        return xs to ys
    }

    @Test fun theFrameReadsUnitsOffTheRuling() {
        assertEquals(15.0, frame.unit, 1e-9)
        assertEquals(128.0, frame.baseFor(120.0), 1e-9) // writing just above a rule belongs to it
        assertEquals(128.0, frame.baseFor(140.0), 1e-9) // a descender a little under it too
        assertEquals(192.0, frame.baseFor(160.0), 1e-9) // past the bottom line: the next row
        assertEquals(1.0, frame.unitsAbove(113.0, 128.0), 1e-9)
    }

    @Test fun theSlantOfADownstrokeIsMeasured() {
        val (x, y) = downstroke(10.0)
        assertEquals(10.0, HandCleanup.degreesOf(HandCleanup.slantOf(x, y, 15.0)!!), 0.1)
    }

    @Test fun aDashHasNoSlant() {
        val x = DoubleArray(20) { 100.0 + it }
        val y = DoubleArray(20) { 128.0 }
        assertNull(HandCleanup.slantOf(x, y, 15.0))
    }

    @Test fun theStrokeIsShearedToTheTargetSlant() {
        val (x, y) = downstroke(5.0)
        val (cx, cy) = HandCleanup.clean(x, y, frame, HandCleanup.Params(steady = 0.0, slant = 1.0, slantDegrees = 25.0))
        assertEquals(25.0, HandCleanup.degreesOf(HandCleanup.slantOf(cx, cy, 15.0)!!), 0.2)
        // A shear: heights never change, and the base line stays put.
        for (i in y.indices) assertEquals(y[i], cy[i], 1e-9)
        assertEquals(x.last(), cx.last(), 1e-9)
    }

    @Test fun steadyingTakesOutTremorButKeepsTheEnds() {
        val (x, y) = downstroke(25.0, tremor = 0.8)
        val (cx, cy) = HandCleanup.clean(x, y, frame, HandCleanup.Params(steady = 1.0, slant = 0.0))
        val (sx, _) = downstroke(25.0)
        var before = 0.0; var after = 0.0
        for (i in 10 until 50) { before += abs(x[i] - sx[i]); after += abs(cx[i] - sx[i]) }
        assertTrue(after < before * 0.5)
        assertEquals(x.first(), cx.first(), 1e-9); assertEquals(y.first(), cy.first(), 1e-9)
        assertEquals(x.last(), cx.last(), 1e-9); assertEquals(y.last(), cy.last(), 1e-9)
    }

    @Test fun aCornerStaysSharp() {
        // Down to the base line, then straight out to the right: a right angle.
        val x = DoubleArray(61) { i -> if (i <= 30) 100.0 else 100.0 + (i - 30) * 0.5 }
        val y = DoubleArray(61) { i -> if (i <= 30) 113.0 + i * 0.5 else 128.0 }
        val (cx, cy) = HandCleanup.clean(x, y, frame, HandCleanup.Params(steady = 1.0, slant = 0.0))
        assertEquals(100.0, cx[30], 1e-9)
        assertEquals(128.0, cy[30], 1e-9)
    }

    @Test fun theLayerSlicesAndTurnsWithItsStroke() {
        val fix = StenoFix(
            listOf(FixSegment(0, 4), FixSegment(5, 9, raw = true)),
            FloatArray(10) { 1f }, FloatArray(10) { 0f },
        )
        val part = fix.slice(3, 7)
        assertNotNull(part)
        assertEquals(4, part!!.size)
        assertEquals(listOf(FixSegment(0, 1), FixSegment(2, 3, raw = true)), part.segments)
        val turned = fix.transformed(0.0, 1.0, -1.0, 0.0) // a quarter turn
        assertEquals(0f, turned.dx[0], 1e-6f)
        assertEquals(1f, turned.dy[0], 1e-6f)
        assertTrue(fix.withAllRaw(true).allRaw)
        assertTrue(!fix.withRaw(1, false).anyRaw)
    }
}
