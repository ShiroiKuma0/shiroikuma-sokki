package com.xnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
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
 * Upstream reached the same conclusion in 0.8.20 and now shadows material3's `DropdownMenu` and
 * `AlertDialog` for every same-package caller ([Popups]), so the fork no longer carries wrappers
 * or touches the call sites at all: those two funnels simply draw [sokkiSurfaceBorder] instead of
 * a hardcoded hairline, which is what puts the UI page's Border colour and Border width back in
 * charge of every dialog and menu. Only the snackbar, which upstream does not funnel, is still
 * wrapped here.
 */

/** Border thickness for a floating surface, honouring the UI page's own control. */
@Composable
private fun borderWidth() = LocalSokkiUi.current.borderWidthDp.dp

/** The stroke every dialog and menu outlines itself with. */
@Composable
fun sokkiSurfaceBorder(): BorderStroke =
    BorderStroke(borderWidth(), LocalPalette.current.border.toComposeColor())

/** Menus follow the UI page's corner radius, like the rest of the chrome. */
@Composable
fun sokkiMenuShape(): Shape = RoundedCornerShape(LocalSokkiUi.current.cornerRadiusDp.dp)

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
