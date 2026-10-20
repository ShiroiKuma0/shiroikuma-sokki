package com.xnotes.core.steno.trace

import com.xnotes.core.steno.FixKind
import com.xnotes.core.steno.HandCleanup
import com.xnotes.core.steno.LineFrame
import com.xnotes.core.steno.glyph.Composer
import com.xnotes.core.steno.glyph.StenoCorrection
import com.xnotes.format.GlyphJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.random.Random

class StenoCorrectionTest {

    private val dir = listOf(File("src/main/assets/glyphs"), File("app/src/main/assets/glyphs")).first { it.isDirectory }
    private val glyphs = listOf("hm-d", "hm-t-vlasove", "hm-s", "hm-m", "hm-ma", "hm-t4-1-t", "hm-t7-1-o").map { GlyphJson.read(File(dir, "$it.json").readText())!! }
    private val catalogue = StenoCorrection.Catalogue(glyphs)
    private val frame = LineFrame(64.0)
    private val base = 192.0

    @Test
    fun aWrittenWordIsCorrectedElementByElement() {
        val shape = { id: String -> glyphs.first { it.id == id }.shape }
        // t o t: elements meeting at corners, which is where a stroke is cut.
        val ink = Composer.compose(listOf(Composer.Part(shape("hm-t4-1-t")), Composer.Part(shape("hm-t7-1-o")), Composer.Part(shape("hm-t4-1-t"))))
        val r = Random(5)
        // On the page: 15 px a step, a little hand jitter, sampled sparser than the ink.
        val idx = (0 until ink.size step 3).toList()
        val rx = DoubleArray(idx.size) { 100 + ink.x[idx[it]] * frame.unit + r.nextDouble(-0.25, 0.25) }
        val ry = DoubleArray(idx.size) { base - ink.y[idx[it]] * frame.unit + r.nextDouble(-0.25, 0.25) }
        val fix = StenoCorrection.correct(rx, ry, frame, HandCleanup.Params(slant = 0.0), catalogue)
        val glyphSegs = fix.segments.filter { it.kind == FixKind.GLYPH }
        assertEquals(listOf("hm-t4-1-t", "hm-t7-1-o", "hm-t4-1-t"), glyphSegs.map { it.glyph })
        assertEquals("t", glyphSegs[0].reading)
        // The segments tile the stroke, in order, without overlap.
        var next = 0
        for (s in fix.segments) { assertEquals(next, s.from); next = s.to + 1 }
        assertEquals(rx.size, next)
        // The corrected line has no step anywhere.
        for (i in 1 until rx.size) {
            val ax = rx[i - 1] + fix.dx[i - 1]; val ay = ry[i - 1] + fix.dy[i - 1]
            val bx = rx[i] + fix.dx[i]; val by = ry[i] + fix.dy[i]
            assertTrue("step at $i", hypot(bx - ax, by - ay) < 0.25 * frame.unit)
        }
    }

    @Test
    fun theFullCatalogueStaysFastAtPenUp() {
        val all = dir.listFiles()!!.filter { it.name.endsWith(".json") }.map { GlyphJson.read(it.readText())!! }
        val cat = StenoCorrection.Catalogue(all)
        val shape = { id: String -> all.first { it.id == id }.shape }
        // t o t: elements meeting at corners, which is where a stroke is cut.
        val ink = Composer.compose(listOf(Composer.Part(shape("hm-t4-1-t")), Composer.Part(shape("hm-t7-1-o")), Composer.Part(shape("hm-t4-1-t"))))
        val idx = (0 until ink.size step 3).toList()
        val rx = DoubleArray(idx.size) { 100 + ink.x[idx[it]] * frame.unit }
        val ry = DoubleArray(idx.size) { base - ink.y[idx[it]] * frame.unit }
        StenoCorrection.correct(rx, ry, frame, HandCleanup.Params(slant = 0.0), cat)
        val t0 = System.nanoTime()
        val fix = StenoCorrection.correct(rx, ry, frame, HandCleanup.Params(slant = 0.0), cat)
        val ms = (System.nanoTime() - t0) / 1e6
        println("FULL ${fix.segments.filter { it.kind == FixKind.GLYPH }.map { it.glyph }} in $ms ms")
        assertTrue("took $ms ms", ms < 1500)
    }

    @Test
    fun withoutACatalogueOnlyTheCleanUpRuns() {
        val rx = DoubleArray(40) { 100.0 + it }
        val ry = DoubleArray(40) { base - it * 0.5 }
        val fix = StenoCorrection.correct(rx, ry, frame, HandCleanup.Params(), null)
        assertEquals(listOf(FixKind.CLEAN), fix.segments.map { it.kind })
    }
}
