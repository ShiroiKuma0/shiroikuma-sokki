package com.xnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.LocalSokkiUi
import com.xnotes.ui.theme.toComposeColor

/**
 * Bordered floating surfaces for the 白い熊 速記 look (shiroikuma-sokki fork).
 *
 * Material's dialogs and menus separate themselves from what is behind them with a *lighter*
 * surface and a shadow. Our house palette makes `surface`, `menuBg` and `bg` all black, so that
 * separation collapses: a dialog over the explorer is black text on black with no edge anywhere —
 * see the "Delete?" dialog 白い熊 caught on 2026-08-04. What replaces the tonal step is a border,
 * which the palette already carries a slot for.
 *
 * The fork's own dialogs ([SokkiExportImport], [SokkiPickers]) each hand-rolled this. These
 * wrappers are that convention stated once, so every floating surface picks it up — upstream's
 * dialogs included — and so the 白い熊 速記 UI page's Border colour and Border width drive all of
 * them rather than only the dividers.
 *
 * Deliberately thin: same parameters, same defaults, same behaviour as the Material components
 * they wrap. A rebase that changes a call site upstream still applies; only the name differs.
 */

/** Border thickness for a floating surface, honouring the UI page's own control. */
@Composable
private fun borderWidth() = LocalSokkiUi.current.borderWidthDp.dp

/** The stroke every dialog and menu outlines itself with. */
@Composable
fun sokkiSurfaceBorder(): BorderStroke =
    BorderStroke(borderWidth(), LocalPalette.current.border.toComposeColor())

/** Material's own dialog corner (extra-large, 28dp) — the border must match it exactly. */
val SokkiDialogShape: Shape = RoundedCornerShape(28.dp)

/** Menus follow the UI page's corner radius, like the rest of the chrome. */
@Composable
fun sokkiMenuShape(): Shape = RoundedCornerShape(LocalSokkiUi.current.cornerRadiusDp.dp)

/**
 * [AlertDialog] that is actually visible against a black background. Same signature; the border,
 * the container colour and the shape are the only things filled in for you.
 */
@Composable
fun SokkiAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        // The border goes on the caller's modifier chain, not under it, so a call site that adds
        // its own sizing still gets the outline.
        modifier = modifier.border(borderWidth(), palette.border.toComposeColor(), SokkiDialogShape),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = SokkiDialogShape,
        containerColor = palette.menuBg.toComposeColor(),
        titleContentColor = palette.text.toComposeColor(),
        textContentColor = palette.text.toComposeColor(),
        properties = properties,
    )
}

/**
 * [DropdownMenu] with the same outline. Menus are anchored to the control that opened them, so
 * they read as "attached" even unbordered — but on a black panel their extent is still guesswork
 * without one, and a popup you cannot see the edge of is a popup you cannot tell has scrolled.
 */
@Composable
fun SokkiDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        properties = properties,
        shape = sokkiMenuShape(),
        containerColor = palette.menuBg.toComposeColor(),
        border = sokkiSurfaceBorder(),
        content = content,
    )
}

/**
 * The Snackbar, outlined like everything else that floats. Its colours come from the theme's
 * inverse roles (set in `XnotesTheme`); this only adds the edge, since a black card on a black
 * page is the same disappearing act the dialogs were doing.
 */
@Composable
fun SokkiSnackbar(data: SnackbarData) {
    Snackbar(
        snackbarData = data,
        modifier = Modifier
            .padding(12.dp)
            .border(borderWidth(), LocalPalette.current.border.toComposeColor(), sokkiMenuShape()),
        shape = sokkiMenuShape(),
    )
}
