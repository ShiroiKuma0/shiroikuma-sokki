package com.xnotes.core.model

/**
 * A page background ruling. [NONE] is an **explicit** "off" — distinct from a null
 * [PageStyle.pattern], which means *inherit from the level below*. Serialized by [id].
 */
enum class PagePattern(val id: String) {
    NONE("none"),
    LINES("lines"),
    DOTS("dots"),
    GRID("grid"),

    /**
     * 速記 shorthand paper (shiroikuma-sokki fork): a heavy rule opening each band, with two
     * hairlines dividing it — the ruling of the Samsung Notes 速記 template 白い熊 writes on.
     * Geometry in [PageStyle.SOKKI_LINES]; one *period* is one whole band, so the existing
     * spacing control scales the paper without disturbing its proportions.
     */
    SOKKI("sokki");

    /**
     * The ruling colour used when neither the page nor the document names one. Steno paper is
     * blue paper — grey hairlines would not read as the thing it is copying — so [SOKKI] brings
     * its own default while every other pattern keeps upstream's grey.
     */
    val defaultColor: Rgba
        get() = if (this == SOKKI) PageStyle.SOKKI_RULE_COLOR else PageStyle.DEFAULT_PATTERN_COLOR

    companion object {
        fun fromId(id: String?): PagePattern? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A per-field-inheritable page style override, held on both [Page] (current page) and [Document]
 * ("all pages" of this note). Each field is **independently** nullable: a null field inherits from
 * the next level down — page → document → global preference (page colour) or a built-in default
 * (pattern/colour/spacing). This per-field nullability is what gives the UI its tri-state controls
 * (Default / explicit value / — for the pattern — an explicit None). Immutable: callers replace it
 * with a [copy]; sharing the reference across a [Page.deepCopy] is safe.
 */
data class PageStyle(
    val pageColor: Rgba? = null,
    val pattern: PagePattern? = null,
    val patternColor: Rgba? = null,
    /** Pattern period in content pixels. */
    val spacing: Double? = null,
) {
    /** True when nothing is overridden — the codec writes no `style` object in this case. */
    val isEmpty: Boolean
        get() = pageColor == null && pattern == null && patternColor == null && spacing == null

    companion object {
        const val DEFAULT_SPACING = 64.0 // content px (~10.8 mm at 150 dpi)
        const val MIN_SPACING = 16.0
        const val MAX_SPACING = 200.0
        val DEFAULT_PATTERN_COLOR = Rgba(150, 150, 150, 64) // grey at ~25% opacity by default

        /** Fixed (non-configurable) ruling thickness / dot radius, in content pixels. */
        const val LINE_THICKNESS = 1.5
        const val DOT_RADIUS = 2.0

        // --- 速記 ruling (shiroikuma-sokki fork) ---------------------------------------------
        // Measured off "速記 samsung notes template.png": a 2 px heavy rule every 64 px, with 1 px
        // hairlines 25 px and 49 px below it. Held as fractions of the period so the spacing
        // slider scales the whole band and the paper keeps its proportions at any size — and note
        // that 64 px is already [DEFAULT_SPACING], so the default is the template at 1:1.
        val SOKKI_LINES = listOf(0.0 to true, 25.0 / 64.0 to false, 49.0 / 64.0 to false)

        /** How much heavier the band rule is than its hairlines. */
        const val SOKKI_HEAVY_FACTOR = 2.0

        /** The template's own blue, opaque — see [PagePattern.defaultColor]. */
        val SOKKI_RULE_COLOR = Rgba(0, 0, 255, 255)
    }
}

// --- resolution: a page's own override -> its document's ("all pages") override -> a caller default ---
// Shared by the live canvas ([CanvasState]) and PDF export so both honour the same hierarchy, and so
// each resolves against the document actually being drawn/exported (not necessarily the open one).

/** Resolved paper colour, or null to fall back to the theme paper. [global] is the app-wide preference. */
fun Page.resolvedPageColor(doc: Document, global: Rgba?): Rgba? =
    style.pageColor ?: doc.style.pageColor ?: global

fun Page.resolvedPattern(doc: Document): PagePattern =
    style.pattern ?: doc.style.pattern ?: PagePattern.NONE

fun Page.resolvedPatternColor(doc: Document): Rgba =
    style.patternColor ?: doc.style.patternColor ?: resolvedPattern(doc).defaultColor

fun Page.resolvedSpacing(doc: Document): Double =
    (style.spacing ?: doc.style.spacing ?: PageStyle.DEFAULT_SPACING)
        .coerceIn(PageStyle.MIN_SPACING, PageStyle.MAX_SPACING)
