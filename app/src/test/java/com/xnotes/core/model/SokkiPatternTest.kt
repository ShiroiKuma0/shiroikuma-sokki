package com.xnotes.core.model

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 速記 ruling (shiroikuma-sokki fork). One band per period, opened by a heavy rule and divided
 * by hairlines at 25/64 and 49/64 of it — the geometry measured off the Samsung Notes 速記 template.
 */
class SokkiPatternTest {

    private val blue = PageStyle.SOKKI_RULE_COLOR
    private val pageW = 480.0
    private val pageH = 640.0

    /** Every ruled y, with true for the heavy band rule, sorted top to bottom. */
    private fun paint(
        region: Rect = Rect(0.0, 0.0, pageW, pageH),
        spacing: Double = PageStyle.DEFAULT_SPACING,
        pattern: PagePattern = PagePattern.SOKKI,
    ): List<Pair<Double, Boolean>> {
        val r = FakeRenderer()
        paintPagePattern(r, pattern, blue, spacing, pageW, pageH, region)
        return r.polylines
            .map { (pts, pen) -> pts[0].y to (pen.width > PageStyle.LINE_THICKNESS) }
            .sortedBy { it.first }
    }

    @Test
    fun bandsRepeatAtTheSpacingWithTwoHairlinesInside() {
        // Spacing 64 is the template at 1:1: heavy rules on the 64s, hairlines 25 and 49 below each.
        val lines = paint()
        assertEquals(listOf(25.0 to false, 49.0 to false, 64.0 to true), lines.take(3))
        assertEquals(listOf(89.0 to false, 113.0 to false, 128.0 to true), lines.drop(3).take(3))
    }

    @Test
    fun theBandRuleIsHeavierThanItsHairlines() {
        val r = FakeRenderer()
        paintPagePattern(r, PagePattern.SOKKI, blue, 64.0, pageW, pageH, Rect(0.0, 0.0, pageW, pageH))
        val widths = r.polylines.map { it.second.width }.distinct().sorted()
        assertEquals(listOf(PageStyle.LINE_THICKNESS,
                            PageStyle.LINE_THICKNESS * PageStyle.SOKKI_HEAVY_FACTOR), widths)
    }

    @Test
    fun theLineAtThePageEdgeIsSkippedLikeEveryOtherPattern() {
        assertTrue(paint().none { it.first <= 0.0 })
    }

    @Test
    fun spacingScalesTheWholeBandAndKeepsItsProportions() {
        val lines = paint(spacing = 128.0)
        assertEquals(listOf(50.0 to false, 98.0 to false, 128.0 to true), lines.take(3))
    }

    @Test
    fun aRegionPaintsExactlyTheLinesInsideIt() {
        // Rect is origin + size: y = 100, height = 100, so the window is 100..200.
        val lines = paint(region = Rect(0.0, 100.0, pageW, 100.0))
        assertEquals(listOf(113.0 to false, 128.0 to true, 153.0 to false, 177.0 to false,
                            192.0 to true), lines)
    }

    @Test
    fun twoHalfRegionsAgreeWithOneWholeOneAtTheSeam() {
        // The sharp-viewport pass paints a sub-rect of a page the cached pass paints whole; if the
        // two disagreed the ruling would jump at the seam.
        val whole = paint()
        val halves = (paint(region = Rect(0.0, 0.0, pageW, 320.0)) +
            paint(region = Rect(0.0, 320.0, pageW, 320.0)))
            .distinct().sortedBy { it.first }
        assertEquals(whole, halves)
    }

    @Test
    fun sokkiDefaultsToTheTemplatesBlueAndLeavesTheOtherPatternsGrey() {
        assertEquals(PageStyle.SOKKI_RULE_COLOR, PagePattern.SOKKI.defaultColor)
        assertNotEquals(PageStyle.DEFAULT_PATTERN_COLOR, PagePattern.SOKKI.defaultColor)
        for (p in listOf(PagePattern.NONE, PagePattern.LINES, PagePattern.DOTS, PagePattern.GRID)) {
            assertEquals(PageStyle.DEFAULT_PATTERN_COLOR, p.defaultColor)
        }
    }

    @Test
    fun itSerializesUnderItsOwnId() {
        assertEquals(PagePattern.SOKKI, PagePattern.fromId("sokki"))
        assertEquals("sokki", PagePattern.SOKKI.id)
    }

    @Test
    fun plainLinesAreUntouchedByTheNewPattern() {
        val lines = paint(pattern = PagePattern.LINES)
        assertEquals(listOf(64.0 to false, 128.0 to false, 192.0 to false), lines.take(3))
    }
}
