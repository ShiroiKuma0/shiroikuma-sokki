package com.xnotes.core.tools

import com.xnotes.core.model.Rgba

/**
 * Eraser behaviour (spec 04 §2): STROKE removes a whole stroke on contact; AREA removes only the
 * portion of a stroke the eraser passes over, splitting it into fragments. Persisted as [id].
 */
enum class EraseMode(val id: String) {
    STROKE("stroke"),
    AREA("area");

    companion object {
        fun fromId(id: String?): EraseMode = entries.firstOrNull { it.id == id } ?: STROKE
    }
}

/**
 * The style record carried by stroke tools and used as the eraser/lasso size
 * carrier (spec 04 §2). A [com.xnotes.core.model.Stroke] stores a **copy** at
 * pen-down so re-tuning a tool never restyles existing strokes.
 *
 * The default `ToolConfig()` is `(3.0, on, 0.35, 0.0)` (spec 04 §3).
 */
data class ToolConfig(
    val baseWidth: Double = 3.0,
    val pressureEnabled: Boolean = true,
    /** `m` — width fraction at zero pressure. */
    val pressureMinFactor: Double = 0.35,
    /** `ds` — calligraphic direction effect (0 = none). */
    val directionStrength: Double = 0.0,
    val rgba: Rgba = InkPalette.DEFAULT,
    // New fields go *after* `rgba` so the positional constructor stays stable.
    /** Velocity thinning (the speed pen): 0 = none; up the line thins as it moves faster. */
    val speedStrength: Double = 0.0,
    /** Taper pen: when true the width eases across the whole stroke, full at the head down to
     *  [taperMinFactor] of full width at the tip. */
    val taperEnabled: Boolean = false,
    /** Neon glow: a soft luminous halo under a bright core. Composable onto any stroke tool. */
    val neon: Boolean = false,
    /** Glow intensity (the neon halo): 0 = faint & tight, 1 = bright & wide. Only used when [neon]. */
    val neonStrength: Double = 0.6,
    /** Dashed pen: length of each dash, in content px. Only used by [Tool.DASHED]. */
    val dashLength: Double = 10.0,
    /** Dashed pen: gap between dashes, in content px. Only used by [Tool.DASHED]. */
    val dashGap: Double = 8.0,
    /** Eraser behaviour: whole-stroke (STROKE) or partial (AREA). Only used by [Tool.ERASER]. */
    val eraseMode: EraseMode = EraseMode.STROKE,
    /** Eraser: re-arm the pen/highlighter used before the eraser once an erase lifts. Only [Tool.ERASER]. */
    val switchBackAfterErase: Boolean = false,
    /** Select: re-arm the pen/highlighter used before the select tool once a selection action completes. Only [Tool.SELECT]. */
    val switchBackAfterSelect: Boolean = false,
    /** Highlighter: commit each drag as a single straight segment (start → release). Only [Tool.HIGHLIGHTER]. */
    val straightLine: Boolean = false,
    /** When false, the pen draws at a constant on-screen size: at pen-down the width (and the
     *  taper/dash extents) are divided by the current zoom, so the stroke looks the same
     *  thickness whatever zoom you draw at. The eraser uses it to size its radius. */
    val scale: Boolean = true,
    /** Highlighter translucency: render-time alpha scale, default 0.35 (the historical value, so
     *  legacy strokes that lack the field reload unchanged). Capped to [0.10, 0.90] by the UI so it
     *  stays a MULTIPLY-blended marker. Only used by [Tool.HIGHLIGHTER]. */
    val highlighterAlpha: Double = 0.35,
    /** Per-tool colour override: when set, new strokes from this tool use this colour instead of
     *  the toolbar's active ink colour. null = follow the toolbar (the default for every tool). */
    val colorOverride: Rgba? = null,
    /** Taper pen: width fraction the tapered end keeps, in `[0, 1]` (the floor of the tail ease).
     *  0 = the tail comes to a sharp point; 0.1 = it bottoms out at a tenth of full width. Only
     *  used when [taperEnabled]. */
    val taperMinFactor: Double = 0.0,
    /** Inverse highlighter: composite with SCREEN instead of MULTIPLY, so the stroke lightens what
     *  is under it. A multiply has nothing to darken on a dark page, where it ends up tinting the
     *  light ink instead of the paper. Only used by [Tool.HIGHLIGHTER]. */
    val highlighterInverse: Boolean = false,
    /** Raw stylus pressure that reads as *no press* — everything at or below it draws at the
     *  thinnest the pen goes ([pressureMinFactor] of [baseWidth]).
     *
     *  This and [pressureHigh] are the input band: the slice of the stylus's reported 0..1 the
     *  hand actually spans, stretched back over the full range before the response curve sees it
     *  (`StrokeEngine.normalizePressure`). Defaults to the whole range, which is the identity —
     *  so a pen that has never been calibrated draws exactly as it did before the band existed.
     *  Narrowing it to what the pen really reports is what makes a hard press reach full width
     *  and a light one collapse to a hairline; measure it on the 白い熊 速記 UI page. */
    val pressureLow: Double = 0.0,
    /** Raw stylus pressure that reads as *pressed hard*: at or above it the pen draws at its full
     *  [baseWidth]. See [pressureLow] — the two are one setting. */
    val pressureHigh: Double = 1.0,
    /** Steepness `k` of the pressure response S-curve (`StrokeEngine.logisticEase`). 0 is a linear
     *  ramp; higher steepens the middle and clips the rails nearer thin and thick, so the pen
     *  swings between hairline and full width over a smaller change of press — the nib feel
     *  shorthand wants. Only meaningful once the band above is right: on a curve centred in a
     *  band the hand never reaches, raising `k` *flattens* the response instead of sharpening it.
     *  Defaults to `StrokeEngine.PRESSURE_CURVE_K`, the value the engine has always used. */
    val pressureCurve: Double = 8.0,
)

/** Factory defaults per tool (spec 04 §3). */
object ToolDefaults {
    /** Tip width the taper pen eases down to, and the value assumed for legacy taper strokes that
     *  predate the setting, so they reload tapered rather than as a sharp point. */
    const val DEFAULT_TAPER_TIP = 0.30

    fun configFor(tool: Tool): ToolConfig = when (tool) {
        Tool.PEN -> ToolConfig(baseWidth = 3.0, pressureEnabled = true, pressureMinFactor = 0.35, directionStrength = 0.0)
        // Dashed pen: uniform-width (pressure off), so its dashes stay even; dash/gap set the rhythm.
        Tool.DASHED -> ToolConfig(baseWidth = 3.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0, dashLength = 10.0, dashGap = 8.0)
        Tool.CALLIGRAPHY -> ToolConfig(baseWidth = 6.0, pressureEnabled = true, pressureMinFactor = 0.40, directionStrength = 0.60)
        Tool.SPEED -> ToolConfig(baseWidth = 4.0, pressureEnabled = true, pressureMinFactor = 0.35, directionStrength = 0.0, speedStrength = 0.8)
        Tool.TAPER -> ToolConfig(baseWidth = 4.0, pressureEnabled = true, pressureMinFactor = 0.45, directionStrength = 0.0, taperEnabled = true, taperMinFactor = DEFAULT_TAPER_TIP)
        Tool.HIGHLIGHTER -> ToolConfig(baseWidth = 16.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0, highlighterAlpha = 0.50)
        Tool.ERASER -> ToolConfig(baseWidth = 24.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0)
        Tool.LASSO -> ToolConfig(baseWidth = 2.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0)
        else -> ToolConfig()
    }

    /** Tools whose config is persisted in settings (spec 09 §2). */
    val persistedTools = listOf(Tool.PEN, Tool.DASHED, Tool.CALLIGRAPHY, Tool.SPEED, Tool.TAPER, Tool.HIGHLIGHTER, Tool.ERASER, Tool.SELECT, Tool.LASSO)
}

/**
 * Conversions between the friendly popup controls and the internal style fields
 * (spec 04 §5). All are exact inverses on their valid ranges.
 */
object ToolConversions {
    /** SENSITIVITY (0..100, higher = thinner light strokes) -> `m` in [0.1, 1.0]. */
    fun sensitivityToMinFactor(sensitivity: Double): Double =
        1.0 - (sensitivity.coerceIn(0.0, 100.0) / 100.0) * 0.9

    fun minFactorToSensitivity(m: Double): Double = (1.0 - m) / 0.9 * 100.0

    /** LIGHT / HARD (percent of the stylus's reported range) <-> the raw pressure band
     *  ([ToolConfig.pressureLow] / [ToolConfig.pressureHigh]). The sliders are quoted in percent
     *  because that is what the calibration pad reports. */
    fun percentToPressure(percent: Double): Double = percent.coerceIn(0.0, 100.0) / 100.0

    fun pressureToPercent(pressure: Double): Double = pressure * 100.0

    /** Narrowest band the sliders may express, as a percent span. Wide enough that the pen keeps a
     *  usable mid-range instead of becoming an on/off switch between hairline and full width. */
    const val MIN_BAND_PERCENT = 5.0

    /** CURVE (the slider) -> `k`. A straight pass-through, named so the popup and the config can't
     *  drift; 0 is the linear ramp and 24 is about as sharp as a nib is legible at. */
    val CURVE_RANGE = 0.0..24.0

    /** MULTIPLIER (thick:thin ratio) -> `ds`, clamped to [0, 0.95]. */
    fun multiplierToDirectionStrength(multiplier: Double): Double =
        ((multiplier - 1.0) / (multiplier + 1.0)).coerceIn(0.0, 0.95)

    fun directionStrengthToMultiplier(ds: Double): Double = (1.0 + ds) / (1.0 - ds)

    /** SPEED (0..100, higher = stronger thinning at speed) -> `speedStrength` in [0, 0.92]. */
    fun speedToStrength(speed: Double): Double = speed.coerceIn(0.0, 100.0) / 100.0 * 0.92

    fun strengthToSpeed(s: Double): Double = (s / 0.92) * 100.0

    /** INTENSITY (0..100, higher = brighter/wider halo) -> `neonStrength` in [0, 1]. */
    fun intensityToNeonStrength(intensity: Double): Double = intensity.coerceIn(0.0, 100.0) / 100.0

    fun neonStrengthToIntensity(s: Double): Double = s * 100.0

    /** Highlighter INTENSITY (10..90 percent opacity) -> alpha in [0.10, 0.90]. Capped below
     *  1.0 so the highlighter stays translucent (its MULTIPLY blend is preserved). */
    fun intensityToHighlighterAlpha(intensity: Double): Double = intensity.coerceIn(10.0, 90.0) / 100.0

    fun highlighterAlphaToIntensity(a: Double): Double = a * 100.0

    /** WIDTH slider range per tool (spec 04 §5): 4..40 for the highlighter, 1..80 for the eraser
     *  (its radius, default 24), else 1..20. */
    fun widthRange(tool: Tool): ClosedFloatingPointRange<Double> = when (tool) {
        Tool.HIGHLIGHTER -> 4.0..40.0
        Tool.ERASER -> 1.0..80.0
        else -> 1.0..20.0
    }
}
