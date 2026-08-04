package com.xnotes.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import com.xnotes.core.pal.FontFace
import com.xnotes.platform.FontCatalog
import java.util.concurrent.ConcurrentHashMap

/**
 * The chrome's font, as a Compose [FontFamily].
 *
 * Resolution is delegated wholesale to [FontCatalog] — the same catalog the document text uses —
 * so the UI page inherits its 19 bundled families, its generic tokens AND its user-imported fonts
 * (`filesDir/fonts`, adopted through the existing SAF import) without a second font store to keep
 * in sync. Only the Android→Compose wrapping lives here.
 */
object UiFonts {
    private val cache = ConcurrentHashMap<String, FontFamily>()

    /** The family for a stored font id; blank (the default) means the platform's own sans. */
    fun family(fontId: String): FontFamily? {
        if (fontId.isBlank()) return null
        return cache.getOrPut(fontId) {
            val tf = FontCatalog.resolve(FontFace(fontId), bold = false, italic = false).typeface
            FontFamily(Typeface(tf))
        }
    }

    /** Every pickable font, in catalog order: generic tokens, then bundled, then imported. */
    fun choices(): List<FontCatalog.Choice> = FontCatalog.choices()

    /** The label to show for a stored id, or "System" when nothing is chosen. */
    fun label(fontId: String): String =
        if (fontId.isBlank()) "System" else FontCatalog.label(FontFace(fontId))

    /** Drop cached families for imported fonts after an import or a delete. */
    fun invalidate() = cache.clear()
}
