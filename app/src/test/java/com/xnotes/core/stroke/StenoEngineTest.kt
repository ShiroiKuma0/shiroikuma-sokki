package com.xnotes.core.stroke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 速記 pen: hairlines, and one shaded width only where a pressed pen moves down. */
class StenoEngineTest {

    private val thin = 1.5 // half of the 3 px hairline
    private val thick = 6.0 // four times it

    /** A straight line sampled every px from ([x0], [y0]) to ([x1], [y1]) at pressure [p]. */
    private fun line(x0: Double, y0: Double, x1: Double, y1: Double, p: Double): StrokeGeometry {
        val n = 101
        val xs = DoubleArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = DoubleArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return StenoEngine.build(xs, ys, DoubleArray(n) { p }, baseWidth = 3.0, shade = 4.0, threshold = 0.45)
    }

    private fun StrokeGeometry.widest() = (0 until pointCount).maxOf { hw(it) }

    @Test fun anUnpressedLineIsAHairlineWhateverItsDirection() {
        for (g in listOf(line(0.0, 0.0, 0.0, 100.0, 0.3), line(0.0, 100.0, 0.0, 0.0, 0.3), line(0.0, 0.0, 100.0, 0.0, 0.3))) {
            for (i in 0 until g.pointCount) assertEquals(thin, g.hw(i), 1e-6)
        }
    }

    @Test fun aPressedDownstrokeShadesItsBodyAndEasesOutAtBothEnds() {
        val g = line(0.0, 0.0, 10.0, 100.0, 0.8)
        assertEquals(thick, g.hw(50), 0.05)
        assertEquals(thin, g.hw(0), 0.05)
        assertEquals(thin, g.hw(g.pointCount - 1), 0.05)
    }

    @Test fun aPressedUpstrokeStaysAHairline() {
        val g = line(0.0, 100.0, 10.0, 0.0, 0.9)
        assertEquals(thin, g.widest(), 1e-6)
    }

    @Test fun aPressedSidewaysStrokeStaysAHairline() {
        val g = line(0.0, 0.0, 100.0, 0.0, 0.9)
        assertEquals(thin, g.widest(), 1e-6)
    }

    @Test fun howHardThePenPressesNeverChangesTheShading() {
        val a = line(0.0, 0.0, 10.0, 100.0, 0.5)
        val b = line(0.0, 0.0, 10.0, 100.0, 1.0)
        for (i in 0 until a.pointCount) assertEquals(a.hw(i), b.hw(i), 1e-9)
    }

    @Test fun aLiveDownstrokeKeepsItsWidthAtThePen() {
        val n = 101
        val xs = DoubleArray(n) { 0.0 }
        val ys = DoubleArray(n) { it.toDouble() }
        val g = StenoEngine.build(xs, ys, DoubleArray(n) { 0.8 }, 3.0, 4.0, 0.45, finished = false)
        assertEquals(thick, g.hw(n - 1), 0.05)
    }

    @Test fun aSmallPressedTurnIsAThickDot() {
        val n = 24
        val xs = DoubleArray(n) { 3.0 * kotlin.math.cos(it * 2 * Math.PI / (n - 1)) }
        val ys = DoubleArray(n) { 3.0 * kotlin.math.sin(it * 2 * Math.PI / (n - 1)) }
        val g = StenoEngine.build(xs, ys, DoubleArray(n) { 0.8 }, 3.0, 4.0, 0.45)
        for (i in 0 until n) assertEquals(thick, g.hw(i), 1e-6)
    }

    @Test fun aPressBuildingAtTheBottomOfAStrokeLeavesNoBlob() {
        // An unpressed downstroke whose press builds over its last few px before it turns back up.
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ps = ArrayList<Double>()
        for (k in 0..100) { xs += 0.0; ys += k.toDouble(); ps += 0.3 }
        for (k in 1..6) { xs += 0.0; ys += 100.0 + k * 0.7; ps += 0.8 }
        for (k in 1..60) { xs += k * 0.5; ys += 104.2 - k.toDouble(); ps += 0.8 }
        val g = StenoEngine.build(xs.toDoubleArray(), ys.toDoubleArray(), ps.toDoubleArray(), 3.0, 4.0, 0.45)
        assertTrue(g.widest() < thin + 0.2)
    }

    @Test fun aPressedReversalIsNoDot() {
        // Down and straight back up, pressed: a turn of half a circle, not a loop.
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (k in 0..8) { xs += 0.0; ys += k.toDouble() }
        for (k in 1..8) { xs += 0.5; ys += 8.0 - k }
        val g = StenoEngine.build(xs.toDoubleArray(), ys.toDoubleArray(), DoubleArray(xs.size) { 0.9 }, 3.0, 4.0, 0.45)
        assertTrue(g.widest() < thin + 0.2)
    }

    @Test fun aPressedTapIsAHairline() {
        val g = StenoEngine.build(doubleArrayOf(5.0), doubleArrayOf(5.0), doubleArrayOf(0.9), 3.0, 4.0, 0.45)
        assertEquals(thin, g.hw(0), 1e-9)
    }

    @Test fun aDotLoopWithinALongerStrokeFillsIn() {
        // A hairline, a small loop circled in place while pressed, and on.
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ps = ArrayList<Double>()
        for (k in 0..40) { xs += k.toDouble(); ys += 50.0; ps += 0.3 }
        val n = 30
        for (k in 1..n) { val a = -Math.PI / 2 + k * 2 * Math.PI / n; xs += 40.0 + 4.0 * kotlin.math.cos(a); ys += 54.0 + 4.0 * kotlin.math.sin(a); ps += 0.8 }
        for (k in 1..40) { xs += 40.0 + k; ys += 50.0; ps += 0.3 }
        val g = StenoEngine.build(xs.toDoubleArray(), ys.toDoubleArray(), ps.toDoubleArray(), 3.0, 4.0, 0.45)
        assertEquals(thick, g.hw(41 + n / 2), 1e-6)
        assertEquals(thin, g.hw(10), 1e-6)
    }

    @Test fun aKnotWrittenZoomedInWithAPressThatDipsStillFillsIn() {
        // Zoomed in, the pen moves a fraction of a px per sample; the press peaks once round the knot.
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ps = ArrayList<Double>()
        for (k in 0..200) { xs += k * 0.05; ys += 20.0; ps += 0.3 }
        val n = 400
        for (k in 1..n) {
            val a = -Math.PI / 2 + k * 2 * Math.PI / n
            xs += 10.0 + 1.5 * kotlin.math.cos(a); ys += 21.5 + 1.5 * kotlin.math.sin(a)
            ps += 0.4 + 0.3 * kotlin.math.sin(Math.PI * k / n)
        }
        for (k in 1..200) { xs += 10.0 + k * 0.05; ys += 20.0; ps += 0.3 }
        val g = StenoEngine.build(xs.toDoubleArray(), ys.toDoubleArray(), ps.toDoubleArray(), 1.0, 3.5, 0.6, smoothScale = 0.12)
        assertEquals(1.75, g.hw(201 + n / 2), 1e-6)
        assertEquals(0.5, g.hw(50), 1e-6)
    }

    @Test fun aLightKnotStaysAHairline() {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (k in 0..40) { xs += k.toDouble(); ys += 50.0 }
        val n = 30
        for (k in 1..n) { val a = -Math.PI / 2 + k * 2 * Math.PI / n; xs += 40.0 + 4.0 * kotlin.math.cos(a); ys += 54.0 + 4.0 * kotlin.math.sin(a) }
        for (k in 1..40) { xs += 40.0 + k; ys += 50.0 }
        val g = StenoEngine.build(xs.toDoubleArray(), ys.toDoubleArray(), DoubleArray(xs.size) { 0.4 }, 3.0, 4.0, 0.45)
        assertEquals(thin, g.widest(), 1e-6)
    }

    @Test fun theShadingStaysWithinTheShadedWidth() {
        val g = line(0.0, 0.0, 0.0, 100.0, 0.9)
        assertTrue(g.widest() <= thick + 1e-6)
    }
}
