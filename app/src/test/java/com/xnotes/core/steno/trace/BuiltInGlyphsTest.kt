package com.xnotes.core.steno.trace

import com.xnotes.format.GlyphJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The elements the app ships (`assets/glyphs`) must all read back, each with its scan crop. */
class BuiltInGlyphsTest {

    private val dir = listOf(File("src/main/assets/glyphs"), File("app/src/main/assets/glyphs")).first { it.isDirectory }

    @Test
    fun everyBuiltInElementReadsAndHasItsCrop() {
        val files = dir.listFiles()!!.filter { it.name.endsWith(".json") }
        assertTrue(files.size >= 5)
        for (f in files) {
            val g = GlyphJson.read(f.readText())
            assertNotNull(f.name, g)
            assertEquals(f.name, f.name.removeSuffix(".json"), g!!.id)
            assertTrue(f.name, g.shape.paths.isNotEmpty() && g.shape.paths.all { it.size > 1 })
            assertNotNull(f.name, g.source)
            assertTrue(f.name, File(dir, g.id + ".png").isFile)
        }
    }
}
