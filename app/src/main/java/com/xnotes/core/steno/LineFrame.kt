package com.xnotes.core.steno

import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.resolvedSpacing
import com.xnotes.core.model.resolvedTemplate
import kotlin.math.ceil

/**
 * The Herout–Mikulík writing lines of a page ruled as 速記 paper, in page-local coordinates.
 *
 * The paper's heavy rule is the base line (základní linka), and one H-M unit is 15/64 of a band:
 * the lower hairline sits 1 unit above the base (the inner line), the upper hairline 2.6 units above
 * it (the top line), and the band below's upper hairline 1.67 units under it (the bottom line). The
 * bands are counted from page-space zero, as [com.xnotes.core.model.paintPattern] draws them, so
 * the base lines are at whole multiples of [band].
 *
 * Every measure the correction takes — how tall a sign is, whether it is raised by half a unit —
 * is in these units, so it means the same whatever size the paper is drawn at.
 */
class LineFrame(val band: Double) {

    /** One H-M unit, in page px. */
    val unit: Double get() = band * UNIT_SHARE

    /**
     * The base line a stretch of writing centred at [y] belongs to: the heavy rule at or below it,
     * unless [y] lies within the bottom-line zone above the next rule down. A row of writing spans
     * from 1.67 units under its base to 2.6 above, which is exactly one band, so every height has
     * one row.
     */
    fun baseFor(y: Double): Double = band * ceil((y - BOTTOM_UNITS * unit) / band)

    /** Height of [y] above [base], in units (page y grows downward, units grow upward). */
    fun unitsAbove(y: Double, base: Double): Double = (base - y) / unit

    /** The page y of [units] above [base]. */
    fun yAt(units: Double, base: Double): Double = base - units * unit

    companion object {
        /** The inner line's height over the base line, as a share of a band (15 px of 64). */
        const val UNIT_SHARE = 15.0 / 64.0

        /** How far under the base line a row's writing may reach: the bottom line, in units. */
        const val BOTTOM_UNITS = 25.0 / 15.0

        /** The frame of [page] in [doc], or null when the page isn't ruled as 速記 paper. */
        fun of(page: Page, doc: Document): LineFrame? {
            if (page.resolvedTemplate(doc) != PagePattern.SOKKI.id) return null
            val band = page.resolvedSpacing(doc) ?: PageStyle.DEFAULT_SPACING
            return if (band > 1.0) LineFrame(band) else null
        }
    }
}
