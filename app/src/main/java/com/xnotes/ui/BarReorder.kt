package com.xnotes.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Moving the bar's items about on the bar itself (fork): a long press still names the item, and
 * dragging on from there carries it along the bar to where it is let go. It lands beside the item
 * it is dropped on, in that item's section, and the new order is kept as the customiser keeps it.
 */
internal class BarReorder(
    private val layout: () -> ToolbarLayout,
    private val onMove: (ToolbarLayout) -> Unit,
    private val vertical: () -> Boolean,
) {
    /** Where each item shown on the bar lies, in root coordinates. */
    val bounds = HashMap<ToolbarItem, Rect>()

    /** The item being carried, and how far it has been carried from where it was picked up. */
    var dragging by mutableStateOf<ToolbarItem?>(null)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The item it would land beside, and on which side ([after] = its trailing one). */
    var target by mutableStateOf<ToolbarItem?>(null)
        private set
    var after by mutableStateOf(false)
        private set

    fun start(item: ToolbarItem) {
        dragging = item
        offset = Offset.Zero
        target = null
    }

    fun move(by: Offset, pointer: Offset) {
        offset = by
        val item = dragging ?: return
        fun along(o: Offset) = if (vertical()) o.y else o.x
        fun mid(r: Rect) = if (vertical()) r.center.y else r.center.x
        val at = along(pointer)
        val best = bounds.entries
            .filter { it.key != item && it.value.width > 0f && it.value.height > 0f }
            .minByOrNull { kotlin.math.abs(mid(it.value) - at) }
        target = best?.key
        after = best != null && at > mid(best.value)
    }

    fun end(drop: Boolean) {
        val item = dragging
        val onto = target
        dragging = null
        offset = Offset.Zero
        target = null
        if (!drop || item == null || onto == null) return
        val l = layout()
        fun find(i: ToolbarItem): Pair<Int, Int>? {
            l.sections.forEachIndexed { si, s -> s.entries.indexOfFirst { it.item == i }.takeIf { it >= 0 }?.let { return si to it } }
            return null
        }
        val (fs, fi) = find(item) ?: return
        val (ts, ti) = find(onto) ?: return
        val next = l.moveItem(fs, fi, ts, ti + if (after) 1 else 0)
        if (next != l) onMove(next)
    }
}

internal val LocalBarReorder = staticCompositionLocalOf<BarReorder?> { null }

@Composable
internal fun rememberBarReorder(layout: ToolbarLayout, onMove: (ToolbarLayout) -> Unit): BarReorder {
    val current by rememberUpdatedState(layout)
    val move by rememberUpdatedState(onMove)
    val bar = LocalBar.current
    val vertical by rememberUpdatedState(bar.vertical)
    return remember { BarReorder({ current }, { move(it) }, { vertical }) }
}

/**
 * One item of the bar, movable by a long press and a drag. Everything else — the tap, the long
 * press's hint, a scroll along the bar — reaches the item and the bar exactly as before: the drag
 * only takes over once the press has been held and the finger then moves.
 */
@Composable
internal fun ReorderSlot(item: ToolbarItem, content: @Composable () -> Unit) {
    val reorder = LocalBarReorder.current
    if (reorder == null) { content(); return }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(item) { onDispose { reorder.bounds.remove(item) } }
    val carried = reorder.dragging == item
    val marked = reorder.target == item && reorder.dragging != null
    val accent = LocalPalette.current.accent.toComposeColor()
    val vertical = LocalBar.current.vertical
    Box(
        Modifier
            .onGloballyPositioned { c -> coords = c; reorder.bounds[item] = c.boundsInRoot() }
            .pointerInput(item) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val slop = viewConfiguration.touchSlop
                    // Held still for a long press, or it is a tap or a scroll: leave it be.
                    val heldStill = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            if (c == null || !c.pressed || (c.position - down.position).getDistance() > slop) break
                        }
                    } == null
                    if (!heldStill) return@awaitEachGesture
                    var dragging = false
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id }
                        if (c == null || !c.pressed) {
                            if (dragging) { c?.consume(); reorder.end(drop = c != null) }
                            return@awaitEachGesture
                        }
                        if (!dragging && (c.position - down.position).getDistance() > slop) {
                            dragging = true
                            reorder.start(item)
                        }
                        if (dragging) {
                            val root = coords?.localToRoot(c.position) ?: c.position
                            reorder.move(c.position - down.position, root)
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .zIndex(if (carried) 1f else 0f)
            .graphicsLayer {
                if (carried) {
                    translationX = reorder.offset.x
                    translationY = reorder.offset.y
                    scaleX = 1.12f
                    scaleY = 1.12f
                    alpha = 0.85f
                }
            }
            .drawWithContent {
                drawContent()
                if (!marked) return@drawWithContent
                // Where it would land: a bar on the side of this item it would land on.
                val w = 3.dp.toPx()
                val edge = if (reorder.after) (if (vertical) size.height else size.width) - w / 2 else w / 2
                if (vertical) drawLine(accent, Offset(0f, edge), Offset(size.width, edge), w)
                else drawLine(accent, Offset(edge, 0f), Offset(edge, size.height), w)
            },
    ) { content() }
}
