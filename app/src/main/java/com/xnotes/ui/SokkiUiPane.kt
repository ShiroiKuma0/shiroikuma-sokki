package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.automation.AutomationAuth
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConversions
import com.xnotes.settings.ColorSlot
import com.xnotes.settings.SokkiBackup
import com.xnotes.settings.SokkiUi
import com.xnotes.settings.argbHex
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.UiFonts
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

// ---- page metrics ------------------------------------------------------------------------------

/**
 * How far each nesting level is pushed in. Deliberately generous (24dp a step, against the 16dp of
 * a conventional settings list): 白い熊's requirement is that the level a row sits at is readable at
 * a glance, without reading the row.
 */
private fun indent(level: Int) = (16 + level * 24).dp

/** Kōjiki's warn red — an unset backup directory, and any failure. */
val SokkiWarnRed = Rgba(255, 82, 82, 255)

// ---- the page ----------------------------------------------------------------------------------

/**
 * "白い熊 速記 UI" — the appearance page, in the kxkb UI-page format: text-wide underlined headings
 * over deeply indented rows, thin hairlines between top-level sections, and no wasted vertical
 * space inside a section.
 *
 * Everything the house look is built from is settable here, and every control previews itself: the
 * colours land on the live chrome the moment they are applied, and the type/shape/icon rows carry a
 * sample that is drawn with the very values being edited.
 */
@Composable
fun SokkiUiPane(editor: Editor, onImportFont: () -> Unit, onClosePage: () -> Unit = {}) {
    val palette = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ui = editor.sokkiUi
    var pickingColor by remember { mutableStateOf<ColorSlot?>(null) }
    var pickingFont by remember { mutableStateOf(false) }
    var showExim by remember { mutableStateOf(false) }
    var automationOn by remember { mutableStateOf(AutomationAuth.enabled(ctx)) }
    var requireToken by remember { mutableStateOf(AutomationAuth.requireToken(ctx)) }
    var token by remember { mutableStateOf(AutomationAuth.token(ctx)) }
    val dirSet = remember(showExim) { SokkiBackup.backupDirLabel(ctx) != null }
    // Pen pressure: hoisted, because the pad, the readout and the three sliders are one control.
    var pen by remember { mutableStateOf(editor.toolConfig(Tool.PEN)) }
    var stats by remember { mutableStateOf(PressureStats()) }
    var padClear by remember { mutableStateOf(0) }

    fun set(next: SokkiUi) = editor.applySokkiUi(next)

    /** The band and the curve are a property of the pen and the hand, not of one tool, so this page
     *  writes all four pressure tools at once. The per-tool override still lives in each tool's own
     *  popup, for when one pen wants a different bite than the rest. */
    fun setPressure(low: Double, high: Double, curve: Double) {
        PRESSURE_TOOLS.forEach { t ->
            editor.updateToolConfig(
                t,
                editor.toolConfig(t).copy(pressureLow = low, pressureHigh = high, pressureCurve = curve),
            )
        }
        pen = editor.toolConfig(Tool.PEN)
    }

    LazyColumn(
        Modifier.fillMaxSize().background(palette.bg.toComposeColor()),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp),
    ) {
        item { SectionHeader("Export / Import", first = true) }
        item {
            NavRow(
                1, "Export / Import…",
                if (dirSet) "Back up everything settable as one ZIP, or restore it."
                else "No backup folder set yet — tap to choose one.",
                warn = !dirSet,
            ) { showExim = true }
        }
        // Per the 保存復元 contract the automation controls belong in THIS section, right under the
        // export rows — not in a section of their own — so every sister app looks the same.
        item {
            SwitchRow(
                1, "Automation export",
                "Let sister apps trigger this app's export and back its settings up. On by default; " +
                    "this switch is the only way to close 白い熊 速記 off entirely.",
                automationOn,
            ) { on -> AutomationAuth.setEnabled(ctx, on); automationOn = on }
        }
        item {
            SwitchRow(
                1, "Use authorization token?",
                if (requireToken) "A caller must also present the token below."
                else "Off: any sister app may drive the automation. The data door checks the " +
                    "caller's package and signing certificate either way.",
                requireToken,
            ) { on -> AutomationAuth.setRequireToken(ctx, on); requireToken = on }
        }
        // Hidden unless it is actually being asked for: a 48-character secret sitting under an off
        // switch only invites 白い熊 to paste it somewhere it will do nothing.
        if (automationOn && requireToken) {
            item {
                TokenRow(1, token, onCopy = {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("automation token", token))
                    android.widget.Toast.makeText(ctx, "Token copied", android.widget.Toast.LENGTH_SHORT).show()
                }, onRegenerate = {
                    token = AutomationAuth.regenerate(ctx)
                    android.widget.Toast.makeText(ctx, "New token — update pasted copies", android.widget.Toast.LENGTH_LONG).show()
                })
            }
        }

        // Not an appearance setting, but this is the fork's settings home and it is the one 白い熊
        // reaches for while writing, so it sits near the top rather than under the colour lists.
        item { SectionHeader("Pen pressure") }
        item {
            PreviewFrame {
                Column {
                    Text(
                        "Write here as you normally would — the ink is the pen being tuned.",
                        fontSize = 11.sp,
                        color = palette.textDim.toComposeColor(),
                    )
                    Spacer(Modifier.height(6.dp))
                    PressurePad(
                        config = pen,
                        ink = palette.accent,
                        clearKey = padClear,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        onStats = { stats = it },
                    )
                }
            }
        }
        item { ReadoutRow(1, stats) }
        item {
            ActionRow(
                1, "Use the measured band", "Take the measured p5–p95",
                if (stats.hasData) {
                    "Sets Light and Hard to ${pct(stats.p5)} – ${pct(stats.p95)} on all four pressure pens."
                } else {
                    "Write in the pad above with the pen first — a finger is not measured."
                },
                enabled = stats.hasData,
            ) { setPressure(stats.p5, stats.p95, pen.pressureCurve) }
        }
        item {
            ActionRow(
                1, "Calibration pad", "Clear",
                "Wipes the ink and starts the measurement over.",
                enabled = true,
            ) { padClear++ }
        }
        item {
            SliderRow(
                1, "Light", bandPercent(pen.pressureLow), "${bandPercent(pen.pressureLow)} % — thinnest below this",
                0f..95f,
            ) { v ->
                val low = ToolConversions.percentToPressure(v.toDouble())
                val high = maxOf(pen.pressureHigh, low + ToolConversions.MIN_BAND_PERCENT / 100.0)
                setPressure(low, high, pen.pressureCurve)
            }
        }
        item {
            SliderRow(
                1, "Hard", bandPercent(pen.pressureHigh), "${bandPercent(pen.pressureHigh)} % — full width above this",
                5f..100f,
            ) { v ->
                val high = ToolConversions.percentToPressure(v.toDouble())
                val low = minOf(pen.pressureLow, high - ToolConversions.MIN_BAND_PERCENT / 100.0)
                setPressure(low, high, pen.pressureCurve)
            }
        }
        item {
            SliderRow(
                1, "Curve", pen.pressureCurve.roundToInt(), curveLabel(pen.pressureCurve),
                ToolConversions.CURVE_RANGE.start.toFloat()..ToolConversions.CURVE_RANGE.endInclusive.toFloat(),
            ) { v -> setPressure(pen.pressureLow, pen.pressureHigh, v.toDouble()) }
        }
        item {
            val d = com.xnotes.core.tools.ToolConfig()
            ActionRow(
                1, "Pressure response", "Reset",
                "Back to the whole reported range and the stock curve.",
                enabled = pen.pressureLow != d.pressureLow ||
                    pen.pressureHigh != d.pressureHigh ||
                    pen.pressureCurve != d.pressureCurve,
            ) { setPressure(d.pressureLow, d.pressureHigh, d.pressureCurve) }
        }
        // The reference sits under the controls it talks about, so a stroke that came out wrong can
        // be read about and fixed without leaving the page.
        item { GroupLabel(1, "If it isn't right") }
        items2(PRESSURE_FIXES) { (symptom, remedy) -> FixRow(2, symptom, remedy) }

        item { SectionHeader("Theme") }
        item {
            SwitchRow(
                1, "白い熊 速記 UI",
                "Off, the app falls back to its stock accent chrome — the settings below are kept.",
                ui.enabled,
            ) { set(ui.copy(enabled = it)) }
        }

        item { SectionHeader("Colours") }
        item { ColorPreview() }
        item { GroupLabel(1, "Surfaces") }
        items2(listOf(ColorSlot.BG, ColorSlot.PANEL, ColorSlot.SURFACE, ColorSlot.SURFACE_HI, ColorSlot.MENU_BG)) { slot ->
            ColorRow(2, slot, ui.color(slot)) { pickingColor = slot }
        }
        item { GroupLabel(1, "Page") }
        items2(listOf(ColorSlot.PAPER, ColorSlot.PAPER_BORDER)) { slot ->
            ColorRow(2, slot, ui.color(slot)) { pickingColor = slot }
        }
        item { GroupLabel(1, "Text") }
        items2(listOf(ColorSlot.TEXT, ColorSlot.TEXT_DIM)) { slot ->
            ColorRow(2, slot, ui.color(slot)) { pickingColor = slot }
        }
        item { GroupLabel(1, "Lines & highlights") }
        items2(listOf(ColorSlot.ACCENT, ColorSlot.BORDER)) { slot ->
            ColorRow(2, slot, ui.color(slot)) { pickingColor = slot }
        }

        item { SectionHeader("Typography") }
        item { TypePreview(ui) }
        item { FontRow(1, ui.fontFile) { pickingFont = true } }
        item {
            SliderRow(1, "Text size", ui.fontSizeSp, "${ui.fontSizeSp} sp", 9f..30f) {
                set(ui.copy(fontSizeSp = it))
            }
        }
        item {
            SliderRow(1, "Weight", ui.fontWeight, weightLabel(ui.fontWeight), 100f..900f, step = 100) {
                set(ui.copy(fontWeight = it))
            }
        }

        item { SectionHeader("Shape & lines") }
        item { ShapePreview(ui) }
        item {
            SliderRow(1, "Border thickness", ui.borderWidthDp, borderLabel(ui.borderWidthDp), 0f..8f) {
                set(ui.copy(borderWidthDp = it))
            }
        }
        item {
            SliderRow(1, "Corner roundness", ui.cornerRadiusDp, cornerLabel(ui.cornerRadiusDp), 0f..32f) {
                set(ui.copy(cornerRadiusDp = it))
            }
        }

        item { SectionHeader("Icons & density") }
        item { IconPreview(ui) }
        item {
            SliderRow(1, "Icon size", ui.iconSizeDp, "${ui.iconSizeDp} dp", 12f..40f) {
                set(ui.copy(iconSizeDp = it))
            }
        }
        item {
            SliderRow(1, "Row spacing", ui.rowPaddingDp, "${ui.rowPaddingDp} dp", 0f..20f) {
                set(ui.copy(rowPaddingDp = it))
            }
        }
    }

    if (showExim) {
        SokkiExportImportPanel(
            editor = editor,
            onDismiss = { showExim = false },
            onFinished = onClosePage,
        )
    }

    pickingColor?.let { slot ->
        SokkiColorPickerDialog(
            title = slot.label,
            initial = ui.color(slot),
            default = slot.default,
            recent = editor.recentUiColors,
            onDismiss = { pickingColor = null },
            onConfirm = { c ->
                editor.applySokkiUi(ui.withColor(slot, c), remember = c)
                pickingColor = null
            },
        )
    }
    if (pickingFont) {
        SokkiFontPickerDialog(
            current = ui.fontFile,
            onDismiss = { pickingFont = false },
            onPick = { set(ui.copy(fontFile = it)); pickingFont = false },
            onImport = { pickingFont = false; onImportFont() },
        )
    }
}

/** LazyListScope helper: the same row shape for a list of slots, without repeating `item {}`. */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.items2(
    values: List<T>,
    row: @Composable (T) -> Unit,
) = values.forEach { v -> item { row(v) } }

// ---- structure ---------------------------------------------------------------------------------

/**
 * A top-level heading: bold, large, underlined **only as wide as its own text** (IntrinsicSize.Min
 * sizes the column to the single line), each section but the first opened by a thin full-width
 * hairline. This is the kxkb UI page's heading, and the only place the page spends vertical space.
 */
@Composable
private fun SectionHeader(title: String, first: Boolean = false) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (!first) {
            HorizontalDivider(
                Modifier.padding(top = 18.dp),
                thickness = 1.dp,
                color = palette.border.toComposeColor().copy(alpha = 0.4f),
            )
        }
        Column(
            Modifier
                .padding(start = 16.dp, top = if (first) 10.dp else 16.dp, end = 16.dp, bottom = 6.dp)
                .width(IntrinsicSize.Min),
        ) {
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = palette.accent.toComposeColor(),
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.height(3.dp))
            HorizontalDivider(thickness = 2.dp, color = palette.accent.toComposeColor())
        }
    }
}

/** A sub-heading inside a section: its rows sit one level deeper again. */
@Composable
private fun GroupLabel(level: Int, text: String) {
    val palette = LocalPalette.current
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = indent(level), end = 16.dp, top = 8.dp, bottom = 2.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = palette.textDim.toComposeColor(),
    )
}

/** The one row shape. Vertical padding is the theme's own density control, so the page tightens
 *  or opens up as it is edited — the setting previews itself by being the thing it changes. */
@Composable
private fun RowScaffold(level: Int, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val pad = com.xnotes.ui.theme.LocalSokkiUi.current.rowPaddingDp.dp
    val base = Modifier.fillMaxWidth()
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(start = indent(level), end = 16.dp, top = pad, bottom = pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

// ---- rows --------------------------------------------------------------------------------------

@Composable
private fun ColorRow(level: Int, slot: ColorSlot, value: Rgba, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val ui = com.xnotes.ui.theme.LocalSokkiUi.current
    RowScaffold(level, onClick = onClick) {
        Column(Modifier.weight(1f)) {
            Text(slot.label, fontSize = 15.sp, color = palette.text.toComposeColor())
            Text(
                "${slot.about}  ·  ${argbHex(value)}",
                fontSize = 11.sp,
                color = palette.textDim.toComposeColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The swatch is a live preview of the very border/corner values being edited elsewhere.
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(ui.cornerRadiusDp.dp))
                .background(value.toComposeColor())
                .border(
                    ui.borderWidthDp.dp.coerceAtLeast(1.dp),
                    palette.border.toComposeColor(),
                    RoundedCornerShape(ui.cornerRadiusDp.dp),
                ),
        )
    }
}

@Composable
private fun SliderRow(
    level: Int,
    label: String,
    value: Int,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val pad = com.xnotes.ui.theme.LocalSokkiUi.current.rowPaddingDp.dp
    Column(
        Modifier.fillMaxWidth().padding(start = indent(level), end = 16.dp, top = pad, bottom = pad),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 15.sp, color = palette.text.toComposeColor())
            Text(valueText, fontSize = 12.sp, color = palette.textDim.toComposeColor())
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> onChange((v / step).roundToInt() * step) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

@Composable
private fun FontRow(level: Int, fontId: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    RowScaffold(level, onClick = onClick) {
        Text("Font", Modifier.weight(1f), fontSize = 15.sp, color = palette.text.toComposeColor())
        // Named in its own glyphs, so the row is already the preview.
        Text(
            UiFonts.label(fontId),
            fontFamily = UiFonts.family(fontId) ?: FontFamily.Default,
            fontSize = 15.sp,
            color = palette.textDim.toComposeColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A row that opens something. [warn] paints it Kōjiki red — used when no backup folder is set. */
@Composable
private fun NavRow(level: Int, label: String, about: String, warn: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    RowScaffold(level, onClick = onClick) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = palette.text.toComposeColor())
            Text(
                about,
                fontSize = 11.sp,
                color = if (warn) SokkiWarnRed.toComposeColor() else palette.textDim.toComposeColor(),
            )
        }
        Icon(XnotesIcons.next, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
    }
}

/** The automation token: tap to copy the whole thing, Regenerate on the right. */
@Composable
private fun TokenRow(level: Int, token: String, onCopy: () -> Unit, onRegenerate: () -> Unit) {
    val palette = LocalPalette.current
    val dim = palette.textDim.toComposeColor()
    RowScaffold(level, onClick = onCopy) {
        Column(Modifier.weight(1f)) {
            Text("Token", fontSize = 15.sp, color = palette.text.toComposeColor())
            Text(
                "${AutomationAuth.abbreviated(token)}  ·  tap to copy",
                fontSize = 11.sp,
                color = dim,
            )
        }
        Text(
            "Regenerate",
            Modifier.clickable { onRegenerate() },
            fontSize = 12.sp,
            color = palette.accent.toComposeColor(),
        )
    }
}

/** A row whose right-hand side is a verb rather than a control: the calibration actions. */
@Composable
private fun ActionRow(
    level: Int,
    label: String,
    verb: String,
    about: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val dim = palette.textDim.toComposeColor()
    RowScaffold(level, onClick = { if (enabled) onClick() }) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = if (enabled) palette.text.toComposeColor() else dim)
            Text(about, fontSize = 11.sp, color = dim)
        }
        Text(verb, fontSize = 12.sp, color = if (enabled) palette.accent.toComposeColor() else dim)
    }
}

/**
 * One line of the symptom → slider reference: what the ink is doing wrong, and which slider moves.
 * Deliberately not a control — it is the page's only piece of pure documentation, so it is drawn
 * flatter than a row that does something, with the remedy carrying the accent rather than the text.
 */
@Composable
private fun FixRow(level: Int, symptom: String, remedy: String) {
    val palette = LocalPalette.current
    RowScaffold(level) {
        Column(Modifier.weight(1f)) {
            Text(symptom, fontSize = 13.sp, color = palette.text.toComposeColor())
            Text(remedy, fontSize = 12.sp, color = palette.accent.toComposeColor())
        }
    }
}

/**
 * What the pad has measured. The band the response is set from is p5–p95, so that pair is the line
 * that reads as the answer; the outright min/max sit behind it as the sanity check that the pen is
 * reporting a range at all rather than a constant.
 */
@Composable
private fun ReadoutRow(level: Int, stats: PressureStats) {
    val palette = LocalPalette.current
    RowScaffold(level) {
        Column(Modifier.weight(1f)) {
            Text(
                if (stats.hasData) "Measured  ${pct(stats.p5)} – ${pct(stats.p95)}" else "Measured  —",
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                color = palette.text.toComposeColor(),
            )
            Text(
                if (stats.hasData) {
                    "now ${pct(stats.current)}  ·  seen ${pct(stats.min)} – ${pct(stats.max)}  ·  ${stats.count} pen samples"
                } else {
                    "No stylus samples yet."
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = palette.textDim.toComposeColor(),
            )
        }
    }
}

@Composable
private fun SwitchRow(level: Int, label: String, about: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    RowScaffold(level, onClick = { onChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = palette.text.toComposeColor())
            Text(about, fontSize = 11.sp, color = palette.textDim.toComposeColor())
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.bg.toComposeColor(),
                checkedTrackColor = palette.accent.toComposeColor(),
            ),
        )
    }
}

// ---- previews ----------------------------------------------------------------------------------

/** The frame every preview sits in: indented like a row, drawn with the live border and corner. */
@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    val ui = com.xnotes.ui.theme.LocalSokkiUi.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = indent(1), end = 16.dp, top = 4.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(ui.cornerRadiusDp.dp))
            .background(palette.surface.toComposeColor())
            .border(ui.borderWidthDp.dp, palette.border.toComposeColor(), RoundedCornerShape(ui.cornerRadiusDp.dp))
            .padding(10.dp),
    ) { content() }
}

@Composable
private fun ColorPreview() {
    val palette = LocalPalette.current
    PreviewFrame {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Primary label", fontSize = 15.sp, color = palette.text.toComposeColor())
            Text("Secondary, helper and hint lines", fontSize = 12.sp, color = palette.textDim.toComposeColor())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(palette.accent.toComposeColor()))
                Text("Accent", fontSize = 12.sp, color = palette.accent.toComposeColor())
            }
        }
    }
}

@Composable
private fun TypePreview(ui: SokkiUi) {
    val palette = LocalPalette.current
    val family = UiFonts.family(ui.fontFile) ?: FontFamily.Default
    PreviewFrame {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "白い熊 速記",
                fontFamily = family,
                fontSize = (ui.fontSizeSp + 4).sp,
                fontWeight = FontWeight(ui.fontWeight),
                color = palette.text.toComposeColor(),
            )
            Text(
                "Handwriting, sketches and PDF notes.",
                fontFamily = family,
                fontSize = ui.fontSizeSp.sp,
                fontWeight = FontWeight(ui.fontWeight),
                color = palette.text.toComposeColor(),
            )
            Text(
                "0123456789  ·  the quick brown fox",
                fontFamily = family,
                fontSize = (ui.fontSizeSp - 3).sp,
                fontWeight = FontWeight(ui.fontWeight),
                color = palette.textDim.toComposeColor(),
            )
        }
    }
}

@Composable
private fun ShapePreview(ui: SokkiUi) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(ui.cornerRadiusDp.dp)
    PreviewFrame {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .height(34.dp)
                    .weight(1f)
                    .clip(shape)
                    .background(palette.bg.toComposeColor())
                    .border(ui.borderWidthDp.dp, palette.border.toComposeColor(), shape),
                contentAlignment = Alignment.Center,
            ) {
                Text("Outlined", fontSize = 12.sp, color = palette.text.toComposeColor())
            }
            Box(
                Modifier
                    .height(34.dp)
                    .weight(1f)
                    .clip(shape)
                    .background(palette.accent.toComposeColor()),
                contentAlignment = Alignment.Center,
            ) {
                Text("Filled", fontSize = 12.sp, color = palette.bg.toComposeColor())
            }
        }
    }
}

@Composable
private fun IconPreview(ui: SokkiUi) {
    val palette = LocalPalette.current
    PreviewFrame {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(XnotesIcons.home, XnotesIcons.edit, XnotesIcons.folder, XnotesIcons.sliders, XnotesIcons.info)
                .forEach { icon ->
                    Icon(icon, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(ui.iconSizeDp.dp))
                }
        }
    }
}

// ---- labels ------------------------------------------------------------------------------------

private fun weightLabel(w: Int) = when (w) {
    100 -> "100 Thin"; 200 -> "200 Extra light"; 300 -> "300 Light"; 400 -> "400 Regular"
    500 -> "500 Medium"; 600 -> "600 Semi bold"; 700 -> "700 Bold"; 800 -> "800 Extra bold"
    else -> "900 Black"
}

/** The four tools that read pressure — the ones this page's calibration writes. */
private val PRESSURE_TOOLS = listOf(Tool.PEN, Tool.CALLIGRAPHY, Tool.SPEED, Tool.TAPER)

/**
 * Symptom → slider. Two rules are worth carrying in mind while reading it, because they are what
 * make the wrong slider tempting: **Width is a multiplier on both ends**, so it moves the thick and
 * the thin together and can never change the ratio between them (Sensitivity is the only control
 * that moves the thin end alone); and **a dead zone is nearly always the band, not the curve** —
 * the press has drifted onto a rail, so the window moves rather than the S flattening.
 */
private val PRESSURE_FIXES = listOf(
    "Thin strokes aren't thin enough" to "Sensitivity up — then Light up",
    "Thick isn't thick enough" to "Width up, and Sensitivity up to keep the hairline",
    "Have to press too hard for thick" to "Hard down",
    "Thick comes too easily, or blobs" to "Hard up",
    "Both ends fine, the change is too gradual" to "Curve up",
    "The line pulses or flickers mid-stroke" to "Curve down",
    "Whole pen too fat, but the ratio is right" to "Width down",
    "A dead zone where pressing changes nothing" to "Move Light/Hard so your usual press sits mid-band",
)

/** Raw pressure as the percent of the reported range the readout and the sliders both speak in. */
private fun pct(p: Double) = "${(p * 100).roundToInt()}%"

private fun bandPercent(p: Double) = ToolConversions.pressureToPercent(p).roundToInt()

private fun curveLabel(k: Double) = when {
    k < 0.5 -> "0 — linear, no S"
    k < 6 -> "${k.roundToInt()} — gentle"
    k < 12 -> "${k.roundToInt()} — the stock pen"
    k < 18 -> "${k.roundToInt()} — nib-like"
    else -> "${k.roundToInt()} — near a hard switch"
}

private fun borderLabel(dp: Int) = if (dp == 0) "0 dp — no borders" else "$dp dp"

private fun cornerLabel(dp: Int) = if (dp == 0) "0 dp — square" else "$dp dp"
