package com.xnotes.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.documentfile.provider.DocumentFile
import com.xnotes.R
import com.xnotes.platform.JsonStore
import com.xnotes.settings.SokkiBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where an automation export actually runs.
 *
 * NOT in the receiver: a manifest receiver must reach `finish()` inside Android's broadcast window
 * (~10 s foreground, ~60 s otherwise) whether or not it holds a `goAsync()` PendingResult, and an
 * overrun is an ANR against us — killed mid-export, nothing replied, a half-written file left on
 * disk. This app can carry imported fonts and a code theme, so the export is not bounded by "a few
 * seconds" and belongs on a foreground service.
 */
class StateExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: run { stopSelf(); return START_NOT_STICKY }

        val replyAction = i.getStringExtra("reply_action")
        val replyPackage = i.getStringExtra("reply_package")
        val replyId = i.getStringExtra("reply_id")
        val progressAction = i.getStringExtra("progress_action")
        val pathOverride = i.getStringExtra("path")
        val items = i.getStringExtra("items")

        // The extras are read BEFORE going foreground, because this call is the second place the
        // background-start restriction bites: the receiver's startForegroundService can be allowed
        // and this still refused, and without the reply details in hand there would be nothing to
        // answer the refusal with. A service that dies here without replying is the silent
        // no-export the caller cannot tell apart from an unimplemented contract.
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            broadcastReply(replyAction, replyPackage, replyId, "ERROR:${e.message ?: e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch {
            val replied = AtomicBoolean(false)
            fun reply(result: String) {
                if (!replied.compareAndSet(false, true)) return
                broadcastReply(replyAction, replyPackage, replyId, result)
            }
            if (!running.compareAndSet(false, true)) {
                reply("ERROR:export already running")
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                return@launch
            }
            cancelRequested = false
            try {
                val cats = parseItems(items) ?: run {
                    reply("ERROR:unknown category in items: $items")
                    return@launch
                }
                val written = runExport(pathOverride, cats, replyId, progressAction, replyPackage)
                reply("OK:${written.path}|${written.bytes}|${SokkiBackup.humanSize(written.bytes)}|${written.count} categories")
            } catch (e: SokkiBackup.Cancelled) {
                reply("ERROR:cancelled")
            } catch (e: Exception) {
                reply("ERROR:${e.message ?: e.javaClass.simpleName}")
            } finally {
                running.set(false)
                cancelRequested = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** The only reply channel that survives EMUI: a fresh broadcast, never a live Binder. */
    private fun broadcastReply(action: String?, pkg: String?, replyId: String?, result: String) {
        if (action.isNullOrBlank()) return
        sendBroadcast(
            Intent(action).apply {
                pkg?.let { setPackage(it) }
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("reply_id", replyId)
                putExtra("result", result)
            },
        )
    }

    private class Written(val path: String, val bytes: Long, val count: Int)

    /** Absent/blank `items` means the contract's DEFAULT set, not everything. */
    private fun parseItems(items: String?): List<SokkiBackup.Cat>? {
        if (items.isNullOrBlank()) return SokkiBackup.Cat.defaultSet
        val ids = items.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val cats = ids.map { SokkiBackup.Cat.byId(it) ?: return null }
        return cats
    }

    private fun runExport(
        pathOverride: String?,
        cats: List<SokkiBackup.Cat>,
        replyId: String?,
        progressAction: String?,
        replyPackage: String?,
    ): Written {
        val settingsJson = JsonStore.settings(applicationContext).read() ?: org.json.JSONObject()
        val name = SokkiBackup.timestampedName()
        var lastProgress = 0L
        val onProgress: (SokkiBackup.Progress) -> Unit = { p ->
            val now = System.currentTimeMillis()
            // Throttled to one every 500 ms; the completion one always goes out.
            // BOTH extras or none: since API 26 an implicit broadcast is not delivered to a
            // manifest-declared receiver at all, so a progress line without setPackage does not
            // arrive weakly — it does not arrive.
            if (progressAction != null && replyPackage != null &&
                (now - lastProgress >= 500 || p.index == p.total)
            ) {
                lastProgress = now
                sendBroadcast(
                    Intent(progressAction).apply {
                        setPackage(replyPackage)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra("reply_id", replyId)
                        putExtra("app", getString(R.string.app_name))
                        putExtra("item", p.cat.id)
                        putExtra("text", "区分 ${p.index}/${p.total} — ${p.cat.label}")
                        putExtra("current", p.index.toLong())
                        putExtra("total", p.total.toLong())
                        putExtra("unit", "区分")
                    },
                )
            }
        }
        val cancelled = { cancelRequested }

        // Precedence: the `path` extra, then our configured SAF directory, then a hard error.
        val absolute = pathOverride?.takeIf { it.isNotBlank() }
        if (absolute != null) {
            if (!hasAllFilesAccess()) {
                // Only ignorable when we have a directory of our own; otherwise it is fatal.
                if (SokkiBackup.backupDirUri(this) == null) throw IllegalStateException("no-storage-access")
            } else {
                val dir = File(absolute).apply { mkdirs() }
                val part = File(dir, "$name.part")
                val final = File(dir, name)
                try {
                    val count = part.outputStream().use {
                        SokkiBackup.export(this, settingsJson, cats, it, cancelled, onProgress)
                    }
                    if (!part.renameTo(final)) throw IllegalStateException("could not finish $name")
                    return Written(final.absolutePath, final.length(), count)
                } catch (e: Exception) {
                    part.delete()
                    throw e
                }
            }
        }

        val treeUri = SokkiBackup.backupDirUri(this) ?: throw IllegalStateException("no-directory")
        return writeToTree(treeUri, name, settingsJson, cats, cancelled, onProgress)
    }

    /**
     * The SAF path. Written as `<name>.zip.part` and renamed only once complete, so a killed export
     * never leaves something a restore would mistake for a real backup.
     */
    private fun writeToTree(
        treeUri: Uri,
        name: String,
        settingsJson: org.json.JSONObject,
        cats: List<SokkiBackup.Cat>,
        cancelled: () -> Boolean,
        onProgress: (SokkiBackup.Progress) -> Unit,
    ): Written {
        val dir = DocumentFile.fromTreeUri(this, treeUri) ?: throw IllegalStateException("no-directory")
        dir.findFile("$name.part")?.delete()
        val part = dir.createFile("application/octet-stream", "$name.part")
            ?: throw IllegalStateException("could not create $name")
        try {
            val count = contentResolver.openOutputStream(part.uri)?.use {
                SokkiBackup.export(this, settingsJson, cats, it, cancelled, onProgress)
            } ?: throw IllegalStateException("could not write $name")
            if (!part.renameTo(name)) throw IllegalStateException("could not finish $name")
            val bytes = dir.findFile(name)?.length() ?: 0L
            return Written("${treeUri}/$name", bytes, count)
        } catch (e: Exception) {
            runCatching { part.delete() }
            throw e
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Backup export", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Exporting…")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "sokki_export"
        private const val NOTIF_ID = 4801

        /** Process-local, never persisted: a stuck flag on disk would wedge exports for good. */
        private val running = AtomicBoolean(false)

        @Volatile
        private var cancelRequested = false

        /** Signal the running export to unwind at its next category boundary. Safe at any time. */
        fun requestCancel(context: Context) {
            if (running.get()) cancelRequested = true
        }
    }
}
