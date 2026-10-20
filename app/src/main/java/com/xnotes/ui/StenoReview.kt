package com.xnotes.ui

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.xnotes.R
import com.xnotes.core.steno.FixKind
import com.xnotes.core.steno.glyph.Glyph
import com.xnotes.core.steno.glyph.GlyphCategory
import com.xnotes.platform.GlyphLibrary
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val PAPER = PAPER_BG
private val INK_DIM = PAPER_DIM

/**
 * The 速記 review (fork): the control item for what was not fixed live. It steps through the
 * current page's stretches — by default only those the recognizer left alone and nobody has
 * looked at — outlining each on the canvas. Two handles pick the exact part (they snap to where
 * the stroke may be cut), and then:
 *
 * - **Is…** — it is this element: drawn in its clean form, and the writing becomes an example of
 *   it, so the next one like it is recognized at pen-up;
 * - **New element** — named and filed in the catalogue, from the pad's form or the writing itself;
 * - **Not an element** — left as the cleaned hand, and passed by from now on;
 * - **Replace** — the part redrawn as the form written in the pad, or as an element picked; it then
 *   teaches the writing as the element the form matches, a new one, another picked, or nothing.
 *
 * While the panel is open, a tap on any 速記 writing on the page turns it to the stretch under the
 * tap (presses pan the page instead of writing). Every action is one undo step on the note; the
 * catalogue keeps what is learned — Replace too, unless the user wants it just this once.
 */
@Composable
fun StenoReviewPanel(editor: Editor, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    var onlyUnfixed by remember { mutableStateOf(true) }
    var version by remember { mutableIntStateOf(0) }
    val items = remember(version, onlyUnfixed) { editor.stenoReviewItems(onlyUnfixed) }
    var index by remember { mutableIntStateOf(0) }
    if (index >= items.size) index = max(0, items.size - 1)
    // A stretch tapped on the page takes the panel over until the next step through the queue.
    var pinned by remember { mutableStateOf<Editor.StenoReviewItem?>(null) }
    LaunchedEffect(editor.stenoPicked) { editor.stenoPicked?.let { pinned = it } }
    val item = pinned ?: items.getOrNull(index)
    val sv = remember(item, version) { item?.let { editor.stenoStrokeView(it) } }
    var a by remember(item, version) { mutableIntStateOf(item?.from ?: 0) }
    var b by remember(item, version) { mutableIntStateOf(item?.to ?: 0) }
    val pad = remember { mutableStateListOf<Offset>() }
    var picking by remember { mutableStateOf<String?>(null) } // "assign" or "replace"
    var naming by remember { mutableStateOf(false) }
    // A replacement by the pad's form waiting to be taught: the element the form looks like, if any.
    var teaching by remember { mutableStateOf(false) }
    var suggestion by remember { mutableStateOf<Glyph?>(null) }

    LaunchedEffect(item, a, b) { editor.stenoFocus(item, a, b) }
    DisposableEffect(Unit) {
        editor.setStenoPickMode(true)
        onDispose {
            editor.setStenoPickMode(false)
            editor.stenoFocus(null)
        }
    }

    /** The pad's form in units (x from its left, y up from its base line), or null when empty. */
    // Written while drawing, read on a tap: plain holders, not state (no redraw loop).
    val padMetrics = remember { FloatArray(2) { 1f } }
    fun padForm(): Pair<DoubleArray, DoubleArray>? {
        val padUnit = padMetrics[0]
        val padBase = padMetrics[1]
        if (pad.size < 3 || padUnit <= 0f) return null
        return DoubleArray(pad.size) { ((pad[it].x - pad[0].x) / padUnit).toDouble() } to
            DoubleArray(pad.size) { ((padBase - pad[it].y) / padUnit).toDouble() }
    }
    fun done() {
        pad.clear()
        pinned = null
        editor.stenoPicked = null
        version++
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        // A panel along the bottom, the note above it left visible, undimmed and live: touches
        // outside the panel reach the page, where a tap on writing picks it.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.setGravity(Gravity.BOTTOM)
            window?.setDimAmount(0f)
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        Column(
            Modifier.fillMaxWidth().height(400.dp).background(palette.panel.toComposeColor())
                .border(1.dp, palette.border.toComposeColor()).padding(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.toolbar_steno_review), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { if (pinned != null) pinned = null else if (index > 0) index-- }, enabled = pinned != null || index > 0) { Icon(XnotesIcons.prev, null, tint = palette.text.toComposeColor()) }
                Text(if (pinned != null) "·" else if (items.isEmpty()) "0 / 0" else "${index + 1} / ${items.size}", color = palette.text.toComposeColor())
                IconButton(onClick = { if (pinned != null) pinned = null else if (index < items.size - 1) index++ }, enabled = pinned != null || index < items.size - 1) { Icon(XnotesIcons.next, null, tint = palette.text.toComposeColor()) }
                Checkbox(onlyUnfixed, { onlyUnfixed = it; index = 0 })
                Text(stringResource(R.string.review_only_unfixed), color = palette.text.toComposeColor(), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onDismiss) { Icon(XnotesIcons.close, stringResource(R.string.done), tint = palette.text.toComposeColor()) }
            }
            if (item == null || sv == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.review_nothing), color = palette.textDim.toComposeColor())
                }
                return@Column
            }
            Text(
                when (item.kind) {
                    FixKind.GLYPH -> stringResource(R.string.review_is_element, item.reading ?: "?")
                    FixKind.REPLACED -> stringResource(R.string.review_replaced)
                    FixKind.CLEAN -> stringResource(R.string.review_unfixed)
                } + " " + stringResource(R.string.review_tap_hint),
                color = palette.textDim.toComposeColor(), fontSize = 13.sp,
            )
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The stroke on its lines, the part picked in the accent; drag a handle along it.
                val accent = palette.accent.toComposeColor()
                Box(Modifier.weight(1.4f).fillMaxHeight().border(1.dp, palette.border.toComposeColor()).background(PAPER)) {
                    val geoHolder = remember { arrayOfNulls<StrokeGeo>(1) }
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(item, version) {
                            var which = 0
                            detectDragGestures(
                                onDragStart = { p ->
                                    val g = geoHolder[0] ?: return@detectDragGestures
                                    which = if (g.dist(p, a) <= g.dist(p, b)) 0 else 1
                                },
                                onDrag = { change, _ ->
                                    val g = geoHolder[0] ?: return@detectDragGestures
                                    val c = sv.cuts.minByOrNull { g.dist(change.position, it) } ?: return@detectDragGestures
                                    if (which == 0) { if (c < b) a = c } else { if (c > a) b = c }
                                },
                            )
                        },
                    ) {
                        val ys = sv.uy
                        val top = max(2.7, ys.max() + 0.3)
                        val bot = min(-1.0, ys.min() - 0.3)
                        val w = (sv.ux.max() - sv.ux.min()).coerceAtLeast(1.0) + 0.6
                        val k = min(size.width / w, size.height / (top - bot)).toFloat()
                        val ox = (size.width - (sv.ux.max() - sv.ux.min()).toFloat() * k) / 2 - sv.ux.min().toFloat() * k
                        val oy = (top * k).toFloat() + (size.height - ((top - bot) * k).toFloat()) / 2
                        val g = StrokeGeo(sv.ux, sv.uy, k, ox, oy)
                        geoHolder[0] = g
                        drawWritingLines(oy, k, size.width)
                        drawPath(g.path(0, sv.n - 1), INK_DIM, style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(g.path(a, b), accent, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        for (c in sv.marks) drawCircle(Color(0xFF606060), 3f, g.at(c))
                        drawCircle(accent, 11f, g.at(a)); drawCircle(PAPER_BG, 5f, g.at(a))
                        drawCircle(accent, 11f, g.at(b)); drawCircle(PAPER_BG, 5f, g.at(b))
                    }
                }
                // The pad: write the right form here for Replace or New element.
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, palette.border.toComposeColor()).background(PAPER)) {
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { p -> pad.clear(); pad.add(p) },
                                    onDrag = { change, _ -> pad.add(change.position) },
                                )
                            },
                        ) {
                            val unit = size.height / 4.4f
                            val base = size.height - 1.3f * unit
                            padMetrics[0] = unit
                            padMetrics[1] = base
                            drawWritingLines(base, unit, size.width)
                            if (pad.size > 1) {
                                val p = Path()
                                p.moveTo(pad[0].x, pad[0].y)
                                for (q in pad) p.lineTo(q.x, q.y)
                                drawPath(p, Color.Black, style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                        if (pad.isEmpty()) Text(stringResource(R.string.review_pad_hint), color = INK_DIM, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
                    }
                    TextButton(onClick = { pad.clear() }, enabled = pad.isNotEmpty()) { Text(stringResource(R.string.clear)) }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { picking = "assign" }) { Text(stringResource(R.string.review_is), fontWeight = FontWeight.Bold) }
                TextButton(onClick = { naming = true }) { Text(stringResource(R.string.review_new)) }
                TextButton(onClick = { editor.stenoPlain(item, a, b); done() }) { Text(stringResource(R.string.review_plain)) }
                TextButton(onClick = {
                    val f = padForm()
                    if (f != null) { suggestion = editor.stenoMatchForm(f.first, f.second); teaching = true } else picking = "replace"
                }) { Text(stringResource(R.string.review_replace)) }
            }
        }
    }

    picking?.let { mode ->
        GlyphPicker(onDismiss = { picking = null }) { g ->
            picking = null
            if (mode == "assign") editor.stenoAssign(item!!, a, b, g) else editor.stenoReplace(item!!, a, b, null, null, g, teach = true)
            done()
        }
    }
    if (teaching && item != null) {
        // Replace teaches: the writing becomes an example of the element it is redrawn as, so the
        // next one like it is redrawn at pen-up — unless it is wanted just this once.
        AlertDialog(
            onDismissRequest = { teaching = false },
            title = { Text(stringResource(R.string.review_teach_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.review_teach_text))
                    Spacer(Modifier.height(8.dp))
                    suggestion?.let { g ->
                        TextButton(onClick = { teaching = false; editor.stenoReplace(item, a, b, null, null, g, teach = true); done() }) {
                            Text(stringResource(R.string.review_teach_as, g.reading), fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = { teaching = false; naming = true }) { Text(stringResource(R.string.review_teach_new)) }
                    TextButton(onClick = { teaching = false; picking = "replace" }) { Text(stringResource(R.string.review_teach_pick)) }
                    TextButton(onClick = {
                        teaching = false
                        padForm()?.let { f -> editor.stenoReplace(item, a, b, f.first, f.second, null) }
                        done()
                    }) { Text(stringResource(R.string.review_teach_once)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { teaching = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (naming && item != null) {
        SaveGlyphDialog(
            initialReading = "", initialCategory = GlyphCategory.OWN,
            onDismiss = { naming = false },
            onSave = { reading, cat ->
                naming = false
                val f = padForm()
                editor.stenoNewElement(item, a, b, f?.first, f?.second, reading, cat)
                done()
            },
        )
    }
}

/** A stroke in units mapped onto a canvas: [k] px per unit, x0/y0 the origin (base line). */
private class StrokeGeo(val ux: DoubleArray, val uy: DoubleArray, val k: Float, val ox: Float, val oy: Float) {
    fun at(i: Int) = Offset(ox + ux[i].toFloat() * k, oy - uy[i].toFloat() * k)
    fun dist(p: Offset, i: Int): Float { val q = at(i); return hypot(p.x - q.x, p.y - q.y) }
    fun path(a: Int, b: Int): Path {
        val p = Path()
        val s = at(a)
        p.moveTo(s.x, s.y)
        for (i in a + 1..b) { val q = at(i); p.lineTo(q.x, q.y) }
        return p
    }
}

/** Every catalogue element (but [exclude]) as a tile, filtered by its reading; a tap picks it. */
@Composable
internal fun GlyphPicker(onDismiss: () -> Unit, exclude: String? = null, onPick: (Glyph) -> Unit) {
    val palette = LocalPalette.current
    var query by remember { mutableStateOf("") }
    val shown = GlyphLibrary.items.filter { it.id != exclude && (query.isBlank() || it.reading.contains(query.trim(), ignoreCase = true)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f).background(palette.bg.toComposeColor()).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.glyph_reading)) }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(XnotesIcons.close, stringResource(R.string.cancel), tint = palette.text.toComposeColor()) }
            }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(GridCells.Adaptive(92.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.id }) { g ->
                    Column(Modifier.border(1.dp, palette.border.toComposeColor()).clickable { onPick(g) }) {
                        GlyphTile(g, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(g.reading, color = palette.text.toComposeColor(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp))
                    }
                }
            }
        }
    }
}
