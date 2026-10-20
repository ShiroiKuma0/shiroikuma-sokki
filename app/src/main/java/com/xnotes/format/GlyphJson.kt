package com.xnotes.format

import com.xnotes.core.steno.glyph.Glyph
import com.xnotes.core.steno.glyph.GlyphCategory
import com.xnotes.core.steno.glyph.GlyphExample
import com.xnotes.core.steno.glyph.GlyphPath
import com.xnotes.core.steno.glyph.GlyphShape
import com.xnotes.core.steno.glyph.GlyphSource
import java.io.StringReader
import java.io.StringWriter

/**
 * A 速記 catalogue element on disk (fork): one JSON file per element.
 *
 * ```
 * { "format": "sokki-glyph", "version": 1, "id": "…", "reading": "d", "category": "consonant",
 *   "reviewed": true, "note": "", "created": 0, "modified": 0,
 *   "source": { "label": "Učebnice.pdf", "page": 22, "crop_base": 210.0, "crop_step": 120.0, "crop_left": 40.0 },
 *   "paths": [ { "closed": false, "curves": [[x0,y0,…,x3,y3], …], "pts": [x, y, shade, …] } ] }
 * ```
 * Coordinates are writing units (steps), rounded to 0.0001; unknown keys are skipped on read, so a
 * later build may add fields without orphaning older files.
 */
object GlyphJson {

    const val FORMAT = "sokki-glyph"
    const val VERSION = 1

    fun write(g: Glyph): String {
        val sw = StringWriter()
        val j = JsonWrite(sw)
        j.beginObject()
        j.name("format").value(FORMAT)
        j.name("version").value(VERSION)
        j.name("id").value(g.id)
        j.name("reading").value(g.reading)
        j.name("category").value(g.category.id)
        j.name("reviewed").value(g.reviewed)
        j.name("note").value(g.note)
        j.name("created").value(g.created.toDouble())
        j.name("modified").value(g.modified.toDouble())
        g.source?.let { s ->
            j.name("source").beginObject()
            j.name("label").value(s.label)
            if (s.page != null) j.name("page").value(s.page)
            j.name("crop_base").value(r4(s.cropBase))
            j.name("crop_step").value(r4(s.cropStep))
            j.name("crop_left").value(r4(s.cropLeft))
            j.endObject()
        }
        j.name("paths").beginArray()
        for (p in g.shape.paths) {
            j.beginObject()
            j.name("closed").value(p.closed)
            j.name("curves").beginArray()
            for (c in p.curves) {
                j.beginArray()
                for (v in c) j.value(r4(v))
                j.endArray()
            }
            j.endArray()
            j.name("pts").beginArray()
            for (i in 0 until p.size) j.value(r4(p.x[i])).value(r4(p.y[i])).value(r4(p.shade[i].toDouble()))
            j.endArray()
            j.endObject()
        }
        j.endArray()
        if (g.atStart) j.name("at_start").value(true)
        if (g.places.isNotEmpty()) {
            j.name("places").beginArray()
            for (v in g.places) j.value(r4(v))
            j.endArray()
        }
        if (g.examples.isNotEmpty()) {
            j.name("examples").beginArray()
            for (e in g.examples) {
                j.beginArray()
                for (i in e.x.indices) j.value(r4(e.x[i])).value(r4(e.y[i]))
                j.endArray()
            }
            j.endArray()
            // Where each writing stood and came from, beside the points so older builds, which
            // read only those, still load the element.
            if (g.examples.any { !it.y0.isNaN() || it.origin.isNotEmpty() }) {
                j.name("example_info").beginArray()
                for (e in g.examples) {
                    j.beginObject()
                    if (!e.y0.isNaN()) j.name("y0").value(r4(e.y0))
                    if (e.origin.isNotEmpty()) j.name("origin").value(e.origin)
                    j.endObject()
                }
                j.endArray()
            }
        }
        j.endObject()
        return sw.toString()
    }

    /** The element in [text], or null when it is not one. */
    fun read(text: String): Glyph? = runCatching { parse(JsonPull(StringReader(text))) }.getOrNull()

    private fun parse(p: JsonPull): Glyph? {
        var format: String? = null
        var id: String? = null
        var reading = ""
        var category = GlyphCategory.OWN
        var reviewed = true
        var note = ""
        var created = 0L
        var modified = 0L
        var source: GlyphSource? = null
        val paths = ArrayList<GlyphPath>()
        val examples = ArrayList<GlyphExample>()
        val info = ArrayList<Pair<Double, String>>()
        var atStart = false
        val places = ArrayList<Double>()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "format" -> format = p.nextString()
                "id" -> id = p.nextString()
                "reading" -> reading = p.nextString()
                "category" -> category = GlyphCategory.fromId(p.nextString())
                "reviewed" -> reviewed = p.nextBoolean()
                "note" -> note = p.nextString()
                "created" -> created = p.nextDouble().toLong()
                "modified" -> modified = p.nextDouble().toLong()
                "source" -> source = parseSource(p)
                "at_start" -> atStart = p.nextBoolean()
                "places" -> {
                    p.beginArray()
                    while (p.hasNext()) places += p.nextDouble()
                    p.endArray()
                }
                "examples" -> {
                    p.beginArray()
                    while (p.hasNext()) {
                        val v = ArrayList<Double>()
                        p.beginArray()
                        while (p.hasNext()) v += p.nextDouble()
                        p.endArray()
                        if (v.size >= 4 && v.size % 2 == 0) examples += GlyphExample(DoubleArray(v.size / 2) { v[2 * it] }, DoubleArray(v.size / 2) { v[2 * it + 1] })
                    }
                    p.endArray()
                }
                "example_info" -> {
                    p.beginArray()
                    while (p.hasNext()) {
                        var y0 = Double.NaN
                        var origin = ""
                        p.beginObject()
                        while (p.hasNext()) {
                            when (p.nextName()) {
                                "y0" -> y0 = p.nextDouble()
                                "origin" -> origin = p.nextString()
                                else -> p.skipValue()
                            }
                        }
                        p.endObject()
                        info += y0 to origin
                    }
                    p.endArray()
                }
                "paths" -> {
                    p.beginArray()
                    while (p.hasNext()) parsePath(p)?.let { paths += it }
                    p.endArray()
                }
                else -> p.skipValue()
            }
        }
        p.endObject()
        if (format != FORMAT || id.isNullOrBlank()) return null
        val told = if (info.size == examples.size) examples.mapIndexed { i, e -> GlyphExample(e.x, e.y, info[i].first, info[i].second) } else examples
        return Glyph(id, reading, category, GlyphShape(paths), source, reviewed, note, created, modified, told, atStart, places)
    }

    private fun parseSource(p: JsonPull): GlyphSource {
        var label = ""
        var page: Int? = null
        var base = 0.0
        var step = 1.0
        var left = 0.0
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "label" -> label = p.nextString()
                "page" -> page = p.nextInt()
                "crop_base" -> base = p.nextDouble()
                "crop_step" -> step = p.nextDouble()
                "crop_left" -> left = p.nextDouble()
                else -> p.skipValue()
            }
        }
        p.endObject()
        return GlyphSource(label, page, base, step, left)
    }

    private fun parsePath(p: JsonPull): GlyphPath? {
        var closed = false
        val curves = ArrayList<DoubleArray>()
        val pts = ArrayList<Double>()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "closed" -> closed = p.nextBoolean()
                "curves" -> {
                    p.beginArray()
                    while (p.hasNext()) {
                        val c = ArrayList<Double>()
                        p.beginArray()
                        while (p.hasNext()) c += p.nextDouble()
                        p.endArray()
                        if (c.size == 8) curves += c.toDoubleArray()
                    }
                    p.endArray()
                }
                "pts" -> {
                    p.beginArray()
                    while (p.hasNext()) pts += p.nextDouble()
                    p.endArray()
                }
                else -> p.skipValue()
            }
        }
        p.endObject()
        if (pts.size % 3 != 0 || pts.isEmpty()) return null
        val n = pts.size / 3
        return GlyphPath(
            curves,
            DoubleArray(n) { pts[3 * it] },
            DoubleArray(n) { pts[3 * it + 1] },
            FloatArray(n) { pts[3 * it + 2].toFloat() },
            closed,
        )
    }

    private fun r4(v: Double): Double = Math.round(v * 10000.0) / 10000.0
}
