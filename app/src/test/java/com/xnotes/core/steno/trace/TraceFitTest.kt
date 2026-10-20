package com.xnotes.core.steno.trace

import com.xnotes.core.steno.glyph.Glyph
import com.xnotes.core.steno.glyph.GlyphCategory
import com.xnotes.core.steno.glyph.GlyphShape
import com.xnotes.core.steno.glyph.GlyphSource
import com.xnotes.format.GlyphJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class TraceFitTest {

    private val step = 100.0
    private val base = 260.0

    /** A white raster with [path] printed on it as ink [width] px wide (wider where [thick]). */
    private fun print(w: Int, h: Int, path: List<DoubleArray>, width: Double, thick: (Int) -> Boolean = { false }): Gray {
        val px = FloatArray(w * h) { 255f }
        val dense = Poly(DoubleArray(path.size) { path[it][0] }, DoubleArray(path.size) { path[it][1] }).resampled(0.5)
        for (i in 0 until dense.size) {
            val r = (if (thick(i * path.size / dense.size)) 3 * width else width) / 2
            for (y in (dense.y[i] - r - 1).toInt()..(dense.y[i] + r + 1).toInt()) for (x in (dense.x[i] - r - 1).toInt()..(dense.x[i] + r + 1).toInt()) {
                if (x !in 0 until w || y !in 0 until h) continue
                if (hypot(x - dense.x[i], y - dense.y[i]) <= r) px[y * w + x] = 10f
            }
        }
        return Gray(w, h, px)
    }

    /** [path] as a wobbling hand would trace it: offset, low-frequency drift and jitter. */
    private fun shaky(path: List<DoubleArray>, seed: Int, amp: Double): List<DoubleArray> {
        val rnd = Random(seed)
        val p = Poly(DoubleArray(path.size) { path[it][0] }, DoubleArray(path.size) { path[it][1] }).resampled(2.0)
        var dx = 0.0
        var dy = 0.0
        return List(p.size) {
            dx = 0.9 * dx + rnd.nextDouble(-1.0, 1.0) * amp * 0.3
            dy = 0.9 * dy + rnd.nextDouble(-1.0, 1.0) * amp * 0.3
            doubleArrayOf(p.x[it] + 4 + dx + rnd.nextDouble(-1.0, 1.0), p.y[it] - 3 + dy + rnd.nextDouble(-1.0, 1.0), 0.5)
        }
    }

    /** Largest distance from any point of [shape] (in units) to the true path (in px). */
    private fun deviation(shape: GlyphShape, truth: List<DoubleArray>, left: Double): Double {
        val t = Poly(DoubleArray(truth.size) { truth[it][0] }, DoubleArray(truth.size) { truth[it][1] }).resampled(0.5)
        var worst = 0.0
        for (p in shape.paths) for (i in 0 until p.size) {
            val x = left + p.x[i] * step
            val y = base - p.y[i] * step
            var best = Double.MAX_VALUE
            for (j in 0 until t.size) best = minOf(best, hypot(t.x[j] - x, t.y[j] - y))
            worst = maxOf(worst, best)
        }
        return worst / step
    }

    @Test
    fun aShakyTraceOfAStraightHairlineBecomesOneExactLine() {
        // Vlasové t: base to 2.5 steps, slanted.
        val truth = List(60) { i -> val f = i / 59.0; doubleArrayOf(80 + f * 110, base - f * 250) }
        val g = print(320, 340, truth, 6.0)
        val shape = TraceFit.fit(g, TraceFrame(base, step), listOf(shaky(truth, 1, 6.0)))!!
        assertEquals(1, shape.paths.size)
        val p = shape.paths[0]
        assertEquals("one straight piece", 1, p.curves.size)
        assertEquals(0.0, shape.minY, 1e-6)
        assertEquals(2.5, shape.maxY, 1e-6)
        assertTrue(deviation(shape, truth, 80.0) < 0.05)
    }

    @Test
    fun aClosedCircleStaysClosedAndOnThePrint() {
        // s: a small oval from the base to half a step.
        val cx = 150.0
        val cy = base - 25.0
        val truth = List(121) { i -> val a = 2 * PI * i / 120; doubleArrayOf(cx + 32 * cos(a), cy + 25 * sin(a)) }
        val g = print(300, 330, truth, 5.0)
        val shape = TraceFit.fit(g, TraceFrame(base, step), listOf(shaky(truth, 2, 5.0)))!!
        val p = shape.paths[0]
        assertTrue("closed", p.closed)
        assertEquals(0.0, shape.minY, 1e-6)
        assertEquals(0.5, shape.maxY, 1e-6)
    }

    @Test
    fun aTraceThatMissesALoopStillFollowsThePrintedLoop() {
        // A narrow loop clear of every line (0.25 to 0.75 steps): the trace runs wide of it, as
        // 白い熊's d trace did.
        val truth = List(81) { i -> val a = 2 * PI * i / 80; doubleArrayOf(150 + 60 * cos(a), base - 50 - 25 * sin(a)) }
        val g = print(320, 340, truth, 6.0)
        val wide = truth.map { doubleArrayOf(150 + (it[0] - 150) * 1.2, base - 50 + (it[1] - (base - 50)) * 1.5) }
        val shape = TraceFit.fit(g, TraceFrame(base, step), listOf(wide))!!
        assertTrue("on the print, not the trace", deviation(shape, truth, 90.0) < 0.06)
    }

    @Test
    fun shadingIsReadFromTheThickDownstroke() {
        // m-like: a cap, a long downstroke printed three times as thick, a turn up.
        val truth = ArrayList<DoubleArray>()
        for (i in 0..20) { val a = PI * (1 - i / 20.0); truth += doubleArrayOf(140 + 25 * cos(a), base - 75 - 25 * sin(a)) }
        for (i in 1..40) { val f = i / 40.0; truth += doubleArrayOf(165 - f * 40, base - 75 + f * 70) }
        for (i in 1..20) { val a = PI + PI * i / 20.0; truth += doubleArrayOf(140 + 15 * cos(a), base - 5 - 5 * sin(a)) }
        val g = print(320, 340, truth, 5.0) { it in 25..58 }
        val shape = TraceFit.fit(g, TraceFrame(base, step), listOf(shaky(truth, 3, 4.0)))!!
        val sh = shape.paths[0].shade
        val mid = sh.size / 2
        val stem = sh[mid]
        val cap = sh[2]
        assertTrue("the downstroke is shaded: $stem", stem > 0.6f)
        assertTrue("the cap is not: $cap", cap < 0.3f)
    }

    @Test
    fun snapSetsTopAndBottomOntoTheLines() {
        val (scale, shift) = TraceFit.snap(1.08, -0.05)
        fun moved(y: Double) = (y - -0.05) * scale + -0.05 + shift
        assertEquals(0.0, moved(-0.05), 1e-9)
        assertEquals(1.0, moved(1.08), 1e-9)
    }

    @Test
    fun glyphJsonRoundTrips() {
        val truth = List(60) { i -> val f = i / 59.0; doubleArrayOf(80 + f * 110, base - f * 250) }
        val shape = TraceFit.fit(print(320, 340, truth, 6.0), TraceFrame(base, step), listOf(shaky(truth, 4, 6.0)))!!
        val g = Glyph("abc", "t vlasové", GlyphCategory.HAIRLINE, shape, GlyphSource("učebnice.pdf", 28, 210.0, 120.0, 40.0), reviewed = false, note = "T27", created = 5, modified = 9)
        val back = GlyphJson.read(GlyphJson.write(g))!!
        assertEquals("abc", back.id)
        assertEquals("t vlasové", back.reading)
        assertEquals(GlyphCategory.HAIRLINE, back.category)
        assertEquals(false, back.reviewed)
        assertEquals(28, back.source!!.page)
        assertEquals(shape.paths[0].size, back.shape.paths[0].size)
        assertEquals(shape.paths[0].curves.size, back.shape.paths[0].curves.size)
        assertEquals(shape.maxY, back.shape.maxY, 1e-3)
        assertEquals(null, GlyphJson.read("{\"format\":\"other\"}"))
    }
}
