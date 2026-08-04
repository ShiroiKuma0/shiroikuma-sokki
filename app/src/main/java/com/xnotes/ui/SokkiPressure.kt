package com.xnotes.ui

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xnotes.core.model.Rgba
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeEngine
import com.xnotes.core.stroke.StrokeGeometry
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.platform.AndroidRenderer

/**
 * What the calibration pad has seen from the stylus so far.
 *
 * [p5] and [p95] — not [min] and [max] — are what the band should be set from: the extremes are a
 * single sample each, and one accidental jab or one feathered lift would otherwise define the whole
 * response. The 5th and 95th percentiles are the press 白い熊 actually reaches while writing, so
 * clipping the outer 5% at either rail is the point rather than a loss.
 */
data class PressureStats(
    val count: Int = 0,
    val current: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val p5: Double = 0.0,
    val p95: Double = 0.0,
) {
    val hasData get() = count > 0
}

/** Percentile resolution of the pad's histogram: 0.1% of the reported range per bucket. */
private const val BUCKETS = 1000

/**
 * The measurement half of the pressure calibration: a pad that draws with the pen being tuned and
 * reports what the stylus really reported while doing it.
 *
 * It paints through the app's own [AndroidRenderer] and [StrokeEngine], with the very [ToolConfig]
 * the settings rows are editing, so the ink in the pad is the ink the pen lays down — not a
 * lookalike curve. Move a slider and the strokes already in the pad rebuild under it, which is what
 * makes tuning against real handwriting possible instead of guessing and going back to a note.
 *
 * Only stylus samples feed the statistics. A finger reports either a constant 1.0 or a contact-area
 * proxy on most devices, and either would poison the percentiles the band is set from; it can still
 * draw, so a tap on the pad is never dead, but it is not measured.
 */
private class PressurePadView(context: Context) : View(context) {

    var config: ToolConfig = ToolDefaults.configFor(Tool.PEN)
        set(value) {
            field = value
            rebuildAll()
            invalidate()
        }

    var ink: Rgba = Rgba(255, 255, 0, 255)
        set(value) {
            field = value
            invalidate()
        }

    var onStats: ((PressureStats) -> Unit)? = null

    /** Bumped by the caller to wipe the pad; compared rather than called so the Compose side stays
     *  declarative and a recomposition can't clear twice. */
    var clearKey: Int = 0
        set(value) {
            if (field == value) return
            field = value
            clear()
        }

    /** Finished traces, kept as samples so a config change can rebuild their geometry. */
    private val finished = mutableListOf<List<Sample>>()
    private val finishedGeom = mutableListOf<StrokeGeometry>()
    private val live = mutableListOf<Sample>()
    private var liveGeom: StrokeGeometry? = null

    // A fixed histogram rather than the sample list: the percentiles then cost a constant 1000-step
    // sweep per event instead of an O(n log n) sort that grows for as long as the pad is written on.
    private val hist = IntArray(BUCKETS)
    private var count = 0
    private var minP = 1.0
    private var maxP = 0.0
    private var current = 0.0

    fun clear() {
        finished.clear()
        finishedGeom.clear()
        live.clear()
        liveGeom = null
        hist.fill(0)
        count = 0
        minP = 1.0
        maxP = 0.0
        current = 0.0
        report()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The pad lives in a LazyColumn; without this the list claims the drag as a scroll
                // a few pixels in and the stroke dies mid-measurement.
                parent?.requestDisallowInterceptTouchEvent(true)
                live.clear()
                add(e.x, e.y, e.pressure.toDouble(), stylus)
            }

            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    add(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h).toDouble(), stylus)
                }
                add(e.x, e.y, e.pressure.toDouble(), stylus)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                add(e.x, e.y, e.pressure.toDouble(), stylus)
                if (live.size > 1) {
                    finished.add(live.toList())
                    finishedGeom.add(buildGeometry(live))
                }
                live.clear()
                liveGeom = null
                current = 0.0
                parent?.requestDisallowInterceptTouchEvent(false)
            }

            else -> return false
        }
        // Once per event, not once per sample: a 200 Hz pen batches a dozen historical points into
        // one MotionEvent, and rebuilding the ribbon and pushing Compose state for each of them
        // would spend the whole frame on work the next sample immediately throws away.
        if (live.size > 1) liveGeom = buildGeometry(live)
        report()
        invalidate()
        return true
    }

    private fun add(x: Float, y: Float, pressure: Double, stylus: Boolean) {
        val p = if (stylus) pressure.coerceIn(0.0, 1.0) else 1.0
        live.add(Sample(x.toDouble(), y.toDouble(), p))
        if (!stylus) return
        current = p
        if (p < minP) minP = p
        if (p > maxP) maxP = p
        hist[(p * (BUCKETS - 1)).toInt().coerceIn(0, BUCKETS - 1)]++
        count++
    }

    /** The pad's ink, built exactly as a page's is — same engine, same style record. */
    private fun buildGeometry(samples: List<Sample>): StrokeGeometry = StrokeEngine.build(
        samples,
        config.baseWidth,
        config.pressureEnabled,
        config.pressureMinFactor,
        config.directionStrength,
        config.speedStrength,
        config.taperEnabled,
        config.taperMinFactor,
        holdEnds = true,
        finished = true,
        pressureLow = config.pressureLow,
        pressureHigh = config.pressureHigh,
        pressureCurve = config.pressureCurve,
    )

    private fun rebuildAll() {
        finishedGeom.clear()
        finished.forEach { finishedGeom.add(buildGeometry(it)) }
        if (live.size > 1) liveGeom = buildGeometry(live)
    }

    private fun percentile(q: Double): Double {
        if (count == 0) return 0.0
        val target = q * count
        var seen = 0
        for (b in 0 until BUCKETS) {
            seen += hist[b]
            if (seen >= target) return (b + 0.5) / BUCKETS
        }
        return 1.0
    }

    private fun report() {
        onStats?.invoke(
            PressureStats(
                count = count,
                current = current,
                min = if (count == 0) 0.0 else minP,
                max = maxP,
                p5 = percentile(0.05),
                p95 = percentile(0.95),
            ),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val r = AndroidRenderer(canvas)
        finishedGeom.forEach { r.fillDiskRibbon(it.centerline, it.halfWidths, ink) }
        liveGeom?.let { r.fillDiskRibbon(it.centerline, it.halfWidths, ink) }
    }
}

/**
 * The calibration pad, as a Compose node. [config] is pushed into the view on every recomposition,
 * so the ink already drawn re-renders under whatever the sliders now say; [clearKey] wipes the pad
 * when it changes.
 */
@Composable
fun PressurePad(
    config: ToolConfig,
    ink: Rgba,
    clearKey: Int,
    modifier: Modifier = Modifier,
    onStats: (PressureStats) -> Unit,
) {
    AndroidView(
        factory = { ctx -> PressurePadView(ctx) },
        modifier = modifier,
        update = { view ->
            view.onStats = onStats
            view.ink = ink
            view.config = config
            view.clearKey = clearKey
        },
    )
}
