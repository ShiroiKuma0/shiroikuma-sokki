package com.xnotes.settings

import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.Palette
import org.json.JSONObject

/**
 * The 白い熊 速記 UI theme — every chrome attribute the "白い熊 速記 UI" page exposes, in one
 * settable, persisted, exportable block.
 *
 * The house look is the DEFAULT, not an option layered on one: black surfaces, yellow text, yellow
 * borders. Every field below therefore carries a concrete black-yellow value rather than a null
 * "inherit", which is what makes the whole look editable from the page — a row can always show what
 * it is set to, and [ColorSlot.default] is what its reset returns it to.
 *
 * [enabled] is the one escape hatch: off, the app falls back to upstream's accent-derived or
 * Material You chrome, so the fork's look can be compared against stock without losing the edits.
 */
data class SokkiUi(
    val enabled: Boolean = true,

    // ---- colours -------------------------------------------------------------------------------
    val bg: Rgba = ColorSlot.BG.default,
    val panel: Rgba = ColorSlot.PANEL.default,
    val paper: Rgba = ColorSlot.PAPER.default,
    val paperBorder: Rgba = ColorSlot.PAPER_BORDER.default,
    val accent: Rgba = ColorSlot.ACCENT.default,
    val border: Rgba = ColorSlot.BORDER.default,
    val text: Rgba = ColorSlot.TEXT.default,
    val textDim: Rgba = ColorSlot.TEXT_DIM.default,
    val surface: Rgba = ColorSlot.SURFACE.default,
    val surfaceHi: Rgba = ColorSlot.SURFACE_HI.default,
    val menuBg: Rgba = ColorSlot.MENU_BG.default,

    // ---- typography ----------------------------------------------------------------------------
    /** File name of the chosen font inside the app's font store, or "" for the system default. */
    val fontFile: String = "",
    /** Base UI text size in sp. Rows scale off this, so it moves the whole page together. */
    val fontSizeSp: Int = 15,
    /** 100..900, in the usual hundreds. */
    val fontWeight: Int = 400,

    // ---- shape & metrics -----------------------------------------------------------------------
    /** Border thickness in dp. 0 removes borders entirely. */
    val borderWidthDp: Int = 1,
    /** Corner radius in dp. 0 is fully square. */
    val cornerRadiusDp: Int = 8,
    /** Icon edge length in dp. */
    val iconSizeDp: Int = 22,
    /** Vertical padding inside a settings row in dp — the page's density control. */
    val rowPaddingDp: Int = 4,
) {
    fun color(slot: ColorSlot): Rgba = when (slot) {
        ColorSlot.BG -> bg
        ColorSlot.PANEL -> panel
        ColorSlot.PAPER -> paper
        ColorSlot.PAPER_BORDER -> paperBorder
        ColorSlot.ACCENT -> accent
        ColorSlot.BORDER -> border
        ColorSlot.TEXT -> text
        ColorSlot.TEXT_DIM -> textDim
        ColorSlot.SURFACE -> surface
        ColorSlot.SURFACE_HI -> surfaceHi
        ColorSlot.MENU_BG -> menuBg
    }

    fun withColor(slot: ColorSlot, value: Rgba): SokkiUi = when (slot) {
        ColorSlot.BG -> copy(bg = value)
        ColorSlot.PANEL -> copy(panel = value)
        ColorSlot.PAPER -> copy(paper = value)
        ColorSlot.PAPER_BORDER -> copy(paperBorder = value)
        ColorSlot.ACCENT -> copy(accent = value)
        ColorSlot.BORDER -> copy(border = value)
        ColorSlot.TEXT -> copy(text = value)
        ColorSlot.TEXT_DIM -> copy(textDim = value)
        ColorSlot.SURFACE -> copy(surface = value)
        ColorSlot.SURFACE_HI -> copy(surfaceHi = value)
        ColorSlot.MENU_BG -> copy(menuBg = value)
    }

    /**
     * The chrome this theme describes. [base] is what the app would have rendered without us — its
     * `isDark` and `isMaterial` flags are kept so the components that branch on them (rounded
     * Material cards, light-mode contrast fixes) keep behaving, and its accent-derived
     * [Palette.accentDim] is recomputed from OUR accent rather than inherited.
     */
    fun applyTo(base: Palette): Palette {
        if (!enabled) return base
        return base.copy(
            bg = bg,
            panel = panel,
            paper = paper,
            paperBorder = paperBorder,
            accent = accent,
            accentDim = com.xnotes.ui.theme.ColorMath.dim(accent),
            border = border,
            text = text,
            textDim = textDim,
            surface = surface,
            surfaceHi = surfaceHi,
            menuBg = menuBg,
            isDark = true,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("bg", argbHex(bg))
        .put("panel", argbHex(panel))
        .put("paper", argbHex(paper))
        .put("paper_border", argbHex(paperBorder))
        .put("accent", argbHex(accent))
        .put("border", argbHex(border))
        .put("text", argbHex(text))
        .put("text_dim", argbHex(textDim))
        .put("surface", argbHex(surface))
        .put("surface_hi", argbHex(surfaceHi))
        .put("menu_bg", argbHex(menuBg))
        .put("font_file", fontFile)
        .put("font_size_sp", fontSizeSp)
        .put("font_weight", fontWeight)
        .put("border_width_dp", borderWidthDp)
        .put("corner_radius_dp", cornerRadiusDp)
        .put("icon_size_dp", iconSizeDp)
        .put("row_padding_dp", rowPaddingDp)

    companion object {
        /** Forgiving load: any missing or malformed field falls back to its black-yellow default. */
        fun fromJson(o: JSONObject?): SokkiUi {
            if (o == null) return SokkiUi()
            val d = SokkiUi()
            fun col(key: String, fallback: Rgba) = parseArgb(o.optString(key, "")) ?: fallback
            return SokkiUi(
                enabled = o.optBoolean("enabled", d.enabled),
                bg = col("bg", d.bg),
                panel = col("panel", d.panel),
                paper = col("paper", d.paper),
                paperBorder = col("paper_border", d.paperBorder),
                accent = col("accent", d.accent),
                border = col("border", d.border),
                text = col("text", d.text),
                textDim = col("text_dim", d.textDim),
                surface = col("surface", d.surface),
                surfaceHi = col("surface_hi", d.surfaceHi),
                menuBg = col("menu_bg", d.menuBg),
                fontFile = o.optString("font_file", d.fontFile),
                fontSizeSp = o.optInt("font_size_sp", d.fontSizeSp).coerceIn(9, 30),
                fontWeight = o.optInt("font_weight", d.fontWeight).coerceIn(100, 900),
                borderWidthDp = o.optInt("border_width_dp", d.borderWidthDp).coerceIn(0, 8),
                cornerRadiusDp = o.optInt("corner_radius_dp", d.cornerRadiusDp).coerceIn(0, 32),
                iconSizeDp = o.optInt("icon_size_dp", d.iconSizeDp).coerceIn(12, 40),
                rowPaddingDp = o.optInt("row_padding_dp", d.rowPaddingDp).coerceIn(0, 20),
            )
        }
    }
}

/**
 * `#AARRGGBB`. The shared [Rgba.toHex] is 6-digit and drops alpha — it serialises document colours,
 * where the alpha travels separately — but every chrome colour here is alpha-settable from the
 * page's four sliders, so the theme keeps its own eight-digit round-trip rather than widening a
 * format the `.xnote` files depend on.
 */
fun argbHex(c: Rgba): String = "#%02X%02X%02X%02X".format(c.a, c.r, c.g, c.b)

/** Parses `#AARRGGBB` (and bare `#RRGGBB`, taken as opaque). Null when it is neither. */
fun parseArgb(s: String?): Rgba? {
    val h = s?.trim()?.removePrefix("#") ?: return null
    if (h.length != 8 && h.length != 6) return null
    val v = h.toLongOrNull(16) ?: return null
    return if (h.length == 8) {
        Rgba(((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt())
    } else {
        Rgba(((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt(), 255)
    }
}

/** Black — the house background. */
private val BLACK = Rgba(0, 0, 0, 255)

/** #FFFF00 — the house yellow, the same one the launcher icon is traced in. */
private val YELLOW = Rgba(255, 255, 0, 255)

/**
 * The chrome colours the UI page exposes, each with the black-yellow value it starts at and a
 * one-line description of what it actually paints — the page shows that under the row label so a
 * slot is picked by what it does, not by guessing from its name.
 */
enum class ColorSlot(val label: String, val about: String, val default: Rgba) {
    BG("Background", "The window behind everything", BLACK),
    PANEL("Panel", "Sidebar and backstage chrome", BLACK),
    PAPER("Page", "The note page itself", BLACK),
    PAPER_BORDER("Page border", "The outline around the page", YELLOW),
    ACCENT("Accent", "Selection, highlights, active controls", YELLOW),
    BORDER("Border", "Dividers and control outlines", YELLOW),
    TEXT("Text", "Primary labels", YELLOW),
    TEXT_DIM("Dim text", "Secondary and helper lines", Rgba(190, 190, 0, 255)),
    SURFACE("Surface", "Toolbars and raised controls", BLACK),
    SURFACE_HI("Raised surface", "Pressed and hovered controls", Rgba(26, 26, 26, 255)),
    MENU_BG("Menu", "Popup and dropdown backgrounds", BLACK),
}
