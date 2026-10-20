package com.xnotes.core.steno.trace

import com.xnotes.core.steno.glyph.Composer
import com.xnotes.core.steno.glyph.GlyphShape
import com.xnotes.core.steno.glyph.Recognizer
import com.xnotes.format.GlyphJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class RecognizerTest {

    private val dir = listOf(File("src/main/assets/glyphs"), File("app/src/main/assets/glyphs")).first { it.isDirectory }
    private val ids = listOf("hm-d", "hm-t-vlasove", "hm-s", "hm-m", "hm-ma")
    private val shapes = ids.associateWith { GlyphJson.read(File(dir, "$it.json").readText())!!.shape }
    private val rec = Recognizer(shapes.toList())

    /** [x], [y] as a hand writes them: resampled like pen samples, a little drift and jitter. */
    private fun hand(x: DoubleArray, y: DoubleArray, seed: Int, amp: Double = 0.012): Pair<DoubleArray, DoubleArray> {
        val r = Random(seed)
        val n = x.size
        var dx = 0.0
        var dy = 0.0
        val ox = DoubleArray(n)
        val oy = DoubleArray(n)
        for (i in 0 until n) {
            dx = 0.92 * dx + r.nextDouble(-amp, amp)
            dy = 0.92 * dy + r.nextDouble(-amp, amp)
            ox[i] = x[i] + dx + r.nextDouble(-amp, amp) / 3
            oy[i] = y[i] + dy + r.nextDouble(-amp, amp) / 3
        }
        return ox to oy
    }

    private fun path(s: GlyphShape) = s.paths.maxBy { it.size }.let { it.x to it.y }

    @Test
    fun eachElementWrittenAloneIsRecognized() {
        for ((k, id) in listOf("hm-d", "hm-t-vlasove", "hm-s", "hm-m").withIndex()) {
            val (x, y) = path(shapes.getValue(id))
            val (hx, hy) = hand(x, y, k)
            val m = rec.recognize(hx, hy)
            assertEquals("$id: ${m.map { it.id }}", 1, m.size)
            assertEquals(id, m[0].id)
        }
    }

    @Test
    fun mAndMaAreOneElementToRecognize() {
        // The same form with and without shading: m is recognized with a clear margin, never má.
        val (x, y) = path(shapes.getValue("hm-m"))
        val m = rec.recognize(x, y)
        assertEquals(listOf("hm-m"), m.map { it.id })
        assertTrue(m[0].margin >= Recognizer.SURE_MARGIN)
        val (ax, ay) = path(shapes.getValue("hm-ma"))
        assertTrue(rec.recognize(ax, ay).none { it.id == "hm-ma" })
    }

    @Test
    fun aComposedWordIsReadBackElementByElement() {
        // Elements meeting at corners (t's downstroke turns sharply into o's hairline, o's runs
        // into t's hook top) are read one by one.
        val more = (ids + listOf("hm-t4-1-t", "hm-t7-1-o")).associateWith { GlyphJson.read(File(dir, "$it.json").readText())!!.shape }
        val r = Recognizer(more.toList())
        val t = more.getValue("hm-t4-1-t")
        val o = more.getValue("hm-t7-1-o")
        val ink = Composer.compose(listOf(Composer.Part(t), Composer.Part(o), Composer.Part(t)))
        val (hx, hy) = hand(ink.x, ink.y, 7)
        assertEquals(listOf("hm-t4-1-t", "hm-t7-1-o", "hm-t4-1-t"), r.recognize(hx, hy).map { it.id })
    }

    @Test
    fun elementsJoinedWithoutACornerAreNeverMisread() {
        // d, vlasové t and s run into each other along tangents, with no corner or turn between
        // them to cut at: such a word may go unread, but never wrongly.
        val ink = Composer.compose(listOf(Composer.Part(shapes.getValue("hm-d")), Composer.Part(shapes.getValue("hm-t-vlasove")), Composer.Part(shapes.getValue("hm-s"))))
        val (hx, hy) = hand(ink.x, ink.y, 7)
        val got = rec.recognize(hx, hy).map { it.id }
        assertTrue(got.toString(), listOf("hm-d", "hm-t-vlasove", "hm-s").containsAll(got))
    }

    @Test
    fun aScribbleIsNotForcedIntoAnElement() {
        val n = 300
        val x = DoubleArray(n) { it * 0.01 }
        val y = DoubleArray(n) { 0.4 + 0.3 * sin(it * 0.21) * cos(it * 0.047) + 0.1 * sin(it * PI / 37) }
        assertTrue(rec.recognize(x, y).isEmpty())
    }

    @Test
    fun anElementWrittenSmallerThanTheBookIsStillFound() {
        val (x, y) = path(shapes.getValue("hm-d"))
        val (hx, hy) = hand(DoubleArray(x.size) { (x[it] - x[0]) * 0.85 }, DoubleArray(y.size) { y[it] * 0.85 }, 3, 0.008)
        val m = rec.recognize(hx, hy)
        assertEquals(listOf("hm-d"), m.map { it.id })
        assertEquals(0.85, m[0].scale, 0.08)
    }

    @Test
    fun aHalfOfAnElementIsNoSmallerElement() {
        // Height tells elements apart: the top half of a d is not a d written small.
        val (x, y) = path(shapes.getValue("hm-d"))
        val half = (0 until x.size).filter { y[it] >= 0.5 }
        val m = rec.recognize(DoubleArray(half.size) { x[half[it]] - x[half[0]] }, DoubleArray(half.size) { y[half[it]] })
        assertTrue(m.none { it.id == "hm-d" })
    }

    @Test
    fun theHandsSlantIsItsHabitNotOneStrokes() {
        com.xnotes.core.steno.HandSlant.clear()
        // Upright strokes, then one leaning far forward: the habit stays upright.
        repeat(6) { com.xnotes.core.steno.HandSlant.add(DoubleArray(20) { 0.0 }, DoubleArray(20) { it.toDouble() }, 15.0) }
        com.xnotes.core.steno.HandSlant.add(DoubleArray(20) { -it.toDouble() }, DoubleArray(20) { it.toDouble() }, 15.0)
        assertEquals(0.0, com.xnotes.core.steno.HandSlant.estimate()!!, 1e-9)
        com.xnotes.core.steno.HandSlant.clear()
    }

    @Test
    fun zVlasoveStandsOnlyInItsPlacesAtAWordsStart() {
        val z = GlyphJson.read(File(dir, "hm-t48-1-z-vlasove.json").readText())!!
        assertTrue(z.atStart)
        assertEquals(listOf(-0.5, 2.0), z.places)
        // The places survive a save.
        assertEquals(z.places, GlyphJson.read(GlyphJson.write(z))!!.places)
        val r = Recognizer(listOf(z.id to z.shape), rules = mapOf(z.id to Recognizer.Rule(z.atStart, z.places)))
        val (fx, fy) = path(z.shape)
        fun at(bottom: Double): List<String> {
            val (hx, hy) = hand(DoubleArray(fx.size) { fx[it] - fx[0] }, DoubleArray(fy.size) { fy[it] - fy.min() + bottom }, 7, 0.004)
            return r.recognize(hx, hy).map { it.id }
        }
        assertEquals(listOf(z.id), at(-0.5))
        assertEquals(listOf(z.id), at(2.0))
        assertTrue(at(0.75).isEmpty())
    }
}
