package com.xnotes.core.steno.trace

import com.xnotes.core.steno.FixKind
import com.xnotes.core.steno.FixSegment
import com.xnotes.core.steno.HandCleanup
import com.xnotes.core.steno.LineFrame
import com.xnotes.core.steno.StenoFix
import com.xnotes.core.steno.glyph.GlyphExample
import com.xnotes.core.steno.glyph.Recognizer
import com.xnotes.core.steno.glyph.StenoEdit
import com.xnotes.format.GlyphJson
import com.xnotes.format.JsonPull
import com.xnotes.format.JsonWrite
import com.xnotes.format.StenoFixJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import kotlin.math.hypot

class StenoEditTest {

    private val dir = listOf(File("src/main/assets/glyphs"), File("app/src/main/assets/glyphs")).first { it.isDirectory }
    private fun glyph(id: String) = GlyphJson.read(File(dir, "$id.json").readText())!!
    private val frame = LineFrame(64.0)
    private val base = 192.0

    /** A wavy stroke along the base line, 60 samples. */
    private val rx = DoubleArray(60) { 100.0 + it * 1.5 }
    private val ry = DoubleArray(60) { base - 4 - 3 * kotlin.math.sin(it * 0.3) }

    @Test
    fun assigningAStretchSplicesItInAndKeepsTheRest() {
        val fix0 = StenoFix(listOf(FixSegment(0, 59, FixKind.CLEAN)), FloatArray(60), FloatArray(60))
        val fix = StenoEdit.assign(rx, ry, fix0, frame, 20, 40, glyph("hm-t40-1-k"))
        assertEquals(listOf(0 to 19, 20 to 40, 41 to 59), fix.segments.map { it.from to it.to })
        val s = fix.segments[1]
        assertEquals(FixKind.GLYPH, s.kind)
        assertEquals("hm-t40-1-k", s.glyph)
        assertTrue(s.checked)
        // Outside the stretch nothing moved.
        for (i in 0 until 15) assertEquals(0f, fix.dx[i])
    }

    @Test
    fun plainMarksTheStretchCheckedAndClean() {
        val fix = StenoEdit.plain(rx, ry, null, frame, HandCleanup.Params(), 10, 30)
        val s = fix.segments.first { it.from == 10 }
        assertEquals(FixKind.CLEAN, s.kind)
        assertTrue(s.checked)
    }

    @Test
    fun aReplacementLandsOnTheStretchEnds() {
        val v = StenoEdit.View(rx, ry, null, frame)
        // A written arc, any size: fitted between the stretch's ends.
        val fx = DoubleArray(30) { it / 29.0 * 2 }
        val fy = DoubleArray(30) { kotlin.math.sin(it / 29.0 * Math.PI) * 0.5 }
        val fix = StenoEdit.replace(rx, ry, null, frame, 10, 50, fx, fy)
        val ax = rx[10] + fix.dx[10]; val ay = ry[10] + fix.dy[10]
        val bx = rx[50] + fix.dx[50]; val by = ry[50] + fix.dy[50]
        assertEquals(v.x[10], ax, 0.01); assertEquals(v.y[10], ay, 0.01)
        assertEquals(v.x[50], bx, 0.5); assertEquals(v.y[50], by, 0.5)
        assertEquals(FixKind.REPLACED, fix.segments.first { it.from == 10 }.kind)
    }

    @Test
    fun segmentFlagsRoundTripTheCodec() {
        val fix = StenoFix(
            listOf(FixSegment(0, 9, FixKind.CLEAN, checked = true), FixSegment(10, 19, FixKind.GLYPH, glyph = "g", reading = "d", raise = 0.5)),
            FloatArray(20) { it * 0.1f }, FloatArray(20),
        )
        val sw = StringWriter()
        val j = JsonWrite(sw)
        j.beginObject(); StenoFixJson.write(j, fix); j.endObject()
        val p = JsonPull(StringReader(sw.toString()))
        p.beginObject(); p.nextName()
        val back = StenoFixJson.read(p)!!
        assertEquals(fix.segments, back.segments)
    }

    @Test
    fun anExampleTeachesTheRecognizerAWayOfWriting() {
        val k = glyph("hm-t40-1-k")
        val main = k.shape.paths.maxBy { it.size }
        // The user's k: flattened and wider than the book's, far enough off that the form alone
        // does not take it.
        val wx = DoubleArray(main.size) { main.x[it] * 1.35 }
        val wy = DoubleArray(main.size) { main.y[it] * 0.55 }

        val taught = Recognizer(
            listOf(k.id to k.shape, glyph("hm-t49-1-nez").let { it.id to it.shape }),
            listOf(k.id to GlyphExample(DoubleArray(wx.size) { wx[it] - wx[0] }, DoubleArray(wy.size) { wy[it] - wy[0] })),
        )
        assertEquals(listOf(k.id), taught.recognize(wx, wy).map { it.id })
    }

    @Test
    fun anExampleKeepsWhereItStoodAndWhereItCameFrom() {
        val k = glyph("hm-t40-1-k")
        val ex = GlyphExample(doubleArrayOf(0.0, 0.5, 1.0), doubleArrayOf(0.0, -0.5, -0.2), 0.75, GlyphExample.DRILL)
        val plain = GlyphExample(doubleArrayOf(0.0, 0.4), doubleArrayOf(0.0, 0.3))
        val back = GlyphJson.read(GlyphJson.write(k.copy(examples = listOf(ex, plain))))!!
        assertEquals(0.75, back.examples[0].y0, 1e-9)
        assertEquals(GlyphExample.DRILL, back.examples[0].origin)
        assertTrue(back.examples[1].y0.isNaN())
        assertEquals("", back.examples[1].origin)
        // A file from before the heights were kept still loads, every writing at no known height.
        val old = GlyphJson.write(k.copy(examples = listOf(plain)))
        assertTrue(!old.contains("example_info"))
        assertTrue(GlyphJson.read(old)!!.examples.single().y0.isNaN())
    }

    @Test
    fun anExampleIsReadAsPenUpReadsWriting() {
        val ex = StenoEdit.example(rx, ry, frame, 10, 40, "note:1", hand = null)
        val r = com.xnotes.core.steno.glyph.StenoCorrection.reading(rx, ry, frame, null, null)
        assertEquals(r.y[10], ex.y0, 1e-9)
        assertEquals(31, ex.x.size)
        for (i in ex.x.indices) {
            assertEquals(r.x[10 + i] - r.x[10], ex.x[i], 1e-9)
            assertEquals(r.y[10 + i] - r.y[10], ex.y[i], 1e-9)
        }
        assertEquals("note:1", ex.origin)
    }

    @Test
    fun theReviewsHandlesStandBetweenCutsToo() {
        val ux = DoubleArray(rx.size) { (rx[it] - rx[0]) / frame.unit }
        val uy = DoubleArray(ry.size) { (base - ry[it]) / frame.unit }
        val cuts = Recognizer.cutPoints(ux, uy).toSet()
        val stops = Recognizer.handlePoints(ux, uy)
        assertTrue(stops.toSet().containsAll(cuts))
        // A stop at least every HANDLE_STEP of path, plus a sample.
        for (i in 1 until stops.size) {
            var len = 0.0
            for (j in stops[i - 1] + 1..stops[i]) len += hypot(ux[j] - ux[j - 1], uy[j] - uy[j - 1])
            assertTrue("gap $len at ${stops[i]}", len < Recognizer.HANDLE_STEP + 0.1)
        }
    }

    @Test
    fun theBuiltInCatalogueHasAllElements() {
        assertEquals(104, dir.listFiles()!!.count { it.name.endsWith(".json") })
    }
}
