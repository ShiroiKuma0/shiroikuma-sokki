package com.xnotes.core.steno

import com.xnotes.core.steno.glyph.Glyph
import com.xnotes.core.steno.glyph.Recognizer
import com.xnotes.core.steno.glyph.StenoCorrection
import com.xnotes.format.GlyphJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The gate live recognition must pass (fork): 白い熊's recorded writing, corrected exactly as at
 * pen-up and scored element by element. A wrong element costs far more than a missed one, since it
 * is redrawn as a textbook form and malforms the word; and nothing outside a recognized element may
 * move from the hand's clean-up. Notes marked held out were never tuned on, so they tell whether a
 * change generalises rather than fits.
 *
 * Two kinds of truth. A stroke with `labels` (a reviewed note: each stretch 白い熊 marked, as
 * `[from, to, reading]`, a null reading for writing that is no element) is scored by place: a
 * recognized element is right where it lies over a stretch of its own reading, wrong over another
 * reading or over plain writing. A stroke with only a word (`truth`) is scored by the elements the
 * word is made of. Labelled strokes also tell where 白い熊's elements really meet, so the gate
 * reports how many of those joins the recognizer cuts at all — an element that is never cut free
 * can never be read.
 *
 * The catalogue is the shipped one, overlaid by 白い熊's own (`steno/glyphs/`, exported from the
 * phone) when present, so the gate reads with the taught examples the phone reads with — except
 * those taught from the very note being scored.
 *
 * While [Recognizer.Companion.Tuning.live] is off the gate only reports; once it is on, it must hold.
 */
class CorpusGateTest {

    /** The elements each written word is made of. */
    private val expect = mapOf("toto" to listOf("t", "o", "t", "o"), "je" to listOf("je"), "test" to listOf("t", "st"))

    /** Readings that are not wrong in a word though not counted right: the e of “test” joins as o. */
    private val allowed = mapOf("test" to setOf("o"))

    /** How near, in units of path, a cut must fall to a join to free the elements either side. */
    private val joinReach = 0.1

    class Score(
        var right: Int = 0, var wrong: Int = 0, var missed: Int = 0, var damage: Double = 0.0,
        var joins: Int = 0, var cut: Int = 0,
    ) {
        fun add(o: Score) {
            right += o.right; wrong += o.wrong; missed += o.missed; damage = max(damage, o.damage)
            joins += o.joins; cut += o.cut
        }
    }

    private fun resourceDir(name: String): File? =
        listOf(File("src/test/resources/steno/$name"), File("app/src/test/resources/steno/$name")).firstOrNull { it.isDirectory }

    /** The shipped elements, with 白い熊's exported catalogue laid over them by id. */
    private fun glyphs(): List<Glyph> {
        val dir = listOf(File("src/main/assets/glyphs"), File("app/src/main/assets/glyphs")).first { it.isDirectory }
        val byId = LinkedHashMap<String, Glyph>()
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".json") }) GlyphJson.read(f.readText())?.let { byId[it.id] = it }
        resourceDir("glyphs")?.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f -> GlyphJson.read(f.readText())?.let { byId[it.id] = it } }
        return byId.values.filter { it.reviewed }
    }

    private fun corpus(): JSONArray =
        JSONArray(javaClass.classLoader!!.getResourceAsStream("steno/corpus.json")!!.bufferedReader().readText())

    @Test
    fun recognitionPassesTheGate() {
        val glyphs = glyphs()
        val examples = glyphs.sumOf { it.examples.size }
        val shared = StenoCorrection.Catalogue(glyphs)
        val notes = corpus()
        val params = HandCleanup.Params()
        val all = Score()
        val held = Score()
        val report = JSONArray()
        for (n in 0 until notes.length()) {
            val note = notes.getJSONObject(n)
            val frame = LineFrame(note.getDouble("band"))
            val heldOut = note.getBoolean("heldOut")
            // Writing taught from this very note would be scored against itself: left out here.
            val doc = note.optString("doc", "")
            val cat = if (doc.isNotEmpty() && glyphs.any { g -> g.examples.any { it.origin == doc } })
                StenoCorrection.Catalogue(glyphs.map { g -> g.copy(examples = g.examples.filter { it.origin != doc }) }) else shared
            HandSlant.clear(); HandSize.clear()
            val strokes = note.getJSONArray("strokes")
            val truth = note.optJSONArray("truth")
            val labels = note.optJSONArray("labels")
            val score = Score()
            val out = JSONArray()
            for (k in 0 until strokes.length()) {
                val a = strokes.getJSONArray(k)
                val x = DoubleArray(a.length()) { a.getJSONArray(it).getDouble(0) }
                val y = DoubleArray(a.length()) { a.getJSONArray(it).getDouble(1) }
                // Exactly the pen-up order: the hand's habits first, then the correction.
                HandSlant.add(x, y, frame.unit)
                val b = frame.baseFor(y.sorted()[y.size / 2])
                HandSize.add(DoubleArray(y.size) { frame.unitsAbove(y[it], b) })
                if (x.size < 2) continue
                val (cx, cy) = HandCleanup.clean(x, y, frame, params)
                val fix = StenoCorrection.correct(x, y, frame, params, cat)
                val found = fix.segments.filter { it.kind == FixKind.GLYPH }
                val got = found.map { it.reading ?: "?" }
                val lab = labels?.optJSONArray(k)
                val word = truth?.takeUnless { it.isNull(k) }?.optString(k)
                var line = ""
                if (lab != null) {
                    val spans = (0 until lab.length()).map { lab.getJSONArray(it) }.map { s ->
                        Triple(s.getInt(0), s.getInt(1), if (s.isNull(2)) null else s.getString(2))
                    }
                    val used = HashSet<Int>()
                    for (g in found) {
                        // The stretch it lies over most, by samples.
                        var bestAt = -1
                        var bestOver = 0
                        for ((i, s) in spans.withIndex()) {
                            val over = min(g.to, s.second) - max(g.from, s.first) + 1
                            if (over > bestOver) { bestOver = over; bestAt = i }
                        }
                        val s = spans.getOrNull(bestAt)
                        when {
                            s == null -> {}
                            s.third == g.reading && 2 * bestOver >= g.to - g.from + 1 && bestAt !in used -> { score.right++; used += bestAt }
                            else -> score.wrong++
                        }
                    }
                    score.missed += spans.indices.count { spans[it].third != null && it !in used }
                    // The joins: where two marked elements meet.
                    val r = StenoCorrection.reading(x, y, frame)
                    val cuts = cat.recognizer.recognitionCuts(r.x, r.y)
                    val cum = DoubleArray(x.size)
                    for (i in 1 until x.size) cum[i] = cum[i - 1] + hypot(r.x[i] - r.x[i - 1], r.y[i] - r.y[i - 1])
                    val missedJoins = ArrayList<Int>()
                    val sorted = spans.sortedBy { it.first }
                    for (i in 1 until sorted.size) {
                        val p = sorted[i - 1]
                        val q = sorted[i]
                        if (p.third == null || q.third == null || q.first - p.second > 3) continue
                        val at = q.first
                        score.joins++
                        if (cuts.any { kotlin.math.abs(cum[it] - cum[at]) <= joinReach }) score.cut++ else missedJoins += at
                    }
                    line = "${sorted.map { it.third ?: "·" }} -> $got" + if (missedJoins.isEmpty()) "" else " · uncut joins at $missedJoins"
                } else if (word != null && expect.containsKey(word)) {
                    val want = expect.getValue(word).toMutableList()
                    val ok = allowed[word] ?: emptySet()
                    for (g in got) if (want.remove(g)) score.right++ else if (g !in ok) score.wrong++
                    score.missed += want.size
                    line = "$word -> $got"
                } else {
                    line = "(no truth) -> $got"
                }
                // Damage: how far the writing outside every recognized element moved from the clean-up.
                for (i in x.indices) {
                    if (found.any { i in it.from..it.to }) continue
                    val d = hypot(x[i] + fix.dx[i] - cx[i], y[i] + fix.dy[i] - cy[i]) / frame.unit
                    if (d > score.damage) score.damage = d
                }
                println("GATE ${note.getString("name")}${if (heldOut) " (held out)" else ""} #$k $line")
                out.put(JSONObject().put("word", word ?: lab?.let { "labelled" } ?: "").put("x", JSONArray(x)).put("y", JSONArray(y))
                    .put("fx", JSONArray(DoubleArray(x.size) { x[it] + fix.dx[it] }))
                    .put("fy", JSONArray(DoubleArray(y.size) { y[it] + fix.dy[it] }))
                    .put("segs", JSONArray(fix.segments.map { JSONArray(listOf(it.from, it.to, it.kind.id, it.reading ?: "")) })))
            }
            all.add(score)
            if (heldOut) held.add(score)
            report.put(JSONObject().put("name", note.getString("name")).put("band", note.getDouble("band")).put("strokes", out))
        }
        HandSlant.clear(); HandSize.clear()
        File("build/steno-gate").apply { mkdirs() }.resolve("report.json").writeText(report.toString())
        fun line(s: Score) = "right ${s.right} wrong ${s.wrong} missed ${s.missed} damage %.3fu".format(s.damage) +
            if (s.joins > 0) " · joins cut ${s.cut} of ${s.joins}" else ""
        println("GATE catalogue: ${glyphs.size} elements, $examples taught examples")
        println("GATE all: ${line(all)}")
        println("GATE held out: ${line(held)}")
        if (!Recognizer.tuning.live) return
        assertEquals("wrong elements on writing never tuned on", 0, held.wrong)
        assertTrue("wrong elements on the whole corpus: ${all.wrong}", all.wrong <= 1)
        assertTrue("writing outside recognized elements moved ${all.damage}u", all.damage < 0.01)
    }
}
