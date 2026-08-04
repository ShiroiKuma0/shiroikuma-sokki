package com.xnotes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import com.xnotes.core.model.Rgba
import com.xnotes.settings.SokkiUi

/** Convert a core [Rgba] to a Compose [Color]. */
fun Rgba.toComposeColor(): Color = Color(r, g, b, a)

val LocalPalette = staticCompositionLocalOf { Palette.dark() }

@Composable
fun XnotesTheme(palette: Palette, ui: SokkiUi = SokkiUi(), content: @Composable () -> Unit) {
    val accent = palette.accent.toComposeColor()
    // The surfaceContainer* roles must come from the palette too: components read them
    // directly (menus draw surfaceContainer), and the darkColorScheme()/lightColorScheme()
    // baselines are purple-seeded constants that ignore the palette entirely.
    val scheme = if (palette.isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = palette.bg.toComposeColor(),
            secondary = accent,
            background = palette.bg.toComposeColor(),
            onBackground = palette.text.toComposeColor(),
            surface = palette.menuBg.toComposeColor(),
            onSurface = palette.text.toComposeColor(),
            surfaceVariant = palette.surface.toComposeColor(),
            onSurfaceVariant = palette.textDim.toComposeColor(),
            outline = palette.border.toComposeColor(),
            surfaceDim = palette.bg.toComposeColor(),
            surfaceBright = palette.surfaceHi.toComposeColor(),
            surfaceContainerLowest = palette.bg.toComposeColor(),
            surfaceContainerLow = palette.panel.toComposeColor(),
            surfaceContainer = palette.menuBg.toComposeColor(),
            surfaceContainerHigh = palette.surface.toComposeColor(),
            surfaceContainerHighest = palette.surfaceHi.toComposeColor(),
            // The inverse roles are what a Snackbar draws itself with. Left at the baseline they
            // are a light grey card, which in a black app reads as a bug rather than a message.
            inverseSurface = palette.menuBg.toComposeColor(),
            inverseOnSurface = palette.text.toComposeColor(),
            inversePrimary = accent,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = accent,
            background = palette.bg.toComposeColor(),
            onBackground = palette.text.toComposeColor(),
            surface = palette.menuBg.toComposeColor(),
            onSurface = palette.text.toComposeColor(),
            surfaceVariant = palette.surface.toComposeColor(),
            onSurfaceVariant = palette.textDim.toComposeColor(),
            outline = palette.border.toComposeColor(),
            surfaceDim = palette.surface.toComposeColor(),
            surfaceBright = palette.paper.toComposeColor(),
            surfaceContainerLowest = palette.paper.toComposeColor(),
            surfaceContainerLow = palette.menuBg.toComposeColor(),
            surfaceContainer = palette.menuBg.toComposeColor(),
            surfaceContainerHigh = palette.panel.toComposeColor(),
            surfaceContainerHighest = palette.bg.toComposeColor(),
            inverseSurface = palette.text.toComposeColor(),
            inverseOnSurface = palette.paper.toComposeColor(),
            inversePrimary = accent,
        )
    }
    // The UI theme's font and text metrics ride on MaterialTheme.typography, which is what the
    // chrome's Text/Button/Menu components read — so a font or size change lands app-wide rather
    // than only where the UI page happens to draw.
    val typography = rememberUiTypography(ui)
    CompositionLocalProvider(LocalPalette provides palette, LocalSokkiUi provides ui) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

val LocalSokkiUi = staticCompositionLocalOf { SokkiUi() }

/**
 * Rescale the default type ramp around the theme's base size and restyle it with the theme's font
 * and weight. Every role keeps its RELATIVE size (a title stays larger than a label) — the base
 * size moves the whole ramp together, which is what makes one slider a legible density control.
 */
@Composable
private fun rememberUiTypography(ui: SokkiUi): Typography {
    val family = UiFonts.family(ui.fontFile)
    val weight = FontWeight(ui.fontWeight.coerceIn(100, 900))
    return remember(ui.fontFile, ui.fontSizeSp, ui.fontWeight) {
        val base = Typography()
        val k = ui.fontSizeSp / 15f          // 15sp is the shipped bodyLarge size
        fun TextStyle.scaled(): TextStyle = copy(
            fontFamily = family ?: fontFamily,
            fontWeight = weight,
            fontSize = if (fontSize.isSpecified) fontSize * k else fontSize,
            lineHeight = if (lineHeight.isSpecified) lineHeight * k else lineHeight,
        )
        base.copy(
            displayLarge = base.displayLarge.scaled(), displayMedium = base.displayMedium.scaled(),
            displaySmall = base.displaySmall.scaled(), headlineLarge = base.headlineLarge.scaled(),
            headlineMedium = base.headlineMedium.scaled(), headlineSmall = base.headlineSmall.scaled(),
            titleLarge = base.titleLarge.scaled(), titleMedium = base.titleMedium.scaled(),
            titleSmall = base.titleSmall.scaled(), bodyLarge = base.bodyLarge.scaled(),
            bodyMedium = base.bodyMedium.scaled(), bodySmall = base.bodySmall.scaled(),
            labelLarge = base.labelLarge.scaled(), labelMedium = base.labelMedium.scaled(),
            labelSmall = base.labelSmall.scaled(),
        )
    }
}
