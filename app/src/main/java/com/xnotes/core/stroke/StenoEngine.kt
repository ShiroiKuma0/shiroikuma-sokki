package com.xnotes.core.stroke

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The 速記 pen: Czech shorthand (Gabelsberger–Herout) shading. Every line is a hairline, except
 * where the pen is **pressed while moving down** — there it swells to one fixed shaded width.
 *
 * - **Pressure is a switch, not a dial.** Past [threshold] the stroke shades, below it it does not;
 *   how hard the pen presses beyond that never changes the width. Shorthand reads shading as a
 *   yes/no mark, so the swell must be the same every time.
 * - **Only downward travel shades.** A hand that presses into a downstroke usually carries the press
 *   into the upstroke after it; that upstroke stays a hairline. The weight follows how steeply the
 *   pen falls, so a pressed circle shades its descending side into a crescent, thin at top and
 *   bottom.
 * - **A shaded run must be a real downstroke.** One shorter than [MIN_RUN] shaded widths stays a
 *   hairline: a press that builds at the bottom of a stroke, just before it turns back up, creeps
 *   down a few px and must not leave a blob there.
 * - **Knots are redrawn clean.** A small loop circled in place ([DOT_TURN], within [DOT_SIZE]) is
 *   replaced by the form the textbooks print: a light knot by an even oval eye of the size and slant
 *   it was drawn at, a pressed one by a solid oval of a fixed size ([KNOT_A] × [KNOT_B]). Only a full
 *   loop is a knot; a pressed reversal or tap never is.
 * - **Each shaded run is a spindle**: it swells from the hairline, holds its width along the body
 *   and eases back out, rather than starting or ending in a blunt thick end. The ramps take a fixed
 *   share of the run, capped at a few shaded widths, so a long downstroke holds its width and a
 *   short one still reaches it at its middle.
 *
 * Everything is computed from the stored samples, so a note reopens as drawn. A live stroke tapers
 * only the start of a run still under the pen; its end eases out once the pen turns or lifts.
 */
object StenoEngine {

    /** Downward direction (sine of the angle below horizontal) where shading starts… */
    const val DOWN_FROM = 0.15

    /** …and where it reaches full weight (about 37° below horizontal). */
    const val DOWN_FULL = 0.6

    /** Share of a shaded run each ramp may take. */
    const val RAMP_SHARE = 0.4

    /** Longest ramp, in shaded widths, so a long downstroke holds its width along the body. */
    const val RAMP_MAX = 2.5

    /** Shortest shaded run, in shaded widths of steep travel, that reaches the full width… */
    const val MIN_RUN = 2.5

    /** …and the longest that still stays a hairline. Only the steep part counts, so the rounded
     *  bottom of a turn adds little: a press that builds there never makes a run of its own. */
    const val MIN_RUN_FROM = 1.5

    /** How far the pen must turn, in radians, to draw a dot: a full loop, give or take. */
    const val DOT_TURN = 1.7 * PI

    /** Most turning one dot covers, so a second loop round it starts a dot of its own. */
    const val DOT_TURN_MAX = 2.3 * PI

    /** A pressed knot's oval, in shaded widths: half its length and half its breadth along the
     *  path the pen swept, which the ink's own width rounds out to a solid oval about 1.9 × 1.5
     *  shaded widths — the 太い結び of the textbook (žula). */
    const val KNOT_A = 0.45
    const val KNOT_B = 0.25

    /** The flattest a light knot's eye may be, as its breadth over its length. */
    const val KNOT_ROUND = 0.55

    /** How near, in shaded widths, the path must come back to itself to close a knot. */
    const val DOT_CLOSE = 0.35

    /** The chord, in shaded widths, the knot test reads the pen's heading over. */
    const val DOT_CHORD = 0.15

    /** Largest loop, across, that still reads as a dot, in shaded widths. */
    const val DOT_SIZE = 1.5

    /** Least travel a dot loop takes, in shaded widths, so a pen jittering in place is no loop. */
    const val DOT_MIN_TRAVEL = 0.8

    /** How far below [threshold] the pressure must fall to end a press, so a pressure hovering at
     *  the threshold doesn't flicker the shading on and off. */
    const val RELEASE_GAP = 0.05

    /** Travel over which the direction is smoothed, in px, so hand tremor at a slow downstroke
     *  doesn't shiver the width. */
    const val DIR_SMOOTH = 3.0

    /**
     * The ribbon for one stroke. [baseWidth] is the hairline, [shade] the shaded width as a multiple
     * of it, [threshold] the raw stylus pressure (0..1) that counts as pressed. [finished] lets the
     * last run ease out; a stroke still being drawn keeps its last run at full width.
     */
    fun build(
        rawX: DoubleArray,
        rawY: DoubleArray,
        rawP: DoubleArray,
        baseWidth: Double,
        shade: Double,
        threshold: Double,
        smoothScale: Double = 1.0,
        finished: Boolean = true,
    ): StrokeGeometry {
        val n = rawX.size
        if (n == 0) return StrokeGeometry.EMPTY
        val thin = baseWidth / 2.0
        val thick = thin * max(shade, 1.0)

        if (n == 1) {
            return StrokeGeometry(floatArrayOf(rawX[0].toFloat(), rawY[0].toFloat()), floatArrayOf(thin.toFloat()))
        }
        val smoothLen = StrokeEngine.SMOOTH_LEN * max(smoothScale, 0.0)
        val rawSteps = DoubleArray(n)
        val rawCum = DoubleArray(n)
        for (i in 1 until n) {
            rawSteps[i] = hypot(rawX[i] - rawX[i - 1], rawY[i] - rawY[i - 1])
            rawCum[i] = rawCum[i - 1] + rawSteps[i]
        }
        val sp = StrokeEngine.emaByArc(rawP, rawSteps, smoothLen)

        // Knots first, on the pen's own path: each is redrawn as its clean oval before anything
        // else reads the positions.
        val knots = findKnots(rawX, rawY, rawCum, sp, threshold, 2.0 * thick)
        val px = rawX.copyOf()
        val py = rawY.copyOf()
        val inKnot = BooleanArray(n)
        for (k in knots) {
            reshapeKnot(px, py, k, 2.0 * thick)
            for (m in k.from..k.to) inKnot[m] = true
        }

        val steps = DoubleArray(n)
        for (i in 1 until n) steps[i] = hypot(px[i] - px[i - 1], py[i] - py[i - 1])
        val sx = StrokeEngine.emaByArc(px, steps, smoothLen)
        val sy = StrokeEngine.emaByArc(py, steps, smoothLen)

        // Unit tangent per point, and the travel along the smoothed line.
        val tx = DoubleArray(n)
        val ty = DoubleArray(n)
        val cum = DoubleArray(n)
        var lastTx = 1.0
        var lastTy = 0.0
        for (i in 0 until n) {
            if (i > 0) cum[i] = cum[i - 1] + hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
            val a = max(i - 1, 0)
            val b = min(i + 1, n - 1)
            val dx = sx[b] - sx[a]
            val dy = sy[b] - sy[a]
            val len = hypot(dx, dy)
            if (len < StrokeEngine.MIN_TANGENT_LEN) {
                tx[i] = lastTx; ty[i] = lastTy
            } else {
                tx[i] = dx / len; ty[i] = dy / len
                lastTx = tx[i]; lastTy = ty[i]
            }
        }
        // The steepness that sets the weight, smoothed over a few px of travel.
        val dSteps = DoubleArray(n)
        for (i in 1 until n) dSteps[i] = cum[i] - cum[i - 1]
        val down = StrokeEngine.emaByArc(ty, dSteps, DIR_SMOOTH * max(smoothScale, 0.0))

        // Pressed, with a little hysteresis, and moving down: the samples that may shade.
        val shading = BooleanArray(n)
        var pressed = false
        for (i in 0 until n) {
            pressed = if (pressed) sp[i] >= threshold - RELEASE_GAP else sp[i] >= threshold
            shading[i] = pressed && down[i] > DOWN_FROM && !inKnot[i]
        }

        // Each maximal shaded run becomes a spindle: weight by steepness, eased in and out.
        val weight = DoubleArray(n)
        val rampMax = RAMP_MAX * 2.0 * thick
        var i = 0
        while (i < n) {
            if (!shading[i]) { i++; continue }
            var j = i
            while (j + 1 < n && shading[j + 1]) j++
            // Extend half a step either side, so a run of one sample still has a length to swell over.
            val s0 = if (i > 0) (cum[i - 1] + cum[i]) / 2.0 else cum[i]
            val s1 = if (j < n - 1) (cum[j] + cum[j + 1]) / 2.0 else cum[j]
            val len = s1 - s0
            val ramp = min(RAMP_SHARE * len, rampMax)
            var steepLen = 0.0
            for (k in i + 1..j) steepLen += (cum[k] - cum[k - 1]) * StrokeEngine.smoothstep(DOWN_FROM, DOWN_FULL, down[k])
            // Held to the same rule while drawing, so a blob never shows under the pen only to vanish at lift.
            val runWeight = StrokeEngine.smoothstep(MIN_RUN_FROM * 2.0 * thick, MIN_RUN * 2.0 * thick, steepLen)
            val open = !finished && j == n - 1
            for (k in i..j) {
                val steep = StrokeEngine.smoothstep(DOWN_FROM, DOWN_FULL, down[k])
                val inR = if (ramp > 0.0) StrokeEngine.smoothstep(0.0, ramp, cum[k] - s0) else 1.0
                val outR = if (open || ramp <= 0.0) 1.0 else StrokeEngine.smoothstep(0.0, ramp, s1 - cum[k])
                weight[k] = steep * inR * outR * runWeight
            }
            i = j + 1
        }
        for (k in knots) for (m in k.from..k.to) weight[m] = if (k.pressed) 1.0 else 0.0

        val centerline = FloatArray(2 * n)
        val halfWidths = FloatArray(n)
        val leftRail = FloatArray(2 * n)
        val rightRail = FloatArray(2 * n)
        for (k in 0 until n) {
            val h = thin + (thick - thin) * weight[k]
            StrokeEngine.writePoint(centerline, halfWidths, leftRail, rightRail, k, sx[k], sy[k], tx[k], ty[k], h)
        }
        return StrokeGeometry(centerline, halfWidths, leftRail, rightRail)
    }

    /** A knot: samples [from]..[to] circle a small loop; [pressed] when the press peaked in it. */
    private class Knot(val from: Int, val to: Int, val pressed: Boolean)

    /**
     * Every knot along the pen's path: a stretch that turns through [DOT_TURN] while staying within
     * [DOT_SIZE] shaded widths. It is pressed when the pressure peaks at [pressedAt] or more
     * somewhere round it — the peak, not the whole loop, since a hand pressing a knot eases off and
     * on again as it goes round. The turning is the net one, so a pen that reverses (down, then back
     * up) turns ±π and is never a knot.
     */
    private fun findKnots(
        rawX: DoubleArray, rawY: DoubleArray, cum: DoubleArray, sp: DoubleArray,
        pressedAt: Double, width: Double,
    ): List<Knot> {
        val n = rawX.size
        // The heading, unwrapped, so its change over a stretch is the net turning. Read over a chord
        // of [DOT_CHORD] shaded widths rather than sample to sample: written zoomed in, the pen moves
        // a fraction of a px per sample.
        val chord = DOT_CHORD * width
        val head = DoubleArray(n)
        var a = 0.0
        var known = false
        var b = 0
        for (k in 0 until n) {
            if (b < k) b = k
            while (b < n - 1 && hypot(rawX[b] - rawX[k], rawY[b] - rawY[k]) < chord) b++
            val dx = rawX[b] - rawX[k]
            val dy = rawY[b] - rawY[k]
            if (hypot(dx, dy) >= 0.5 * chord) {
                a = atan2(dy, dx)
                if (!known) { known = true; for (m in 0 until k) head[m] = a }
            }
            if (k == 0) { head[0] = a; continue }
            var d = a - head[k - 1]
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            head[k] = head[k - 1] + d
        }
        val out = ArrayList<Knot>()
        val size = DOT_SIZE * width
        val maxTravel = PI * size
        val close = DOT_CLOSE * width
        var i = 0
        while (i < n) {
            var minX = rawX[i]; var maxX = rawX[i]; var minY = rawY[i]; var maxY = rawY[i]
            var peak = sp[i]
            var found = -1
            var j = i + 1
            while (j < n && cum[j] - cum[i] <= maxTravel) {
                minX = min(minX, rawX[j]); maxX = max(maxX, rawX[j]); minY = min(minY, rawY[j]); maxY = max(maxY, rawY[j])
                if (maxX - minX > size || maxY - minY > size) break
                peak = max(peak, sp[j])
                if (kotlin.math.abs(head[j] - head[i]) >= DOT_TURN) {
                    if (cum[j] - cum[i] >= DOT_MIN_TRAVEL * width) found = j
                    break
                }
                j++
            }
            if (found < 0) { i++; continue }
            // Carry on round the rest of the loop: inside its box and short of a second turn.
            while (found + 1 < n &&
                rawX[found + 1] in minX..maxX && rawY[found + 1] in minY..maxY &&
                kotlin.math.abs(head[found + 1] - head[i]) <= DOT_TURN_MAX
            ) found++
            // The turning starts counting on the curve into the knot; the knot itself runs from
            // where the path first crosses back over itself to the last point that closes on it.
            var from = i
            var to = found
            search@ for (u in i..found) {
                for (v in found downTo u + 1) {
                    if (cum[v] - cum[u] < DOT_MIN_TRAVEL * width) break
                    if (hypot(rawX[v] - rawX[u], rawY[v] - rawY[u]) <= close) { from = u; to = v; break@search }
                }
            }
            out += Knot(from, to, peak >= pressedAt)
            i = found + 1
        }
        return out
    }

    /**
     * Redraws knot [k] in [px]/[py] as a clean oval, going round the way it was drawn and starting
     * and ending where the pen entered it, so the line runs on unbroken. Its centre and slant are
     * those of the loop as written. A light knot keeps its size, made even ([KNOT_ROUND] at the
     * flattest); a pressed one is a fixed solid oval, whatever size the hand made it.
     */
    private fun reshapeKnot(px: DoubleArray, py: DoubleArray, k: Knot, width: Double) {
        val m = k.to - k.from + 1
        if (m < 4) return
        var cx = 0.0; var cy = 0.0
        for (q in k.from..k.to) { cx += px[q]; cy += py[q] }
        cx /= m; cy /= m
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (q in k.from..k.to) {
            val dx = px[q] - cx; val dy = py[q] - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        sxx /= m; syy /= m; sxy /= m
        // Principal axes of the loop's points.
        val phi = 0.5 * atan2(2 * sxy, sxx - syy)
        val tr = (sxx + syy) / 2
        val det = kotlin.math.sqrt(max(((sxx - syy) / 2).let { it * it } + sxy * sxy, 0.0))
        val major = kotlin.math.sqrt(max(tr + det, 0.0) * 2.0)
        val minor = kotlin.math.sqrt(max(tr - det, 0.0) * 2.0)
        val ra: Double
        val rb: Double
        if (k.pressed) {
            ra = KNOT_A * width
            rb = KNOT_B * width
        } else {
            ra = max(major, 1e-6)
            rb = minor.coerceIn(KNOT_ROUND * ra, ra)
        }
        val c = kotlin.math.cos(phi)
        val s = kotlin.math.sin(phi)
        // Which way round it was drawn: the sign of the loop's area.
        var area = 0.0
        for (q in k.from until k.to) area += (px[q] - cx) * (py[q + 1] - cy) - (px[q + 1] - cx) * (py[q] - cy)
        val dir = if (area >= 0) 1.0 else -1.0
        // Start where the pen came in, in the oval's own frame.
        val ex = (px[k.from] - cx) * c + (py[k.from] - cy) * s
        val ey = -(px[k.from] - cx) * s + (py[k.from] - cy) * c
        val t0 = atan2(ey / rb, ex / ra)
        for (q in 0 until m) {
            val t = t0 + dir * 2 * PI * q / (m - 1)
            val ox = ra * kotlin.math.cos(t)
            val oy = rb * kotlin.math.sin(t)
            px[k.from + q] = cx + ox * c - oy * s
            py[k.from + q] = cy + ox * s + oy * c
        }
    }
}
