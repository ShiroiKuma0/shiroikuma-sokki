package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.Rgba
import com.xnotes.settings.argbHex
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.UiFonts
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * The house colour picker: four 0–255 channel sliders (R, G, B, A) over a live preview, with a row
 * of one-click boxes above them prefilled with the colours picked before — so the palette 白い熊 is
 * actually building is one tap away rather than being dialled in again on every row.
 */
@Composable
fun SokkiColorPickerDialog(
    title: String,
    initial: Rgba,
    default: Rgba,
    recent: List<Rgba>,
    onDismiss: () -> Unit,
    onConfirm: (Rgba) -> Unit,
) {
    val palette = LocalPalette.current
    var r by remember { mutableIntStateOf(initial.r) }
    var g by remember { mutableIntStateOf(initial.g) }
    var b by remember { mutableIntStateOf(initial.b) }
    var a by remember { mutableIntStateOf(initial.a) }
    val current = Rgba(r, g, b, a)
    fun set(c: Rgba) { r = c.r; g = c.g; b = c.b; a = c.a }

    // The default first, then the recents — the one box that is always worth having.
    val swatches = remember(recent, default) {
        (listOf(default) + recent).distinct().take(18)
    }

    SokkiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Recent",
                    fontSize = 11.sp,
                    color = palette.textDim.toComposeColor(),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    swatches.forEach { c ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(c.toComposeColor())
                                .border(
                                    if (c == current) 2.dp else 1.dp,
                                    if (c == current) palette.accent.toComposeColor() else palette.border.toComposeColor(),
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable { set(c) },
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(current.toComposeColor())
                        .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp)),
                )
                ChannelSlider("R", r) { r = it }
                ChannelSlider("G", g) { g = it }
                ChannelSlider("B", b) { b = it }
                ChannelSlider("A", a) { a = it }
                Text(
                    argbHex(current),
                    fontSize = 12.sp,
                    color = palette.textDim.toComposeColor(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current) }) {
                Text("Apply", color = palette.accent.toComposeColor())
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { set(default) }) {
                    Text("Default", color = palette.textDim.toComposeColor())
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = palette.textDim.toComposeColor())
                }
            }
        },
    )
}

@Composable
private fun ChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.width(14.dp), fontSize = 12.sp, color = palette.textDim.toComposeColor())
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
        )
        Text(
            value.toString(),
            Modifier.width(30.dp),
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            color = palette.textDim.toComposeColor(),
        )
    }
}

/**
 * The font picker. Every option is drawn **in its own glyphs**, so the list is the preview — the
 * only honest way to choose a typeface. Imported fonts carry a delete action; "Import a font file…"
 * hands off to the app's existing SAF font import.
 */
@Composable
fun SokkiFontPickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onImport: () -> Unit,
) {
    val palette = LocalPalette.current
    val choices = remember { UiFonts.choices() }
    SokkiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Font", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                FontOptionRow("System", "", current == "", onPick)
                choices.forEach { c ->
                    FontOptionRow(c.label, c.face.id, current == c.face.id, onPick)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport) {
                Text("Import a font file…", color = palette.accent.toComposeColor())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = palette.textDim.toComposeColor())
            }
        },
    )
}

@Composable
private fun FontOptionRow(label: String, id: String, selected: Boolean, onPick: (String) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(id) }
            .padding(vertical = 7.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            Modifier.weight(1f),
            // The point of the row: the name is set in the face it names.
            fontFamily = UiFonts.family(id) ?: FontFamily.Default,
            fontSize = 16.sp,
            color = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                XnotesIcons.check,
                null,
                tint = palette.accent.toComposeColor(),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
