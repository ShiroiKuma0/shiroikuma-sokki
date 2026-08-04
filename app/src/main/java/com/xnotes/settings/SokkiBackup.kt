package com.xnotes.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The 白い熊 速記 backup — one category ZIP holding everything the app lets you set.
 *
 * The family shape (17 sister apps and the 保存復元 contract): `manifest.json` describing the
 * archive, then one `<id>.json` per category, plus the binary stores as their own entries. Import
 * MERGES per key and skips absent categories, so a partial backup restores exactly its own parts.
 *
 * The engine is deliberately headless — [export] takes an [OutputStream] and a progress callback,
 * knows nothing about Compose, and is driven identically by the Export/Import panel and by the
 * automation receiver. There is no second copy of this logic anywhere.
 */
object SokkiBackup {

    const val FORMAT = "shiroikuma-sokki-backup"
    const val VERSION = 1

    /** The mandatory family naming convention: `<english-app-name>_<stamp>.zip`, nothing else. */
    const val EXPORT_PREFIX = "shiroikuma-sokki_"

    /** Thrown out of the write loop when a cancel lands; the partial file is removed by the caller. */
    class Cancelled : Exception("cancelled")

    /**
     * One selectable part of the backup. [keys] are the top-level `settings.json` keys it owns;
     * [files] marks the categories whose payload is a file store rather than settings JSON.
     *
     * [defaultSelected] is what the automation contract's fourth `LIST_CATEGORIES` field reports:
     * everything authored stays on, and only what can be made again is off by default.
     */
    enum class Cat(
        val id: String,
        val label: String,
        val keys: List<String> = emptyList(),
        val files: Boolean = false,
        val defaultSelected: Boolean = true,
    ) {
        UI("ui", "白い熊 速記 UI (colours · fonts · sizes)", listOf("sokki_ui")),
        PREFS("prefs", "Preferences (appearance · page · stylus · zoom)", listOf("prefs")),
        TOOLS(
            "tools", "Tools & toolbars",
            listOf("tools", "shape_config", "toolbar_colors", "toolbar_color_count", "toolbar_layout", "canvas_toolbar_layout", "active_color", "recent_colors"),
        ),
        VIEW("view", "View defaults", listOf("view_defaults", "render_scale")),
        TEXT("text", "New-note text & page defaults", listOf("new_note_style", "new_note_flow")),
        EXPLORER("explorer", "Explorer (folder · sorting · start-up)", listOf("browse_root", "explorer_sort_key", "explorer_sort_descending", "start_on_home", "sidebar_visible")),
        PRESENTATION("presentation", "Presentation server", listOf("presentation")),
        FONTS("fonts", "Imported fonts", files = true),
        CODE_THEME("code_theme", "Imported code theme", files = true);

        companion object {
            fun byId(id: String): Cat? = entries.firstOrNull { it.id == id }
            val defaultSet: List<Cat> get() = entries.filter { it.defaultSelected }
        }
    }

    /** Progress as the export walks its categories: [index] is 1-based POSITION, per the contract. */
    class Progress(val cat: Cat, val index: Int, val total: Int)

    fun timestampedName(now: Date = Date()): String =
        EXPORT_PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now) + ".zip"

    fun isBackupFileName(name: String): Boolean =
        name.startsWith(EXPORT_PREFIX) && name.endsWith(".zip")

    // ---- export ---------------------------------------------------------------------------------

    /**
     * Write [cats] of the app's state into [out]. [settingsJson] is the live settings blob;
     * [cancelled] is polled at every category boundary so a cancel unwinds at a safe point rather
     * than tearing a half-written entry.
     */
    fun export(
        context: Context,
        settingsJson: JSONObject,
        cats: List<Cat>,
        out: OutputStream,
        cancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): Int {
        val chosen = cats.ifEmpty { Cat.defaultSet }
        var written = 0
        ZipOutputStream(out.buffered()).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("app", "shiroikuma.sokki")
                .put("created_ts", System.currentTimeMillis())
                .put("categories", org.json.JSONArray(chosen.map { it.id }))
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()

            chosen.forEachIndexed { i, cat ->
                if (cancelled()) throw Cancelled()
                onProgress(Progress(cat, i + 1, chosen.size))
                when (cat) {
                    Cat.FONTS -> writeDir(zip, fontsDir(context), "fonts/", cancelled)
                    Cat.CODE_THEME -> writeCodeTheme(zip, context, settingsJson)
                    else -> {
                        val o = JSONObject()
                        for (k in cat.keys) if (settingsJson.has(k)) o.put(k, settingsJson.get(k))
                        zip.putNextEntry(ZipEntry("${cat.id}.json"))
                        zip.write(o.toString(2).toByteArray())
                        zip.closeEntry()
                    }
                }
                written++
            }
        }
        return written
    }

    private fun writeDir(zip: ZipOutputStream, dir: File, prefix: String, cancelled: () -> Boolean) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        for (f in files) {
            if (cancelled()) throw Cancelled()
            zip.putNextEntry(ZipEntry(prefix + f.name))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun writeCodeTheme(zip: ZipOutputStream, context: Context, settingsJson: JSONObject) {
        val path = settingsJson.optJSONObject("prefs")?.optString("code_theme_path", "").orEmpty()
        if (path.isBlank()) return
        val f = File(path)
        if (!f.isFile) return
        zip.putNextEntry(ZipEntry("code_theme/${f.name}"))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    // ---- import ---------------------------------------------------------------------------------

    class ImportResult(val categories: List<Cat>, val merged: JSONObject)

    /**
     * Read a backup and merge it into [current]. Absent categories are skipped rather than reset —
     * restoring a settings-only archive must not wipe the fonts.
     */
    fun import(context: Context, current: JSONObject, input: InputStream): ImportResult {
        val merged = JSONObject(current.toString())
        val found = LinkedHashSet<Cat>()
        ZipInputStream(input.buffered()).use { zip ->
            var e: ZipEntry? = zip.nextEntry
            while (e != null) {
                val name = e.name
                when {
                    name == "manifest.json" -> Unit
                    name.startsWith("fonts/") && !e.isDirectory -> {
                        found += Cat.FONTS
                        val target = File(fontsDir(context), File(name).name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    name.startsWith("code_theme/") && !e.isDirectory -> {
                        found += Cat.CODE_THEME
                        val target = File(codeThemeDir(context), File(name).name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        val prefs = merged.optJSONObject("prefs") ?: JSONObject().also { merged.put("prefs", it) }
                        prefs.put("code_theme_path", target.absolutePath)
                        prefs.put("code_theme_name", target.name)
                    }
                    name.endsWith(".json") -> {
                        val cat = Cat.byId(name.removeSuffix(".json")) ?: run { e = zip.nextEntry; return@use }
                        val o = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        for (k in cat.keys) if (o.has(k)) merged.put(k, o.get(k))
                        found += cat
                    }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        return ImportResult(found.toList(), merged)
    }

    // ---- where things live -----------------------------------------------------------------------

    fun fontsDir(context: Context): File =
        File(context.applicationContext.filesDir, "fonts").apply { mkdirs() }

    fun codeThemeDir(context: Context): File =
        File(context.applicationContext.filesDir, "code-themes").apply { mkdirs() }

    // ---- the backup directory (device-local; never itself exported) --------------------------------

    private const val PREFS = "sokki_backup"
    private const val KEY_DIR = "backup_dir"

    fun backupDirUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DIR, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setBackupDirUri(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply { if (uri == null) remove(KEY_DIR) else putString(KEY_DIR, uri.toString()) }
            .apply()
    }

    /** The chosen directory as a human path, for the folder box on the panel. */
    fun backupDirLabel(context: Context): String? {
        val uri = backupDirUri(context) ?: return null
        val doc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        return doc?.name ?: uri.lastPathSegment ?: uri.toString()
    }

    /** The newest backup already in the directory — the page's "last backup" line. */
    fun latestBackup(context: Context): Pair<String, Long>? {
        val uri = backupDirUri(context) ?: return null
        val dir = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return null
        return dir.listFiles()
            .filter { it.isFile && isBackupFileName(it.name.orEmpty()) }
            .maxByOrNull { it.lastModified() }
            ?.let { it.name!! to it.length() }
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1_000 -> "%.0f kB".format(bytes / 1e3)
        else -> "$bytes B"
    }
}
