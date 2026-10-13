package com.xnotes.core.stroke

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The 筆 brush: a fude loaded with sumi, for kanji written large. Pure and deterministic like
 * [StrokeEngine] — everything below is read off the stored samples (position, pressure and the
 * elapsed-ms channel), so a note reopens exactly as it was written.
 *
 * The brush lives in **time**, as a real one does:
 *
 *  - The width it is asked for comes from the press (band, dead zone, power response) and from the
 *    hand's speed: a brush moving fast lays down a narrower, lighter line, and one that slows lays
 *    down more ink. Speed is the pen's net displacement over [MOTION_WINDOW_MS], so a hand
 *    trembling in place reads as still, not as fast.
 *  - The width reaches for that over time: ink spreads under a slowing or resting brush ([GROW_MS])
 *    and a moving brush narrows quickly ([SHRINK_MS]). So a stroke that slows into a stop swells
 *    smoothly into the round pool of a 止め, a fast flick off the end narrows to a hairy point
 *    on its own, and a stroke that sets off at once lands on its tip ([entry] sets how much of
 *    the press the very first touch has).
 *
 * The output is the disc-swept outline every other pen draws — what bounds, hit tests and
 * selection use — plus, once the stroke is finished, the translucent hair strips that paint it
 * ([BrushTexture]).
 */
object BrushEngine {
    /** Pressure at or below this (after the band) reads as the hair of the brush only touching. */
    const val DEAD_ZONE = 0.03

    /** Time constants (ms) of the pressure response: the brush flattens fast and recovers slow. */
    const val PRESS_TAU_MS = 15.0
    const val RELEASE_TAU_MS = 40.0

    /** Time constants (ms) of the width: ink spreads slowly under the brush, a moving one narrows fast.
     *  [SHRINK_MS] is the value at harai 0; the release sweep dial quickens it down to [SHRINK_MIN_MS]. */
    const val GROW_MS = 200.0
    const val SHRINK_MS = 60.0
    const val SHRINK_MIN_MS = 18.0

    /** The very first touch's share of the asked-for width: [LANDING_MIN] at entry 0, plus [LANDING_SPAN] at 1. */
    const val LANDING_MIN = 0.05
    const val LANDING_SPAN = 0.3

    /** Speed thinning: how much narrower at full speed, and the dp/ms band over which it comes in. */
    const val SPEED_THIN = 0.2
    const val SPEED_LO = 0.15
    const val SPEED_HI = 1.2

    /** Speed is the pen's net displacement over this window (ms), never the sum of its steps: a pen
     *  pressed hard in place trembles a px or two at every report, and summed at 240 Hz that reads
     *  as a fast-moving brush. */
    const val MOTION_WINDOW_MS = 50.0

    /** Displacement (content px at 100% zoom) within the window that is still just a trembling hand. */
    const val JITTER = 2.0

    /** How far in from each end (brush widths, at most half the stroke) its pool is looked for. */
    const val TAIL_REACH = 0.6

    /** Re-gathering: the share of the asked-for width a brush moving off from rest (or setting out)
     *  starts at, the rest (ms) after which it has gathered, and the travel (brush widths) over which
     *  it spreads back out. */
    const val GATHER_FROM = 0.3
    const val GATHER_REST_MS = 80.0

    /** Hand speed (dp/ms) below which the brush is truly at rest, for re-gathering — well under
     *  [POOL_SPEED], so a slow stroke that merely dawdles doesn't keep gathering in steps. */
    const val REST_SPEED = 0.08
    const val GATHER_LEN = 0.7

    /** A move off from rest gathers only when at most this much stroke (brush widths) follows it. */
    const val GATHER_TAIL = 0.5

    /** …and only after at least this much stroke (brush widths): before it, the rest was the landing. */
    const val GATHER_AFTER = 0.3

    /** How far past the width it arrived at a slowing brush may pool, and how quickly the remembered
     *  arriving width lets go as the brush narrows on the way (per moving sample). */
    const val POOL_GROWTH = 1.8
    const val ARRIVED_DECAY = 0.97

    /** A flick whose hand then rests this long (ms) before lifting is a stop, not a flick. */
    const val END_REST_MS = 40.0

    /** Hand speed (dp/ms) below which the brush counts as having pooled ink there. */
    const val POOL_SPEED = 0.35


    /** Movement after the pool (content px at 100% zoom) that counts as lifting off, not as noise. */
    const val LIFT_MIN = 3.0

    /** …and at least this share of the pool's radius, or [LIFT_BACK] of it when it doubles back. */
    const val LIFT_REL = 1.0
    const val LIFT_BACK = 1.5

    /** The lift's point is at least this long, in brush widths: [TAIL_MIN] at harai 0, plus [TAIL_SPAN]
     *  at 1; and it narrows along this power of the distance left (under 1: a full-bodied triangle). */
    const val TAIL_MIN = 0.45
    const val TAIL_SPAN = 0.8
    const val TAIL_CURVE = 0.85

    /** The lift's point leaves the pool at this share of the pool's width, easing there over this
     *  fraction of the lift. */
    const val TAIL_BASE = 0.55

    /** A hook: a turn sharper than [HOOK_COS] (cosine), over [HOOK_SPAN] brush widths each side. */
    const val HOOK_SPAN = 0.15
    const val HOOK_COS = 0.2

    /** A final run shorter than this (brush widths) is a curl at the tip, not a flick. */
    const val HOOK_MIN_RUN = 0.3

    /** A flick's triangle narrows along this power of the distance left: well under 1, so it stays
     *  full most of the way and reads as a triangle well clear of the pool it leaves. */
    const val FLICK_CURVE = 0.55

    /** A flick's triangle leaves the stop at this share of its width: broad, as the brush is flung out. */
    const val FLICK_BASE = 0.85
    const val TAIL_EASE = 0.3

    /** A stroke at most this long (brush widths) is a dot: its landing blends into its ball. */
    const val DOT_LENGTH = 1.2

    /** A dot's round blot runs this share of its radius either side of its middle: just enough for a direction. */
    const val DOT_SPAN = 0.05

    /** How steeply the stroke widens into a wider footprint ahead or behind (half-width per px). */
    const val FILL_SLOPE = 0.35

    /** Points the outline carries on over a lift-off drag. */
    const val DRAG_COVER = 4

    /** Tilt off upright (radians) at which a leaning pen starts to press the brush's side, and at
     *  which it has fully; and how quickly the lean is followed (ms). A pen held "upright" reads
     *  4–17°. */
    const val TILT_UPRIGHT = 0.26
    const val TILT_FLAT = 0.96
    const val TILT_TAU_MS = 40.0

    /** At full lean and full strength the ink is this much wider, and its body this share of its
     *  half-width off the tip. */
    const val TILT_WIDEN = 1.0
    const val TILT_SHIFT = 0.6

    /** Thinnest the brush goes, in content px. */
    const val TIP_HALF_WIDTH = 0.35

    /** Nominal speed (content px per ms) used to invent a time channel for samples that carry none —
     *  a brush stroke always records time, so this only covers foreign or hand-made data. */
    private const val NOMINAL_SPEED = 0.5

    /**
     * Brush geometry for the samples. [m] is the thinnest the brush goes as a fraction of
     * [baseWidth]; [gamma] the pressure response (higher: a light touch stays thin and the belly
     * arrives late); [entry] and [harai] are 0..1: how much of the press the first touch has, and
     * how quickly the brush narrows as it moves off (so how sharp a flick's point is). [speedScale] converts content px to dp (zoom ÷ density at pen-down) and [smoothScale]
     * is the draw zoom's scale on the smoothing and travel lengths, both as for [StrokeEngine].
     */
    fun build(
        rawX: DoubleArray,
        rawY: DoubleArray,
        rawP: DoubleArray,
        rawT: DoubleArray?,
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        gamma: Double,
        entry: Double,
        harai: Double,
        pressureLow: Double,
        pressureHigh: Double,
        speedScale: Double,
        smoothScale: Double,
        finished: Boolean,
        rawTilt: DoubleArray? = null,
        rawLean: DoubleArray? = null,
        tiltWidth: Double = 0.0,
        tiltShift: Double = 0.0,
    ): StrokeGeometry = buildInk(
        rawX, rawY, rawP, rawT, baseWidth, pressureEnabled, m, gamma, entry, harai,
        pressureLow, pressureHigh, speedScale, smoothScale, finished,
        dry = 0.0, textured = false,
        rawTilt = rawTilt, rawLean = rawLean, tiltWidth = tiltWidth, tiltShift = tiltShift,
    ).outline

    /**
     * [build], plus the ink's texture when [textured]: the outline (bounds, hit tests, selection —
     * everything that asks where the stroke *is*) and the translucent strips that actually paint
     * it, a core and the hairs laid across it ([BrushTexture]). [dry] (0..1) is how readily the brush runs
     * dry into streaks.
     *
     * [rawTilt] is the pen's tilt off upright per sample (radians, 0..π/2) and [rawLean] the way it
     * leans, as an angle in content space (radians, the direction from the tip toward the pen's top),
     * both absent for a stroke written without them. A leaning pen presses the brush's side down:
     * [tiltWidth] (0..1) is how much wider that makes the ink, and [tiltShift] (0..1) how far the
     * ink spreads off the tip toward the lean, where the belly lies.
     */
    fun buildInk(
        rawX: DoubleArray,
        rawY: DoubleArray,
        rawP: DoubleArray,
        rawT: DoubleArray?,
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        gamma: Double,
        entry: Double,
        harai: Double,
        pressureLow: Double,
        pressureHigh: Double,
        speedScale: Double,
        smoothScale: Double,
        finished: Boolean,
        dry: Double,
        textured: Boolean,
        rawTilt: DoubleArray? = null,
        rawLean: DoubleArray? = null,
        tiltWidth: Double = 0.0,
        tiltShift: Double = 0.0,
    ): BrushInk {
        val n = rawX.size
        if (n == 0) return BrushInk(StrokeGeometry.EMPTY, emptyList())
        val scale = max(smoothScale, 0.0)

        val steps = DoubleArray(n)
        for (i in 1 until n) steps[i] = hypot(rawX[i] - rawX[i - 1], rawY[i] - rawY[i - 1])
        val times = rawT ?: DoubleArray(n).also { t ->
            for (i in 1 until n) t[i] = t[i - 1] + steps[i] / NOMINAL_SPEED
        }

        // Position: the same travel-measured low-pass as the ribbon pens.
        val smoothLen = StrokeEngine.SMOOTH_LEN * scale
        val sx = StrokeEngine.emaByArc(rawX, steps, smoothLen)
        val sy = StrokeEngine.emaByArc(rawY, steps, smoothLen)
        // Real motion: the hand's speed per sample, jitter excluded.
        val rate = DoubleArray(n) // content px per ms
        val jitter = JITTER * scale
        var j = 0
        for (i in 1 until n) {
            while (j + 1 < i && times[i] - times[j + 1] >= MOTION_WINDOW_MS) j++
            val span = max(times[i] - times[j], StrokeEngine.MIN_DT)
            val disp = hypot(rawX[i] - rawX[j], rawY[i] - rawY[j])
            rate[i] = max(0.0, disp - jitter) / span
        }
        if (n > 1) rate[0] = rate[1]

        // Pressure: band, dead zone, then the time response — fast to flatten, slow to recover.
        val q = DoubleArray(n)
        for (i in 0 until n) {
            val p = if (pressureEnabled) {
                val b = StrokeEngine.normalizePressure(rawP[i], pressureLow, pressureHigh)
                ((b - DEAD_ZONE) / (1.0 - DEAD_ZONE)).coerceIn(0.0, 1.0)
            } else 1.0
            if (i == 0) {
                q[0] = p
            } else {
                val dt = max(times[i] - times[i - 1], 0.0)
                val tau = if (p > q[i - 1]) PRESS_TAU_MS else RELEASE_TAU_MS
                q[i] = q[i - 1] + (p - q[i - 1]) * (1.0 - exp(-dt / tau))
            }
        }

        // How far the pen leans past upright (0..1), and which way (a unit vector), eased over time:
        // the pen reports its tilt only when it changes, in whole degrees, so taken raw it steps.
        val lean = DoubleArray(n)
        val leanX = DoubleArray(n)
        val leanY = DoubleArray(n)
        val widen = tiltWidth.coerceIn(0.0, 1.0)
        val shift = tiltShift.coerceIn(0.0, 1.0)
        if (rawTilt != null && rawLean != null && rawTilt.size >= n && rawLean.size >= n && (widen > 0.0 || shift > 0.0)) {
            for (i in 0 until n) {
                val k = StrokeEngine.smoothstep(TILT_UPRIGHT, TILT_FLAT, rawTilt[i])
                var vx = cos(rawLean[i])
                var vy = sin(rawLean[i])
                if (i > 0) {
                    val e = 1.0 - exp(-max(times[i] - times[i - 1], 0.0) / TILT_TAU_MS)
                    lean[i] = lean[i - 1] + (k - lean[i - 1]) * e
                    vx = leanX[i - 1] + (vx - leanX[i - 1]) * e
                    vy = leanY[i - 1] + (vy - leanY[i - 1]) * e
                    val vl = hypot(vx, vy)
                    if (vl > 1e-9) { vx /= vl; vy /= vl } else { vx = leanX[i - 1]; vy = leanY[i - 1] }
                } else {
                    lean[i] = k
                }
                leanX[i] = vx
                leanY[i] = vy
            }
        }

        // The width asked for: the press, narrowed by speed, widened by the pen's lean.
        val half = baseWidth / 2.0
        val floor = m.coerceIn(0.0, 1.0)
        val g = gamma.coerceIn(0.3, 4.0)
        val minHalf = max(half * floor, TIP_HALF_WIDTH)
        val speed = DoubleArray(n) { rate[it] * speedScale }
        val target = DoubleArray(n) { i ->
            half * (floor + (1.0 - floor) * q[i].pow(g)) *
                (1.0 - SPEED_THIN * StrokeEngine.smoothstep(SPEED_LO, SPEED_HI, speed[i])) *
                (1.0 + TILT_WIDEN * widen * lean[i])
        }

        // The width laid down: it reaches for the target over time — spreading slowly where the
        // brush slows or rests, narrowing quickly as it moves off.
        val shrinkMs = SHRINK_MS - (SHRINK_MS - SHRINK_MIN_MS) * harai.coerceIn(0.0, 1.0)
        val h = DoubleArray(n)
        h[0] = max(minHalf, target[0] * (LANDING_MIN + LANDING_SPAN * entry.coerceIn(0.0, 1.0)))
        // Once the brush has been travelling, slowing or stopping may pool its ink only a little past
        // the width it arrived at: the end of a stroke is the stroke finishing, not a ball stuck on it.
        //
        // A brush that has rested and then moves off has gathered back to its tip: the movement
        // starts thin and spreads again only with travel — which is what turns a flick out of a stop
        // (はね, the drag of a 丶) into a hairy point, and makes a stroke open from its tip.
        // Only a short movement out of a stop *at the end* gathers — a pause mid-stroke doesn't pinch
        // the line — so the moves-off that count are found first, against how much stroke follows.
        val arcAt = DoubleArray(n)
        for (i in 1 until n) arcAt[i] = arcAt[i - 1] + hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
        val regather = BooleanArray(n)
        run {
            var rest = 0.0
            for (i in 1 until n) {
                val dt = max(times[i] - times[i - 1], 0.0)
                if (speed[i] < REST_SPEED) {
                    rest += dt
                } else if (speed[i] >= POOL_SPEED) {
                    // A rest at the landing is the brush settling, not a stop: what follows it is the stroke.
                    if (rest >= GATHER_REST_MS && arcAt[i] >= GATHER_AFTER * baseWidth &&
                        arcAt[n - 1] - arcAt[i] <= GATHER_TAIL * baseWidth) regather[i] = true
                    rest = 0.0
                }
            }
        }
        var arrived = 0.0
        var gather = GATHER_FROM
        var resting = 0.0
        val gatherLen = max(GATHER_LEN * baseWidth, 1e-6)
        for (i in 1 until n) {
            val dt = max(times[i] - times[i - 1], 0.0)
            // A brush that has rested has spread: moving off, it is full — unless it is a short
            // move-off at the end, which gathers it back to its tip (below).
            if (speed[i] < REST_SPEED) {
                resting += dt
                if (resting >= GATHER_REST_MS) gather = 1.0
            } else {
                resting = 0.0
            }
            if (speed[i] >= POOL_SPEED) {
                if (regather[i]) gather = min(gather, GATHER_FROM)
                val travel = hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
                gather += (1.0 - gather) * (1.0 - exp(-travel / gatherLen))
            }
            val want = if (speed[i] < POOL_SPEED) target[i] else target[i] * gather
            val tau = if (want > h[i - 1]) GROW_MS else shrinkMs
            var next = h[i - 1] + (want - h[i - 1]) * (1.0 - exp(-dt / tau))
            if (speed[i] >= POOL_SPEED) arrived = max(arrived * ARRIVED_DECAY, next)
            else if (arrived > 0.0) next = min(next, max(h[i - 1], arrived * POOL_GROWTH))
            h[i] = max(minHalf, next)
        }

        if (shift > 0.0) {
            // The belly lies off the tip toward the lean: the ink is carried that way by a share of
            // its own width, so a slanted brush lays its body beside the line the tip draws.
            for (i in 0 until n) {
                val d = TILT_SHIFT * shift * lean[i] * h[i]
                sx[i] += leanX[i] * d
                sy[i] += leanY[i] * d
            }
        }

        if (n == 1) {
            val dot = StrokeGeometry(floatArrayOf(sx[0].toFloat(), sy[0].toFloat()), floatArrayOf(h[0].toFloat()))
            return BrushInk(dot, emptyList())
        }

        // The ends, once the pen is up. Near each end, where the hand was slowest is where the brush
        // pooled; at the far end, everything after that point is the brush lifting off the paper,
        // and the width tapers along it to a hair point — a triangle aimed the way the hand left,
        // never a dot. A lift too short to read as a triangle is carried on along the exit
        // direction until it does.
        var px = sx
        var py = sy
        var ph = h
        var pv = speed
        var poolEnd = -1
        var tailStart = -1
        var flick = false
        if (finished && n >= 3) {
            val arc = DoubleArray(n)
            for (i in 1 until n) arc[i] = arc[i - 1] + hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
            val total = arc[n - 1]
            val reach = min(TAIL_REACH * baseWidth, 0.5 * total)

            var e0 = n - 1
            var hPool = 0.0
            for (i in n - 1 downTo 0) {
                if (total - arc[i] > reach) break
                if (speed[i] < speed[e0]) e0 = i
                hPool = max(hPool, h[i])
            }
            if (speed[e0] < POOL_SPEED) poolEnd = e0

            // A flick out of a stop at the end — a はね, the hook of 亅 or 力 — is all tail: from the
            // stop, the whole flick narrows to a point, and the far end never pools into a ball.
            var g = -1
            for (i in n - 1 downTo 1) if (regather[i]) { g = i; break }
            // …or a sharp turn near the end followed by a short final run, rested at the corner or not:
            // that is a hook, and its final run is the flick.
            if (g < 0) {
                // The earliest such turn: the one the final run leaves from, not a curl at its very tip.
                val span = HOOK_SPAN * baseWidth
                for (i in n - 1 downTo 1) {
                    if (total - arc[i] > GATHER_TAIL * baseWidth) break
                    if (total - arc[i] < HOOK_MIN_RUN * baseWidth || arc[i] < span) continue
                    var a = i
                    while (a > 0 && arc[i] - arc[a] < span) a--
                    var b = i
                    while (b < n - 1 && arc[b] - arc[i] < span) b++
                    val ix = sx[i] - sx[a]; val iy = sy[i] - sy[a]
                    val ox = sx[b] - sx[i]; val oy = sy[b] - sy[i]
                    val il = hypot(ix, iy); val ol = hypot(ox, oy)
                    if (il < 1e-6 || ol < 1e-6) continue
                    val c = (ix * ox + iy * oy) / (il * ol)
                    if (c < HOOK_COS) g = i + 1
                }
            }
            // …unless the hand comes to rest at the end of it: then the brush stops there and pools,
            // whatever came before — a hook written into a stop ends in its ball, not a point.
            if (g > 0) {
                var still = 0.0
                for (i in max(g, 1) until n) if (speed[i] < POOL_SPEED) still += max(times[i] - times[i - 1], 0.0)
                if (still >= END_REST_MS) g = -1
            }
            if (g > 0) {
                var stop = g - 1
                var wide = 0.0
                for (i in g - 1 downTo 0) {
                    if (arc[g] - arc[i] > reach) break
                    if (h[i] > wide) { wide = h[i]; stop = i }
                }
                if (wide > 0.0) {
                    e0 = stop
                    hPool = wide
                    poolEnd = stop
                    flick = true
                }
            }
            val lift = total - arc[e0]
            // A lift is a real movement against the brush's size, not the twitch a hand makes as it
            // comes off the paper — which often doubles back a few px the way it came, and must leave
            // a round end. Doubling back has to go twice as far to count (a はね does).
            var back = false
            run {
                var k = e0
                while (k > 0 && arc[e0] - arc[k] < 0.3 * baseWidth) k--
                val ix = sx[e0] - sx[k]
                val iy = sy[e0] - sy[k]
                val ox = sx[n - 1] - sx[e0]
                val oy = sy[n - 1] - sy[e0]
                val il = hypot(ix, iy)
                val ol = hypot(ox, oy)
                if (il > 1e-6 && ol > 1e-6) back = (ix * ox + iy * oy) / (il * ol) < -0.3
            }
            val needed = max(LIFT_MIN * scale, hPool * (if (back) LIFT_BACK else LIFT_REL))
            if (flick || (lift >= needed && hPool > 0.0)) {
                tailStart = e0
                val tail = max(lift, 2.0 * hPool * (TAIL_MIN + TAIL_SPAN * harai.coerceIn(0.0, 1.0)))
                // Only the tip of the brush drags off: the point leaves the pool narrower than the pool —
                // except a flick, which carries the brush's whole width out into a broad triangle.
                val base = if (flick) FLICK_BASE else TAIL_BASE
                for (i in e0 + 1 until n) {
                    val w = tailWidth(hPool, (arc[i] - arc[e0]) / tail, base)
                    h[i] = max(TIP_HALF_WIDTH, if (flick) w else min(h[i], w))
                }
                if (tail > lift) {
                    // Carry the point on along the way the hand left.
                    var dx = sx[n - 1] - sx[e0]
                    var dy = sy[n - 1] - sy[e0]
                    var dl = hypot(dx, dy)
                    if (dl < 1e-6) { dx = sx[n - 1] - sx[n - 2]; dy = sy[n - 1] - sy[n - 2]; dl = hypot(dx, dy) }
                    if (dl >= 1e-6) {
                        dx /= dl; dy /= dl
                        val extra = tail - lift
                        val k = max(2, (extra / max(2.0, 0.02 * baseWidth)).toInt())
                        px = sx.copyOf(n + k); py = sy.copyOf(n + k); ph = h.copyOf(n + k); pv = speed.copyOf(n + k)
                        for (q in 1..k) {
                            val e = extra * q / k
                            px[n - 1 + q] = sx[n - 1] + dx * e
                            py[n - 1 + q] = sy[n - 1] + dy * e
                            ph[n - 1 + q] = max(TIP_HALF_WIDTH, tailWidth(hPool, (lift + e) / tail, base))
                            pv[n - 1 + q] = speed[n - 1]
                        }
                    }
                }
            }
        }
        // A pooled end is the stroke itself carried on into a round end of its full width, so the
        // hairs run into it and close it: thick, round and hairy like the rest of the ink, never a
        // disc laid over it. A pressed landing begins the same way, thick from its first hair.
        // The ink is everything the brush's round footprint covers, live as well as at the lift — so
        // a stop is the last footprint, thick and round, and a landing the first, never cut square.
        // A pooled end is where the brush stopped: the hand's twitch as it comes off the paper is
        // noise, and drawn it only folds the hairs over themselves.
        // Its way off the paper is kept, though: the brush drags a few faint hairs out of its ball
        // that way as it lifts.
        var dragX = 0.0
        var dragY = 0.0
        if (finished && poolEnd >= 0 && tailStart < 0) {
            val lx = rawX[n - 1] - rawX[poolEnd]
            val ly = rawY[n - 1] - rawY[poolEnd]
            val ll = hypot(lx, ly)
            if (ll >= LIFT_MIN * scale) {
                // The hairs trail the way the brush left.
                dragX = lx / ll
                dragY = ly / ll
            }
        }
        if (poolEnd >= 0 && tailStart < 0) {
            val m = poolEnd + 1
            px = px.copyOf(m); py = py.copyOf(m); ph = ph.copyOf(m); pv = pv.copyOf(m)
        }
        // A landing is the brush's first footprint: it opens from its tip as it travels and as it
        // rests, never filled out to the width it reaches later.
        if (px.size >= 3) {
            // Footprints blend into each other: wherever the brush is wider than the stroke just
            // before or after it — the ball of a stop, a press mid-stroke — the stroke widens toward it
            // at no more than [FILL_SLOPE] per px, so it flows into it like a club, never a ball on a
            // neck. A lift-off's point is left alone: it narrows as it must.
            ph = ph.copyOf()
            val last = if (tailStart >= 0) tailStart else px.size - 1
            for (i in 1..last) {
                val ds = hypot(px[i] - px[i - 1], py[i] - py[i - 1])
                ph[i] = max(ph[i], ph[i - 1] - FILL_SLOPE * ds)
            }
            // Backwards only as far as the landing: a stroke that opens from its tip keeps its tip.
            // The landing has opened where it is widest within its first brush width of travel.
            // A stroke hardly longer than its footprints — a dot — is one blot: it blends through.
            var opened = 0
            var length = 0.0
            for (i in 1 until px.size) length += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
            if (length > DOT_LENGTH * baseWidth) run {
                var a = 0.0
                for (i in 1..last) {
                    a += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
                    if (a > baseWidth) break
                    if (ph[i] > ph[opened]) opened = i
                }
            }
            for (i in last - 1 downTo opened) {
                val ds = hypot(px[i + 1] - px[i], py[i + 1] - py[i])
                ph[i] = max(ph[i], ph[i + 1] - FILL_SLOPE * ds)
            }
        }
        if (px.size >= 3) {
            // Inside its first and last footprint the brush sits in its own ink: the hand's wander
            // there is no stroke, and followed it only folds the hairs round its curls. Run the path
            // straight from where it enters the footprint to where it stops.
            px = px.copyOf(); py = py.copyOf()
            straighten(px, py, px.size - 1, -1, ph[px.size - 1])
            straighten(px, py, 0, 1, ph[0])
        }
        if (finished && tailStart < 0 && px.size >= 2) run {
            // A dot — a 丶 dabbed and lifted — is one round blot, however the hand slid while it
            // pressed: the ink it covers, gathered into the circle of the same area about its middle.
            var length = 0.0
            for (i in 1 until px.size) length += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
            if (length > DOT_LENGTH * baseWidth) return@run
            val last = px.size - 1
            var dx = px[last] - px[0]
            var dy = py[last] - py[0]
            val dl = hypot(dx, dy)
            if (dl < 1e-6) return@run
            dx /= dl; dy /= dl
            var wide = 0.0
            for (v in ph) wide = max(wide, v)
            // Its extent along the slide, from the back of its first footprint to the front of its last.
            val lo = -ph[0]
            val hi = dl + ph[last]
            val r = sqrt(wide * 0.5 * (hi - lo))
            val cx = px[0] + dx * 0.5 * (lo + hi)
            val cy = py[0] + dy * 0.5 * (lo + hi)
            val e = DOT_SPAN * r
            px = doubleArrayOf(cx - dx * e, cx, cx + dx * e)
            py = doubleArrayOf(cy - dy * e, cy, cy + dy * e)
            ph = doubleArrayOf(r, r, r)
            pv = doubleArrayOf(pv[0], pv[pv.size / 2], pv[pv.size - 1])
        }
        val drag = if (dragX != 0.0 || dragY != 0.0) {
            doubleArrayOf(px[px.size - 1], py[py.size - 1], dragX, dragY, ph[ph.size - 1])
        } else null
        if (px.size >= 2) {
            val fp = footprints(px, py, ph, pv, baseWidth)
            if (tailStart >= 0) tailStart += fp.lead
            px = fp.x; py = fp.y; ph = fp.h; pv = fp.v
        }
        val count = px.size

        // Tangents as the ribbon pens take them, carrying the last good one across standstills. A
        // textured stroke's outline reaches a little past the ink's body, over its fraying fringe.
        val pad = if (textured) BrushTexture.FUZZ_REACH else 1.0
        // The lift-off drag reaches out of the ball, beyond the ink: the outline carries on over it,
        // so bounds and redraws take it in.
        val tail = if (textured && drag != null) DRAG_COVER else 0
        val centerline = FloatArray(2 * (count + tail))
        val halfWidths = FloatArray(count + tail)
        val leftRail = FloatArray(2 * (count + tail))
        val rightRail = FloatArray(2 * (count + tail))
        var lastTx = 1.0
        var lastTy = 0.0
        val tx = DoubleArray(count)
        val ty = DoubleArray(count)
        for (i in 0 until count) {
            val dx: Double
            val dy: Double
            when (i) {
                0 -> { dx = px[1] - px[0]; dy = py[1] - py[0] }
                count - 1 -> { dx = px[i] - px[i - 1]; dy = py[i] - py[i - 1] }
                else -> { dx = px[i + 1] - px[i - 1]; dy = py[i + 1] - py[i - 1] }
            }
            val len = hypot(dx, dy)
            if (len >= StrokeEngine.MIN_TANGENT_LEN) {
                lastTx = dx / len
                lastTy = dy / len
            }
            tx[i] = lastTx
            ty[i] = lastTy
            StrokeEngine.writePoint(centerline, halfWidths, leftRail, rightRail, i, px[i], py[i], lastTx, lastTy, ph[i] * pad)
        }
        if (tail > 0) {
            val d = drag!!
            for (k in 1..tail) {
                val along = BrushTexture.DRAG_REACH * d[4] * k / tail
                StrokeEngine.writePoint(centerline, halfWidths, leftRail, rightRail, count - 1 + k,
                    d[0] + d[2] * along, d[1] + d[3] * along, d[2], d[3], BrushTexture.DRAG_WIDTH * d[4])
            }
        }
        val outline = StrokeGeometry(centerline, halfWidths, leftRail, rightRail)
        if (!textured) return BrushInk(outline, emptyList())
        val seed = BrushTexture.seedOf(rawX[0], rawY[0])
        return BrushInk(outline, BrushTexture.strips(px, py, tx, ty, ph, pv, baseWidth, dry, seed, tailStart, flick, drag))
    }

    /** The lift's half-width at fraction [t] (0..1) of the way along it from a pool of [hPool]: it
     *  eases from the pool's width down to the point's base over the first [TAIL_EASE] of the way,
     *  so the point grows out of the pool rather than stepping out of it, then narrows to the tip. */
    private class Footprints(val x: DoubleArray, val y: DoubleArray, val h: DoubleArray, val v: DoubleArray, val lead: Int)

    /**
     * The union of the brush's round footprints, one of half-width `h[i]` at each point: each width
     * becomes the widest any footprint reaches there along the stroke, and each end carries on as far
     * as its footprints reach past it. [lead] is how many points went in before the first.
     */
    private fun footprints(px: DoubleArray, py: DoubleArray, ph: DoubleArray, pv: DoubleArray, baseWidth: Double): Footprints {
        val m = px.size
        val arc = DoubleArray(m)
        for (i in 1 until m) arc[i] = arc[i - 1] + hypot(px[i] - px[i - 1], py[i] - py[i - 1])
        val total = arc[m - 1]
        var back = 0.0
        var front = 0.0
        for (i in 0 until m) {
            back = max(back, ph[i] - arc[i])
            front = max(front, ph[i] - (total - arc[i]))
        }
        val step = max(1.0, 0.02 * baseWidth)
        val startDir = if (back >= 1.0) outward(px, py, arc, 0, back) else null
        val endDir = if (front >= 1.0) outward(px, py, arc, m - 1, front) else null
        val k0 = if (startDir != null) (1.6 * back / step).toInt().coerceIn(8, 64) else 0
        val k1 = if (endDir != null) (1.6 * front / step).toInt().coerceIn(8, 64) else 0
        val size = k0 + m + k1
        val x = DoubleArray(size)
        val y = DoubleArray(size)
        val s = DoubleArray(size)
        val v = DoubleArray(size)
        // Spaced by angle round the footprint, so the round is as fine at its tip as at its sides.
        for (q in 0 until k0) {
            val d = back * sin(0.5 * PI * (k0 - q) / k0)
            x[q] = px[0] + startDir!!.first * d; y[q] = py[0] + startDir.second * d; s[q] = -d; v[q] = pv[0]
        }
        for (i in 0 until m) { x[k0 + i] = px[i]; y[k0 + i] = py[i]; s[k0 + i] = arc[i]; v[k0 + i] = pv[i] }
        for (q in 1..k1) {
            val d = front * sin(0.5 * PI * q / k1)
            val j = k0 + m - 1 + q
            x[j] = px[m - 1] + endDir!!.first * d; y[j] = py[m - 1] + endDir.second * d; s[j] = total + d; v[j] = pv[m - 1]
        }
        var hMax = 0.0
        for (w in ph) hMax = max(hMax, w)
        val h = DoubleArray(size)
        var lo = 0
        for (j in 0 until size) {
            while (lo < m && arc[lo] < s[j] - hMax) lo++
            var w = TIP_HALF_WIDTH
            var i = lo
            while (i < m && arc[i] <= s[j] + hMax) {
                val dd = s[j] - arc[i]
                val r2 = ph[i] * ph[i] - dd * dd
                if (r2 > w * w) w = sqrt(r2)
                i++
            }
            h[j] = w
        }
        return Footprints(x, y, h, v, k0)
    }

    /** Lays the points from end [end] back (step [toward]) to the first more than [radius] from it on
     *  the straight line between the two, each where its share of the way along puts it. */
    private fun straighten(px: DoubleArray, py: DoubleArray, end: Int, toward: Int, radius: Double) {
        var j = end
        while (j + toward in px.indices && hypot(px[j] - px[end], py[j] - py[end]) <= radius) j += toward
        val count = abs(j - end)
        if (count < 2) return
        var len = 0.0
        var k = end
        while (k != j) { len += hypot(px[k + toward] - px[k], py[k + toward] - py[k]); k += toward }
        if (len < 1e-6) return
        val ex = px[end]; val ey = py[end]; val jx = px[j]; val jy = py[j]
        var run = 0.0
        k = j
        val along = DoubleArray(count + 1)
        var q = 0
        while (k != end) { along[q++] = run; run += hypot(px[k - toward] - px[k], py[k - toward] - py[k]); k -= toward }
        k = j
        q = 0
        while (k != end) {
            val t = along[q++] / len
            px[k] = jx + (ex - jx) * t
            py[k] = jy + (ey - jy) * t
            k -= toward
        }
    }

    /** The unit direction out of the stroke at end [i]: away from where it is [reach] px along. */
    private fun outward(px: DoubleArray, py: DoubleArray, arc: DoubleArray, i: Int, reach: Double): Pair<Double, Double>? {
        val toward = if (i == 0) 1 else -1
        var j = i
        while (j + toward in px.indices && abs(arc[j] - arc[i]) < reach) j += toward
        val dx = px[i] - px[j]
        val dy = py[i] - py[j]
        val dl = hypot(dx, dy)
        return if (dl < 1e-6) null else Pair(dx / dl, dy / dl)
    }

    private fun tailWidth(hPool: Double, t: Double, base: Double): Double {
        val u = t.coerceIn(0.0, 1.0)
        val ease = StrokeEngine.smoothstep(0.0, TAIL_EASE, u)
        return hPool * (1.0 + (base - 1.0) * ease) * (1.0 - u).pow(if (base >= FLICK_BASE) FLICK_CURVE else TAIL_CURVE)
    }
}

/** A brush stroke's ink: where it is ([outline]) and what paints it ([strips], back to front; empty
 *  when untextured, in which case the outline is painted as solid ink). */
class BrushInk(val outline: StrokeGeometry, val strips: List<HairStrip>)
