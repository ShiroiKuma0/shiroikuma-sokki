package com.xnotes.core.stroke

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One translucent strip of the 筆 brush's ink: its two edges point for point, and the opacity at
 * each point (0..1, on top of the ink colour's own).
 */
class HairStrip(
    /** Left edge, interleaved x,y. */
    val left: FloatArray,
    /** Right edge, interleaved x,y, in the same order (not reversed). */
    val right: FloatArray,
    /** Opacity per point. */
    val alphas: FloatArray,
) {
    val pointCount: Int get() = left.size / 2
}

/**
 * The 筆 brush's texture: what turns its outline into ink that reads as hair and sumi.
 *
 * The stroke is painted as [HAIRS] thin translucent hairs laid side by side across its width, drawn
 * over each other so the ink builds up. How much ink each lays down follows the hand: slow, the
 * hairs are dark and merge into solid black; fast, they go light and the stroke turns grey and
 * streaked with them, edges first. The [dry] dial sets how far that goes.
 *
 * Where the brush slows into a stop, or rests as it lands, ink pools into a round blob. Hairs can't
 * draw that — they run along the stroke — so the ends get **caps**: a few stacked translucent discs
 * with ragged rims, solid in the middle and fuzzy at the edge, at the widest point near each end.
 *
 * The hairs are laid along a **lagged direction** — the way the brush body points, trailing the
 * hand — so a dab or a wiggle can't swing them round an end. Everything is a pure function of the
 * stroke and a seed from where it starts, so a note reopens with exactly the texture it was drawn
 * with. Every strip is a plain quad strip, so nothing overlaps itself.
 */
object BrushTexture {
    const val HAIRS = 320

    /** Extra faint, fine hairs along each edge, on top of [HAIRS]: the fuzz of a brush's outline. */
    const val FRINGE = 40

    /** How far past the body the fringe may feather, as a share of the half-width. The outline is
     *  padded by the same, so bounds and redraws cover it. */
    const val FRINGE_REACH = 1.1

    /** Stray fibres: how many per side, how far past the body they may wander (a share of the
     *  half-width — the outline is padded to it), their opacity, and how much of the stroke they skip. */
    const val STRAY = 90
    const val STRAY_REACH = 1.12
    const val STRAY_ALPHA = 0.12
    const val STRAY_ALPHA_SPREAD = 0.2
    const val STRAY_GAPS = 0.35

    /** How far a lifted-out-of pool's teardrop lobe reaches past its radius, as a share of it. */
    const val TEAR = 1.2

    /** A lift shorter than [LEAN_MIN] of the pool's radius leaves it round; at [LEAN_FULL] it leans fully. */
    const val LEAN_MIN = 0.3

    /** A stroke shorter than this (brush widths) is a dot, all pool. */
    const val DOT_WIDTHS = 0.6

    /** How much further than an end's a dot's teardrop reaches. */
    const val DOT_LEAN = 1.6
    const val LEAN_FULL = 1.2

    /** How narrowly that lobe gathers to its point (higher: a slimmer, sharper teardrop). */
    const val TEAR_SHARPNESS = 6.0

    /** A pool the brush lifts straight out of keeps its thick round end, with only a small, narrow
     *  lift-off point on the way it was moving: how far that reaches past the rim (a share of the
     *  radius) and how narrow it is. */
    const val LIFTOFF_REACH = 0.35
    const val LIFTOFF_SHARPNESS = 16.0

    /** Corners: turns sharper than [CORNER_COS] (cosine) measured over [CORNER_SPAN] brush widths each side. */
    const val CORNER_SPAN = 0.12
    const val CORNER_COS = 0.55

    /** A pool's cap radius, as a share of the width there: a little inside, under the hairs. */
    const val CAP_SHARE = 0.9

    /** Share of the hairs that drag on into a lift, and their strength there. */
    const val LIFT_HAIRS = 0.45
    const val LIFT_ALPHA = 0.85

    /** How steeply a lift's hairs fade with its narrowing (higher: sooner). */
    const val LIFT_FADE = 1.0

    /** A flick fades far more gently, and keeps at least this ink density. */
    const val FLICK_FADE = 0.45

    /** How far short of a flick's point the outermost hairs may drop out, as a share of its length. */
    const val FLICK_DROP = 0.75
    const val FLICK_INK = 0.45

    /** Below this share of the stroke's widest, hairs fade out toward the tip. */
    const val TIP_FADE = 0.15

    /** How far (a share of the half-width, peak to peak) the outermost body hairs jitter across the edge. */
    const val EDGE_JITTER = 0.16

    /** Body hairs keep within this share of the half-width; the fringe feathers the rest. */
    const val BODY_REACH = 1.05

    /** From this share of the half-width out, the hairs break into lengths of about [EDGE_DASH] brush
     *  widths, up to [EDGE_BREAK] of them gone at the very edge. */
    const val EDGE_BREAK_FROM = 0.6
    const val EDGE_DASH = 0.004
    const val EDGE_BREAK = 1.0
    const val EDGE_BREAK_TO = 0.92

    /** Round an end the hairs stop at this share of its radius, give or take [CORE_RAGGED]. */
    const val CORE = 0.8
    const val CORE_RAGGED = 0.24

    /** An end is round — a ball, cored — when half a radius out from its footprint the ink is still
     *  this share of the radius wide (a circle's is 0.87). */
    const val ROUND_SHARE = 0.75

    /** Hair half-width, as a share of the stroke's half-width: base plus random spread. */
    const val HAIR_SHARE = 0.008
    const val HAIR_SPREAD = 0.012

    /** Hair opacity at full ink: base plus random spread; edge hairs are fainter. */
    const val HAIR_ALPHA = 0.6
    const val HAIR_ALPHA_SPREAD = 0.3

    /** Ink density: how far the fastest stroke lightens, at dry 0 and at dry 1, and the dp/ms band. */
    const val LIGHT_WET = 0.3
    const val LIGHT_DRY = 0.8
    const val LIGHT_LO = 0.5
    const val LIGHT_HI = 2.0

    /** Direction lag, in brush widths of travel. */
    const val LAG = 0.06

    /** Cap discs: radii as shares of the cap radius, each disc's opacity, and the rim's raggedness. */
    /** A pool's rings, outer rim to heart, as shares of its radius; the last is the opaque heart. */
    private val CAP_RINGS = doubleArrayOf(1.02, 0.98, 0.94, 0.9, 0.86, 0.82, 0.78)
    const val CAP_RIM = 0.2

    /** A pool's rim strands: how many per px of radius, how far out they reach as a share of the
     *  radius, how much they bend, how many points describe each, and their (faint) opacity. */
    const val STRANDS_PER_PX = 2.5
    const val STRANDS_MIN = 60
    const val STRANDS_MAX = 500
    const val STRAND_MIN = 0.98
    const val STRAND_SPAN = 0.1
    const val STRAND_BEND = 0.25
    const val STRAND_POINTS = 5
    const val STRAND_ALPHA = 0.08
    const val STRAND_ALPHA_SPREAD = 0.14

    /** How far (radians, either way) a strand slants off the radial, so the rim tangles. */
    const val STRAND_SLANT = 0.6

    /** The heart's opacity, and how ragged the rings' edges are. */
    const val CAP_HEART = 1.0
    const val CAP_RAGGED = 0.025

    /** Edge grain: one fibre per [FIBRE_STEP] brush widths along each edge (at least [FIBRE_STEP_MIN]
     *  px), each a short tip [FIBRE_LEN] plus up to [FIBRE_LEN_SPREAD] of the half-width long, lying
     *  at its own angle up to [FIBRE_ANGLE] radians off the outward normal. They start between
     *  [FIBRE_IN] and [FIBRE_IN_MAX] of the half-width, crowded inward, so the edge is a dense
     *  stipple thinning out into loose grains rather than a line with lashes. */
    const val FIBRE_STEP = 0.00015
    const val FIBRE_STEP_MIN = 0.1
    const val FIBRE_LEN = 0.006
    const val FIBRE_LEN_SPREAD = 0.025
    const val FIBRE_ANGLE = 0.9
    const val FIBRE_IN = 0.6
    const val FIBRE_IN_MAX = 1.1
    const val FIBRE_WIDTH = 0.005
    const val FIBRE_POINTS = 2
    const val FIBRE_ALPHA = 0.3
    const val FIBRE_ALPHA_SPREAD = 0.4

    /** How far the fuzz reaches at most, as a share of the half-width: the outline is padded to it,
     *  so bounds and redraws cover every fibre. */
    const val FUZZ_REACH = 1.35

    /** Rim grain: at most this many fibres per round end. */
    const val RIM_MAX = 25000

    /** The sides' grain is this many times as dense as one fibre per [FIBRE_STEP]: its band is deep. */
    const val SIDE_DENSITY = 1.7

    /** Rim grain is this many times as dense along the rim as the sides' grain is along the sides:
     *  out of a round end it splays apart, so it needs more to read as the same grain. */
    const val RIM_DENSITY = 4.0

    /** Rim grain starts this far in (a share of the radius): as deep as any hair's core stops. */
    const val RIM_IN = CORE - 0.5 * CORE_RAGGED - 0.04

    /** The lift-off drag: how many hairs, how far across the ball they lie (a share of its radius),
     *  where they start and how far past that they run (shares of the radius), how much they gather
     *  and bend, how many points describe each, and their (faint) opacity. */
    const val DRAG_HAIRS = 2000

    /** How far out of its ball the drag reaches, and how wide it spreads (shares of the radius, the
     *  most any hair goes): what the outline is carried over. */
    const val DRAG_REACH = 2.7
    const val DRAG_WIDTH = 1.3
    const val DRAG_SPREAD = 0.7
    const val DRAG_FROM_MIN = 0.3
    const val DRAG_FROM_MAX = 0.8
    const val DRAG_LEN_MIN = 0.6
    const val DRAG_LEN_MAX = 1.8
    const val DRAG_GATHER = 0.25
    const val DRAG_BEND = 0.1
    const val DRAG_POINTS = 7
    const val DRAG_ALPHA = 0.03
    const val DRAG_ALPHA_SPREAD = 0.08

    /** Narrowest hair half-width painted, content px. */
    const val MIN_HAIR = 0.5

    /** A stroke's texture seed, from where it starts — coarse enough that the 0.01 px rounding a save
     *  applies can't move it, so a reopened note keeps its texture. */
    fun seedOf(x0: Double, y0: Double): Int {
        val qx = floor(x0 / 4.0).toLong()
        val qy = floor(y0 / 4.0).toLong()
        return mix((qx * 73856093L xor qy * 19349663L).toInt())
    }

    /**
     * The painting strips, back to front. [sx]/[sy] is the smoothed centreline and [tx]/[ty] its unit
     * tangents; [h] the half-widths; [speed] the hand speed in dp/ms; [dry] the user's 0..1 dial.
     */
    fun strips(
        sx: DoubleArray,
        sy: DoubleArray,
        tx: DoubleArray,
        ty: DoubleArray,
        h: DoubleArray,
        speed: DoubleArray,
        baseWidth: Double,
        dry: Double,
        seed: Int,
        poolStart: Int,
        poolEnd: Int,
        tailStart: Int,
        flick: Boolean = false,
        drag: DoubleArray? = null,
    ): List<HairStrip> {
        val out = ArrayList(stripsOf(sx, sy, tx, ty, h, speed, baseWidth, dry, seed, poolStart, poolEnd, tailStart, flick))
        if (drag != null) drag(drag[0], drag[1], drag[2], drag[3], drag[4], seed + 12007, out)
        return out
    }

    /**
     * The brush lifting out of its ball at ([cx], [cy]) of radius [r], moving off along ([dx], [dy]):
     * a few of its hairs drag over the paper as it rises — faint, fine streaks that start inside the
     * ball and run out of it, each fading out at its own length.
     */
    private fun drag(cx: Double, cy: Double, dx: Double, dy: Double, r: Double, seed: Int, out: MutableList<HairStrip>) {
        if (r < 1.0) return
        val ox = -dy
        val oy = dx
        for (k in 0 until DRAG_HAIRS) {
            val q1 = unit(seed, k * 8 + 1)
            val q2 = unit(seed, k * 8 + 2)
            val q3 = unit(seed, k * 8 + 3)
            val q4 = unit(seed, k * 8 + 4)
            val q5 = unit(seed, k * 8 + 5)
            // Across the ball, thicker toward its middle, where the brush's tip is.
            val side = (q1 + q2 - 1.0) * DRAG_SPREAD * r
            val from = r * (DRAG_FROM_MIN + (DRAG_FROM_MAX - DRAG_FROM_MIN) * q3)
            val len = r * (DRAG_LEN_MIN + (DRAG_LEN_MAX - DRAG_LEN_MIN) * q4 * q4)
            val bend = (q5 - 0.5) * DRAG_BEND * r
            val alpha0 = DRAG_ALPHA + DRAG_ALPHA_SPREAD * unit(seed, k * 8 + 6)
            val hw = max(MIN_HAIR, r * (0.004 + 0.01 * unit(seed, k * 8 + 7)))
            val m = DRAG_POINTS
            val left = FloatArray(2 * m)
            val right = FloatArray(2 * m)
            val a = FloatArray(m)
            for (j in 0 until m) {
                val t = j / (m - 1.0)
                val along = from + len * t
                // The hairs draw a little together as they leave, and curve their own way.
                val across = side * (1.0 - DRAG_GATHER * t) + bend * t * t
                val x = cx + dx * along + ox * across
                val y = cy + dy * along + oy * across
                val w = hw * (1.0 - 0.6 * t)
                left[2 * j] = (x - ox * w).toFloat(); left[2 * j + 1] = (y - oy * w).toFloat()
                right[2 * j] = (x + ox * w).toFloat(); right[2 * j + 1] = (y + oy * w).toFloat()
                a[j] = (alpha0 * (1.0 - t).pow(1.3)).toFloat()
            }
            out += HairStrip(left, right, a)
        }
    }

    private fun stripsOf(
        sx: DoubleArray,
        sy: DoubleArray,
        tx: DoubleArray,
        ty: DoubleArray,
        h: DoubleArray,
        speed: DoubleArray,
        baseWidth: Double,
        dry: Double,
        seed: Int,
        poolStart: Int,
        poolEnd: Int,
        tailStart: Int,
        flick: Boolean,
    ): List<HairStrip> {
        val tipped = tailStart >= 0
        val n = sx.size
        if (n < 2) return emptyList()
        val w = max(baseWidth, 1.0)

        val arc = DoubleArray(n)
        for (i in 1 until n) arc[i] = arc[i - 1] + hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
        val total = arc[n - 1]

        // The brush body's direction, trailing the hand: seeded from the stroke's first stretch.
        val nx = DoubleArray(n)
        val ny = DoubleArray(n)
        run {
            var k = 0
            while (k < n - 1 && arc[k] < 0.15 * w) k++
            var dx = sx[k] - sx[0]
            var dy = sy[k] - sy[0]
            var len = hypot(dx, dy)
            if (len < 1e-6) { dx = tx[0]; dy = ty[0]; len = 1.0 }
            dx /= len; dy /= len
            val lag = LAG * w
            // Through the lift the hairs follow the way the hand left, so the point isn't sheared.
            var liftX = 0.0
            var liftY = 0.0
            if (tipped && tailStart < n - 1) {
                val lx = sx[n - 1] - sx[tailStart]
                val ly = sy[n - 1] - sy[tailStart]
                val ll = hypot(lx, ly)
                if (ll > 1e-6) { liftX = lx / ll; liftY = ly / ll }
            }
            for (i in 0 until n) {
                if (tipped && i >= tailStart && (liftX != 0.0 || liftY != 0.0)) {
                    // Turn onto the lift over its first stretch rather than snapping.
                    val a = 1.0 - exp(-(arc[i] - arc[max(i - 1, 0)]) / (0.25 * lag))
                    val ex = dx + (liftX - dx) * a
                    val ey = dy + (liftY - dy) * a
                    val el = hypot(ex, ey)
                    if (el > 0.2) { dx = ex / el; dy = ey / el }
                    nx[i] = -dy
                    ny[i] = dx
                    continue
                }
                if (i > 0) {
                    val a = 1.0 - exp(-(arc[i] - arc[i - 1]) / lag)
                    val ex = dx + (tx[i] - dx) * a
                    val ey = dy + (ty[i] - dy) * a
                    val el = hypot(ex, ey)
                    if (el > 0.2) { dx = ex / el; dy = ey / el }
                }
                nx[i] = -dy
                ny[i] = dx
            }
            // Into a round end the hairs come round to the way the stroke really runs, or the last
            // footprint is sheared off along a slanted line where they stop.
            if (!tipped) {
                val reach = max(1.5 * h[n - 1], 1.0)
                for (i in 0 until n) {
                    val t = 1.0 - (total - arc[i]) / reach
                    if (t <= 0.0) continue
                    val e = StrokeEngine.smoothstep(0.0, 1.0, t)
                    val ex = nx[i] + (-ty[i] - nx[i]) * e
                    val ey = ny[i] + (tx[i] - ny[i]) * e
                    val el = hypot(ex, ey)
                    if (el > 1e-6) { nx[i] = ex / el; ny[i] = ey / el }
                }
            }
        }

        // Ink density per point, 1 (loaded, slow) .. lighter as the hand speeds up.
        val light = LIGHT_WET + (LIGHT_DRY - LIGHT_WET) * dry.coerceIn(0.0, 1.0)
        val rho = DoubleArray(n) { 1.0 - light * StrokeEngine.smoothstep(LIGHT_LO, LIGHT_HI, speed[it]) }
        // Ink soaks through each footprint: wherever a slow, wet footprint covers the stroke — the
        // ball of a stop over the run that came into it — the ink is as wet as that footprint, so a
        // ball is one blot, not split along where the hand slowed.
        run {
            val wet = rho.copyOf()
            var lo = 0
            var hi = 0
            var reach = 0.0
            for (v in h) reach = max(reach, v)
            for (i in 0 until n) {
                while (arc[i] - arc[lo] > 2.5 * reach) lo++
                while (hi + 1 < n && arc[hi + 1] - arc[i] <= 2.5 * reach) hi++
                for (k in lo..hi) {
                    if (rho[k] <= wet[i]) continue
                    // Full inside the footprint, then soaking out over as far again beyond it.
                    val t = 1.0 - StrokeEngine.smoothstep(0.6 * h[k], 2.5 * h[k], abs(arc[k] - arc[i]))
                    wet[i] = max(wet[i], rho[i] + (rho[k] - rho[i]) * t)
                }
            }
            wet.copyInto(rho)
        }
        // A flick is flung with a loaded brush: its triangle stays dark, thinning only toward the point.
        if (flick && tailStart >= 0) for (i in tailStart until n) rho[i] = max(rho[i], FLICK_INK)

        // Thin the points the hairs are built on: a hair needs no finer than a px or so. Its outer end
        // moves both with the stroke and as the brush turns — at a pivot the hairs swing round while
        // the line barely advances — so both count, or a turn in place is drawn as a polygon.
        val pick = ArrayList<Int>(n)
        val step = max(1.5, 0.015 * w)
        pick += 0
        for (i in 1 until n - 1) {
            val l = pick.last()
            val swing = hypot(nx[i] - nx[l], ny[i] - ny[l]) * max(h[i], h[l])
            // …and so does the width: round an end the hairs fan out fast while the line barely moves.
            if (arc[i] - arc[l] + swing + abs(h[i] - h[l]) >= step) pick += i
        }
        pick += n - 1

        var hMax = 0.0
        for (v in h) hMax = max(hMax, v)

        // The pools go down first, a little inside the ink, so the hairs fray over their rims and they
        // read as ink gathered under the brush, not as discs laid on the stroke.
        val out = ArrayList<HairStrip>(HAIRS + 2 * FRINGE + 2 * STRANDS_MAX)
        // Sharp turns need nothing of their own: the brush's footprints already fill the corner.
        // The end pools go on last, over the hair ends there, so an end is one piece of ink: an
        // opaque heart covering where the hairs stop, softening out through a graded rim.
        val ends = ArrayList<HairStrip>()
        if (poolStart in 0 until n) cap(sx[poolStart], sy[poolStart], h[poolStart] * CAP_SHARE, rho[poolStart], seed + 7001, ends)
        if (poolEnd in 0 until n) {
            // A pool the brush lifts out of is a teardrop leaning the way it left.
            var ex = 0.0
            var ey = 0.0
            if (!tipped && total < DOT_WIDTHS * w) {
                // A stroke that is all pool — a 丶 — leans the way it was dragged.
                val dx = sx[n - 1] - sx[0]
                val dy = sy[n - 1] - sy[0]
                val dl = hypot(dx, dy)
                val r = max(h[poolEnd], 1e-6)
                if (dl >= LEAN_MIN * 0.5 * r) {
                    // A dot is all teardrop: it leans hard, further than any end does.
                    val lean = DOT_LEAN * min(1.0, dl / (0.5 * LEAN_FULL * r))
                    ex = dx / dl * lean
                    ey = dy / dl * lean
                }
            }
            if (tipped && !flick) {
                val lx = sx[n - 1] - sx[poolEnd]
                val ly = sy[n - 1] - sy[poolEnd]
                val ll = hypot(lx, ly)
                // Lean only as far as the lift is long against the pool: a small wiggle stays round.
                val r = max(h[poolEnd], 1e-6)
                val lean = min(1.0, ll / (LEAN_FULL * r))
                if (ll > 1e-6 && ll >= LEAN_MIN * r) { ex = lx / ll * lean; ey = ly / ll * lean }
            }
            var sharp = TEAR_SHARPNESS
            if (ex == 0.0 && ey == 0.0 && !tipped) {
                // The way the brush arrived, over about the pool's radius.
                val reach = max(h[poolEnd], 1.0)
                var a = poolEnd
                while (a > 0 && arc[poolEnd] - arc[a] < reach) a--
                val dx = sx[poolEnd] - sx[a]
                val dy = sy[poolEnd] - sy[a]
                val dl = hypot(dx, dy)
                if (dl > 1e-6) {
                    ex = dx / dl * LIFTOFF_REACH / TEAR
                    ey = dy / dl * LIFTOFF_REACH / TEAR
                    sharp = LIFTOFF_SHARPNESS
                }
            }
            cap(sx[poolEnd], sy[poolEnd], h[poolEnd] * CAP_SHARE, rho[poolEnd], seed + 9001, ends, ex, ey, sharp)
        }

        // The round ends: the footprint each belongs to — the first point whose disc reaches the tip —
        // and which way it faces. Inside them the hairs stop at a ragged core well short of the rim,
        // and the grain makes up the rest, so a ball is grain through its outer part, not a disc.
        val endC = intArrayOf(-1, -1)
        val endDx = DoubleArray(2)
        val endDy = DoubleArray(2)
        val round = BooleanArray(2)
        if (total > 0.0) {
            for (end in 0..1) {
                if (end == 1 && tipped) continue
                var c = if (end == 0) 0 else n - 1
                val inward = if (end == 0) 1 else -1
                while (c + inward in 0 until n && (if (end == 0) arc[c] else total - arc[c]) < h[c] * 0.98) c += inward
                val r = h[c]
                if (r < TIP_FADE * hMax || r < 1.0) continue
                var dx = sx[if (end == 0) 0 else n - 1] - sx[c]
                var dy = sy[if (end == 0) 0 else n - 1] - sy[c]
                val dl = hypot(dx, dy)
                if (dl < 1e-6) { dx = if (end == 0) -tx[c] else tx[c]; dy = if (end == 0) -ty[c] else ty[c] } else { dx /= dl; dy /= dl }
                endC[end] = c; endDx[end] = dx; endDy[end] = dy
                // Only a round end gets a core: one that opens from its tip has no ball to grain.
                var t = c
                while (t - inward in 0 until n && abs(arc[t] - arc[c]) < 0.5 * r) t -= inward
                round[end] = h[t] >= ROUND_SHARE * r
            }
        }

        // Length along the edge: longer than the centreline's where the width changes, as round an end.
        val earc = DoubleArray(n)
        for (i in 1 until n) earc[i] = earc[i - 1] + hypot(arc[i] - arc[i - 1], h[i] - h[i - 1])

        val offs = DoubleArray(n)
        val half = DoubleArray(n)
        val alphas = DoubleArray(n)
        for (j in 0 until HAIRS + 2 * FRINGE + 2 * STRAY) {
            val r1 = unit(seed, j * 8 + 1)
            val r2 = unit(seed, j * 8 + 2)
            val r3 = unit(seed, j * 8 + 3)
            val r4 = unit(seed, j * 8 + 4)
            val r5 = unit(seed, j * 8 + 5)
            val r6 = unit(seed, j * 8 + 6)
            val r7 = unit(seed, j * 8 + 7)
            // Only some hairs drag on past the pool into a lift; the rest stay in the ink there.
            val drags = flick || unit(seed, j * 8 + 8) < LIFT_HAIRS
            // Stray fibres: the faint loose hairs that wander just outside a brush's body.
            val stray = j >= HAIRS + 2 * FRINGE
            val fringe = j >= HAIRS
            val u = when {
                !fringe -> (-1.0 + 2.0 * (j + 0.5) / HAIRS + (r1 - 0.5) * 2.0 / HAIRS).coerceIn(-1.0, 1.0)
                stray -> (if ((j - HAIRS) % 2 == 0) 1.0 else -1.0) * (1.0 + (STRAY_REACH - 1.0) * r1)
                else -> (if ((j - HAIRS) % 2 == 0) 1.0 else -1.0) * (0.7 + 0.3 * r1)
            }
            val au = abs(u)
            // A hair that stays behind still runs on a little way, each its own distance, so the
            // pool's edge is ragged rather than one line where most of the hairs stop.
            // They stop along the pool's round rim — the middle ones furthest — plus a little each.
            val flickReach = if (flick && tipped) {
                (total - arc[tailStart]) * (1.0 - FLICK_DROP * au * unit(seed, j * 8 + 10))
            } else total
            val stayUntil = if (tipped) {
                val rim = h[tailStart] * CAP_SHARE * sqrt(max(0.0, 1.0 - u * u))
                arc[tailStart] + rim * (0.6 + 0.4 * unit(seed, j * 8 + 9))
            } else total
            val share = if (fringe) 0.003 + 0.004 * r2 else HAIR_SHARE + HAIR_SPREAD * r2
            val base = if (stray) STRAY_ALPHA + STRAY_ALPHA_SPREAD * r3 else if (fringe) 0.06 + 0.14 * r3 else (HAIR_ALPHA + HAIR_ALPHA_SPREAD * r3) * (1.0 - 0.5 * StrokeEngine.smoothstep(0.8, 1.0, au))
            val gapLen = w * (0.3 + 0.9 * r4)
            val coreCut = CORE + CORE_RAGGED * (unit(seed, j * 8 + 11) - 0.5)
            // The ends are already round in the outline the hairs follow, and the hairs converge into
            // it: cut short, even a few px, they would all stop on one line and square it off.
            for (i in 0 until n) {
                half[i] = max(h[i] * share, MIN_HAIR)
                // Fringe hairs wander in and out finely and fast, so the outline frays; the rest barely wobble.
                val wobble = if (stray) {
                    // A stray wanders in and out across the edge on its own slow drift.
                    1.0 + 0.14 * (noise(seed + j * 977, arc[i] / (w * (0.1 + 0.25 * r4)) + r7 * 50.0) - 0.6)
                } else if (fringe) {
                    1.0 + 0.14 * (noise(seed + j * 977, arc[i] / (w * 0.006) + r7 * 50.0) - 0.6)
                } else {
                    // Hairs drift across each other on two scales, so the ink is a weave, not bands.
                    // Toward the edge they drift less: there, hairs pulled apart would open dark seams
                    // running along the stroke, where a brush's edge only frays.
                    // The outermost jitter finely in and out instead, so the body's edge is ragged.
                    1.0 + (1.0 - au * au) * (0.16 * (noise(seed + j * 977, arc[i] / (w * (0.15 + 0.3 * r4)) + r7 * 50.0) - 0.5) +
                        0.05 * (noise(seed + j * 389, arc[i] / (w * 0.03) + r7 * 20.0) - 0.5)) +
                        StrokeEngine.smoothstep(0.8, 1.0, au) * EDGE_JITTER * (noise(seed + j * 53, arc[i] / (w * 0.01) + r7 * 70.0) - 0.5)
                }
                // The fringe feathers out past the body, up to the outline's own reach.
                val room = max(h[i] * (if (stray) STRAY_REACH else if (fringe) FRINGE_REACH else 1.0) - half[i], 0.0)
                val reach = if (fringe) room else room * BODY_REACH
                // A stray lies its own way out past the body, spread across the reach — never all
                // pressed onto the outline in one hard line.
                offs[i] = if (stray) sign(u) * (h[i] * au * wobble - half[i]).coerceIn(0.0, room)
                else (u * reach * wobble).coerceIn(-room - h[i] * EDGE_JITTER * 0.5, room + h[i] * EDGE_JITTER * 0.5)
                // Lighter ink thins the edges first: a hair's share of the ink falls with how far out it lies.
                val thin = (1.0 - rho[i]) * au
                // Where the ink narrows to a point the hairs thin out too, so a tip frays into a few
                // hairs instead of every hair converging into one dark line.
                val tipFade = min(1.0, h[i] / max(TIP_FADE * hMax, 1e-6)).pow(0.7)
                // Along a lift the ink runs out: the hairs fade with how far the point has narrowed.
                val liftFade = if (tipped && i > tailStart) {
                    min(1.0, h[i] / max(h[min(tailStart + 1, n - 1)], 1e-6)).pow(if (flick) FLICK_FADE else LIFT_FADE)
                } else 1.0
                alphas[i] = base * rho[i] * (1.0 - 0.6 * thin) * tipFade * liftFade *
                    (if (tipped && i > tailStart && !flick) LIFT_ALPHA else 1.0)
            }
            var runStart = -1
            for (p in pick.indices) {
                val i = pick[p]
                                // Only a brush running really light breaks its hairs, and then into long streaks.
                val grain = noise(seed + j * 131, arc[i] / gapLen + r7 * 100.0)
                // A lift drags the hairs apart into streaks, more of them toward the point.
                val spent = if (tipped && i > tailStart) 1.0 - min(1.0, h[i] / max(h[min(tailStart + 1, n - 1)], 1e-6)) else 0.0
                // Along a flick the hairs drop out one by one, the outer ones soonest, so the triangle's
                // edges are hair ends — ragged — rather than two straight lines meeting at the point.
                val flickOk = !flick || i <= tailStart || arc[i] - arc[tailStart] <= flickReach
                // A stray shows only in lengths, here and there along the stroke.
                val strayOk = !stray || noise(seed + j * 211, arc[i] / (w * (0.15 + 0.4 * r5)) + r6 * 30.0) > STRAY_GAPS
                // Out past an end's footprint, how far out a hair lies is its distance from the end's
                // middle, so every end — a landing as much as a ball — breaks up as the sides do. Round
                // a ball, each hair also stops at its own radius of the core.
                var coreOk = true
                var far = au
                for (e in 0..1) {
                    val c = endC[e]
                    if (c < 0) continue
                    // Only out past the end's footprint: back along the stroke the broken edge does it.
                    if (if (e == 0) i >= c else i <= c) continue
                    val px = sx[i] + nx[i] * offs[i] - sx[c]
                    val py = sy[i] + ny[i] * offs[i] - sy[c]
                    val d = hypot(px, py)
                    if (round[e] && d > h[c] * coreCut) coreOk = false
                    far = max(far, d / max(h[c], 1e-6))
                }
                // Toward the edge the hairs break into short lengths, fewer of them the further out, so
                // the edge is a grain of hair ends rather than a line.
                // Past [EDGE_BREAK_TO] none is left: the outer edge is all grain, specks rather than streaks.
                val edgeOk = far < EDGE_BREAK_FROM ||
                    noise(seed + j * 733, earc[i] / (w * EDGE_DASH) + r6 * 40.0) > EDGE_BREAK * StrokeEngine.smoothstep(EDGE_BREAK_FROM, EDGE_BREAK_TO, far)
                val present = flickOk && strayOk && coreOk && edgeOk && (drags || !tipped || arc[i] <= stayUntil) && grain > max((1.0 - rho[i]) * (0.3 + 0.7 * au) - 0.15, spent * (if (flick) 0.55 else 1.0) * (0.5 + 0.5 * au) - 0.1)
                if (present) {
                    if (runStart < 0) runStart = p
                } else if (runStart >= 0) {
                    strip(sx, sy, nx, ny, pick, runStart, p, offs, half, alphas)?.let { out += it }
                    runStart = -1
                }
            }
            if (runStart >= 0) strip(sx, sy, nx, ny, pick, runStart, pick.size, offs, half, alphas)?.let { out += it }
        }

        // The fuzz of the edge: short fine fibres lying a little across the stroke, each starting in
        // the ink and running out past the edge, so the outline is a mass of hair tips, not a line.
        if (total > 0.0) {
            val fstep = max(FIBRE_STEP_MIN, FIBRE_STEP * w) / SIDE_DENSITY
            var k = 0
            var seg = 0
            fun locate(a: Double): Double {
                while (seg < n - 2 && arc[seg + 1] < a) seg++
                while (seg > 0 && arc[seg] > a) seg--
                val d = arc[seg + 1] - arc[seg]
                return seg + if (d > 1e-9) ((a - arc[seg]) / d).coerceIn(0.0, 1.0) else 0.0
            }
            // Only along the sides: round an end's footprint the rim grain stands radially instead. Laid
            // there along the sides' normals, the grains would bunch into arcs where the rim closes.
            val from = if (endC[0] >= 0) arc[endC[0]] else 0.0
            val until = if (endC[1] >= 0) arc[endC[1]] else total
            for (side in intArrayOf(-1, 1)) {
                var a = from + unit(seed + 5003, side + 7) * fstep
                seg = 0
                while (a < until) {
                    val q = seed + 6007 + side * 7919
                    val f1 = unit(q, k * 8 + 1)
                    val f2 = unit(q, k * 8 + 2)
                    val f3 = unit(q, k * 8 + 3)
                    val f4 = unit(q, k * 8 + 4)
                    val f5 = unit(q, k * 8 + 5)
                    // Each grain lies at its own angle, mostly pointing out, and short against the ink here.
                    val hHere = h[locate(a).toInt().coerceAtMost(n - 1)]
                    val len = hHere * (FIBRE_LEN + FIBRE_LEN_SPREAD * f1 * f1)
                    val phi = (f2 - 0.5) * 2.0 * FIBRE_ANGLE
                    val out1 = if (unit(q, k * 8 + 7) < 0.8) 1.0 else -1.0
                    val dir = sin(phi)
                    val u0 = FIBRE_IN_MAX - (FIBRE_IN_MAX - FIBRE_IN) * sqrt(f3)
                    val u1 = u0 + out1 * cos(phi) * len / max(hHere, 1e-6)
                    // Out past the edge the grains thin to loose specks.
                    val alpha0 = (FIBRE_ALPHA + FIBRE_ALPHA_SPREAD * f5) * (1.0 - 0.7 * StrokeEngine.smoothstep(0.85, FIBRE_IN_MAX, u0))
                    val m = FIBRE_POINTS
                    val left = FloatArray(2 * m)
                    val right = FloatArray(2 * m)
                    val al = FloatArray(m)
                    var ok = true
                    val start = a
                    for (j in 0 until m) {
                        val t = j / (m - 1.0)
                        val pos = locate((start + dir * len * t).coerceIn(0.0, total))
                        val i0 = pos.toInt().coerceAtMost(n - 1)
                        val i1 = min(i0 + 1, n - 1)
                        val fr = pos - i0
                        val hh = h[i0] + (h[i1] - h[i0]) * fr
                        if (hh < TIP_FADE * hMax) { ok = false; break }
                        val cx = sx[i0] + (sx[i1] - sx[i0]) * fr
                        val cy = sy[i0] + (sy[i1] - sy[i0]) * fr
                        var ex = nx[i0] + (nx[i1] - nx[i0]) * fr
                        var ey = ny[i0] + (ny[i1] - ny[i0]) * fr
                        val el = hypot(ex, ey)
                        if (el > 1e-9) { ex /= el; ey /= el }
                        val off = side * hh * (u0 + (u1 - u0) * t)
                        val px = cx + ex * off
                        val py = cy + ey * off
                        val hw = max(MIN_HAIR * 0.7, hh * FIBRE_WIDTH) * (1.0 - 0.7 * t)
                        left[2 * j] = (px - ex * hw).toFloat(); left[2 * j + 1] = (py - ey * hw).toFloat()
                        right[2 * j] = (px + ex * hw).toFloat(); right[2 * j + 1] = (py + ey * hw).toFloat()
                        val wet = rho[i0] + (rho[i1] - rho[i0]) * fr
                        al[j] = (alpha0 * wet * (1.0 - 0.7 * t)).toFloat()
                    }
                    if (ok) out += HairStrip(left, right, al)
                    // Spaced along the edge itself, which runs longer than the centreline where the width changes.
                    val sl = (h[min(seg + 1, n - 1)] - h[seg]) / max(arc[min(seg + 1, n - 1)] - arc[seg], 1e-6)
                    a += fstep * (0.4 + 1.2 * f4) / sqrt(1.0 + sl * sl)
                    k++
                }
            }
        }

        // Round each end, the same grain stands in and out of the rim — radially, where the brush's
        // hairs splay out of its last (or first) footprint.
        for (e in 0..1) {
            val c = endC[e]
            if (c < 0) continue
            rim(sx[c], sy[c], endDx[e], endDy[e], h[c], rho[c], w, seed + 7919 * (e + 1), out)
        }

        out += ends
        return out
    }

    /** Fibres splaying out of the round rim of the footprint at ([cx], [cy]) of radius [r], over the
     *  half facing ([dx], [dy]) — each a short tip from just inside the rim to past it. */
    private fun rim(cx: Double, cy: Double, dx: Double, dy: Double, r: Double, wet: Double, w: Double, seed: Int, out: MutableList<HairStrip>) {
        val count = (RIM_DENSITY * PI * r / max(FIBRE_STEP_MIN, FIBRE_STEP * w)).toInt().coerceAtMost(RIM_MAX)
        val base = atan2(dy, dx)
        for (k in 0 until count) {
            val f1 = unit(seed, k * 8 + 1)
            val f2 = unit(seed, k * 8 + 2)
            val f3 = unit(seed, k * 8 + 3)
            val f4 = unit(seed, k * 8 + 4)
            val f5 = unit(seed, k * 8 + 5)
            // Round the half-disc facing out, thinning toward where the rim meets the stroke's sides.
            val th = base + (f1 - 0.5) * PI * 1.05
            // From where the hairs' core may stop, crowded hard inward: under the hair ends the grain is
            // all but solid, so the core's edge never shows as a ring.
            val u0 = FIBRE_IN_MAX - (FIBRE_IN_MAX - RIM_IN) * f3.pow(0.35)
            val inward = unit(seed, k * 8 + 6) >= 0.8
            val slant = th + (f2 - 0.5) * 2.0 * FIBRE_ANGLE + (if (inward) PI else 0.0)
            val r0 = r * u0
            val len = r * (FIBRE_LEN + FIBRE_LEN_SPREAD * f4 * f4)
            val x0 = cx + cos(th) * r0
            val y0 = cy + sin(th) * r0
            val ux = cos(slant)
            val uy = sin(slant)
            val hw = max(MIN_HAIR * 0.7, r * FIBRE_WIDTH)
            val alpha0 = (FIBRE_ALPHA + FIBRE_ALPHA_SPREAD * f5) * wet * (1.0 - 0.7 * StrokeEngine.smoothstep(0.85, FIBRE_IN_MAX, u0))
            val left = FloatArray(4)
            val right = FloatArray(4)
            val a = FloatArray(2)
            for (j in 0..1) {
                val x = x0 + ux * len * j
                val y = y0 + uy * len * j
                val ww = hw * (1.0 - 0.7 * j)
                left[2 * j] = (x + uy * ww).toFloat(); left[2 * j + 1] = (y - ux * ww).toFloat()
                right[2 * j] = (x - uy * ww).toFloat(); right[2 * j + 1] = (y + ux * ww).toFloat()
                a[j] = (alpha0 * (1.0 - 0.7 * j)).toFloat()
            }
            out += HairStrip(left, right, a)
        }
    }

    /** A pooled end: stacked translucent discs with ragged rims, solid in the middle, fuzzy outside. */
    private fun cap(
        cx: Double,
        cy: Double,
        r: Double,
        density: Double,
        seed: Int,
        out: MutableList<HairStrip>,
        leanX: Double = 0.0,
        leanY: Double = 0.0,
        sharpness: Double = TEAR_SHARPNESS,
    ) {
        val leans = leanX != 0.0 || leanY != 0.0
        // Teardrop: the radius swells toward the lean, a narrowing lobe the tail's hairs carry on from;
        // the lean's length (0..1) scales how far.
        val leanLen = hypot(leanX, leanY)
        fun lobe(ax: Double, ay: Double): Double {
            if (!leans) return 1.0
            val c = ((ax * leanX + ay * leanY) / leanLen).coerceAtLeast(0.0)
            return 1.0 + TEAR * leanLen * c.pow(sharpness)
        }
        if (r < 1.0) return
        val segs = (r * 1.2).toInt().coerceIn(32, 240)

        // Rings from the outer rim in to the heart: faint and ragged outside, opaque at the heart.
        for ((layer, share) in CAP_RINGS.withIndex()) {
            val heart = layer == CAP_RINGS.lastIndex
            val alpha = (if (heart) CAP_HEART * (0.75 + 0.25 * density) else CAP_RIM * (0.5 + 0.5 * density)).toFloat()
            val ragged = if (heart) CAP_RAGGED * 0.5 else CAP_RAGGED * 1.6
            val left = FloatArray(2 * (segs + 1))
            val right = FloatArray(2 * (segs + 1))
            for (k in 0..segs) {
                val th = PI * k / segs // left edge along the top half, right edge along the bottom
                val freq = if (heart) 0.4 else 1.3
                val ragTop = 1.0 + ragged * (2.0 * noise(seed + layer * 53, k * freq) - 1.0)
                val ragBot = 1.0 + ragged * (2.0 * noise(seed + layer * 53 + 1, k * freq) - 1.0)
                val rr = r * share
                val c = cos(th)
                val sn = sin(th)
                val rt = rr * ragTop * lobe(c, -sn)
                val rb = rr * ragBot * lobe(c, sn)
                left[2 * k] = (cx + rt * c).toFloat()
                left[2 * k + 1] = (cy - rt * sn).toFloat()
                right[2 * k] = (cx + rb * c).toFloat()
                right[2 * k + 1] = (cy + rb * sn).toFloat()
            }
            out += HairStrip(left, right, FloatArray(segs + 1) { alpha })
        }

        // A faint fuzz of short, curling strands round the rim — a haze, never spikes.
        val count = (r * STRANDS_PER_PX).toInt().coerceIn(STRANDS_MIN, STRANDS_MAX)
        val pts = STRAND_POINTS
        for (k in 0 until count) {
            val q1 = unit(seed + 31, k * 5 + 1)
            val q2 = unit(seed + 31, k * 5 + 2)
            val q3 = unit(seed + 31, k * 5 + 3)
            val q4 = unit(seed + 31, k * 5 + 4)
            val q5 = unit(seed + 31, k * 5 + 5)
            val th = 2.0 * PI * (k + q1) / count
            val rx = cos(th)
            val ry = sin(th)
            val slant = (q4 - 0.5) * 2.0 * STRAND_SLANT
            val dx = rx * cos(slant) - ry * sin(slant)
            val dy = rx * sin(slant) + ry * cos(slant)
            val lb = lobe(rx, ry)
            val from = r * lb * (0.82 + 0.08 * q3)
            val reach = r * lb * (STRAND_MIN + STRAND_SPAN * q2)
            if (reach <= from) continue
            val bend = (q4 - 0.5) * STRAND_BEND
            val hw = max(MIN_HAIR, r * (0.002 + 0.003 * q5))
            val alpha = ((STRAND_ALPHA + STRAND_ALPHA_SPREAD * q1) * (0.5 + 0.5 * density)).toFloat()
            val left = FloatArray(2 * pts)
            val right = FloatArray(2 * pts)
            val a = FloatArray(pts)
            for (m in 0 until pts) {
                val t = m.toDouble() / (pts - 1)
                val rootX = cx + rx * from
                val rootY = cy + ry * from
                val len = (reach - from) * t
                val side = bend * t * t * r
                val px = rootX + dx * len - dy * side
                val py = rootY + dy * len + dx * side
                val w = hw * (1.0 - 0.8 * t)
                left[2 * m] = (px + dy * w).toFloat()
                left[2 * m + 1] = (py - dx * w).toFloat()
                right[2 * m] = (px - dy * w).toFloat()
                right[2 * m + 1] = (py + dx * w).toFloat()
                a[m] = (alpha * (1.0 - t)).toFloat()
            }
            out += HairStrip(left, right, a)
        }
    }

    /** One strip over `pick[from until to]`, offset [offs] along the lagged normal, at [half]. */
    private fun strip(
        sx: DoubleArray,
        sy: DoubleArray,
        nx: DoubleArray,
        ny: DoubleArray,
        pick: List<Int>,
        from: Int,
        to: Int,
        offs: DoubleArray,
        half: DoubleArray,
        alphas: DoubleArray,
    ): HairStrip? {
        val m = to - from
        if (m < 2) return null
        val left = FloatArray(2 * m)
        val right = FloatArray(2 * m)
        val a = FloatArray(m)
        for (k in 0 until m) {
            val i = pick[from + k]
            var hw = half[i]
            val e = min(k, m - 1 - k)
            if (e < 2) hw *= (e + 1) / 3.0
            val cx = sx[i] + nx[i] * offs[i]
            val cy = sy[i] + ny[i] * offs[i]
            left[2 * k] = (cx - nx[i] * hw).toFloat()
            left[2 * k + 1] = (cy - ny[i] * hw).toFloat()
            right[2 * k] = (cx + nx[i] * hw).toFloat()
            right[2 * k + 1] = (cy + ny[i] * hw).toFloat()
            a[k] = alphas[i].toFloat()
        }
        return HairStrip(left, right, a)
    }

    /** Smooth 1D value noise in [0, 1]. */
    private fun noise(seed: Int, x: Double): Double {
        val i = floor(x)
        val f = x - i
        val a = unit(seed, i.toInt())
        val b = unit(seed, i.toInt() + 1)
        val t = f * f * (3 - 2 * f)
        return a + (b - a) * t
    }

    /** A hash of ([seed], [k]) as a double in [0, 1). */
    private fun unit(seed: Int, k: Int): Double = (mix(seed * 31 + k) ushr 8) / 16777216.0

    private fun mix(v: Int): Int {
        var x = v
        x = x xor (x ushr 16)
        x *= 0x7feb352d
        x = x xor (x ushr 15)
        x *= -0x7b5c9e79
        x = x xor (x ushr 16)
        return x
    }
}
