package com.xnotes.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import com.xnotes.R
import com.xnotes.core.steno.glyph.Glyph
import com.xnotes.core.steno.glyph.GlyphCategory
import com.xnotes.core.steno.glyph.GlyphShape
import com.xnotes.core.steno.glyph.ShapeEdit
import com.xnotes.core.steno.glyph.GlyphSource
import com.xnotes.core.steno.trace.Gray
import com.xnotes.core.steno.trace.TraceFit
import com.xnotes.core.steno.trace.TraceFrame
import com.xnotes.platform.GlyphLibrary
import com.xnotes.platform.PdfSource
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The surface of the 速記 studio (fork): a scan under pan and zoom, the two writing lines laid over
 * it with handles to drag them onto the print's own lines, and the pen's traces.
 *
 * The stylus traces; one finger pans (or traces, when [fingerDraws]); two fingers pan and zoom. A
 * touch on a handle at the right edge drags its line — red the base line, green one step above —
 * whatever is touching. All geometry is kept in the scan's pixels, so zooming changes nothing.
 */
internal class StudioView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            val first = field == null || value == null || field?.width != value.width || field?.height != value.height
            field = value
            if (first) fitPending = true
            invalidate()
        }

    /** The base line's y and one step, in the scan's pixels; -1 until placed. */
    var baseY = -1f
    var stepPx = -1f

    val strokes = ArrayList<ArrayList<FloatArray>>()
    var fitted: TraceFit.Fitted? = null
        set(value) {
            field = value
            invalidate()
        }
    var fingerDraws = false
    var onStrokesChanged: (() -> Unit)? = null
    var onLinesChanged: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitPending = false

    private enum class Mode { NONE, DRAW, PAN, PINCH, BASE, STEP }
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var live: ArrayList<FloatArray>? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xC0FF00FF.toInt()
    }
    private val fitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFFFF2020.toInt()
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14 * dp; color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    private fun toScreenY(y: Float) = y * scale + ty
    private fun toBitmapX(x: Float) = (x - tx) / scale
    private fun toBitmapY(y: Float) = (y - ty) / scale

    private fun fitToView() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        tx = (width - b.width * scale) / 2
        ty = (height - b.height * scale) / 2
        if (stepPx <= 0) {
            stepPx = 110 * dp / scale
            baseY = toBitmapY(height * 0.6f)
            onLinesChanged?.invoke()
        }
        fitPending = false
    }

    /** Lines placed afresh in the middle of the view (a new scan or page). */
    fun resetLines() {
        stepPx = -1f
        fitPending = true
        invalidate()
    }

    private val handleX get() = width - 26 * dp
    private fun handleHit(x: Float, y: Float): Mode? {
        if (stepPx <= 0) return null
        val r = 30 * dp
        if (hypot(x - handleX, y - toScreenY(baseY)) < r) return Mode.BASE
        if (hypot(x - handleX, y - toScreenY(baseY - stepPx)) < r) return Mode.STEP
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        val stylus = e.getToolType(e.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x
                lastY = e.y
                val h = handleHit(e.x, e.y)
                mode = when {
                    h != null -> h
                    stylus || fingerDraws -> Mode.DRAW
                    else -> Mode.PAN
                }
                if (mode == Mode.DRAW) {
                    live = arrayListOf(floatArrayOf(toBitmapX(e.x), toBitmapY(e.y), e.pressure))
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAW || mode == Mode.PAN || mode == Mode.NONE) {
                    // A second finger: the stroke just begun was the start of a pinch, not ink.
                    live = null
                    mode = Mode.PINCH
                    lastSpan = span(e)
                    lastX = focusX(e)
                    lastY = focusY(e)
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> {
                    val s = live ?: return true
                    for (h in 0 until e.historySize) s += floatArrayOf(toBitmapX(e.getHistoricalX(h)), toBitmapY(e.getHistoricalY(h)), e.getHistoricalPressure(h))
                    s += floatArrayOf(toBitmapX(e.x), toBitmapY(e.y), e.pressure)
                }
                Mode.PAN -> {
                    tx += e.x - lastX
                    ty += e.y - lastY
                    lastX = e.x
                    lastY = e.y
                }
                Mode.PINCH -> if (e.pointerCount >= 2) {
                    val sp = span(e)
                    val fx = focusX(e)
                    val fy = focusY(e)
                    if (lastSpan > 0 && sp > 0) {
                        val k = (sp / lastSpan).coerceIn(0.5f, 2f)
                        val ns = (scale * k).coerceIn(0.05f, 40f)
                        val kk = ns / scale
                        tx = fx - (lastX - tx) * kk
                        ty = fy - (lastY - ty) * kk
                        scale = ns
                    }
                    lastSpan = sp
                    lastX = fx
                    lastY = fy
                }
                Mode.BASE -> {
                    baseY = toBitmapY(e.y)
                    onLinesChanged?.invoke()
                }
                Mode.STEP -> {
                    stepPx = max(4f / scale, baseY - toBitmapY(e.y))
                    onLinesChanged?.invoke()
                }
                Mode.NONE -> Unit
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAW) {
                    live?.let { s ->
                        s += floatArrayOf(toBitmapX(e.x), toBitmapY(e.y), e.pressure)
                        if (s.size > 3) {
                            strokes += s
                            onStrokesChanged?.invoke()
                        }
                    }
                }
                live = null
                mode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        invalidate()
        return true
    }

    private fun span(e: MotionEvent) = if (e.pointerCount < 2) 0f else hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun focusX(e: MotionEvent) = if (e.pointerCount < 2) e.x else (e.getX(0) + e.getX(1)) / 2
    private fun focusY(e: MotionEvent) = if (e.pointerCount < 2) e.y else (e.getY(0) + e.getY(1)) / 2

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (fitPending) fitToView()
        canvas.drawColor(0xFF202020.toInt())
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        canvas.drawBitmap(b, 0f, 0f, bmpPaint)
        strokePaint.strokeWidth = 4 * dp / scale
        for (s in strokes) drawPoly(canvas, s, strokePaint)
        live?.let { drawPoly(canvas, it, strokePaint) }
        fitted?.let { f ->
            fitPaint.strokeWidth = 2.2f * dp / scale
            for (p in f.shape.paths) {
                val path = Path()
                for (i in 0 until p.size) {
                    val x = (f.leftPx + p.x[i] * stepPx).toFloat()
                    val y = (baseY - p.y[i] * stepPx).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, fitPaint)
            }
        }
        canvas.restore()
        if (stepPx > 0) {
            fun line(units: Float, color: Int, w: Float) {
                linePaint.color = color
                linePaint.strokeWidth = w * dp
                val y = toScreenY(baseY - units * stepPx)
                canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            }
            line(2.5f, 0x904060FF.toInt(), 1f)
            line(0.5f, 0x90FF9020.toInt(), 1f)
            line(1f, 0xD000B000.toInt(), 1.5f)
            line(0f, 0xD0FF2020.toInt(), 1.5f)
            fun handle(units: Float, color: Int, label: String) {
                val y = toScreenY(baseY - units * stepPx)
                handlePaint.color = color
                canvas.drawCircle(handleX, y, 16 * dp, handlePaint)
                canvas.drawText(label, handleX, y + 5 * dp, labelPaint)
            }
            handle(0f, 0xFFE02020.toInt(), "0")
            handle(1f, 0xFF00A000.toInt(), "1")
        }
    }

    private fun drawPoly(canvas: Canvas, s: List<FloatArray>, paint: Paint) {
        if (s.size < 2) return
        val path = Path()
        path.moveTo(s[0][0], s[0][1])
        for (i in 1 until s.size) path.lineTo(s[i][0], s[i][1])
        canvas.drawPath(path, paint)
    }
}

/** Where the studio's scan came from, kept so the next visit reopens it. */
private object StudioMemory {
    private const val PREFS = "glyph_studio"
    fun save(ctx: Context, uri: Uri, pdf: Boolean, page: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("uri", uri.toString()).putBoolean("pdf", pdf).putInt("page", page).apply()
    }
    fun load(ctx: Context): Triple<Uri, Boolean, Int>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val u = p.getString("uri", null) ?: return null
        return Triple(Uri.parse(u), p.getBoolean("pdf", false), p.getInt("page", 0))
    }
}

/** The scan's pixels around the traces, as a [Gray], with the region's origin. */
private fun grayRegion(b: Bitmap, x0: Int, y0: Int, w: Int, h: Int): Gray {
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, x0, y0, w, h)
    return Gray(w, h, FloatArray(w * h) { val c = px[it]; ((c shr 16 and 255) * 0.299f + (c shr 8 and 255) * 0.587f + (c and 255) * 0.114f) })
}

/** The fit of [strokes] over [b] with the lines at [base]/[step], or null. Not on the main thread. */
private fun fitStrokes(b: Bitmap, base: Float, step: Float, strokes: List<List<FloatArray>>): TraceFit.Fitted? {
    if (strokes.isEmpty() || step <= 0) return null
    val pad = 0.6f * step
    val xs = strokes.flatMap { s -> s.map { it[0] } }
    val ys = strokes.flatMap { s -> s.map { it[1] } }
    val x0 = (xs.min() - pad).toInt().coerceIn(0, b.width - 1)
    val y0 = (ys.min() - pad).toInt().coerceIn(0, b.height - 1)
    val x1 = (xs.max() + pad).toInt().coerceIn(x0 + 1, b.width)
    val y1 = (ys.max() + pad).toInt().coerceIn(y0 + 1, b.height)
    val g = grayRegion(b, x0, y0, x1 - x0, y1 - y0)
    val traces = strokes.map { s -> s.map { doubleArrayOf((it[0] - x0).toDouble(), (it[1] - y0).toDouble()) } }
    val f = TraceFit.fitPlaced(g, TraceFrame((base - y0).toDouble(), step.toDouble()), traces) ?: return null
    return TraceFit.Fitted(f.shape, f.leftPx + x0)
}

@Composable
internal fun glyphCategoryLabel(c: GlyphCategory): String = stringResource(
    when (c) {
        GlyphCategory.CONSONANT -> R.string.glyph_cat_consonant
        GlyphCategory.HAIRLINE -> R.string.glyph_cat_hairline
        GlyphCategory.CLUSTER -> R.string.glyph_cat_cluster
        GlyphCategory.SYMBOL -> R.string.glyph_cat_symbol
        GlyphCategory.PREFIX -> R.string.glyph_cat_prefix
        GlyphCategory.ENDING -> R.string.glyph_cat_ending
        GlyphCategory.MARK -> R.string.glyph_cat_mark
        GlyphCategory.OWN -> R.string.glyph_cat_own
    },
)

/**
 * The 速記 studio (fork): open a textbook scan (image or PDF page), drag the two lines onto the
 * print's own lines, trace one element with the pen, and see at once what the pipeline makes of
 * it — red over the scan, clean on the lines below. Save files it in the catalogue.
 *
 * [editing] re-traces an existing element: the studio opens on the crop it was traced over, with
 * its lines, and saving replaces its form while keeping its name.
 */
@Composable
internal fun GlyphStudio(editing: Glyph?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var label by remember { mutableStateOf(editing?.source?.label ?: "") }
    var pdf by remember { mutableStateOf<PdfSource?>(null) }
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var fitted by remember { mutableStateOf<TraceFit.Fitted?>(null) }
    // One-tap fixes to the fit: the fixed form, the fixes to undo, the segment picked. A new fit
    // (another stroke, a line moved) starts them afresh.
    var fixedShape by remember { mutableStateOf<GlyphShape?>(null) }
    val fixHistory = remember { mutableStateListOf<GlyphShape>() }
    var seg by remember { mutableStateOf<ShapeEdit.Seg?>(null) }
    LaunchedEffect(fitted) { fixedShape = null; fixHistory.clear(); seg = null }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var strokesVersion by remember { mutableIntStateOf(0) }
    var linesVersion by remember { mutableIntStateOf(0) }
    var fingerDraws by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val view = remember { mutableStateOf<StudioView?>(null) }

    fun renderPage(src: PdfSource, index: Int) {
        scope.launch {
            busy = true
            val b = withContext(Dispatchers.IO) {
                val (wPt, hPt) = src.pageSizePoints(index) ?: return@withContext null
                val k = min(2600.0 / wPt, 4000.0 / hPt)
                src.renderPage(index, (wPt * k).toInt(), (hPt * k).toInt())?.bitmap
            }
            busy = false
            if (b != null) {
                bitmap = b
                view.value?.let { it.strokes.clear(); it.resetLines() }
                fitted = null
                strokesVersion++
            }
        }
    }

    fun openUri(uri: Uri, isPdf: Boolean, startPage: Int) {
        scope.launch {
            busy = true
            label = runCatching { DocumentFile.fromSingleUri(ctx, uri)?.name }.getOrNull() ?: uri.lastPathSegment.orEmpty()
            if (isPdf) {
                val src = withContext(Dispatchers.IO) {
                    runCatching {
                        val f = File(ctx.cacheDir, "glyph-studio.pdf")
                        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                        PdfSource.create(f)
                    }.getOrNull()
                }
                busy = false
                if (src == null) return@launch
                pdf?.close()
                pdf = src
                pdfUri = uri
                pageCount = src.pageCount
                page = startPage.coerceIn(0, pageCount - 1)
                renderPage(src, page)
            } else {
                val b = withContext(Dispatchers.IO) {
                    runCatching {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
                        var sample = 1
                        while (max(o.outWidth, o.outHeight) / sample > 4096) sample *= 2
                        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                    }.getOrNull()
                }
                busy = false
                pdf?.close()
                pdf = null
                pdfUri = null
                pageCount = 0
                if (b != null) {
                    bitmap = b
                    view.value?.let { it.strokes.clear(); it.resetLines() }
                    fitted = null
                    strokesVersion++
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val isPdf = ctx.contentResolver.getType(uri) == "application/pdf" || uri.toString().endsWith(".pdf", true)
        StudioMemory.save(ctx, uri, isPdf, 0)
        openUri(uri, isPdf, 0)
    }

    // First open: a re-trace starts on the element's own crop; otherwise the last scan comes back.
    LaunchedEffect(Unit) {
        if (editing != null) {
            val crop = withContext(Dispatchers.IO) { GlyphLibrary.crop(editing.id) }
            val src = editing.source
            if (crop != null && src != null) {
                bitmap = crop
                view.value?.let { v ->
                    v.baseY = src.cropBase.toFloat()
                    v.stepPx = src.cropStep.toFloat()
                }
                linesVersion++
                return@LaunchedEffect
            }
        }
        StudioMemory.load(ctx)?.let { (uri, isPdf, p) -> openUri(uri, isPdf, p) }
    }

    DisposableEffect(Unit) { onDispose { pdf?.close() } }

    // Refit after every stroke and every line move, a moment after the hand rests.
    LaunchedEffect(strokesVersion, linesVersion) {
        val v = view.value ?: return@LaunchedEffect
        val b = bitmap ?: return@LaunchedEffect
        if (v.strokes.isEmpty()) {
            fitted = null
            v.fitted = null
            failed = false
            return@LaunchedEffect
        }
        delay(120)
        busy = true
        val strokes = v.strokes.map { ArrayList(it) }
        val f = withContext(Dispatchers.Default) { runCatching { fitStrokes(b, v.baseY, v.stepPx, strokes) }.getOrNull() }
        busy = false
        fitted = f
        v.fitted = f
        failed = f == null
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(palette.bg.toComposeColor())) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(XnotesIcons.close, stringResource(R.string.cancel), tint = palette.text.toComposeColor()) }
                Text(
                    stringResource(if (editing != null) R.string.studio_retrace_title else R.string.studio_title),
                    color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                )
                Spacer(Modifier.width(8.dp))
                if (editing == null) {
                    TextButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }) { Text(stringResource(R.string.studio_open)) }
                }
                if (pdf != null && pageCount > 0) {
                    IconButton(onClick = { if (page > 0) { page--; pdfUri?.let { StudioMemory.save(ctx, it, true, page) }; renderPage(pdf!!, page) } }) {
                        Icon(XnotesIcons.prev, null, tint = palette.text.toComposeColor())
                    }
                    Text(stringResource(R.string.studio_page, page + 1, pageCount), color = palette.text.toComposeColor())
                    IconButton(onClick = { if (page < pageCount - 1) { page++; pdfUri?.let { StudioMemory.save(ctx, it, true, page) }; renderPage(pdf!!, page) } }) {
                        Icon(XnotesIcons.next, null, tint = palette.text.toComposeColor())
                    }
                }
                IconButton(onClick = { view.value?.let { if (it.strokes.isNotEmpty()) { it.strokes.removeAt(it.strokes.size - 1); strokesVersion++; it.invalidate() } } }) {
                    Icon(XnotesIcons.undo, stringResource(R.string.studio_undo), tint = palette.text.toComposeColor())
                }
                TextButton(onClick = { view.value?.let { it.strokes.clear(); strokesVersion++; it.invalidate() } }) { Text(stringResource(R.string.clear)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fingerDraws, onCheckedChange = { fingerDraws = it; view.value?.fingerDraws = it })
                    Text(stringResource(R.string.studio_finger), color = palette.text.toComposeColor(), fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { saving = true }, enabled = fitted != null && !busy) {
                    Text(stringResource(R.string.studio_save), fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { c ->
                        StudioView(c).also { v ->
                            view.value = v
                            v.onStrokesChanged = { strokesVersion++ }
                            v.onLinesChanged = { linesVersion++ }
                            editing?.source?.let { src -> v.baseY = src.cropBase.toFloat(); v.stepPx = src.cropStep.toFloat() }
                        }
                    },
                    update = { v -> v.bitmap = bitmap; v.fingerDraws = fingerDraws },
                    modifier = Modifier.fillMaxSize(),
                )
                if (bitmap == null) {
                    Text(
                        stringResource(R.string.studio_hint_open), color = palette.textDim.toComposeColor(),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
            // The result, clean on the lines, and what to do next.
            Row(Modifier.fillMaxWidth().height(170.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(220.dp).fillMaxHeight().border(1.dp, palette.border.toComposeColor()).background(PAPER_BG),
                ) {
                    val fit = fitted?.shape
                    val shape = fixedShape ?: fit
                    val layout = remember { FloatArray(3) }
                    val shapeNow by rememberUpdatedState(shape)
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTapGestures { p ->
                                val s = shapeNow ?: return@detectTapGestures
                                if (layout[0] <= 0f) return@detectTapGestures
                                seg = ShapeEdit.hit(s, ((p.x - layout[1]) / layout[0]).toDouble(), ((layout[2] - p.y) / layout[0]).toDouble())
                            }
                        },
                    ) {
                        val unit = size.height / 4.2f
                        val base = size.height - 0.9f * unit
                        drawWritingLines(base, unit, size.width)
                        if (shape != null && fit != null) {
                            val w = (fit.maxX - fit.minX).toFloat() * unit
                            val ox = (size.width - w) / 2 - fit.minX.toFloat() * unit
                            layout[0] = unit; layout[1] = ox; layout[2] = base
                            drawGlyph(shape, ox, base, unit, PAPER_INK)
                            seg?.let { drawSegment(shape, it, ox, base, unit, Color(0xFFE000A0), 0.08f * unit) }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                Text(
                    when {
                        busy -> stringResource(R.string.studio_fitting)
                        failed -> stringResource(R.string.studio_failed)
                        bitmap == null -> ""
                        fitted == null -> stringResource(R.string.studio_hint_lines) + "\n\n" + stringResource(R.string.studio_hint_trace)
                        else -> stringResource(R.string.studio_hint_check)
                    },
                    color = palette.text.toComposeColor(), fontSize = 14.sp,
                )
                val shape = fixedShape ?: fitted?.shape
                if (shape != null && !busy) {
                    SegmentFixRow(
                        shape, seg, fixHistory.isNotEmpty(),
                        onFix = { f -> seg?.let { sg -> fixHistory += shape; fixedShape = ShapeEdit.apply(shape, sg, f) } },
                        onUndo = { if (fixHistory.isNotEmpty()) fixedShape = fixHistory.removeAt(fixHistory.size - 1) },
                    )
                }
                }
            }
        }
        if (saving) {
            SaveGlyphDialog(
                initialReading = editing?.reading ?: "",
                initialCategory = editing?.category ?: GlyphCategory.CONSONANT,
                onDismiss = { saving = false },
                onSave = { reading, category ->
                    saving = false
                    val v = view.value ?: return@SaveGlyphDialog
                    val f = fitted ?: return@SaveGlyphDialog
                    val form = fixedShape ?: f.shape
                    val b = bitmap ?: return@SaveGlyphDialog
                    val step = v.stepPx
                    val xs = v.strokes.flatMap { s -> s.map { it[0] } } + listOf(f.leftPx.toFloat(), (f.leftPx + f.shape.maxX * step).toFloat())
                    val cx0 = (xs.min() - 0.6f * step).toInt().coerceIn(0, b.width - 1)
                    val cx1 = (xs.max() + 0.6f * step).toInt().coerceIn(cx0 + 1, b.width)
                    val cy0 = (v.baseY - (f.shape.maxY.toFloat() + 0.6f) * step).toInt().coerceIn(0, b.height - 1)
                    val cy1 = (v.baseY - (f.shape.minY.toFloat() - 0.6f) * step).toInt().coerceIn(cy0 + 1, b.height)
                    val crop = Bitmap.createBitmap(b, cx0, cy0, cx1 - cx0, cy1 - cy0)
                    val glyph = Glyph(
                        id = editing?.id ?: GlyphLibrary.newId(),
                        reading = reading.trim(),
                        category = category,
                        shape = form,
                        source = GlyphSource(label, if (pdf != null) page + 1 else editing?.source?.page, (v.baseY - cy0).toDouble(), step.toDouble(), f.leftPx - cx0),
                        reviewed = true,
                        note = editing?.note ?: "",
                        created = editing?.created ?: 0L,
                    )
                    scope.launch {
                        withContext(Dispatchers.IO) { GlyphLibrary.save(glyph, crop) }
                        if (editing != null) onClose() else {
                            v.strokes.clear()
                            strokesVersion++
                            v.invalidate()
                        }
                    }
                },
            )
        }
    }
}

/** Name and file an element. */
@Composable
internal fun SaveGlyphDialog(
    initialReading: String,
    initialCategory: GlyphCategory,
    onDismiss: () -> Unit,
    onSave: (String, GlyphCategory) -> Unit,
) {
    var reading by remember { mutableStateOf(initialReading) }
    var category by remember { mutableStateOf(initialCategory) }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.studio_save)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(reading, { reading = it }, label = { Text(stringResource(R.string.glyph_reading)) }, singleLine = true)
                Box {
                    TextButton(onClick = { menu = true }) { Text(stringResource(R.string.glyph_category) + ": " + glyphCategoryLabel(category)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        GlyphCategory.entries.forEach { c ->
                            DropdownMenuItem(text = { Text(glyphCategoryLabel(c)) }, onClick = { category = c; menu = false })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(reading, category) }, enabled = reading.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
