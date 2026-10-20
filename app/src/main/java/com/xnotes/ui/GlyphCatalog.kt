package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.core.steno.FixKind
import com.xnotes.core.steno.HandCleanup
import com.xnotes.core.steno.LineFrame
import com.xnotes.core.steno.StenoFix
import com.xnotes.core.steno.glyph.CatalogEdit
import com.xnotes.core.steno.glyph.Glyph
import com.xnotes.core.steno.glyph.GlyphCategory
import com.xnotes.core.steno.glyph.GlyphExample
import com.xnotes.core.steno.glyph.GlyphShape
import com.xnotes.core.steno.glyph.Recognizer
import com.xnotes.core.steno.glyph.ShapeEdit
import com.xnotes.core.steno.glyph.StenoCorrection
import com.xnotes.platform.GlyphLibrary
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Elements are drawn on 速記 paper as a note shows it: yellow on black, ruled in blue. */
private val INK = PAPER_INK

/** An element on its lines, centred, one step being a quarter of the height and a bit. */
@Composable
internal fun GlyphTile(g: Glyph, modifier: Modifier) {
    Canvas(modifier.background(PAPER_BG)) {
        val unit = size.height / 4.2f
        val base = size.height - 0.9f * unit
        drawWritingLines(base, unit, size.width)
        val w = (g.shape.maxX - g.shape.minX).toFloat() * unit
        val fit = if (w > size.width * 0.9f) size.width * 0.9f / w else 1f
        drawGlyph(g.shape, (size.width - w * fit) / 2, base, unit * fit, INK)
    }
}

/**
 * The 速記 element catalogue (fork): every element as a card on its lines, grouped by category;
 * a tap opens it to rename, re-file, mark reviewed, fix its form, re-trace, merge, split or delete;
 * “Add element” opens the studio, “Drill” a pad on which each element is written again and again to
 * teach the recognizer this hand, and “Test” a pad that shows what the correction makes of writing.
 * The catalogue is the base the correction draws from, and it stays editable.
 */
@Composable
internal fun GlyphCatalogPane(sidebarOpen: Boolean, onShowSidebar: () -> Unit, params: () -> HandCleanup.Params) {
    val palette = LocalPalette.current
    var studio by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var drilling by remember { mutableStateOf(false) }
    var retrace by remember { mutableStateOf<Glyph?>(null) }
    var open by remember { mutableStateOf<Glyph?>(null) }
    var onlyUnreviewed by remember { mutableStateOf(false) }
    val all = GlyphLibrary.items
    val shown = all.filter { !onlyUnreviewed || !it.reviewed }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!sidebarOpen) {
                SidebarMenuButton(onShowSidebar)
            }
            Text(
                stringResource(R.string.glyph_catalog), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold,
                fontSize = 20.sp, maxLines = 1, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { drilling = true }) { Text(stringResource(R.string.glyph_drill)) }
            TextButton(onClick = { testing = true }) { Text(stringResource(R.string.glyph_test)) }
            TextButton(onClick = { studio = true }) { Text(stringResource(R.string.glyph_add), fontWeight = FontWeight.Bold) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !onlyUnreviewed, onClick = { onlyUnreviewed = false }, label = { Text(stringResource(R.string.glyph_filter_all) + " · " + all.size) })
            FilterChip(selected = onlyUnreviewed, onClick = { onlyUnreviewed = true }, label = { Text(stringResource(R.string.glyph_filter_unreviewed) + " · " + all.count { !it.reviewed }) })
        }
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.glyph_empty), color = palette.textDim.toComposeColor(), modifier = Modifier.padding(32.dp))
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(116.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { g ->
                    Column(
                        Modifier.border(1.dp, if (g.reviewed) palette.border.toComposeColor() else palette.accent.toComposeColor())
                            .clickable { open = g },
                    ) {
                        GlyphTile(g, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(
                            g.reading, color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp, top = 4.dp, end = 6.dp),
                        )
                        Text(
                            glyphCategoryLabel(g.category), color = palette.textDim.toComposeColor(), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                        )
                    }
                }
            }
        }
    }

    if (studio) GlyphStudio(editing = null, onClose = { studio = false })
    if (testing) GlyphTestPad(params(), onDismiss = { testing = false })
    if (drilling) GlyphDrill(onDismiss = { drilling = false })
    retrace?.let { g -> GlyphStudio(editing = g, onClose = { retrace = null }) }
    open?.let { g ->
        GlyphDetail(
            g,
            onDismiss = { open = null },
            onRetrace = { open = null; retrace = g },
        )
    }
}

/** A scan's print as a negative: white paper black, black ink white. */
private val INVERT = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
    androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)),
)

/** Your writings, drawn over the form in a colour of their own. */
private val EXAMPLE = Color(0x90E06000)
private val PICKED = Color(0xFFE000A0)
private val DIRECTION = Color(0xFF00A000)

/**
 * One element large, over the print it was traced from, with everything about it editable: a tap
 * on a segment of the form picks it for a one-tap fix, the direction it is written in and the
 * user's own writings of it can be laid over it, and it can be merged into another or split.
 */
@Composable
private fun GlyphDetail(g: Glyph, onDismiss: () -> Unit, onRetrace: () -> Unit) {
    val palette = LocalPalette.current
    var reading by remember(g.id) { mutableStateOf(g.reading) }
    var category by remember(g.id) { mutableStateOf(g.category) }
    var reviewed by remember(g.id) { mutableStateOf(g.reviewed) }
    var note by remember(g.id) { mutableStateOf(g.note) }
    var shape by remember(g.id) { mutableStateOf(g.shape) }
    val history = remember(g.id) { mutableStateListOf<GlyphShape>() }
    var sel by remember(g.id) { mutableStateOf<ShapeEdit.Seg?>(null) }
    var examples by remember(g.id) { mutableStateOf(g.examples) }
    var showDirection by remember { mutableStateOf(true) }
    var showExamples by remember { mutableStateOf(true) }
    var scan by remember { mutableFloatStateOf(0.35f) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exampleMenu by remember { mutableStateOf<Int?>(null) }
    var moving by remember { mutableStateOf<Int?>(null) }
    var merging by remember { mutableStateOf(false) }
    var mergeInto by remember { mutableStateOf<Glyph?>(null) }
    var splitting by remember { mutableStateOf(false) }
    var crop by remember(g.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(g.id, g.modified) { crop = withContext(Dispatchers.IO) { GlyphLibrary.crop(g.id) } }

    /** The element as it now stands in this view. */
    fun current() = g.copy(
        reading = reading.trim().ifEmpty { g.reading }, category = category, reviewed = reviewed, note = note,
        shape = shape, examples = examples,
    )

    // Where the form sits on the canvas, written while drawing and read by a tap.
    val layout = remember { FloatArray(3) }
    val shapeNow by rememberUpdatedState(shape)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(palette.bg.toComposeColor()).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(XnotesIcons.close, stringResource(R.string.cancel), tint = palette.text.toComposeColor()) }
                Text(g.reading, color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    GlyphLibrary.save(current())
                    onDismiss()
                }) { Text(stringResource(R.string.save), fontWeight = FontWeight.Bold) }
            }
            // The form, large, with the print under it at the chosen strength.
            Box(Modifier.fillMaxWidth().height(320.dp).border(1.dp, palette.border.toComposeColor())) {
                val img = crop
                val src = g.source
                Canvas(
                    Modifier.fillMaxSize().background(PAPER_BG).pointerInput(g.id) {
                        detectTapGestures { p ->
                            val unit = layout[0]
                            if (unit <= 0f) return@detectTapGestures
                            sel = ShapeEdit.hit(shapeNow, ((p.x - layout[1]) / unit).toDouble(), ((layout[2] - p.y) / unit).toDouble())
                        }
                    },
                ) {
                    val unit = size.height / 4.4f
                    val base = size.height - 1.0f * unit
                    // Laid out by the form as saved, so it stays put while it is being fixed.
                    val w = (g.shape.maxX - g.shape.minX).toFloat() * unit
                    val ox = (size.width - w) / 2 - g.shape.minX.toFloat() * unit
                    layout[0] = unit; layout[1] = ox; layout[2] = base
                    if (img != null && src != null && scan > 0.01f) {
                        val k = unit / src.cropStep.toFloat()
                        drawImage(
                            img.asImageBitmap(),
                            dstOffset = IntOffset((ox - src.cropLeft.toFloat() * k).roundToInt(), (base - src.cropBase.toFloat() * k).roundToInt()),
                            dstSize = IntSize((img.width * k).roundToInt(), (img.height * k).roundToInt()),
                            alpha = scan,
                            // The print turned negative, so it lies light on the black paper.
                            colorFilter = INVERT,
                        )
                    }
                    drawWritingLines(base, unit, size.width)
                    if (showExamples) {
                        val main = shape.paths.maxByOrNull { it.size }
                        if (main != null && main.size > 0) {
                            val sx = ox + main.x[0].toFloat() * unit
                            val sy = base - main.y[0].toFloat() * unit
                            for (e in examples) drawExample(e, sx, sy, unit, EXAMPLE, 2.5f)
                        }
                    }
                    drawGlyph(shape, ox, base, unit, INK)
                    sel?.let { drawSegment(shape, it, ox, base, unit, PICKED, 0.08f * unit) }
                    if (showDirection) drawDirection(shape, ox, base, unit, DIRECTION)
                }
            }
            SegmentFixRow(
                shape, sel, history.isNotEmpty(),
                onFix = { f -> sel?.let { s -> history += shape; shape = ShapeEdit.apply(shape, s, f) } },
                onUndo = { if (history.isNotEmpty()) shape = history.removeAt(history.size - 1) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(showDirection, { showDirection = it })
                Text(stringResource(R.string.glyph_show_direction), color = palette.text.toComposeColor())
                Spacer(Modifier.width(12.dp))
                Checkbox(showExamples, { showExamples = it })
                Text(stringResource(R.string.glyph_show_examples, examples.size), color = palette.text.toComposeColor())
            }
            if (examples.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    examples.forEachIndexed { i, e ->
                        Box {
                            ExampleTile(e, Modifier.size(72.dp).border(1.dp, palette.border.toComposeColor()).clickable { exampleMenu = i })
                            DropdownMenu(expanded = exampleMenu == i, onDismissRequest = { exampleMenu = null }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.glyph_example_delete)) }, onClick = {
                                    exampleMenu = null
                                    examples = examples.filterIndexed { k, _ -> k != i }
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.glyph_example_move)) }, onClick = { exampleMenu = null; moving = i })
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.glyph_scan_opacity), color = palette.text.toComposeColor(), modifier = Modifier.width(80.dp))
                Slider(scan, { scan = it }, modifier = Modifier.weight(1f))
            }
            g.source?.let { s ->
                Text(
                    s.label + (s.page?.let { " · " + it } ?: ""),
                    color = palette.textDim.toComposeColor(), fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(reading, { reading = it }, label = { Text(stringResource(R.string.glyph_reading)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Box {
                TextButton(onClick = { menu = true }) { Text(stringResource(R.string.glyph_category) + ": " + glyphCategoryLabel(category)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    GlyphCategory.entries.forEach { c -> DropdownMenuItem(text = { Text(glyphCategoryLabel(c)) }, onClick = { category = c; menu = false }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(reviewed, { reviewed = it })
                Text(stringResource(R.string.glyph_reviewed), color = palette.text.toComposeColor())
            }
            OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.glyph_note)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetrace, enabled = crop != null) { Text(stringResource(R.string.glyph_retrace)) }
                TextButton(onClick = { merging = true }) { Text(stringResource(R.string.glyph_merge)) }
                TextButton(onClick = { splitting = true }) { Text(stringResource(R.string.glyph_split)) }
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.glyph_delete), color = palette.danger.toComposeColor()) }
            }
        }
    }
    moving?.let { i ->
        GlyphPicker(onDismiss = { moving = null }, exclude = g.id) { to ->
            moving = null
            val (a, b) = CatalogEdit.moveExample(current(), i, to)
            examples = a.examples
            GlyphLibrary.save(a)
            GlyphLibrary.save(b)
        }
    }
    if (merging) {
        GlyphPicker(onDismiss = { merging = false }, exclude = g.id) { to -> merging = false; mergeInto = to }
    }
    mergeInto?.let { to ->
        AlertDialog(
            onDismissRequest = { mergeInto = null },
            title = { Text(stringResource(R.string.glyph_merge_confirm, current().reading, to.reading)) },
            confirmButton = {
                TextButton(onClick = {
                    mergeInto = null
                    GlyphLibrary.save(CatalogEdit.merge(current(), to))
                    GlyphLibrary.delete(g.id)
                    onDismiss()
                }) { Text(stringResource(R.string.glyph_merge_do), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { mergeInto = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (splitting) {
        SplitDialog(
            current(),
            onDismiss = { splitting = false },
            onSplit = { take, newReading, newCategory ->
                splitting = false
                val (left, made) = CatalogEdit.split(current(), take, GlyphLibrary.newId(), newReading.trim(), newCategory)
                examples = left.examples
                GlyphLibrary.save(left)
                GlyphLibrary.save(made, if (take.isEmpty()) crop else null)
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.glyph_delete_confirm, g.reading)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; GlyphLibrary.delete(g.id); onDismiss() }) {
                    Text(stringResource(R.string.delete), color = palette.danger.toComposeColor())
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The one-tap fixes for the segment picked on a form: an exact straight line, a curve flowing on
 * from its neighbours, its shading on or off — and back, one fix at a time.
 */
@Composable
internal fun SegmentFixRow(shape: GlyphShape, sel: ShapeEdit.Seg?, canUndo: Boolean, onFix: (ShapeEdit.Fix) -> Unit, onUndo: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        if (sel == null) {
            Text(stringResource(R.string.glyph_fix_hint), color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        } else {
            TextButton(onClick = { onFix(ShapeEdit.Fix.STRAIGHT) }) { Text(stringResource(R.string.glyph_fix_straight)) }
            TextButton(onClick = { onFix(ShapeEdit.Fix.CURVE) }) { Text(stringResource(R.string.glyph_fix_curve)) }
            FilterChip(
                selected = ShapeEdit.isShaded(shape, sel), onClick = { onFix(ShapeEdit.Fix.SHADED) },
                label = { Text(stringResource(R.string.glyph_fix_shaded)) },
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onUndo, enabled = canUndo) { Text(stringResource(R.string.glyph_fix_undo)) }
    }
}

/** One of the user's writings, fitted into its tile. */
@Composable
private fun ExampleTile(e: GlyphExample, modifier: Modifier) {
    Canvas(modifier.background(PAPER_BG)) {
        val x0 = e.x.min(); val x1 = e.x.max(); val y0 = e.y.min(); val y1 = e.y.max()
        val k = min(size.width * 0.8f / max(1e-3, x1 - x0).toFloat(), size.height * 0.8f / max(1e-3, y1 - y0).toFloat())
        val sx = (size.width - (x1 - x0).toFloat() * k) / 2 - x0.toFloat() * k
        val sy = (size.height + (y1 - y0).toFloat() * k) / 2 + y0.toFloat() * k
        drawExample(e, sx, sy, k, INK, 2f)
        drawCircle(DIRECTION, 3.5f, Offset(sx, sy))
    }
}

/** Splitting an element: which of the writings go to the new one, and its name. */
@Composable
private fun SplitDialog(g: Glyph, onDismiss: () -> Unit, onSplit: (Set<Int>, String, GlyphCategory) -> Unit) {
    val palette = LocalPalette.current
    var reading by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(g.category) }
    var menu by remember { mutableStateOf(false) }
    val take = remember { mutableStateListOf<Int>() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f).background(palette.bg.toComposeColor()).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.glyph_split), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(XnotesIcons.close, stringResource(R.string.cancel), tint = palette.text.toComposeColor()) }
            }
            Text(stringResource(R.string.glyph_split_hint), color = palette.textDim.toComposeColor(), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(reading, { reading = it }, label = { Text(stringResource(R.string.glyph_reading)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Box {
                TextButton(onClick = { menu = true }) { Text(stringResource(R.string.glyph_category) + ": " + glyphCategoryLabel(category)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    GlyphCategory.entries.forEach { c -> DropdownMenuItem(text = { Text(glyphCategoryLabel(c)) }, onClick = { category = c; menu = false }) }
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(g.examples.indices.toList()) { i ->
                    val on = i in take
                    Box(
                        Modifier.border(if (on) 3.dp else 1.dp, if (on) palette.accent.toComposeColor() else palette.border.toComposeColor())
                            .clickable { if (on) take.remove(i) else take.add(i) },
                    ) {
                        ExampleTile(g.examples[i], Modifier.fillMaxWidth().aspectRatio(1f))
                        Checkbox(on, null, modifier = Modifier.align(Alignment.TopEnd))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { onSplit(take.toSet(), reading, category) }, enabled = reading.isNotBlank()) {
                    Text(stringResource(R.string.glyph_split_do), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** A stroke written on the test pad, and what the correction made of it. */
private class PadStroke(val x: DoubleArray, val y: DoubleArray) {
    var fix: StenoFix? = null
    var lines: List<String> = emptyList()
}

/**
 * The catalogue's test pad: 速記 paper on which writing goes through exactly what a note does at
 * pen-up — the clean-up with the pen's own settings, then recognition against the reviewed
 * elements — and nothing is saved. Under it, for the last stroke: what was fixed live, and the
 * recognizer's best reading of every stretch whether sure or not, with its nearest rivals, so a
 * miss shows why it missed.
 */
@Composable
private fun GlyphTestPad(params: HandCleanup.Params, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val strokes = remember { mutableStateListOf<PadStroke>() }
    val live = remember { mutableStateListOf<Offset>() }
    var version by remember { mutableStateOf(0) }
    var computed by remember { mutableStateOf(0) }
    var catalogue by remember { mutableStateOf<StenoCorrection.Catalogue?>(null) }
    val glyphs = GlyphLibrary.items.toList()
    LaunchedEffect(glyphs) {
        catalogue = withContext(Dispatchers.Default) {
            glyphs.filter { it.reviewed }.takeIf { it.isNotEmpty() }?.let { StenoCorrection.Catalogue(it) }
        }
    }
    // The pad's ruling, set while drawing: one band, as on a note.
    val frameHolder = remember { arrayOfNulls<LineFrame>(1) }
    val sure = stringResource(R.string.glyph_test_sure)
    val unsure = stringResource(R.string.glyph_test_unsure)
    val liveTitle = stringResource(R.string.glyph_test_live)
    val guessTitle = stringResource(R.string.glyph_test_guess)
    val none = stringResource(R.string.glyph_test_none)

    // Each new stroke is corrected off the main thread, as at pen-up in a note.
    LaunchedEffect(version, catalogue) {
        val frame = frameHolder[0] ?: return@LaunchedEffect
        val cat = catalogue
        for (s in strokes.toList()) {
            if (s.fix != null) continue
            val (fix, lines) = withContext(Dispatchers.Default) { testStroke(s.x, s.y, frame, params, cat, sure, unsure, liveTitle, guessTitle, none) }
            s.fix = fix
            s.lines = lines
            computed++
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(palette.bg.toComposeColor()).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(XnotesIcons.close, stringResource(R.string.cancel), tint = palette.text.toComposeColor()) }
                Text(stringResource(R.string.glyph_test_title), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.size - 1); version++ } }, enabled = strokes.isNotEmpty()) {
                    Icon(XnotesIcons.undo, stringResource(R.string.studio_undo), tint = palette.text.toComposeColor())
                }
                TextButton(onClick = { strokes.clear(); version++ }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.clear)) }
            }
            Text(
                stringResource(R.string.glyph_test_hint) + " " + stringResource(R.string.glyph_test_unreviewed),
                color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            // Recognized elements stand out from the yellow ink in orange.
            val accent = Color(0xFFFF8C00)
            val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true } }
            Box(Modifier.weight(1.3f).fillMaxWidth().border(1.dp, palette.border.toComposeColor()).background(PAPER_BG)) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            live.clear()
                            live.add(down.position)
                            down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                for (h in c.historical) live.add(h.position)
                                live.add(c.position)
                                c.consume()
                                if (!c.pressed) break
                            }
                            if (live.size >= 6) {
                                strokes += PadStroke(DoubleArray(live.size) { live[it].x.toDouble() }, DoubleArray(live.size) { live[it].y.toDouble() })
                                version++
                            }
                            live.clear()
                        }
                    },
                ) {
                    if (version < 0 || computed < 0) return@Canvas
                    // Two rows of 速記 paper: the band fits the pad's height a little over twice.
                    val band = size.height / 2.3f
                    val frame = LineFrame(band.toDouble())
                    frameHolder[0] = frame
                    val unit = frame.unit.toFloat()
                    drawWritingLines(band, unit, size.width)
                    labelPaint.textSize = 0.55f * unit
                    labelPaint.color = accent.toArgb()
                    for (s in strokes) {
                        drawPoly(s.x, s.y, null, 0, s.x.size - 1, PAPER_DIM, 2f)
                        val fix = s.fix ?: continue
                        drawPoly(s.x, s.y, fix, 0, s.x.size - 1, INK, 3f)
                        for (seg in fix.segments) if (seg.kind == FixKind.GLYPH) {
                            drawPoly(s.x, s.y, fix, seg.from, seg.to, accent, 4.5f)
                            val i = (seg.from + seg.to) / 2
                            drawContext.canvas.nativeCanvas.drawText(
                                seg.reading ?: "?", (s.x[i] + fix.dx[i]).toFloat(), (s.y[i] + fix.dy[i]).toFloat() - 0.45f * unit, labelPaint,
                            )
                        }
                    }
                    if (live.size > 1) {
                        val p = Path()
                        p.moveTo(live[0].x, live[0].y)
                        for (q in live) p.lineTo(q.x, q.y)
                        drawPath(p, INK, style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                val last = strokes.lastOrNull()
                if (last != null && computed >= 0) for (l in last.lines) {
                    val title = l == liveTitle || l == guessTitle
                    Text(
                        l, color = if (title) palette.text.toComposeColor() else palette.textDim.toComposeColor(),
                        fontWeight = if (title) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp,
                        modifier = Modifier.padding(top = if (title) 6.dp else 0.dp),
                    )
                }
            }
        }
    }
}

/** Samples [a]..[b] of a pad stroke as a line, where [fix] puts them (or as written). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPoly(x: DoubleArray, y: DoubleArray, fix: StenoFix?, a: Int, b: Int, color: Color, width: Float) {
    if (b <= a) return
    val p = Path()
    for (i in a..b) {
        val px = (x[i] + (fix?.dx?.getOrNull(i) ?: 0f)).toFloat()
        val py = (y[i] + (fix?.dy?.getOrNull(i) ?: 0f)).toFloat()
        if (i == a) p.moveTo(px, py) else p.lineTo(px, py)
    }
    drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * One pad stroke through the pen-up correction, and the lines that report it: the stretches fixed
 * live, then the recognizer's reading with nothing held back — each stretch's best element, its
 * error and margin against the very-sure thresholds, and the next nearest elements.
 */
private fun testStroke(
    x: DoubleArray, y: DoubleArray, frame: LineFrame, params: HandCleanup.Params, cat: StenoCorrection.Catalogue?,
    sure: String, unsure: String, liveTitle: String, guessTitle: String, none: String,
): Pair<StenoFix, List<String>> {
    com.xnotes.core.steno.HandSlant.add(x, y, frame.unit)
    com.xnotes.core.steno.HandSize.add(DoubleArray(y.size) { frame.unitsAbove(y[it], frame.baseFor(y.sorted()[y.size / 2])) })
    val fix = StenoCorrection.correct(x, y, frame, params, cat)
    val lines = ArrayList<String>()
    val habit = com.xnotes.core.steno.HandSlant.estimate()
    val own = HandCleanup.slantOf(x, y, frame.unit)
    lines += "∠ %s · %s → %.0f°".format(
        own?.let { "%.0f°".format(HandCleanup.degreesOf(it)) } ?: "–",
        habit?.let { "%.0f°".format(HandCleanup.degreesOf(it)) } ?: "–",
        HandCleanup.BOOK_SLANT_DEGREES,
    )
    lines += liveTitle
    val fixed = fix.segments.filter { it.kind == FixKind.GLYPH }
    if (fixed.isEmpty()) lines += none else for (s in fixed) lines += "${s.reading} · ${s.from}–${s.to}" + if (s.raise != 0.0) " · ↑${s.raise}" else ""
    lines += guessTitle
    if (cat == null || cat.recognizer.isEmpty) { lines += none; return fix to lines }
    // The same reading of the hand the correction makes: at the book's slant, unsmoothed, in units.
    val n = x.size
    val (sx, sy, _) = HandCleanup.toBook(x, y, frame)
    val base = frame.baseFor(sy.sorted()[n / 2])
    val (h0, h1) = com.xnotes.core.steno.HandSize.estimate() ?: (0.0 to 1.0)
    lines += "⇕ %.2f – %.2f → 0 – 1".format(h0, h1)
    val ux = DoubleArray(n) { (sx[it] - sx[0]) / frame.unit / (h1 - h0) }
    val uy = DoubleArray(n) { (frame.unitsAbove(sy[it], base) - h0) / (h1 - h0) }
    val matches = cat.recognizer.recognize(ux, uy, sureOnly = false)
    if (matches.isEmpty()) lines += none
    fun name(id: String) = cat.byId[id]?.reading ?: id
    for (m in matches) {
        val ok = m.error <= Recognizer.SURE_ERROR && m.margin >= Recognizer.SURE_MARGIN
        lines += "%s · %d–%d · err %.3f (≤ %.3f) · margin %.2f (≥ %.2f) · ×%.2f ↔%.2f · %s".format(
            name(m.id), m.from, m.to, m.error, Recognizer.SURE_ERROR, m.margin, Recognizer.SURE_MARGIN, m.scale, m.widen, if (ok) sure else unsure,
        )
        val rivals = cat.recognizer.rank(ux, uy, m.from, m.to, 4).drop(1)
        if (rivals.isNotEmpty()) lines += "    " + rivals.joinToString("  ") { "%s %.3f".format(name(it.id), it.error) }
    }
    return fix to lines
}
