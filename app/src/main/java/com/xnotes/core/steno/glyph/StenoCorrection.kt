package com.xnotes.core.steno.glyph

import com.xnotes.core.steno.FixKind
import com.xnotes.core.steno.FixSegment
import com.xnotes.core.steno.HandCleanup
import com.xnotes.core.steno.HandSize
import com.xnotes.core.steno.LineFrame
import com.xnotes.core.steno.StenoFix
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The whole correction of a 速記 stroke at pen-up (fork): the hand's clean-up first, then, where the
 * catalogue recognizes an element for sure, that stretch redrawn in the element's clean form and
 * marked with it. Everything not recognized stays the cleaned hand (a CLEAN stretch), which is the
 * normal case: only very sure matches are fixed on the fly.
 */
object StenoCorrection {

    /** The catalogue as the recognizer sees it: built once per catalogue state. */
    class Catalogue(val glyphs: List<Glyph>) {
        val byId: Map<String, Glyph> = glyphs.associateBy { it.id }
        private val live = glyphs.filter { it.category !in Recognizer.tuning.skip }
        val recognizer = Recognizer(
            live.map { it.id to it.shape }, live.flatMap { g -> g.examples.map { g.id to it } },
            live.filter { it.atStart || it.places.isNotEmpty() }.associate { it.id to Recognizer.Rule(it.atStart, it.places) },
        )
    }

    /** Where a placed form meets the hand at its ends: blended over this much of it, in units. */
    private const val BLEND = 0.1

    /** How close (in steps) an element must already sit to a half-step height to be set onto it. */
    private const val SNAP = 0.15

    /**
     * Element [shape] drawn into samples [a]..[b] of ([fx], [fy]) (page px, written in place), along
     * the hand ([hx], [hy], page px, set to the book's slant). The form is drawn at [scale] times its
     * size, from where the hand's stretch starts; a height within [SNAP] of a whole half step is
     * set exactly onto it; [widen] stretches it across beyond that. A straight hairline keeps its direction and takes the hand's length.
     * The placed form is then sheared by [back] (x per px of height above [base]) from the book's
     * slant to the slant the stroke is shown at. Returns the element's raise: how far it sits
     * above its own height, in half steps' multiples.
     */
    internal fun place(
        shape: GlyphShape, a: Int, b: Int, hx: DoubleArray, hy: DoubleArray,
        fx: DoubleArray, fy: DoubleArray, base: Double, unit: Double, scale: Double = 1.0, back: Double = 0.0, widen: Double = 1.0,
    ): Double {
        val ux = DoubleArray(b - a + 1) { (hx[a + it] - hx[a]) / unit }
        val uy = DoubleArray(b - a + 1) { (base - hy[a + it]) / unit }
        val cum = DoubleArray(b - a + 1)
        for (i in 1 until cum.size) cum[i] = cum[i - 1] + hypot(ux[i] - ux[i - 1], uy[i] - uy[i - 1])
        val total = max(cum.last(), 1e-9)
        val fractions = DoubleArray(cum.size) { cum[it] / total }
        val dir = straightDirection(shape)
        var raise = 0.0
        val (px, py) = if (dir == null) {
            val main = shape.paths.maxBy { it.size }
            val own = main.y[0] * scale
            val offset = uy[0] - own
            val half = Math.round(offset * 2) / 2.0
            raise = half
            val startY = if (abs(offset - half) < SNAP) own + half else uy[0]
            Recognizer.placed(shape, 0.0, startY, fractions, scale, widen)
        } else {
            var d = dir
            val wx = ux.last()
            val wy = uy.last() - uy[0]
            if (d[0] * wx + d[1] * wy < 0) d = doubleArrayOf(-d[0], -d[1])
            val len = d[0] * wx + d[1] * wy
            DoubleArray(fractions.size) { d[0] * len * fractions[it] } to DoubleArray(fractions.size) { uy[0] + d[1] * len * fractions[it] }
        }
        for (k in cum.indices) {
            val i = a + k
            // Blend into the hand at both ends, so the form meets its neighbours without a step.
            val edge = min(cum[k], total - cum[k])
            val t = (edge / BLEND).coerceIn(0.0, 1.0)
            val w = if (a == 0 && k < cum.size / 2 || b == fx.size - 1 && k >= cum.size / 2) 1.0 else t * t * (3 - 2 * t)
            val gy = base - py[k] * unit
            val gx = hx[a] + px[k] * unit + back * (base - gy)
            fx[i] = fx[i] + (gx - fx[i]) * w
            fy[i] = fy[i] + (gy - fy[i]) * w
        }
        return raise
    }

    /** The direction of an element drawn as one straight line, or null. */
    internal fun straightDirection(s: GlyphShape): DoubleArray? {
        val p = s.paths.maxByOrNull { it.size } ?: return null
        if (s.paths.size != 1 || p.closed || p.size < 3) return null
        val dx = p.x.last() - p.x[0]
        val dy = p.y.last() - p.y[0]
        val l = hypot(dx, dy)
        if (l < 0.5) return null
        for (i in 0 until p.size) if (kotlin.math.abs((p.x[i] - p.x[0]) * dy - (p.y[i] - p.y[0]) * dx) / l > 0.03 * l) return null
        return doubleArrayOf(dx / l, dy / l)
    }

    /**
     * A stroke as the recognizer reads it: [x]/[y] in units (x from its start, y up from its base
     * line), with [bx] the page x at the book's slant, [base] its base line and [toBook] the shear
     * that set it there.
     */
    class Reading(val x: DoubleArray, val y: DoubleArray, val bx: DoubleArray, val base: Double, val toBook: Double)

    /**
     * How the recognizer reads samples [rx]/[ry] (page px): unsmoothed (the clean-up's smoothing
     * shrinks small loops, s, past what a sure match allows; the recognizer steadies the line
     * itself, more lightly), set to the book's slant the forms are printed at (from the hand's
     * habit [hand], else the stroke's own), and the hand mapped onto the book's lines — its habitual
     * bottoms onto the base line and tops onto the 1 line ([lines]), across as much as up — so
     * elements are read at the textbook's size. Pen-up, the corpus gate and a taught example all
     * read writing through this one function, so an example is matched as it was written.
     */
    fun reading(
        rx: DoubleArray, ry: DoubleArray, frame: LineFrame, lines: Pair<Double, Double>? = HandSize.estimate(),
        hand: Double? = com.xnotes.core.steno.HandSlant.estimate(),
    ): Reading {
        val n = rx.size
        val unit = frame.unit
        val (bx, by, toBook) = HandCleanup.toBook(rx, ry, frame, hand)
        val base = frame.baseFor(by.sorted()[n / 2])
        val (h0, h1) = lines ?: (0.0 to 1.0)
        val k = 1.0 / (h1 - h0)
        return Reading(
            DoubleArray(n) { (bx[it] - bx[0]) / unit * k },
            DoubleArray(n) { (frame.unitsAbove(by[it], base) - h0) * k },
            bx, base, toBook,
        )
    }

    fun correct(
        rx: DoubleArray, ry: DoubleArray, frame: LineFrame?, params: HandCleanup.Params, catalogue: Catalogue?,
        lines: Pair<Double, Double>? = HandSize.estimate(),
    ): StenoFix {
        val n = rx.size
        val (cx, cy) = HandCleanup.clean(rx, ry, frame, params)
        if (frame == null || catalogue == null || catalogue.recognizer.isEmpty || n < 6) return StenoFix.of(rx, ry, cx, cy)
        val unit = frame.unit
        val r = reading(rx, ry, frame, lines)
        val bx = r.bx
        val my = r.y
        val base = r.base
        // A placed form is sheared back to the slant the stroke is shown at: the hand's own,
        // unless leaned.
        val shown = HandCleanup.slantChange(rx, ry, unit, params.slantDegrees, params.slant)
        val toBook = r.toBook
        val matches = catalogue.recognizer.recognize(r.x, my)
        if (matches.isEmpty()) return StenoFix.of(rx, ry, cx, cy)

        // The stroke's stretches in order: each recognized element, and the hand between them.
        val segs = ArrayList<FixSegment>()
        var next = 0
        for (m in matches) {
            val g = catalogue.byId[m.id] ?: continue
            val a = max(m.from, next)
            val b = m.to
            if (b - a < 3) continue
            if (a > next) segs += FixSegment(next, a - 1, FixKind.CLEAN)
            segs += FixSegment(a, b, FixKind.GLYPH, glyph = g.id, reading = g.reading)
            next = b + 1
        }
        if (segs.none { it.kind == FixKind.GLYPH }) return StenoFix.of(rx, ry, cx, cy)
        if (next <= n - 1) segs += FixSegment(next, n - 1, FixKind.CLEAN)
        return redraw(rx, ry, cx, cy, bx, my, segs, catalogue, base, unit, shown - toBook)
    }

    /** How much of a recognized element, in units of arc at each end, bends from the hand into
     *  the textbook form, so it meets the writing beside it without a step. */
    private const val BRIDGE = 0.15

    /**
     * The stroke with each recognized element in [segs] redrawn in its textbook form, and nothing
     * else touched: every sample outside an element keeps the hand's clean-up ([cx], [cy]), so one
     * element never moves, rescales or deletes the writing around it. An element keeps the hand's
     * place and advance across (its form laid over the stretch's width at the book's slant, [bx]),
     * takes its textbook height on its own line (raised by the nearest half step the hand raised it,
     * read in the recognizer's units [uy]), and bends into the hand over [BRIDGE] at each end that
     * has writing beside it. Samples ride the form at their share of the stretch's arc. ([back]:
     * the shear from the book's slant to the slant the stroke is shown at.)
     */
    internal fun redraw(
        rx: DoubleArray, ry: DoubleArray, cx: DoubleArray, cy: DoubleArray, bx: DoubleArray, uy: DoubleArray,
        segs: List<FixSegment>, catalogue: Catalogue, base: Double, unit: Double, back: Double,
    ): StenoFix {
        val n = rx.size
        val fx = cx.copyOf()
        val fy = cy.copyOf()
        val out = ArrayList<FixSegment>()
        for (sg in segs) {
            val g = sg.glyph?.let { catalogue.byId[it] }
            if (sg.kind != FixKind.GLYPH || g == null) { out += sg; continue }
            val a = sg.from
            val b = sg.to
            val main = g.shape.paths.maxBy { it.size }
            var low = Double.MAX_VALUE
            var high = -Double.MAX_VALUE
            var left = Double.MAX_VALUE
            var right = -Double.MAX_VALUE
            for (i in a..b) {
                low = min(low, uy[i]); high = max(high, uy[i])
                left = min(left, bx[i]); right = max(right, bx[i])
            }
            // Its height: the textbook's, on its own line, raised by the nearest half step the hand
            // raised it (an element with fixed places sits at the one nearest where the hand put it).
            val lift = ((low - main.y.min()) + (high - main.y.max())) / 2
            val raise = if (g.places.isNotEmpty()) g.places.minBy { abs(it - low) } - main.y.min()
            else Math.round(lift / 0.5) * 0.5
            // Across: the form's own width laid over the hand's, so what follows stays where it was.
            val formLeft = main.x.min()
            val formWidth = main.x.max() - formLeft
            val across = if (formWidth > 0.15) ((right - left) / unit / formWidth).coerceIn(0.5, 2.0) else 1.0
            val cum = DoubleArray(b - a + 1)
            for (k in 1 until cum.size) cum[k] = cum[k - 1] + hypot(cx[a + k] - cx[a + k - 1], cy[a + k] - cy[a + k - 1]) / unit
            val total = max(cum.last(), 1e-9)
            val (ox, oy) = Recognizer.placed(g.shape, 0.0, 0.0, DoubleArray(cum.size) { cum[it] / total })
            for (k in cum.indices) {
                val i = a + k
                val gy = base - (main.y[0] + oy[k] + raise) * unit
                val gx = left + (main.x[0] - formLeft) * across * unit + ox[k] * across * unit + back * (base - gy)
                val inStart = if (a > 0) cum[k] else Double.MAX_VALUE
                val inEnd = if (b < n - 1) total - cum[k] else Double.MAX_VALUE
                val t = (min(inStart, inEnd) / BRIDGE).coerceIn(0.0, 1.0)
                val w = t * t * (3 - 2 * t)
                fx[i] = cx[i] + (gx - cx[i]) * w
                fy[i] = cy[i] + (gy - cy[i]) * w
            }
            out += sg.copy(raise = raise)
        }
        return StenoFix(out, FloatArray(n) { (fx[it] - rx[it]).toFloat() }, FloatArray(n) { (fy[it] - ry[it]).toFloat() })
    }
}
