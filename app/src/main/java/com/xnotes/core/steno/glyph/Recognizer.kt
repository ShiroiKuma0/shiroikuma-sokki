package com.xnotes.core.steno.glyph

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Finds catalogue elements in a written 速記 stroke — only the ones it is certain of (白い熊: “only
 * recognize highly-certain elements”); everything else stays the hand's own, cleaned.
 *
 * The stroke, in writing units (x right, y up from its base line), is cut only where it really
 * turns: its ends, its corners and its clear turns at top and bottom. A dynamic programme then
 * covers it with stretches, each either an element it is sure of or left as plain writing. A
 * stretch is sure of an element when the element's form, at the stretch's own height on the lines,
 * lies close to it (measured against the form's own size), every other element at any size lies
 * clearly further, and no longer stretch around it reads as another element as well. Every limit
 * is scored on 白い熊's recorded writing (CorpusGateTest) before it ships.
 */
class Recognizer(
    elements: List<Pair<String, GlyphShape>>,
    /** The user's own writings, by element id: matched as well as the clean forms. */
    examples: List<Pair<String, GlyphExample>> = emptyList(),
    /** Where elements may stand, by id (see [Glyph.atStart], [Glyph.places]). */
    private val rules: Map<String, Rule> = emptyMap(),
) {
    /** An element written only at a word's start, and the heights its bottom may sit at. */
    class Rule(val atStart: Boolean, val places: List<Double>)


    /**
     * Samples [from]..[to] of the stroke are element [id], written [scale] times its height and,
     * beyond that, [widen] times its width; [error] is the mean distance in the element's own units.
     */
    class Match(
        val from: Int, val to: Int, val id: String, val error: Double, val margin: Double,
        val scale: Double = 1.0, val widen: Double = 1.0,
    )

    private class Template(
        val id: String, val x: DoubleArray, val y: DoubleArray, val length: Double, val closed: Boolean,
        val shading: Double,
        /** A straight hairline's direction (unit vector), or null for any other form. */
        val straight: DoubleArray?,
        /** The form without its entry hook: matched only where the stroke runs in from an element
         *  before it, whose join makes the hook (t after o). */
        val joined: Boolean = false,
        /** The heights it spans above the base line, in units (NaN when not known). */
        val low: Double = Double.NaN,
        val high: Double = Double.NaN,
        /** The height it starts at above the base line, in units (NaN when not known). */
        val start: Double = Double.NaN,
    ) {
        /** How far its points lie from their centre, root mean square: its size. */
        val spread: Double = spread(x, y)
        val height: Double = y.max() - y.min()
        val width: Double = x.max() - x.min()

        private val sx = HashMap<Int, DoubleArray>()
        private val sy = HashMap<Int, DoubleArray>()

        /** A closed form started [k] points further round (its last point repeats its first). */
        fun shiftedX(k: Int): DoubleArray = sx.getOrPut(k) { val m = x.size - 1; DoubleArray(x.size) { x[(k + it) % m] }.let { a -> val o = a[0]; DoubleArray(a.size) { a[it] - o } } }
        fun shiftedY(k: Int): DoubleArray = sy.getOrPut(k) { val m = y.size - 1; DoubleArray(y.size) { y[(k + it) % m] }.let { a -> val o = a[0]; DoubleArray(a.size) { a[it] - o } } }
    }

    private val templates: List<Template> = run {
        val all = elements.mapNotNull { (id, s) ->
            val p = s.paths.maxByOrNull { it.size } ?: return@mapNotNull null
            if (p.size < 2) return@mapNotNull null
            val (rx, ry) = resample(p.x, p.y, N)
            Template(
                id, DoubleArray(N) { rx[it] - rx[0] }, DoubleArray(N) { ry[it] - ry[0] }, arcLength(p.x, p.y), p.closed,
                p.shade.average(), straightDirection(p.x, p.y), low = p.y.min(), high = p.y.max(), start = p.y[0],
            )
        }
        // One form with and without shading (m and má) is one element to recognize: the shading
        // comes from the pen's pressure, not the shape. Keep the unshaded one.
        val kept = ArrayList<Template>()
        for (t in all.sortedBy { it.shading }) {
            if (kept.none { k -> abs(ln(k.length / t.length)) < 0.15 && dtwStatic(k.x, k.y, t.x, t.y) < 0.08 && sameHeights(k, t) }) kept += t
        }
        // An element that opens with a hook — up and back down, as t does — is written without it
        // after another element: the join is the hook. Match those forms without it as well.
        for ((id, s) in elements) {
            val p = s.paths.maxByOrNull { it.size } ?: continue
            if (p.closed || p.size < 8 || kept.none { it.id == id && it.straight == null }) continue
            val cut = hookEnd(p.x, p.y) ?: continue
            val tx = p.x.copyOfRange(cut, p.size)
            val ty = p.y.copyOfRange(cut, p.size)
            val (rx, ry) = resample(tx, ty, N)
            kept += Template(id, DoubleArray(N) { rx[it] - rx[0] }, DoubleArray(N) { ry[it] - ry[0] }, arcLength(tx, ty), false, 0.0, null, joined = true)
        }
        // The user's writings of an element: further forms to match it by (straight hairlines are
        // matched by direction alone and need none).
        val straightIds = kept.filter { it.straight != null }.map { it.id }.toSet()
        for ((id, e) in examples) {
            if (id in straightIds || e.x.size < 2 || kept.none { it.id == id }) continue
            val (rx, ry) = resample(e.x, e.y, N)
            val closed = hypot(e.x.last() - e.x[0], e.y.last() - e.y[0]) < 0.08
            // A writing that kept where it stood is held to its heights on the lines, as the form is.
            val y0 = e.y0
            kept += Template(
                id, DoubleArray(N) { rx[it] - rx[0] }, DoubleArray(N) { ry[it] - ry[0] }, arcLength(e.x, e.y), closed, 0.0, null,
                low = if (y0.isNaN()) Double.NaN else y0 + e.y.min(), high = if (y0.isNaN()) Double.NaN else y0 + e.y.max(), start = y0,
            )
        }
        kept
    }

    /** Whether two forms span the same heights: alike in shape but not in height (o and z
     *  vlasové) they are different elements. */
    private fun sameHeights(a: Template, b: Template) =
        a.low.isNaN() || b.low.isNaN() || (abs(a.low - b.low) < 0.2 && abs(a.high - b.high) < 0.2)

    val isEmpty: Boolean get() = templates.isEmpty()

    /**
     * The elements in a stroke ([rawX], [rawY], units above its base line), only those it is
     * certain of when [sureOnly]. A stretch is reported when its form fits it closely at its own
     * height on the lines (an error measured against the element's own size), clearly better than
     * any other element at any size, and when no longer stretch around it reads as another element
     * as well: a part of a t is never a ť.
     */
    fun recognize(rawX: DoubleArray, rawY: DoubleArray, sureOnly: Boolean = true): List<Match> {
        val n = rawX.size
        if (templates.isEmpty() || n < 6) return emptyList()
        // Measured on a lightly smoothed copy: a hand's jitter lengthens a path far beyond its shape.
        val (x, y) = steadied(rawX, rawY)
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        if (cum.last() < MIN_STRETCH) return emptyList()
        val cuts = cuts(x, y, cum)
        val m = cuts.size
        // Every stretch's best reading, once: (template, [error, margin, scale, widen]).
        val memo = HashMap<Long, Pair<Template, DoubleArray>?>()
        fun scored(i: Int, j: Int, after: Boolean): Pair<Template, DoubleArray>? {
            val a = cuts[i]
            val b = cuts[j]
            if (cum[b] - cum[a] < MIN_STRETCH) return null
            return memo.getOrPut((i.toLong() shl 32) or (j.toLong() shl 1) or (if (after) 1L else 0L)) { score(x, y, a, b, cum[b] - cum[a], after) }
        }
        fun sure(s: DoubleArray) = s[0] <= SURE_ERROR && s[1] >= SURE_MARGIN
        val best = DoubleArray(m) { Double.POSITIVE_INFINITY }
        val back = IntArray(m) { -1 }
        val label = arrayOfNulls<Pair<Template, DoubleArray>>(m)
        best[0] = 0.0
        for (j in 1 until m) {
            for (i in max(0, j - MAX_SPAN) until j) {
                if (!best[i].isFinite()) continue
                val len = cum[cuts[j]] - cum[cuts[i]]
                var cost = len * PLAIN_PRICE
                var lab: Pair<Template, DoubleArray>? = null
                // Only a reading it is sure of may take a stretch from the hand (unsure ones too
                // for the test pad), always for less than leaving it plain.
                val sc = scored(i, j, label[i] != null)
                if (sc != null && (!sureOnly || sure(sc.second))) {
                    val c = len * PLAIN_PRICE * (0.25 + 0.5 * min(1.0, sc.second[0] / SURE_ERROR)) + MATCH_PRICE
                    if (c < cost) { cost = c; lab = sc }
                }
                if (best[i] + cost < best[j]) {
                    best[j] = best[i] + cost
                    back[j] = i
                    label[j] = lab
                }
            }
        }
        val out = ArrayList<Match>()
        var j = m - 1
        while (j > 0) {
            val i = back[j]
            if (i < 0) break
            label[j]?.let { (t, s) ->
                // A longer stretch around it that reads as another element nearly as well: then
                // it may be only a part of that one, and nothing is sure.
                val rival = sureOnly && listOf(i - 1 to j, i to j + 1, i - 1 to j + 1).any { (p, q) ->
                    p >= 0 && q < m && scored(p, q, p > 0 && label[p] != null)?.let { (u, v) -> u.id != t.id && v[0] <= s[0] + SURE_MARGIN } == true
                }
                if (!rival) out += Match(cuts[i], cuts[j], t.id, s[0], s[1], s[2], s[3])
            }
            j = i
        }
        return out.reversed()
    }

    /** [x], [y] smoothed over about 0.03 units of arc, sample for sample. */
    private fun steadied(x: DoubleArray, y: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        val total = arcLength(x, y)
        if (total <= 0) return x to y
        val sigma = (0.03 / (total / (n - 1))).coerceIn(0.0, 25.0)
        if (sigma < 0.5) return x to y
        val r = (3 * sigma).toInt()
        val k = DoubleArray(2 * r + 1) { kotlin.math.exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        fun sm(v: DoubleArray) = DoubleArray(n) { i ->
            var s = 0.0
            var w = 0.0
            for (j in -r..r) {
                val q = i + j
                if (q < 0 || q >= n) continue
                s += k[j + r] * v[q]; w += k[j + r]
            }
            s / w
        }
        val sx = sm(x)
        val sy = sm(y)
        sx[0] = x[0]; sy[0] = y[0]; sx[n - 1] = x[n - 1]; sy[n - 1] = y[n - 1]
        return sx to sy
    }

    /** Diagnostics: every element's error against the whole of [x], [y], best first. */
    internal fun explain(rx: DoubleArray, ry: DoubleArray): List<String> {
        val (x, y) = steadied(rx, ry)
        val len = arcLength(x, y)
        val (sx, sy) = resample(x, y, N)
        val x0 = sx[0]
        val y0 = sy[0]
        for (k in sx.indices) { sx[k] -= x0; sy[k] -= y0 }
        return templates.map { t -> t.id + " err=" + "%.3f".format(dtw(sx, sy, t.x, t.y)) + " ratio=" + "%.2f".format(len / t.length) + " straight=" + (t.straight != null) } +
            listOf("kept=" + templates.map { it.id } + " cuts=" + cuts(x, y, DoubleArray(x.size).also { c -> for (i in 1 until x.size) c[i] = c[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1]) }).size)
    }

    /** One element's fit to a stretch, for the catalogue's test pad. */
    class Candidate(val id: String, val error: Double, val margin: Double, val sure: Boolean, val scale: Double = 1.0, val widen: Double = 1.0)

    /**
     * Every element's fit to samples [a]..[b] of the stroke, best first, with the best one's margin
     * to the next (the others carry 0): what the recognizer weighed for that stretch.
     */
    fun rank(rawX: DoubleArray, rawY: DoubleArray, a: Int, b: Int, limit: Int = 5): List<Candidate> {
        if (templates.isEmpty() || b - a < 2) return emptyList()
        val (x, y) = steadied(rawX, rawY)
        var len = 0.0
        for (i in a + 1..b) len += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        val ranked = ranked(x, y, a, b, len, after = a > 0, strict = true)
        val margin = ranked.firstOrNull()?.let { (t, sc) -> margin(x, y, a, b, len, a > 0, t.id, sc[0]) } ?: 0.0
        return ranked.take(limit).mapIndexed { k, (t, sc) ->
            val m = if (k == 0) margin else 0.0
            Candidate(t.id, sc[0], m, k == 0 && sc[0] <= SURE_ERROR && m >= SURE_MARGIN, sc[1], sc[2])
        }
    }

    /** The best element for samples [a]..[b]: (template, [error, margin, scale, widen]). */
    private fun score(x: DoubleArray, y: DoubleArray, a: Int, b: Int, len: Double, after: Boolean): Pair<Template, DoubleArray>? {
        val (t, sc) = ranked(x, y, a, b, len, after, strict = true).firstOrNull() ?: return null
        // Far from sure anyway: no need to weigh its rivals.
        if (sc[0] > 2 * SURE_ERROR) return t to doubleArrayOf(sc[0], 0.0, sc[1], sc[2])
        return t to doubleArrayOf(sc[0], margin(x, y, a, b, len, after, t.id, sc[0]), sc[1], sc[2])
    }

    /**
     * How much worse the best *other* element fits samples [a]..[b] than [id] at [error], each
     * other element taken at whatever size fits it best: a rival ruled out only for its size is
     * still a rival, so a lone survivor is never sure by default.
     */
    private fun margin(x: DoubleArray, y: DoubleArray, a: Int, b: Int, len: Double, after: Boolean, id: String, error: Double): Double {
        val other = ranked(x, y, a, b, len, after, strict = false).firstOrNull { it.first.id != id }?.second?.get(0) ?: RIVAL_CEILING
        return min(other, RIVAL_CEILING) - error
    }

    /**
     * Each element's best form for samples [a]..[b], best first: (template, [error, scale,
     * widen]). The error is the mean distance between the stretch and the form, measured against
     * the form's own size (so small elements are held to the same standard as large ones), plus
     * what it costs that the stretch is written at another size, width or length than the book's,
     * and away from the heights the form stands at on the lines (or whole half steps above or
     * below them, which carry vowels). [strict] keeps only sizes the hand plausibly writes the
     * element at; otherwise any size counts, at its price. [after]: the stretch follows a
     * recognized element, so forms written without their entry hook may be it.
     */
    private fun ranked(
        x: DoubleArray, y: DoubleArray, a: Int, b: Int, len: Double, after: Boolean, strict: Boolean,
    ): List<Pair<Template, DoubleArray>> {
        val (sx, sy) = resample(x.copyOfRange(a, b + 1), y.copyOfRange(a, b + 1), N)
        val x0 = sx[0]
        val y0 = sy[0]
        for (k in sx.indices) { sx[k] -= x0; sy[k] -= y0 }
        val perId = HashMap<String, Pair<Template, DoubleArray>>()
        fun offer(t: Template, error: Double, scale: Double, widen: Double) {
            val cur = perId[t.id]
            if (cur == null || error < cur.second[0]) perId[t.id] = t to doubleArrayOf(error, scale, widen)
        }
        val spread = spread(sx, sy)
        val height = sy.max() - sy.min()
        val width = sx.max() - sx.min()
        val closedStretch = hypot(x[b] - x[a], y[b] - y[a]) < max(0.12, 0.1 * len)
        val straight = straightDirection(x.copyOfRange(a, b + 1), y.copyOfRange(a, b + 1))
        var handLo = Double.MAX_VALUE
        var handHi = -Double.MAX_VALUE
        for (i in a..b) { handLo = min(handLo, y[i]); handHi = max(handHi, y[i]) }
        for (t in templates) {
            if (t.joined && !after) continue
            val rule = rules[t.id]
            if (rule?.atStart == true && a != 0) continue
            val places = rule?.places.orEmpty()
            // An element with fixed places must be written at one of them.
            val placed = if (places.isEmpty()) 0.0 else places.minOf { p ->
                max(0.0, abs(handLo - p) - HEIGHT_SLACK) + max(0.0, abs(handHi - (p + t.height)) - HEIGHT_SLACK)
            }
            if (t.straight != null) {
                // A straight hairline: its length follows the word, within reason, so its
                // direction, straightness and where it runs on the lines are what count.
                if (straight == null) continue
                val ratio = len / t.length
                if (strict && (ratio < 0.6 || ratio > 1.6)) continue
                if (t.straight[0] * straight[0] + t.straight[1] * straight[1] < 0.9) continue
                val ang = abs(t.straight[0] * straight[1] - t.straight[1] * straight[0])
                var outside = placed
                if (places.isEmpty() && !t.low.isNaN()) {
                    outside = max(0.0, t.low - HEIGHT_SLACK - handLo) + max(0.0, handHi - t.high - HEIGHT_SLACK)
                    // A hairline reaching under the base line (z vlasové, from half a step under it)
                    // must be written down there too: o, alike in direction, rises from the line.
                    if (t.low < -2 * FLAT) outside += max(0.0, handLo - t.low - HEIGHT_SLACK)
                }
                offer(t, straightDeviation(x, y, a, b) / len + ang * 0.6 + outside + abs(ln(ratio)) * LENGTH_PRICE, 1.0, 1.0)
                continue
            }
            val (scale, widen) = size(spread, height, width, t.spread, t.height, t.width, strict) ?: continue
            val hx = DoubleArray(N) { sx[it] / (scale * widen) }
            val hy = DoubleArray(N) { sy[it] / scale }
            val ratio = arcLength(hx, hy) / t.length
            if (strict && (ratio < 0.7 || ratio > 1.4)) continue
            val e = if (t.closed && closedStretch) (0 until N - 1 step 2).minOf { k -> dtw(hx, hy, t.shiftedX(k), t.shiftedY(k)) }
            else dtw(hx, hy, t.x, t.y)
            // Where it stands: its bottom and top against the form's, give or take whole half steps.
            var stand = 0.0
            if (!t.low.isNaN() && places.isEmpty()) {
                val lift = ((handLo - t.low * scale) + (handHi - t.high * scale)) / 2
                val step = (Math.round(lift * 2) / 2.0).coerceIn(MIN_RAISE, MAX_RAISE)
                stand = max(0.0, abs(lift - step) - STAND_SLACK)
            }
            val error = e / max(t.spread, MIN_SPREAD) + placed + stand +
                abs(ln(ratio)) * LENGTH_PRICE + abs(ln(scale)) * SCALE_PRICE + abs(ln(widen)) * WIDEN_PRICE
            offer(t, error, scale, widen)
        }
        return perId.values.sortedBy { it.second[0] }
    }

    private fun dtw(ax: DoubleArray, ay: DoubleArray, bx: DoubleArray, by: DoubleArray) = dtwStatic(ax, ay, bx, by)

    /** Mean point distance along the best warping of a onto b, within a band. */
    private fun dtwStatic(ax: DoubleArray, ay: DoubleArray, bx: DoubleArray, by: DoubleArray): Double {
        val n = ax.size
        val inf = Double.POSITIVE_INFINITY
        // cost[i][j] and the number of pairs on its best path, two rows at a time.
        var pc = DoubleArray(n + 1) { inf }
        var pl = IntArray(n + 1)
        var cc = DoubleArray(n + 1)
        var cl = IntArray(n + 1)
        pc[0] = 0.0
        for (i in 1..n) {
            cc.fill(inf)
            cl.fill(0)
            for (j in max(1, i - BAND)..min(n, i + BAND)) {
                val d = hypot(ax[i - 1] - bx[j - 1], ay[i - 1] - by[j - 1])
                var c = pc[j - 1]
                var l = pl[j - 1]
                if (pc[j] < c) { c = pc[j]; l = pl[j] }
                if (cc[j - 1] < c) { c = cc[j - 1]; l = cl[j - 1] }
                cc[j] = c + d
                cl[j] = l + 1
            }
            val t = pc; pc = cc; cc = t
            val u = pl; pl = cl; cl = u
        }
        return pc[n] / max(1, pl[n])
    }

    /** Where [recognize] cuts a stroke ([rawX], [rawY], units): on its steadied line, exactly. */
    fun recognitionCuts(rawX: DoubleArray, rawY: DoubleArray): IntArray {
        if (rawX.size < 2) return IntArray(rawX.size) { it }
        val (x, y) = steadied(rawX, rawY)
        return cutsOf(x, y)
    }

    /** Candidate cut points: ends, corners, clear turns at top and bottom (never jitter), and a
     *  regular net between. */
    fun cutsOf(x: DoubleArray, y: DoubleArray): IntArray {
        val n = x.size
        if (n < 2) return IntArray(n) { it }
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        return cuts(x, y, cum)
    }

    private fun cuts(x: DoubleArray, y: DoubleArray, cum: DoubleArray): IntArray {
        val n = x.size
        val set = sortedSetOf(0, n - 1)
        val w = 0.08
        fun at(d: Double): Int {
            var lo = 0; var hi = n - 1
            while (lo < hi) { val mid = (lo + hi) / 2; if (cum[mid] < d) lo = mid + 1 else hi = mid }
            return lo
        }
        for (i in 1 until n - 1) {
            val a = at(cum[i] - w)
            val b = at(cum[i] + w)
            if (a < i && b > i) {
                val t1 = atan2(y[i] - y[a], x[i] - x[a])
                val t2 = atan2(y[b] - y[i], x[b] - x[i])
                var d = abs(t2 - t1)
                if (d > Math.PI) d = 2 * Math.PI - d
                if (d > CORNER) set += i
            }
            // A turn at the top or bottom that is a real one: the highest (lowest) point for
            // [TURN_REACH] each way, standing [TURN_DEPTH] clear of both ends of that reach.
            val l = at(cum[i] - TURN_REACH)
            val r = at(cum[i] + TURN_REACH)
            if (l < i && r > i) {
                var top = true
                var bottom = true
                for (k in l..r) { if (y[k] > y[i]) top = false; if (y[k] < y[i]) bottom = false }
                if (top && y[i] - max(y[l], y[r]) >= TURN_DEPTH || bottom && min(y[l], y[r]) - y[i] >= TURN_DEPTH) set += i
            }
        }
        // A light regular net, since elements joined by the book's tangent bridges meet without
        // any corner or turn; what keeps the many stretches this makes from matching by chance is
        // the error against the element's own size, the margin, and the longer-stretch check.
        if (NET > 0) {
            var d = NET
            while (d < cum.last()) { set += at(d); d += NET }
        }
        // Thin out: no two cuts closer than [CUT_GAP] units.
        val out = ArrayList<Int>()
        for (i in set) if (out.isEmpty() || cum[i] - cum[out.last()] >= CUT_GAP || i == n - 1) {
            if (i == n - 1 && out.isNotEmpty() && cum[i] - cum[out.last()] < CUT_GAP) out[out.size - 1] = i else out += i
        }
        return out.toIntArray()
    }

    /** The unit direction, as written, of a path that is straight (every point within 3% of its
     *  length of the chord), or null. */
    private fun straightDirection(x: DoubleArray, y: DoubleArray): DoubleArray? {
        val dx = x.last() - x[0]
        val dy = y.last() - y[0]
        val n = hypot(dx, dy)
        if (n < 0.3) return null
        for (i in x.indices) if (abs((x[i] - x[0]) * dy - (y[i] - y[0]) * dx) / n > 0.03 * n + 0.02) return null
        return doubleArrayOf(dx / n, dy / n)
    }

    private fun straightDeviation(x: DoubleArray, y: DoubleArray, a: Int, b: Int): Double {
        val dx = x[b] - x[a]
        val dy = y[b] - y[a]
        val n = hypot(dx, dy).coerceAtLeast(1e-9)
        var s = 0.0
        for (i in a..b) s += abs((x[i] - x[a]) * dy - (y[i] - y[a]) * dx) / n
        return s / (b - a + 1)
    }

    companion object {
        private val CUTTER by lazy { Recognizer(emptyList()) }

        /** Where a stroke (units) may be cut into elements: what the review's handles snap to. */
        fun cutPoints(x: DoubleArray, y: DoubleArray): IntArray = CUTTER.cutsOf(x, y)

        /** The spacing, in units of path, of the review's handle stops between cuts. */
        const val HANDLE_STEP = 0.05

        /** Where the review's handles may stand on a stroke (units): its cuts, and a stop every
         *  [HANDLE_STEP] of path. */
        fun handlePoints(x: DoubleArray, y: DoubleArray): IntArray {
            val set = sortedSetOf<Int>()
            for (c in cutPoints(x, y)) set += c
            var acc = 0.0
            for (i in 1 until x.size) {
                acc += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
                if (acc >= HANDLE_STEP) { set += i; acc = 0.0 }
            }
            return set.toIntArray()
        }

        private const val N = 32

        /** The sizes, against the book's, an element is recognized at. */
        val MIN_SCALE get() = tuning.minScale
        val MAX_SCALE get() = tuning.maxScale

        /** What a size away from the book's costs, per unit of its log, in the error. */
        private const val SCALE_PRICE = 0.3

        /** What a width away from the book's costs: less than height, which carries meaning. */
        private const val WIDEN_PRICE = 0.15

        /** What a length of path away from the form's costs, per unit of its log. */
        private const val LENGTH_PRICE = 0.3

        /** Below this size (spread, units) a form is measured as if it were this large. */
        private const val MIN_SPREAD = 0.15

        /** How far, in units, an element may stand off its heights (or a half step from them). */
        private const val STAND_SLACK = 0.1

        /** The half steps an element may be raised (lowered) by: a vowel's. */
        private const val MIN_RAISE = -0.5
        private const val MAX_RAISE = 1.0

        /** The shortest stretch, in units of path, that may be an element. */
        private const val MIN_STRETCH = 0.3

        /** What “no rival at all” counts as: an error no reading would be taken at. */
        private const val RIVAL_CEILING = 1.0

        /** A cut's turn: a corner turns more than this (rad) within 0.08 units each way. */
        private const val CORNER = 1.2

        /** A turn at top or bottom is the extreme over this reach (units of path) each way… */
        private const val TURN_REACH = 0.25

        /** …standing this far (units) clear of both ends of it. */
        private const val TURN_DEPTH = 0.04

        /** The spacing of the regular net of cuts, in units of path (0: none). */
        private val NET get() = tuning.net

        /** No two cuts closer than this, in units of path. */
        private const val CUT_GAP = 0.1

        /**
         * Where a form's entry hook ends: the top (or bottom) it turns back from within its first
         * [HOOK_SHARE] of length, when it rises (falls) at least [HOOK_RISE] to it and then runs on
         * well past where it began; null for a form without one.
         */
        internal fun hookEnd(x: DoubleArray, y: DoubleArray): Int? {
            val cum = DoubleArray(x.size)
            for (i in 1 until x.size) cum[i] = cum[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            val reach = cum.last() * HOOK_SHARE
            val within = (0 until x.size).takeWhile { cum[it] <= reach }
            if (within.size < 3) return null
            val top = within.maxBy { y[it] }
            val bottom = within.minBy { y[it] }
            val rest = y.last() - y[0]
            return when {
                y[top] - y[0] >= HOOK_RISE && rest < -HOOK_RISE && top > 0 -> top
                y[0] - y[bottom] >= HOOK_RISE && rest > HOOK_RISE && bottom > 0 -> bottom
                else -> null
            }
        }

        /** How far, in units, writing may run past the heights a hairline spans. */
        private const val HEIGHT_SLACK = 0.25

        private const val HOOK_SHARE = 0.35
        private const val HOOK_RISE = 0.12

        /** The size, against element [s]'s own, a stretch ([x], [y], units) is written at:
         *  (height factor, further width factor), as the recognizer reads it. */
        fun scaleFor(s: GlyphShape, x: DoubleArray, y: DoubleArray): Pair<Double, Double> {
            val p = s.paths.maxByOrNull { it.size } ?: return 1.0 to 1.0
            if (p.size < 2 || x.size < 2) return 1.0 to 1.0
            val (fx, fy) = resample(p.x, p.y, N)
            val (hx, hy) = resample(x, y, N)
            val (s, w) = size(spread(hx, hy), hy.max() - hy.min(), hx.max() - hx.min(), spread(fx, fy), fy.max() - fy.min(), fx.max() - fx.min(), strict = false)!!
            return s to w
        }

        /** Below this extent, in units, a form has no height (or width) to measure by. */
        private const val FLAT = 0.15

        /**
         * A stretch's size against a form's: its height factor (by the heights, or by the overall
         * spread for a flat form) and its further width factor (1 for a form with no width). With
         * [strict], a stretch outside [MIN_SCALE]..[MAX_SCALE] of the form's height is no writing of
         * it (null): height is what tells elements apart, and a part of a t is no small t.
         */
        private fun size(
            spread: Double, height: Double, width: Double, tSpread: Double, tHeight: Double, tWidth: Double,
            strict: Boolean = true,
        ): Pair<Double, Double>? {
            val raw = when {
                tHeight >= FLAT -> height / tHeight
                tSpread > 1e-6 -> spread / tSpread
                else -> 1.0
            }
            if (strict && (raw < MIN_SCALE || raw > MAX_SCALE)) return null
            val scale = raw.coerceIn(MIN_SCALE, MAX_SCALE)
            val rawWiden = if (tWidth >= FLAT && tHeight >= FLAT) width / tWidth / scale else 1.0
            // Nor is a stretch far narrower or wider than the form: half a U is no squeezed -smus.
            if (strict && (rawWiden < MIN_WIDEN || rawWiden > MAX_WIDEN)) return null
            val widen = rawWiden.coerceIn(MIN_WIDEN, MAX_WIDEN)
            return scale to widen
        }

        /** How much narrower or wider than the book, at its height, an element is recognized. */
        val MIN_WIDEN get() = tuning.minWiden
        val MAX_WIDEN get() = tuning.maxWiden

        /** How far the points lie from their centre, root mean square. */
        internal fun spread(x: DoubleArray, y: DoubleArray): Double {
            val mx = x.average()
            val my = y.average()
            var s = 0.0
            for (i in x.indices) s += (x[i] - mx) * (x[i] - mx) + (y[i] - my) * (y[i] - my)
            return kotlin.math.sqrt(s / x.size)
        }
        private const val BAND = 5
        private const val MAX_SPAN = 24

        /** The price of leaving writing unrecognized, per unit of length. */
        private val PLAIN_PRICE get() = tuning.plainPrice

        /** A fixed price per recognized element, so one long match beats two pieces of it. */
        private val MATCH_PRICE get() = tuning.matchPrice

        /** Only these are reported: close to the element (error against its own size), and
         *  clearly closer than to any other (by at least the margin). */
        val SURE_ERROR get() = tuning.sureError
        val SURE_MARGIN get() = tuning.sureMargin

        /** The recognizer's limits and prices, together: what is tuned against recorded writing. */
        data class Tuning(
            val minScale: Double = 0.8, val maxScale: Double = 1.25,
            val minWiden: Double = 0.6, val maxWiden: Double = 1.6,
            val plainPrice: Double = 1.0, val matchPrice: Double = 0.05,
            val sureError: Double = 0.3, val sureMargin: Double = 0.15, val net: Double = 0.0,
            /**
             * Categories never read live: prefixes, endings, word signs and the user's own signs are
             * shapes that whole runs of single elements also make (roz- and eva out of t and o), so
             * they wait for context; measured on 白い熊's writing of 2026-10-17, reading them cost
             * nine wrong elements for one right one.
             */
            val skip: Set<GlyphCategory> = setOf(GlyphCategory.PREFIX, GlyphCategory.ENDING, GlyphCategory.SYMBOL, GlyphCategory.OWN),
            /**
             * Whether elements are recognized and redrawn at pen-up: only while the recognizer
             * passes the corpus gate (CorpusGateTest — no wrong element on writing it was never
             * tuned on, at most one on the whole corpus, nothing outside an element moved). Off
             * from +051 after +047–+050 misread nearly every t and o of 白い熊's 2026-10-17 lines;
             * on again from +052 with the certain-only recognizer and the local redraw.
             */
            val live: Boolean = true,
        )

        /** The tuning in force. Only tests set it. */
        @Volatile
        var tuning = Tuning()

        internal fun arcLength(x: DoubleArray, y: DoubleArray): Double {
            var s = 0.0
            for (i in 1 until x.size) s += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            return s
        }

        internal fun resample(x: DoubleArray, y: DoubleArray, n: Int): Pair<DoubleArray, DoubleArray> {
            val cum = DoubleArray(x.size)
            for (i in 1 until x.size) cum[i] = cum[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            val total = cum.last()
            val ox = DoubleArray(n)
            val oy = DoubleArray(n)
            var j = 0
            for (k in 0 until n) {
                val t = total * k / (n - 1)
                while (j < x.size - 2 && cum[j + 1] < t) j++
                val seg = cum[min(j + 1, x.size - 1)] - cum[j]
                val f = if (seg > 0) ((t - cum[j]) / seg).coerceIn(0.0, 1.0) else 0.0
                val j1 = min(j + 1, x.size - 1)
                ox[k] = x[j] + f * (x[j1] - x[j])
                oy[k] = y[j] + f * (y[j1] - y[j])
            }
            return ox to oy
        }

        /** Where element [s] puts sample positions of a matched stretch: its form, at [scale] times
         *  its size, placed at the stretch's start, each sample at its own arc fraction. */
        fun placed(s: GlyphShape, startX: Double, startY: Double, fractions: DoubleArray, scale: Double = 1.0, widen: Double = 1.0): Pair<DoubleArray, DoubleArray> {
            val p = s.paths.maxByOrNull { it.size }!!
            val cum = DoubleArray(p.size)
            for (i in 1 until p.size) cum[i] = cum[i - 1] + hypot(p.x[i] - p.x[i - 1], p.y[i] - p.y[i - 1])
            val total = cum.last()
            val ox = DoubleArray(fractions.size)
            val oy = DoubleArray(fractions.size)
            var j = 0
            for ((k, f) in fractions.withIndex()) {
                val t = total * f
                while (j < p.size - 2 && cum[j + 1] < t) j++
                val seg = cum[j + 1] - cum[j]
                val g = if (seg > 0) ((t - cum[j]) / seg).coerceIn(0.0, 1.0) else 0.0
                ox[k] = (p.x[j] + g * (p.x[j + 1] - p.x[j]) - p.x[0]) * scale * widen + startX
                oy[k] = (p.y[j] + g * (p.y[j + 1] - p.y[j]) - p.y[0]) * scale + startY
            }
            return ox to oy
        }
    }
}
