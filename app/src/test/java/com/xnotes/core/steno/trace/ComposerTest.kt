package com.xnotes.core.steno.trace

import com.xnotes.core.steno.glyph.Composer
import com.xnotes.core.steno.glyph.GlyphShape
import com.xnotes.format.GlyphJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

class ComposerTest {

    private val dir = listOf(File("src/main/assets/glyphs"), File("app/src/main/assets/glyphs")).first { it.isDirectory }
    private fun shape(id: String) = GlyphJson.read(File(dir, "$id.json").readText())!!.shape

    @Test
    fun datSeIsComposedByTheRules() {
        val ink = Composer.compose(
            listOf(Composer.Part(shape("hm-d"), shaded = true), Composer.Part(shape("hm-t-vlasove")), Composer.Part(shape("hm-s"))),
        )
        // One continuous line: no jump anywhere.
        for (i in 1 until ink.size) assertTrue("jump at $i", hypot(ink.x[i] - ink.x[i - 1], ink.y[i] - ink.y[i - 1]) < 0.05)
        // d is shaded: some ink is three times the hairline; the rest of the word is hairline.
        assertTrue(ink.w.max() > 2.8 * GlyphShape.HAIR)
        assertEquals(GlyphShape.HAIR, ink.w.last(), 1e-9)
        // s hangs from the 2.5 line.
        assertEquals(2.5, ink.y.max(), 0.06)
        assertEquals(0.0, ink.y.min(), 0.06)
        System.getenv("COMPOSE_OUT")?.let { out ->
            File(out).writeText((0 until ink.size).joinToString("\n") { "${ink.x[it]} ${ink.y[it]} ${ink.w[it]}" })
        }
    }
}
