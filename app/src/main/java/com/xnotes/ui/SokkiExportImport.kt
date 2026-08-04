package com.xnotes.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.xnotes.settings.SokkiBackup
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Export / Import panel, in the Kōjiki sheet format: the whole thing inside one bordered
 * rounded box, a centred title over a dim intro, the backup folder as its own tappable box, thin
 * accent hairlines around a flat checklist, and the ArcaneChat button bar at the foot — Cancel
 * alone on the left, Import and Export grouped right, all of them fully round pills.
 *
 * [onFinished] is the close-the-whole-chain signal: after a SUCCESSFUL export or import, dismissing
 * the result dialog closes this panel and the UI page under it. A failure leaves everything open,
 * because the thing that failed is what you were about to fix.
 */
@Composable
fun SokkiExportImportPanel(editor: Editor, onDismiss: () -> Unit, onFinished: () -> Unit) {
    val palette = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = palette.accent.toComposeColor()
    val warn = SokkiWarnRed.toComposeColor()

    var dirLabel by remember { mutableStateOf(SokkiBackup.backupDirLabel(ctx)) }
    var latest by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PanelResult?>(null) }
    val selected = remember {
        SokkiBackup.Cat.entries.filter { it.defaultSelected }.map { it.id }.toMutableStateList()
    }

    // The directory is queried for its newest backup as the panel opens — the same question 白い熊
    // would otherwise open a file manager to answer.
    LaunchedEffect(dirLabel) {
        latest = withContext(Dispatchers.IO) { runCatching { SokkiBackup.latestBackup(ctx) }.getOrNull() }
    }

    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            SokkiBackup.setBackupDirUri(ctx, uri)
            dirLabel = SokkiBackup.backupDirLabel(ctx)
        }
    }
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val current = editor.settingsJson()
                    ctx.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "could not read that file" }
                        SokkiBackup.import(ctx, current, input)
                    }
                }
            }
            busy = false
            result = r.fold(
                onSuccess = { imported ->
                    editor.applyImportedSettings(imported.merged)
                    PanelResult(
                        ok = true,
                        title = "Import finished",
                        body = "Restored ${imported.categories.size} " +
                            (if (imported.categories.size == 1) "category" else "categories") + ":\n" +
                            imported.categories.joinToString("\n") { "· ${it.label}" },
                        isImport = true,
                    )
                },
                onFailure = { PanelResult(false, "Import failed", it.message ?: "Unknown error", isImport = true) },
            )
        }
    }

    fun runExport() {
        val cats = SokkiBackup.Cat.entries.filter { it.id in selected }
        if (cats.isEmpty()) {
            result = PanelResult(false, "Nothing to export", "No categories selected.")
            return
        }
        val treeUri = SokkiBackup.backupDirUri(ctx)
        if (treeUri == null) {
            result = PanelResult(false, "Export failed", "No backup folder set.")
            return
        }
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val json = editor.settingsJson()
                    val name = SokkiBackup.timestampedName()
                    val dir = DocumentFile.fromTreeUri(ctx, treeUri) ?: error("Backup folder is unavailable.")
                    dir.findFile("$name.part")?.delete()
                    // Written as .part and renamed only once closed: a killed export must never
                    // leave something a later restore would mistake for a real backup.
                    val part = dir.createFile("application/zip", "$name.part") ?: error("Could not create the file.")
                    try {
                        val count = ctx.contentResolver.openOutputStream(part.uri).use { out ->
                            requireNotNull(out) { "Could not write to the backup folder." }
                            SokkiBackup.export(ctx, json, cats, out)
                        }
                        if (!part.renameTo(name)) error("Could not finish writing $name.")
                        val bytes = dir.findFile(name)?.length() ?: 0L
                        Triple(name, bytes, count)
                    } catch (e: Exception) {
                        runCatching { part.delete() }
                        throw e
                    }
                }
            }
            busy = false
            result = r.fold(
                onSuccess = { (name, bytes, count) ->
                    latest = name to bytes
                    PanelResult(
                        ok = true,
                        title = "Export finished",
                        body = "$name\n${SokkiBackup.humanSize(bytes)} · $count categories",
                    )
                },
                onFailure = { PanelResult(false, "Export failed", it.message ?: "Unknown error") },
            )
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.bg.toComposeColor())
            .border(2.dp, accent, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                "Export / Import",
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = palette.text.toComposeColor(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Back up everything settable in 白い熊 速記 as one ZIP, or restore it. " +
                    "Importing merges — categories missing from an archive are left alone.",
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = palette.textDim.toComposeColor(),
            )

            Spacer(Modifier.height(12.dp))
            // The folder box: warn-red until a directory is set, house yellow once it is.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.5.dp, if (dirLabel == null) warn else accent, RoundedCornerShape(10.dp))
                    .clickable { pickDir.launch(null) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column {
                    Text(
                        "Backup folder",
                        fontSize = 10.sp,
                        color = if (dirLabel == null) warn else palette.textDim.toComposeColor(),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        dirLabel ?: "Not set — tap to choose a folder",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (dirLabel == null) warn else palette.text.toComposeColor(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                latest?.let { (n, b) -> "Last backup: $n · ${SokkiBackup.humanSize(b)}" }
                    ?: if (dirLabel == null) "No folder set, so nothing can be exported yet." else "No backup in this folder yet.",
                fontSize = 10.sp,
                color = if (dirLabel == null) warn else palette.textDim.toComposeColor(),
            )

            Spacer(Modifier.height(12.dp))
            AccentHairline()
            CheckRow(
                "Select all",
                bold = true,
                checked = selected.size == SokkiBackup.Cat.entries.size,
            ) { on ->
                selected.clear()
                if (on) selected.addAll(SokkiBackup.Cat.entries.map { it.id })
            }
            SokkiBackup.Cat.entries.forEach { cat ->
                CheckRow(cat.label, bold = false, checked = cat.id in selected) { on ->
                    if (on) selected.add(cat.id) else selected.remove(cat.id)
                }
            }
            AccentHairline()

            Spacer(Modifier.height(14.dp))
            // ArcaneChat button bar: Cancel alone left, the two actions grouped right.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Pill("Cancel", enabled = !busy, onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Import", enabled = !busy) { pickImport.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
                    Pill("Export", enabled = !busy && dirLabel != null) { runExport() }
                }
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                Text("Working…", fontSize = 11.sp, color = palette.textDim.toComposeColor())
            }
        }
    }

    result?.let { r ->
        ResultDialog(r) {
            result = null
            // Only success collapses the chain; a failure leaves the panel up to be fixed.
            if (r.ok) { onDismiss(); onFinished() }
        }
    }
}

private class PanelResult(
    val ok: Boolean,
    val title: String,
    val body: String,
    val isImport: Boolean = false,
)

/** Black fill, yellow border, yellow text — and it never reports success it did not have. */
@Composable
private fun ResultDialog(r: PanelResult, onClose: () -> Unit) {
    val palette = LocalPalette.current
    val accent = palette.accent.toComposeColor()
    AlertDialog(
        modifier = Modifier.border(1.5.dp, accent, RoundedCornerShape(28.dp)),
        containerColor = palette.bg.toComposeColor(),
        onDismissRequest = onClose,
        title = {
            Text(
                r.title,
                fontWeight = FontWeight.Bold,
                color = if (r.ok) accent else SokkiWarnRed.toComposeColor(),
            )
        },
        text = { Text(r.body, fontSize = 13.sp, color = palette.text.toComposeColor()) },
        confirmButton = {
            // An import may want a restart for cached fonts; either choice closes the chain.
            TextButton(onClick = onClose) {
                Text(if (r.ok && r.isImport) "Later" else "OK", color = accent)
            }
        },
    )
}

@Composable
private fun AccentHairline() {
    HorizontalDivider(
        thickness = 1.dp,
        color = LocalPalette.current.accent.toComposeColor().copy(alpha = 0.4f),
    )
}

@Composable
private fun CheckRow(label: String, bold: Boolean, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    val accent = palette.accent.toComposeColor()
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.dp, accent, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(XnotesIcons.check, null, tint = accent, modifier = Modifier.size(11.dp))
        }
        Spacer(Modifier.size(10.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = palette.text.toComposeColor(),
        )
    }
}

/** Fully round: 50dp radius, 1.5dp accent stroke, black fill, accent text. */
@Composable
private fun Pill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val accent = palette.accent.toComposeColor()
    val shape = RoundedCornerShape(50.dp)
    val tint = if (enabled) accent else palette.textDim.toComposeColor()
    Box(
        Modifier
            .clip(shape)
            .background(palette.bg.toComposeColor())
            .border(1.5.dp, tint, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}
