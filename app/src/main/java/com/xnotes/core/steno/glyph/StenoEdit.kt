package com.xnotes.core.steno.glyph

import com.xnotes.core.steno.FixKind
import com.xnotes.core.steno.FixSegment
import com.xnotes.core.steno.HandCleanup
import com.xnotes.core.steno.LineFrame
import com.xnotes.core.steno.StenoFix
import com.xnotes.core.steno.trace.CurveFit
import com.xnotes.core.steno.trace.Poly
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Correcting 速記 writing by hand (fork): what the review does to one stretch of a stroke. Every
 * operation takes the stroke's own samples ([rx], [ry], page px) and its correction layer, and
 * returns a new layer — the samples are never touched, so every edit is undoable by swapping the
 * layer back. The stretch becomes its own segment, marked checked; the rest keeps what it had.
 */
object StenoEdit {

    /** A stroke as the review sees it: where its samples now stand, in page px and in units. */
    class View(rx: DoubleArray, ry: DoubleArray, fix: StenoFix?, frame: LineFrame?) {
        val n = rx.size
        val x = DoubleArray(n) { rx[it] + (fix?.dx?.getOrNull(it)?.toDouble() ?: 0.0) }
        val y = DoubleArray(n) { ry[it] + (fix?.dy?.getOrNull(it)?.toDouble() ?: 0.0) }
        val unit = frame?.unit ?: HandCleanup.FALLBACK_UNIT
        val base = frame?.baseFor(y.sorted()[n / 2]) ?: y.max()
        val ux = DoubleArray(n) { (x[it] - x[0]) / unit }
        val uy = DoubleArray(n) { (base - y[it]) / unit }

        /** The shear that sets the shown stroke to the book's slant, which the catalogue reads at. */
        val toBook = HandCleanup.toBook(x, y, frame).third

        /** The shown stroke at the book's slant: page px, and units from its start. */
        val bx = DoubleArray(n) { x[it] + toBook * (base - y[it]) }
        val bux = DoubleArray(n) { (bx[it] - bx[0]) / unit }

        /** Where the recognizer cuts the stroke: its corners and its turns at top and bottom. */
        val marks: IntArray = Recognizer.cutPoints(ux, uy)

        /** Where the review's handles may stand: those, and every [Recognizer.HANDLE_STEP] of
         *  path between, since elements joined without a corner meet where nothing is cut. */
        val cuts: IntArray = Recognizer.handlePoints(ux, uy)
    }

    /** Samples [a]..[b] become element [g], drawn in its clean form. */
    fun assign(rx: DoubleArray, ry: DoubleArray, fix: StenoFix?, frame: LineFrame?, a: Int, b: Int, g: Glyph): StenoFix {
        val v = View(rx, ry, fix, frame)
        val fx = v.x.copyOf()
        val fy = v.y.copyOf()
        val (scale, widen) = Recognizer.scaleFor(g.shape, v.bux.copyOfRange(a, b + 1), v.uy.copyOfRange(a, b + 1))
        val raise = StenoCorrection.place(g.shape, a, b, v.bx, v.y, fx, fy, v.base, v.unit, scale, -v.toBook, widen)
        return layer(rx, ry, fix, fx, fy, FixSegment(a, b, FixKind.GLYPH, glyph = g.id, reading = g.reading, raise = raise, checked = true))
    }

    /** Samples [a]..[b] are no element: shown as the cleaned hand, and left alone from now on. */
    fun plain(rx: DoubleArray, ry: DoubleArray, fix: StenoFix?, frame: LineFrame?, params: HandCleanup.Params, a: Int, b: Int): StenoFix {
        val v = View(rx, ry, fix, frame)
        val (cx, cy) = HandCleanup.clean(rx, ry, frame, params)
        val fx = v.x.copyOf()
        val fy = v.y.copyOf()
        blendIn(fx, fy, cx, cy, a, b, v.unit)
        return layer(rx, ry, fix, fx, fy, FixSegment(a, b, FixKind.CLEAN, checked = true))
    }

    /**
     * Samples [a]..[b] replaced by [formX]/[formY] (units, y up — a form written in the pad, or an
     * element's), fitted between the stretch's two ends: turned and scaled so its start lands on the
     * stretch's start and its end on its end. A closed form (start ≈ end) is placed at the start at
     * its own size.
     */
    fun replace(
        rx: DoubleArray, ry: DoubleArray, fix: StenoFix?, frame: LineFrame?, a: Int, b: Int,
        formX: DoubleArray, formY: DoubleArray, glyph: Glyph? = null,
    ): StenoFix {
        val v = View(rx, ry, fix, frame)
        val m = formX.size
        val fcum = DoubleArray(m)
        for (i in 1 until m) fcum[i] = fcum[i - 1] + hypot(formX[i] - formX[i - 1], formY[i] - formY[i - 1])
        // The form in page px (y down), from its own start.
        val px = DoubleArray(m) { (formX[it] - formX[0]) * v.unit }
        val py = DoubleArray(m) { -(formY[it] - formY[0]) * v.unit }
        val ax = v.x[a]; val ay = v.y[a]; val bx = v.x[b]; val by = v.y[b]
        val chordF = hypot(px.last(), py.last())
        val chordS = hypot(bx - ax, by - ay)
        val (k, rot) = if (chordF < 0.3 * v.unit || chordS < 0.3 * v.unit) 1.0 to 0.0
        else chordS / chordF to (atan2(by - ay, bx - ax) - atan2(py.last(), px.last()))
        val c = cos(rot) * k
        val s = sin(rot) * k
        val fx = v.x.copyOf()
        val fy = v.y.copyOf()
        val scum = DoubleArray(b - a + 1)
        for (i in 1 until scum.size) scum[i] = scum[i - 1] + hypot(v.x[a + i] - v.x[a + i - 1], v.y[a + i] - v.y[a + i - 1])
        val total = max(scum.last(), 1e-9)
        var j = 0
        for (q in scum.indices) {
            val t = fcum.last() * scum[q] / total
            while (j < m - 2 && fcum[j + 1] < t) j++
            val seg = fcum[min(j + 1, m - 1)] - fcum[j]
            val f = if (seg > 0) ((t - fcum[j]) / seg).coerceIn(0.0, 1.0) else 0.0
            val j1 = min(j + 1, m - 1)
            val lx = px[j] + f * (px[j1] - px[j])
            val ly = py[j] + f * (py[j1] - py[j])
            fx[a + q] = ax + c * lx - s * ly
            fy[a + q] = ay + s * lx + c * ly
        }
        return layer(rx, ry, fix, fx, fy, FixSegment(a, b, FixKind.REPLACED, glyph = glyph?.id, reading = glyph?.reading, checked = true))
    }

    /**
     * Samples [a]..[b] as the user wrote them, read exactly as the recognizer reads writing
     * ([StenoCorrection.reading]: unsmoothed, at the book's slant, the hand's size mapped onto the
     * book's), in units from their start, with the height they started at: an example, from
     * [origin] (see [GlyphExample.origin]). [hand] is the hand's slant habit, as at pen-up.
     */
    fun example(
        rx: DoubleArray, ry: DoubleArray, frame: LineFrame?, a: Int, b: Int, origin: String,
        hand: Double? = com.xnotes.core.steno.HandSlant.estimate(),
    ): GlyphExample {
        if (frame == null) {
            val v = View(rx, ry, null, null)
            return GlyphExample(DoubleArray(b - a + 1) { v.bux[a + it] - v.bux[a] }, DoubleArray(b - a + 1) { v.uy[a + it] - v.uy[a] }, origin = origin)
        }
        val r = StenoCorrection.reading(rx, ry, frame, hand = hand)
        return GlyphExample(
            DoubleArray(b - a + 1) { r.x[a + it] - r.x[a] },
            DoubleArray(b - a + 1) { r.y[a + it] - r.y[a] },
            r.y[a], origin,
        )
    }

    /** A clean element form from a polyline in units (y up from the base line): fitted curves,
     *  x from the form's left edge. */
    fun shapeOf(x: DoubleArray, y: DoubleArray): GlyphShape {
        val k = 100.0
        val poly = Poly(DoubleArray(x.size) { x[it] * k }, DoubleArray(y.size) { -y[it] * k }).resampled(1.0).smoothed(3.0)
        val fit = CurveFit(k).fit(poly)
        val c = CurveFit.sample(fit.curves, 32).resampled(1.0)
        val left = c.x.min()
        val closed = hypot(x.last() - x[0], y.last() - y[0]) < 0.08
        return GlyphShape(
            listOf(
                GlyphPath(
                    fit.curves.map { cv -> DoubleArray(8) { i -> if (i % 2 == 0) (cv[i] - left) / k else -cv[i] / k } },
                    DoubleArray(c.size) { (c.x[it] - left) / k }, DoubleArray(c.size) { -c.y[it] / k },
                    FloatArray(c.size), closed,
                ),
            ),
        )
    }

    // --- the layer ---

    /** [fx]/[fy] as a layer over the samples, with [seg] cut into the stroke's segments. */
    private fun layer(rx: DoubleArray, ry: DoubleArray, fix: StenoFix?, fx: DoubleArray, fy: DoubleArray, seg: FixSegment): StenoFix {
        val n = rx.size
        val old = fix?.segments ?: listOf(FixSegment(0, n - 1, FixKind.CLEAN))
        return StenoFix(
            splice(old, seg),
            FloatArray(n) { (fx[it] - rx[it]).toFloat() },
            FloatArray(n) { (fy[it] - ry[it]).toFloat() },
        )
    }

    /** [segs] (tiling the stroke) with [seg] put in: what overlaps it is cut back to its outside. */
    internal fun splice(segs: List<FixSegment>, seg: FixSegment): List<FixSegment> {
        val out = ArrayList<FixSegment>()
        for (s in segs) {
            if (s.to < seg.from || s.from > seg.to) { out += s; continue }
            if (s.from < seg.from) out += s.copy(to = seg.from - 1)
            if (s.to > seg.to) out += s.copy(from = seg.to + 1)
        }
        out += seg
        return out.sortedBy { it.from }
    }

    /** ([tx], [ty]) laid over samples [a]..[b] of ([fx], [fy]), blended in at both inner ends. */
    private fun blendIn(fx: DoubleArray, fy: DoubleArray, tx: DoubleArray, ty: DoubleArray, a: Int, b: Int, unit: Double) {
        val cum = DoubleArray(b - a + 1)
        for (i in 1 until cum.size) cum[i] = cum[i - 1] + hypot(fx[a + i] - fx[a + i - 1], fy[a + i] - fy[a + i - 1])
        val total = max(cum.last(), 1e-9)
        for (k in cum.indices) {
            val i = a + k
            val atStart = a == 0 && k < cum.size / 2
            val atEnd = b == fx.size - 1 && k >= cum.size / 2
            val t = (min(cum[k], total - cum[k]) / (0.1 * unit)).coerceIn(0.0, 1.0)
            val w = if (atStart || atEnd) 1.0 else t * t * (3 - 2 * t)
            fx[i] += (tx[i] - fx[i]) * w
            fy[i] += (ty[i] - fy[i]) * w
        }
    }
}
