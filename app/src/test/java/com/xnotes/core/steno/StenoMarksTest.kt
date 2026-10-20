package com.xnotes.core.steno

import com.xnotes.core.FakeRenderer
import com.xnotes.core.model.Document
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.snapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StenoMarksTest {

    private fun stroke(fix: StenoFix?): Stroke {
        val s = Stroke(Tool.STENO, ToolDefaults.configFor(Tool.STENO), MutableList(8) { Sample(10.0 + 5 * it, 40.0 + (it % 3), 0.5) })
        s.stenoFix = fix
        return s
    }

    private fun fix(vararg segs: FixSegment) = StenoFix(segs.toList(), FloatArray(8), FloatArray(8))

    /** The colours of the mists painted, in order. */
    private fun mists(r: FakeRenderer) = r.polylines.filter { it.second.glowRadius > 0 }.map { it.second.color.r to it.second.color.b }

    @Test fun everyStretchOfWritingIsMarked() {
        val r = FakeRenderer().apply { stenoMarks = true }
        stroke(fix(FixSegment(0, 2), FixSegment(3, 5, FixKind.GLYPH, reading = "t"), FixSegment(6, 7, FixKind.GLYPH, raw = true, reading = "o"))).paint(r)
        // Plain writing and the element shown as written orange, the corrected element cyan.
        assertEquals(listOf(255 to 0, 0 to 255, 255 to 0), mists(r))
        // A ring at both ends of each element, none on plain writing.
        assertEquals(4, r.ops.count { it == "strokeEllipse" })
    }

    @Test fun writingWithNoCorrectionIsMarkedAsWritten() {
        val r = FakeRenderer().apply { stenoMarks = true }
        stroke(null).paint(r)
        assertEquals(listOf(255 to 0), mists(r))
    }

    @Test fun noMarksWithoutTheSwitch() {
        val r = FakeRenderer()
        stroke(fix(FixSegment(0, 7, FixKind.GLYPH, reading = "t"))).paint(r)
        assertTrue(mists(r).isEmpty())
    }

    @Test fun theSavedCopyKeepsTheNotesStenoFlags() {
        val doc = Document.blank(count = 1).apply { steno = true; stenoMarks = true }
        val copy = doc.snapshot()
        assertTrue(copy.steno && copy.stenoMarks)
    }
}
