package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.xnotes.core.steno.glyph.GlyphExample
import com.xnotes.core.steno.glyph.GlyphPath
import com.xnotes.core.steno.glyph.GlyphShape
import com.xnotes.core.steno.glyph.ShapeEdit
import com.xnotes.core.steno.trace.CurveFit
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Drawing a 速記 element in Compose (fork): its paths as a ribbon of round-capped segments, each as
 * wide as the shading there says — the hairline, swelling to three times it where the print does.
 * [originX], [baseY] place x = 0 on the base line; [unitPx] is one step.
 */
internal fun DrawScope.drawGlyph(shape: GlyphShape, originX: Float, baseY: Float, unitPx: Float, color: Color) {
    for (p in shape.paths) {
        if (p.size == 1) {
            drawCircle(color, (GlyphShape.width(p.shade[0]) * unitPx / 2).toFloat(), Offset(originX + p.x[0].toFloat() * unitPx, baseY - p.y[0].toFloat() * unitPx))
            continue
        }
        for (i in 1 until p.size) {
            val w = (GlyphShape.width((p.shade[i] + p.shade[i - 1]) / 2) * unitPx).toFloat()
            drawLine(
                color,
                Offset(originX + p.x[i - 1].toFloat() * unitPx, baseY - p.y[i - 1].toFloat() * unitPx),
                Offset(originX + p.x[i].toFloat() * unitPx, baseY - p.y[i].toFloat() * unitPx),
                strokeWidth = w.coerceAtLeast(1f),
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 白い熊's 速記 paper, as a note shows it: black, ruled in the note's blue, written in yellow. */
internal val PAPER_BG = Color.Black
internal val PAPER_RULE = Color(0xFF0000FF)
internal val PAPER_INK = Color(0xFFFFFF00)

/** Writing shown only for comparison (as written, under the corrected line): dimmed. */
internal val PAPER_DIM = Color(0xFF6E6E6E)

/**
 * The 速記 ruling, as a note draws it ([com.xnotes.core.model.PageStyle.SOKKI_LINES]): every band
 * opened by its heavy base line, with hairlines one step and 2.6 steps above it, repeated a band
 * (64/15 steps) apart over the whole canvas. [baseY] is any one base line, [unitPx] one step, and
 * [hair] the hairline's width in px (the base line is twice it).
 */
internal fun DrawScope.drawWritingLines(baseY: Float, unitPx: Float, width: Float, hair: Float = 1.5f) {
    if (unitPx <= 0f) return
    val band = unitPx * (64f / 15f)
    var b = baseY - kotlin.math.ceil(baseY / band) * band
    while (b - 2.6f * unitPx <= size.height) {
        for ((y, w) in listOf(b to 2 * hair, b - unitPx to hair, b - 2.6f * unitPx to hair)) {
            if (y >= -w && y <= size.height + w) drawLine(PAPER_RULE, Offset(0f, y), Offset(width, y), strokeWidth = w)
        }
        b += band
    }
}

/** Segment [seg] of [shape] (one fitted curve) drawn over it, to show it picked. */
internal fun DrawScope.drawSegment(shape: GlyphShape, seg: ShapeEdit.Seg, originX: Float, baseY: Float, unitPx: Float, color: Color, width: Float) {
    val c = shape.paths.getOrNull(seg.path)?.curves?.getOrNull(seg.curve) ?: return
    val path = Path()
    for (i in 0..32) {
        val q = CurveFit.point(c, i / 32.0)
        val x = originX + q[0].toFloat() * unitPx
        val y = baseY - q[1].toFloat() * unitPx
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Which way each path of [shape] is written: a dot where it starts, chevrons along it, a bar where
 *  it ends. */
internal fun DrawScope.drawDirection(shape: GlyphShape, originX: Float, baseY: Float, unitPx: Float, color: Color) {
    fun at(p: GlyphPath, i: Int) = Offset(originX + p.x[i].toFloat() * unitPx, baseY - p.y[i].toFloat() * unitPx)
    for (p in shape.paths) {
        if (p.size < 2) continue
        val cum = DoubleArray(p.size)
        for (i in 1 until p.size) cum[i] = cum[i - 1] + hypot(p.x[i] - p.x[i - 1], p.y[i] - p.y[i - 1])
        val total = cum.last()
        drawCircle(color, 0.07f * unitPx, at(p, 0))
        // A chevron about every half step, at least two.
        val marks = max(2, (total / 0.5).roundToInt())
        for (m in 1..marks) {
            val d = total * m / (marks + 1)
            val i = cum.indexOfFirst { it >= d }.coerceIn(1, p.size - 1)
            val j = cum.indexOfFirst { it >= d + 0.04 }.let { if (it < 0) p.size - 1 else it }.coerceAtLeast(i)
            val k = cum.indexOfLast { it <= d - 0.04 }.coerceIn(0, i - 1)
            val a = at(p, k)
            val b = at(p, j)
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len < 1e-3f) continue
            val ux = (b.x - a.x) / len
            val uy = (b.y - a.y) / len
            val tip = at(p, i)
            val s = 0.09f * unitPx
            for (side in listOf(1f, -1f)) {
                drawLine(color, tip, Offset(tip.x - ux * s + side * uy * s * 0.7f, tip.y - uy * s - side * ux * s * 0.7f), strokeWidth = 0.025f * unitPx, cap = StrokeCap.Round)
            }
        }
        val e = at(p, p.size - 1)
        val f = at(p, p.size - 2)
        val len = hypot(e.x - f.x, e.y - f.y).coerceAtLeast(1e-3f)
        val nx = -(e.y - f.y) / len * 0.08f * unitPx
        val ny = (e.x - f.x) / len * 0.08f * unitPx
        drawLine(color, Offset(e.x - nx, e.y - ny), Offset(e.x + nx, e.y + ny), strokeWidth = 0.03f * unitPx, cap = StrokeCap.Round)
    }
}

/** One of the user's writings of an element, started at ([startX], [startY]) px. */
internal fun DrawScope.drawExample(e: GlyphExample, startX: Float, startY: Float, unitPx: Float, color: Color, width: Float) {
    if (e.x.size < 2) return
    val path = Path()
    for (i in e.x.indices) {
        val x = startX + e.x[i].toFloat() * unitPx
        val y = startY - e.y[i].toFloat() * unitPx
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
