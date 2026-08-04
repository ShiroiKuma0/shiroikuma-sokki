package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Presentation control (spec 10 §12 / 12 §8): port, mode, network scope, start/stop. */
@Composable
fun PresentationDialog(editor: Editor, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val defaults = editor.presentationDefaults
    var port by remember { mutableStateOf(defaults.port.toString()) }
    var mode by remember { mutableStateOf(defaults.mode) }
    var scope by remember { mutableStateOf(defaults.scope) }
    val running = editor.presentationRunning

    SokkiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Presentation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (running) {
                    Text("● Live", color = palette.accent.toComposeColor(), fontFamily = FontFamily.Monospace)
                    Text(editor.presentationUrl, color = palette.text.toComposeColor(), fontFamily = FontFamily.Monospace)
                    Text("Connected: ${editor.presentationClients}", color = palette.textDim.toComposeColor())
                } else {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                    )
                }
                Label("Mode")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pick("Page", mode == "page") { mode = "page"; if (running) editor.setPresentationMode("page") }
                    Pick("Follow", mode == "follow") { mode = "follow"; if (running) editor.setPresentationMode("follow") }
                }
                if (!running) {
                    Label("Network")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pick("This computer only", scope == "localhost") { scope = "localhost" }
                        Pick("Allow network", scope == "lan") { scope = "lan" }
                    }
                    if (scope == "lan") {
                        Text(
                            "Anyone on the network who opens the URL can watch — there is no password.",
                            color = palette.accent.toComposeColor(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = { editor.stopPresentation() }) { Text("Stop") }
            } else {
                TextButton(onClick = {
                    val error = editor.startPresentation(port.toIntOrNull() ?: 8000, scope, mode)
                    if (error != null) editor.message = error
                }) { Text("Start") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, color = LocalPalette.current.accent.toComposeColor())
}

@Composable
private fun Pick(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) palette.accentAlpha(48).toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor())
    }
}
