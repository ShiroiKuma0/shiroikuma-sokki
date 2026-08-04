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
    var token by remember { mutableStateOf(AutomationAuth.token(ctx)) }
    val dirSet = remember(showExim) { SokkiBackup.backupDirLabel(ctx) != null }

    fun set(next: SokkiUi) = editor.applySokkiUi(next)

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
                "Let 白い熊 自由作業盤 trigger this app's export over the token-gated intent.",
                automationOn,
            ) { on -> AutomationAuth.setEnabled(ctx, on); automationOn = on }
        }
        item {
            TokenRow(1, token, enabled = automationOn, onCopy = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("automation token", token))
                android.widget.Toast.makeText(ctx, "Token copied", android.widget.Toast.LENGTH_SHORT).show()
            }, onRegenerate = {
                token = AutomationAuth.regenerate(ctx)
                android.widget.Toast.makeText(ctx, "New token — update pasted copies", android.widget.Toast.LENGTH_LONG).show()
            })
        }

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
private fun TokenRow(level: Int, token: String, enabled: Boolean, onCopy: () -> Unit, onRegenerate: () -> Unit) {
    val palette = LocalPalette.current
    val dim = palette.textDim.toComposeColor()
    RowScaffold(level, onClick = { if (enabled) onCopy() }) {
        Column(Modifier.weight(1f)) {
            Text(
                "Token",
                fontSize = 15.sp,
                color = if (enabled) palette.text.toComposeColor() else dim,
            )
            Text(
                if (enabled) "${AutomationAuth.abbreviated(token)}  ·  tap to copy" else "Turn automation on to use the token",
                fontSize = 11.sp,
                color = dim,
            )
        }
        Text(
            "Regenerate",
            Modifier.clickable(enabled = enabled) { onRegenerate() },
            fontSize = 12.sp,
            color = if (enabled) palette.accent.toComposeColor() else dim,
        )
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

private fun borderLabel(dp: Int) = if (dp == 0) "0 dp — no borders" else "$dp dp"

private fun cornerLabel(dp: Int) = if (dp == 0) "0 dp — square" else "$dp dp"
