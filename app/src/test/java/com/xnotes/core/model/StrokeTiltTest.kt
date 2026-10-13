package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** The pen's tilt rides along with a stroke's samples, and its lean turns with the stroke. */
class StrokeTiltTest {

    private fun tilted() = Stroke(
        Tool.BRUSH, ToolDefaults.configFor(Tool.BRUSH),
        mutableListOf(Sample(0.0, 0.0, 0.5, 0.0, 0.7, 0.0), Sample(10.0, 0.0, 0.5, 8.0, 0.7, 0.0), Sample(20.0, 0.0, 0.5, 16.0, 0.7, 0.0)),
    )

    @Test fun anUntiltedStrokeCarriesNoTilt() {
        val s = Stroke(Tool.BRUSH, ToolDefaults.configFor(Tool.BRUSH), mutableListOf(Sample(0.0, 0.0, 0.5), Sample(5.0, 0.0, 0.5, 4.0)))
        assertFalse(s.hasTilt)
    }

    @Test fun samplesAddedOneByOneKeepTheirTilt() {
        val s = Stroke(Tool.BRUSH, ToolDefaults.configFor(Tool.BRUSH), mutableListOf())
        s.addSample(Sample(0.0, 0.0, 0.5))
        s.addSample(Sample(5.0, 0.0, 0.5, 4.0, 0.4, 1.0))
        assertTrue(s.hasTilt)
        assertEquals(0.0, s.samples[0].tilt, 1e-6)
        assertEquals(0.4, s.samples[1].tilt, 1e-6)
        assertEquals(1.0, s.samples[1].lean, 1e-6)
    }

    @Test fun aRotationTurnsTheLean() {
        val s = tilted()
        s.applyTransform(Affine.rotateAbout(Pt(0.0, 0.0), PI / 2))
        assertEquals(0.7, s.samples[1].tilt, 1e-6)
        assertEquals(PI / 2, s.samples[1].lean, 1e-5)
    }

    @Test fun copiesUndoAndMovesKeepTheTilt() {
        val s = tilted()
        val snap = s.snapshotGeometry()
        s.applyTransform(Affine.rotateAbout(Pt(0.0, 0.0), PI / 2))
        s.restoreGeometry(snap)
        assertEquals(0.0, s.samples[2].lean, 1e-6)
        s.translate(5.0, 5.0)
        val copy = Stroke(s)
        assertEquals(0.7, copy.samples[2].tilt, 1e-6)
    }
}
