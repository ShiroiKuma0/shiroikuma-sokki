package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.core.model.PageStyle
import com.xnotes.core.steno.HandCleanup
import com.xnotes.core.steno.HandSize
import com.xnotes.core.steno.HandSlant
import com.xnotes.core.steno.LineFrame
import com.xnotes.core.steno.glyph.Glyph
import com.xnotes.core.steno.glyph.GlyphExample
import com.xnotes.core.steno.glyph.Recognizer
import com.xnotes.core.steno.glyph.StenoCorrection
import com.xnotes.core.steno.glyph.StenoEdit
import com.xnotes.platform.GlyphLibrary
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.tan

private val DRILL_INK = PAPER_INK
private val DRILL_RIGHT = Color(0xFF00E060)
private val DRILL_OTHER = Color(0xFFFF8C00)

/** The drill paper's band, in paper px: a note's 速記 band at its usual size. */
private const val DRILL_BAND = 122.0

/** One writing on the drill pad, and what the recognizer reads it as now. */
private class DrillStroke(val x: DoubleArray, val y: DoubleArray) {
    var verdict: String? = null
    var right: Boolean? = null
}

/** How far the drill paper zooms, in screen px per paper px. */
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 12f

/** How many writings of each element the drill asks for. */
private const val DRILL_TARGET = 8

/**
 * The catalogue's drill (fork): one element at a time, its textbook form above a strip of 速記
 * paper (black, ruled in blue, as a note shows it; pinch to zoom and pan), written by 白い熊 again
 * and again. Every writing becomes an example of the element, read
 * exactly as pen-up reads writing — so the recognizer learns this hand's form of it, at the
 * heights it stands at — and each shows at once what the recognizer reads it as. The writings are
 * filed when the drill moves on, is saved, or closes.
 *
 * Only elements recognition can learn from are drilled: reviewed ones, in the categories read
 * live, and not straight hairlines (those are matched by direction alone).
 */
@Composable
internal fun GlyphDrill(onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val order = remember {
        GlyphLibrary.items.filter {
            it.reviewed && it.category !in Recognizer.tuning.skip && StenoCorrection.straightDirection(it.shape) == null
        }.sortedWith(compareBy({ it.category.ordinal }, { it.reading.lowercase() })).map { it.id }
    }
    var index by remember { mutableIntStateOf(0) }
    val pending = remember { mutableStateListOf<DrillStroke>() }
    val live = remember { mutableStateListOf<Offset>() }
    var version by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    // The paper: an endless strip of 速記 bands in paper px, shown at [zoom] from [pan] (screen =
    // paper · zoom + pan). Zero zoom until the pad is first laid out.
    val frame = remember { LineFrame(DRILL_BAND) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val glyph: Glyph? = order.getOrNull(index)?.let { GlyphLibrary.get(it) }

    // The drill reads the hand afresh, as a note does when it opens, and leaves it clean behind.
    DisposableEffect(Unit) {
        HandSlant.clear(); HandSize.clear()
        onDispose { HandSlant.clear(); HandSize.clear() }
    }

    /** Files the pending writings as examples of the element in hand, read with the hand's habits
     *  as they stand now (enough writing has usually been seen by then to know them). */
    fun file() {
        val g = order.getOrNull(index)?.let { GlyphLibrary.get(it) } ?: return
        if (pending.isEmpty()) return
        // Until the hand's slant is known, the writing is read at its own slant, unsheared.
        val hand = HandSlant.estimate() ?: tan(HandCleanup.BOOK_SLANT_DEGREES * PI / 180.0)
        val added = pending.map { s -> StenoEdit.example(s.x, s.y, frame, 0, s.x.size - 1, GlyphExample.DRILL, hand) }
        GlyphLibrary.save(g.copy(examples = g.examples + added))
        pending.clear()
        version++
    }

    fun goTo(i: Int) {
        file()
        index = i.coerceIn(0, (order.size - 1).coerceAtLeast(0))
    }

    // Each new writing is read off the main thread, against the catalogue as it stands.
    LaunchedEffect(version) {
        val g = glyph ?: return@LaunchedEffect
        val todo = pending.filter { it.verdict == null }
        if (todo.isEmpty()) return@LaunchedEffect
        val cat = withContext(Dispatchers.Default) {
            GlyphLibrary.items.filter { it.reviewed }.takeIf { it.isNotEmpty() }?.let { StenoCorrection.Catalogue(it) }
        } ?: return@LaunchedEffect
        for (s in todo) {
            val top = withContext(Dispatchers.Default) {
                val r = StenoCorrection.reading(s.x, s.y, frame)
                cat.recognizer.rank(r.x, r.y, 0, s.x.size - 1, 1).firstOrNull()
            }
            val reading = top?.let { cat.byId[it.id]?.reading } ?: "–"
            s.right = top != null && reading == g.reading
            s.verdict = if (top == null) "–" else "%s %.2f".format(reading, top.error)
        }
        version++
    }

    Dialog(onDismissRequest = { file(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(palette.bg.toComposeColor()).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { file(); onDismiss() }) { Icon(XnotesIcons.close, stringResource(R.string.done), tint = palette.text.toComposeColor()) }
                Text(stringResource(R.string.glyph_drill_title), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { goTo(index - 1) }, enabled = index > 0) { Icon(XnotesIcons.prev, null, tint = palette.text.toComposeColor()) }
                Text(if (order.isEmpty()) "0 / 0" else "${index + 1} / ${order.size}", color = palette.text.toComposeColor())
                IconButton(onClick = { goTo(index + 1) }, enabled = index < order.size - 1) { Icon(XnotesIcons.next, null, tint = palette.text.toComposeColor()) }
                TextButton(onClick = { picking = true }) { Text(stringResource(R.string.glyph_drill_pick)) }
            }
            if (glyph == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.glyph_drill_none), color = palette.textDim.toComposeColor(), modifier = Modifier.padding(32.dp))
                }
                return@Column
            }
            Text(
                stringResource(R.string.glyph_drill_hint, DRILL_TARGET),
                color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphTile(glyph, Modifier.size(104.dp).border(1.dp, palette.border.toComposeColor()))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(glyph.reading, color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    Text(glyphCategoryLabel(glyph.category), color = palette.textDim.toComposeColor(), fontSize = 13.sp)
                    val drilled = glyph.examples.count { it.origin == GlyphExample.DRILL }
                    Text(
                        stringResource(R.string.glyph_drill_count, drilled, pending.size, DRILL_TARGET),
                        color = if (drilled + pending.size >= DRILL_TARGET) DRILL_RIGHT else palette.text.toComposeColor(), fontSize = 14.sp,
                    )
                    val read = pending.filter { it.right != null }
                    if (read.isNotEmpty()) Text(
                        stringResource(R.string.glyph_drill_read, read.count { it.right == true }, read.size),
                        color = palette.textDim.toComposeColor(), fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (pending.isNotEmpty()) { pending.removeAt(pending.size - 1); version++ } }, enabled = pending.isNotEmpty()) {
                    Icon(XnotesIcons.undo, stringResource(R.string.studio_undo), tint = palette.text.toComposeColor())
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { file() }, enabled = pending.isNotEmpty()) { Text(stringResource(R.string.glyph_drill_save)) }
                TextButton(onClick = { goTo(index + 1) }, enabled = index < order.size - 1) {
                    Text(stringResource(R.string.glyph_drill_next), fontWeight = FontWeight.Bold)
                }
            }
            val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true } }
            Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, palette.border.toComposeColor()).background(PAPER_BG).clipToBounds()) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(index) {
                        // One pointer writes; a second one turns the gesture into a pinch (zoom
                        // about the fingers, and pan with them), and the writing begun is dropped.
                        fun toPaper(p: Offset) = Offset((p.x - pan.x) / zoom, (p.y - pan.y) / zoom)
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (zoom <= 0f) return@awaitEachGesture
                            live.clear()
                            live.add(toPaper(down.position))
                            down.consume()
                            var pinching = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.count { it.pressed }
                                if (pinching || pressed >= 2) {
                                    if (!pinching) { pinching = true; live.clear() }
                                    val c = ev.calculateCentroid()
                                    if (c.isSpecified && pressed >= 2) {
                                        val z = ev.calculateZoom()
                                        val next = (zoom * z).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                        pan = c - (c - pan) * (next / zoom) + ev.calculatePan()
                                        zoom = next
                                    }
                                    ev.changes.forEach { it.consume() }
                                    if (pressed == 0) break
                                    continue
                                }
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                for (h in c.historical) live.add(toPaper(h.position))
                                live.add(toPaper(c.position))
                                c.consume()
                                if (!c.pressed) break
                            }
                            if (!pinching && live.size >= 6) {
                                val s = DrillStroke(DoubleArray(live.size) { live[it].x.toDouble() }, DoubleArray(live.size) { live[it].y.toDouble() })
                                // The hand's habits learn from the drill as from a note.
                                HandSlant.add(s.x, s.y, frame.unit)
                                HandSize.add(DoubleArray(s.y.size) { frame.unitsAbove(s.y[it], frame.baseFor(s.y.sorted()[s.y.size / 2])) })
                                pending += s
                                version++
                            }
                            live.clear()
                        }
                    },
                ) {
                    if (version < 0) return@Canvas
                    // First laid out: one row's writing zone (2.6 steps over its base line to 1.67
                    // under it, a band) fills the pad, its base line a band down.
                    if (zoom <= 0f) {
                        zoom = (size.height / (1.45 * DRILL_BAND)).toFloat()
                        pan = Offset.Zero
                    }
                    val z = zoom
                    val unit = (frame.unit * z).toFloat()
                    drawWritingLines((DRILL_BAND * z + pan.y).toFloat(), unit, size.width, (PageStyle.LINE_THICKNESS * z).toFloat().coerceAtLeast(1f))
                    val width = (1.6f * z).coerceAtLeast(2f)
                    labelPaint.textSize = 14.sp.toPx()
                    fun line(xs: List<Offset>, col: Color) {
                        if (xs.size < 2) return
                        val p = Path()
                        p.moveTo(xs[0].x * z + pan.x, xs[0].y * z + pan.y)
                        for (q in xs) p.lineTo(q.x * z + pan.x, q.y * z + pan.y)
                        drawPath(p, col, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    for (s in pending) {
                        val col = when (s.right) { true -> DRILL_RIGHT; false -> DRILL_OTHER; null -> DRILL_INK }
                        line(s.x.indices.map { Offset(s.x[it].toFloat(), s.y[it].toFloat()) }, col)
                        s.verdict?.let { v ->
                            labelPaint.color = col.toArgb()
                            drawContext.canvas.nativeCanvas.drawText(
                                v, (s.x.min() * z + pan.x).toFloat(), (s.y.min() * z + pan.y).toFloat() - 8.dp.toPx(), labelPaint,
                            )
                        }
                    }
                    line(live.toList(), DRILL_INK)
                }
            }
        }
    }
    if (picking) {
        GlyphPicker(onDismiss = { picking = false }) { g ->
            picking = false
            val i = order.indexOf(g.id)
            if (i >= 0) goTo(i)
        }
    }
}
