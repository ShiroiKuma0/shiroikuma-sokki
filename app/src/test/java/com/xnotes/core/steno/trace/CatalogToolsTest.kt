package com.xnotes.core.steno.trace

import com.xnotes.core.steno.glyph.CatalogEdit
import com.xnotes.core.steno.glyph.GlyphCategory
import com.xnotes.core.steno.glyph.GlyphExample
import com.xnotes.core.steno.glyph.Recognizer
import com.xnotes.core.steno.glyph.ShapeEdit
import com.xnotes.format.GlyphJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/** The catalogue's tools: one-tap segment fixes, merging and splitting, the test pad's ranking. */
class CatalogToolsTest {

    private val dir = listOf(File("src/main/assets/glyphs"), File("app/src/main/assets/glyphs")).first { it.isDirectory }
    private fun glyph(id: String) = GlyphJson.read(File(dir, "$id.json").readText())!!

    @Test
    fun straighteningOneSegmentMakesItALineAndLeavesTheOthers() {
        val d = glyph("hm-d")
        val seg = ShapeEdit.Seg(0, 1)
        val out = ShapeEdit.apply(d.shape, seg, ShapeEdit.Fix.STRAIGHT)
        val c = out.paths[0].curves[1]
        // The control points lie on the chord, the ends where they were.
        val dx = c[6] - c[0]; val dy = c[7] - c[1]
        val len = hypot(dx, dy)
        for (k in listOf(2, 4)) assertTrue(abs((c[k] - c[0]) * dy - (c[k + 1] - c[1]) * dx) / len < 1e-9)
        val before = d.shape.paths[0].curves[1]
        assertEquals(before[0], c[0], 0.0); assertEquals(before[7], c[7], 0.0)
        for (k in listOf(0, 2, 3)) assertArrayEquals(d.shape.paths[0].curves[k], out.paths[0].curves[k], 0.0)
        // Resampled at about the old spacing, and the form's ends unmoved.
        val p0 = d.shape.paths[0]; val p1 = out.paths[0]
        assertTrue(abs(p1.size - p0.size) < p0.size / 5)
        assertEquals(p0.x[0], p1.x[0], 0.002); assertEquals(p0.y.last(), p1.y.last(), 0.002)
    }

    @Test
    fun shadingTogglesOnOneSegmentOnly() {
        val m = glyph("hm-m")
        val seg = ShapeEdit.Seg(0, 1)
        val was = ShapeEdit.isShaded(m.shape, seg)
        val once = ShapeEdit.apply(m.shape, seg, ShapeEdit.Fix.SHADED)
        assertEquals(!was, ShapeEdit.isShaded(once, seg))
        val twice = ShapeEdit.apply(once, seg, ShapeEdit.Fix.SHADED)
        assertEquals(was, ShapeEdit.isShaded(twice, seg))
        // The first segment keeps its shading.
        assertEquals(ShapeEdit.isShaded(m.shape, ShapeEdit.Seg(0, 0)), ShapeEdit.isShaded(once, ShapeEdit.Seg(0, 0)))
    }

    @Test
    fun aCurveFlowsOnFromItsNeighbours() {
        val d = glyph("hm-d")
        val straight = ShapeEdit.apply(d.shape, ShapeEdit.Seg(0, 1), ShapeEdit.Fix.STRAIGHT)
        val out = ShapeEdit.apply(straight, ShapeEdit.Seg(0, 1), ShapeEdit.Fix.CURVE)
        val cs = out.paths[0].curves
        fun angle(a: DoubleArray, b: DoubleArray): Double {
            val n = hypot(a[0], a[1]) * hypot(b[0], b[1])
            return acos(((a[0] * b[0] + a[1] * b[1]) / n).coerceIn(-1.0, 1.0))
        }
        assertTrue(angle(CurveFit.deriv(cs[0], 1.0), CurveFit.deriv(cs[1], 0.0)) < 1e-6)
        assertTrue(angle(CurveFit.deriv(cs[1], 1.0), CurveFit.deriv(cs[2], 0.0)) < 1e-6)
    }

    @Test
    fun aTapPicksTheSegmentUnderIt() {
        val d = glyph("hm-d")
        val c = d.shape.paths[0].curves[2]
        val q = CurveFit.point(c, 0.5)
        assertEquals(ShapeEdit.Seg(0, 2), ShapeEdit.hit(d.shape, q[0] + 0.02, q[1]))
        assertEquals(null, ShapeEdit.hit(d.shape, 50.0, 50.0))
    }

    @Test
    fun mergingKeepsEverythingTheOtherWasRecognizedBy() {
        val ex = GlyphExample(doubleArrayOf(0.0, 0.5, 1.0), doubleArrayOf(0.0, -0.5, -1.0))
        val a = glyph("hm-m").copy(examples = listOf(ex, ex))
        val b = glyph("hm-s").copy(examples = listOf(ex))
        val merged = CatalogEdit.merge(a, b)
        assertEquals(b.id, merged.id)
        assertEquals(b.reading, merged.reading)
        assertEquals(1 + 2 + 1, merged.examples.size)
        val form = merged.examples.last()
        assertEquals(0.0, form.x[0], 0.0); assertEquals(0.0, form.y[0], 0.0)
    }

    @Test
    fun splittingPartsTheWritings() {
        val e1 = GlyphExample(DoubleArray(20) { it * 0.05 }, DoubleArray(20) { -it * 0.1 })
        val e2 = GlyphExample(DoubleArray(20) { it * 0.04 }, DoubleArray(20) { -it * 0.09 })
        val g = glyph("hm-d").copy(examples = listOf(e1, e2))
        val (left, made) = CatalogEdit.split(g, setOf(1), "new", "dd", GlyphCategory.OWN)
        assertEquals(listOf(e1), left.examples)
        assertEquals(listOf(e2), made.examples)
        assertEquals("dd", made.reading)
        // Its form drawn from the writing, at the height the old form starts at.
        val y0 = g.shape.paths[0].y[0]
        assertEquals(y0, made.shape.paths[0].y[0], 0.05)
        // Nothing taken: a copy of the form.
        val (_, copy) = CatalogEdit.split(g, emptySet(), "c", "d2", GlyphCategory.OWN)
        assertTrue(copy.shape === g.shape)
        assertFalse(copy.examples.isNotEmpty())
    }

    @Test
    fun theTestPadRanksTheWrittenElementFirst() {
        val ids = listOf("hm-d", "hm-s", "hm-m", "hm-t40-1-k")
        val r = Recognizer(ids.map { it to glyph(it).shape })
        val p = glyph("hm-m").shape.paths.maxBy { it.size }
        val ranked = r.rank(p.x, p.y, 0, p.size - 1)
        assertNotNull(ranked.firstOrNull())
        assertEquals("hm-m", ranked.first().id)
        assertTrue(ranked.first().error < 0.02)
        assertTrue(ranked.size > 1)
    }
}
