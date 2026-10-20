package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.xnotes.core.steno.glyph.Glyph
import com.xnotes.format.GlyphJson
import java.io.File
import java.util.UUID

/**
 * The 速記 element catalogue (fork): one `<id>.json` per element in `filesDir/glyphs`, beside it
 * the `<id>.png` crop of the scan it was traced over. Everything is editable — elements are added,
 * renamed, re-traced and deleted at any time — and the directory travels in the backup as its own
 * category.
 *
 * [items] is observable, so the catalogue page redraws as elements come and go.
 */
object GlyphLibrary {

    const val DIR = "glyphs"

    @Volatile
    private var dir: File? = null

    val items: SnapshotStateList<Glyph> = mutableStateListOf()

    fun init(context: Context) {
        val app = context.applicationContext
        dir = File(app.filesDir, DIR).apply { mkdirs() }
        seed(app)
        reload()
    }

    /**
     * The built-in elements (`assets/glyphs`), traced over the Učebnice těsnopisu I. Each is copied
     * into the catalogue the first time this build sees it, and from then on it is the user's like
     * any other — edited, re-traced or deleted, and a deleted one stays deleted. A built-in the
     * user never touched (an app save always stamps [Glyph.modified]) follows the shipped form when
     * a later build corrects it.
     */
    private fun seed(app: Context) {
        val d = dir ?: return
        val prefs = app.getSharedPreferences("glyph_library", Context.MODE_PRIVATE)
        val seeded = prefs.getStringSet("seeded", emptySet()).orEmpty().toMutableSet()
        val names = runCatching { app.assets.list(DIR) }.getOrNull() ?: return
        var changed = false
        for (n in names.filter { it.endsWith(".json") }) {
            val id = n.removeSuffix(".json")
            runCatching {
                val shipped = app.assets.open("$DIR/$n").use { String(it.readBytes(), Charsets.UTF_8) }
                val mine = File(d, n)
                val copy = when {
                    id !in seeded -> !mine.exists()
                    !mine.exists() -> false
                    else -> {
                        val text = mine.readText(Charsets.UTF_8)
                        text != shipped && (GlyphJson.read(text)?.modified ?: 1L) == 0L
                    }
                }
                if (copy) {
                    mine.writeText(shipped, Charsets.UTF_8)
                    val png = "$id.png"
                    if (png in names) app.assets.open("$DIR/$png").use { i -> File(d, png).outputStream().use { i.copyTo(it) } }
                }
                if (seeded.add(id)) changed = true
            }
        }
        if (changed) prefs.edit().putStringSet("seeded", seeded).apply()
    }

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    /** Re-read the directory (after a backup import, say). */
    fun reload() {
        val d = dir ?: return
        val loaded = (d.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { f -> runCatching { GlyphJson.read(f.readText(Charsets.UTF_8)) }.getOrNull() }
            .sortedWith(compareBy({ it.category.ordinal }, { it.reading.lowercase() }))
        onMain {
            items.clear()
            items.addAll(loaded)
        }
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    fun get(id: String): Glyph? = items.firstOrNull { it.id == id }

    /** Store [g] (new or edited), with [crop] as its scan crop when given. Written `.part` first and
     *  renamed, so a crash never leaves half an element. */
    fun save(g: Glyph, crop: Bitmap? = null) {
        val d = dir ?: return
        val now = System.currentTimeMillis()
        val stamped = g.copy(created = if (g.created == 0L) now else g.created, modified = now)
        val part = File(d, stamped.id + ".json.part")
        part.writeText(GlyphJson.write(stamped), Charsets.UTF_8)
        part.renameTo(File(d, stamped.id + ".json"))
        if (crop != null) {
            val png = File(d, stamped.id + ".png.part")
            png.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            png.renameTo(File(d, stamped.id + ".png"))
        }
        onMain {
            val i = items.indexOfFirst { it.id == stamped.id }
            if (i >= 0) items[i] = stamped else items += stamped
            items.sortWith(compareBy({ it.category.ordinal }, { it.reading.lowercase() }))
        }
    }

    /** The observable list is the UI's; it is only ever changed on the main thread. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
    }

    fun delete(id: String) {
        val d = dir ?: return
        File(d, "$id.json").delete()
        File(d, "$id.png").delete()
        onMain { items.removeAll { it.id == id } }
    }

    /** The scan crop [id] was traced over, or null. */
    fun crop(id: String): Bitmap? {
        val f = File(dir ?: return null, "$id.png")
        return if (f.isFile) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
    }
}
