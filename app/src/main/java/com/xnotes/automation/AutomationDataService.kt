package com.xnotes.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.xnotes.R
import com.xnotes.platform.JsonStore
import com.xnotes.settings.SokkiBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a data-door export or import actually runs.
 *
 * ## Why a foreground service and not the provider call
 *
 * The call returns in milliseconds; this can run for minutes — 白い熊 速記 carries imported font
 * files and a code theme, so the archive is not bounded by "a few seconds". Two hard reasons it
 * cannot be done anywhere cheaper:
 *
 * - **A binder call holds the caller.** 応用管理 is drawing a list; a multi-minute synchronous call
 *   would freeze its UI, report no progress, and refuse cancellation.
 * - **A backgrounded app writing for minutes is frozen mid-stream on this phone**, which yields a
 *   truncated archive underneath a success reply — the worst possible failure, because it is
 *   indistinguishable from a good backup until the day it is restored.
 *
 * ## The descriptor
 *
 * Already duplicated by [AutomationProvider] before it got here, because the original belongs to
 * the binder transaction and is closed the moment `call()` returns. This service owns the copy and
 * closes it in a `finally` — leaking one would hold the caller's file open indefinitely, and the
 * caller cannot checksum or encrypt a file that is still open.
 */
class AutomationDataService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val importing = intent?.getBooleanExtra(EXTRA_IMPORTING, false) ?: false
        val jobId = intent?.getStringExtra(EXTRA_JOB)
        // Drained here and nowhere else, so from this line on exactly one owner holds the caller's
        // descriptor — and that owner closes it, on every path out including a startForeground that
        // throws. Left in the map it would hold the caller's file open forever, and a caller cannot
        // checksum or encrypt a file that is still open.
        val fd = jobId?.let { HANDOVER.remove(it) }
        try {
            // The system started us with startForegroundService and wants a notification within
            // 5 s, whether or not the request turns out to be usable.
            startForeground(NOTIFICATION_ID, notification(importing))
        } catch (t: Throwable) {
            runCatching { fd?.close() }
            jobId?.let { AutomationJobs.finish(it) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (jobId == null || fd == null) return stop(startId)

        val replyAction = intent.getStringExtra(AutomationProvider.KEY_REPLY_ACTION)
        val replyPackage = intent.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE)
        val progressAction = intent.getStringExtra(AutomationProvider.KEY_PROGRESS_ACTION)
        val items = intent.getStringExtra(AutomationProvider.KEY_ITEMS)

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            // Exactly one terminal answer per job, whatever path got here — a synchronous failure
            // and an asynchronous success must never both fire. The same guard the broadcast
            // contract has carried since the first sister app.
            if (!replied.compareAndSet(false, true)) return
            AutomationJobs.finish(jobId)
            if (replyAction.isNullOrEmpty() || replyPackage.isNullOrEmpty()) return
            sendBroadcast(
                Intent(replyAction).apply {
                    setPackage(replyPackage)
                    // Without this a backgrounded caller never hears the answer, and on a clean
                    // phone the caller may not have been launched at all.
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(AutomationProvider.KEY_JOB_ID, jobId)
                    putExtra(AutomationProvider.KEY_RESULT, result)
                },
            )
        }

        scope.launch {
            try {
                fd.use { open ->
                    if (importing) runImport(open, jobId, ::reply)
                    else runExport(open, jobId, items, progressAction, replyPackage, ::reply)
                }
            } catch (e: SokkiBackup.Cancelled) {
                reply("ERROR:cancelled")
            } catch (t: Throwable) {
                reply("ERROR:${t.message ?: t.javaClass.simpleName}")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun runExport(
        fd: ParcelFileDescriptor,
        jobId: String,
        items: String?,
        progressAction: String?,
        replyPackage: String?,
        reply: (String) -> Unit,
    ) {
        val cats = resolve(items) ?: run { reply("ERROR:unknown category in items: $items"); return }
        val settingsJson = JsonStore.settings(applicationContext).read()
        var written = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            // Counted as it goes rather than stat'ed afterwards: the caller owns the file and we may
            // not be able to see it at all — it can be an anonymous pipe or a descriptor into a
            // directory this app cannot list.
            val counting = object : OutputStream() {
                override fun write(b: Int) { out.write(b); written++ }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len); written += len
                }
                override fun flush() = out.flush()
            }
            SokkiBackup.export(
                context = this,
                settingsJson = settingsJson,
                cats = cats,
                out = counting,
                cancelled = { AutomationJobs.isCancelled(jobId) },
                onProgress = { p -> progress(progressAction, replyPackage, jobId, p) },
            )
        }
        if (AutomationJobs.isCancelled(jobId)) reply("ERROR:cancelled")
        else reply("OK:$written|${cats.size} categories")
    }

    private fun runImport(fd: ParcelFileDescriptor, jobId: String, reply: (String) -> Unit) {
        // Spooled to disk, not held in the heap: a 白い熊 速記 archive carries every imported font
        // file and the code theme, so it is measured in tens of megabytes and readBytes() on a
        // restore is an OutOfMemoryError waiting for the largest backup 白い熊 owns.
        val spool = File.createTempFile("automation-import", ".zip", cacheDir)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                spool.outputStream().use { input.copyTo(it) }
            }
            if (spool.length() == 0L) { reply("ERROR:empty archive"); return }
            if (AutomationJobs.isCancelled(jobId)) { reply("ERROR:cancelled"); return }
            importFrom(spool, reply)
        } finally {
            spool.delete()
        }
    }

    /**
     * The archive is read to the end before anything is written back.
     *
     * Not merely convenient: a read that failed halfway would otherwise import half an archive, and
     * a half-restored app is worse than one that refused.
     */
    private fun importFrom(spool: File, reply: (String) -> Unit) {
        val store = JsonStore.settings(applicationContext)
        val result = spool.inputStream().use { SokkiBackup.import(this, store.read(), it) }
        // Absent categories are skipped rather than reset, so restoring a settings-only archive
        // does not wipe the imported fonts — the merge is SokkiBackup's, not repeated here.
        if (result.categories.isEmpty()) { reply("ERROR:archive carries no categories"); return }
        store.write(result.merged)
        // 応用管理 force-stops us straight after this, deliberately: a running process writes its
        // cached settings back out at orderly shutdown and would silently undo the import that just
        // happened. The guarantee lives on the caller's side so forty-two apps need not remember it.
        reply("OK:${result.categories.size} restored")
    }

    /**
     * §3 progress: real counts, never a percentage. Throttled to one every 500 ms, with the final
     * category always going out.
     */
    private fun progress(
        progressAction: String?,
        replyPackage: String?,
        jobId: String,
        p: SokkiBackup.Progress,
    ) {
        // BOTH, or none. Since API 26 an implicit broadcast is not delivered to a manifest-declared
        // receiver at all, so a progress line without setPackage does not arrive weakly — it does
        // not arrive. The two extras are not independent optionals: progress_action is useless
        // without reply_package, and sending anyway would only look like progress in our own logs.
        if (progressAction.isNullOrBlank() || replyPackage.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastProgress < 500 && p.index != p.total) return
        lastProgress = now
        sendBroadcast(
            Intent(progressAction).apply {
                setPackage(replyPackage)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(AutomationProvider.KEY_JOB_ID, jobId)
                // Also as reply_id, so a caller parsing the §1/§3 shape reads the same correlation.
                putExtra("reply_id", jobId)
                putExtra("app", getString(R.string.app_name))
                putExtra("item", p.cat.id)
                putExtra("text", "区分 ${p.index}/${p.total} — ${p.cat.label}")
                putExtra("current", p.index.toLong())
                putExtra("total", p.total.toLong())
                putExtra("unit", "区分")
            },
        )
    }

    /** Absent/blank `items` means the contract's DEFAULT set, not everything. */
    private fun resolve(items: String?): List<SokkiBackup.Cat>? {
        if (items.isNullOrBlank()) return SokkiBackup.Cat.defaultSet
        val wanted = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return wanted.map { SokkiBackup.Cat.byId(it) ?: return null }
    }

    private fun notification(importing: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "自動化データ", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (importing) "データを戻しています" else "データを書き出しています")
            .setSmallIcon(
                if (importing) android.R.drawable.stat_sys_download
                else android.R.drawable.stat_sys_upload,
            )
            .setOngoing(true)
            .build()
    }

    private fun stop(startId: Int): Int {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    @Volatile
    private var lastProgress = 0L

    companion object {
        private const val CHANNEL = "sokki_automation_data"
        private const val NOTIFICATION_ID = 4802
        private const val EXTRA_JOB = "job"
        private const val EXTRA_IMPORTING = "importing"

        /**
         * The descriptor's way across, because an Intent is the wrong vehicle for one.
         *
         * A `ParcelFileDescriptor` in an Intent extra is duplicated by the system on delivery and
         * the copy's lifetime stops being ours to reason about. Handing it through a map keyed by
         * the job id keeps exactly one open descriptor with exactly one owner — this service, which
         * closes it in a `finally`.
         */
        private val HANDOVER = ConcurrentHashMap<String, ParcelFileDescriptor>()

        fun start(
            context: Context,
            jobId: String,
            fd: ParcelFileDescriptor,
            importing: Boolean,
            extras: Bundle?,
        ) {
            HANDOVER[jobId] = fd
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AutomationDataService::class.java).apply {
                        putExtra(EXTRA_JOB, jobId)
                        putExtra(EXTRA_IMPORTING, importing)
                        putExtra(AutomationProvider.KEY_ITEMS, extras?.getString(AutomationProvider.KEY_ITEMS))
                        putExtra(
                            AutomationProvider.KEY_REPLY_ACTION,
                            extras?.getString(AutomationProvider.KEY_REPLY_ACTION),
                        )
                        putExtra(
                            AutomationProvider.KEY_REPLY_PACKAGE,
                            extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE),
                        )
                        putExtra(
                            AutomationProvider.KEY_PROGRESS_ACTION,
                            extras?.getString(AutomationProvider.KEY_PROGRESS_ACTION),
                        )
                    },
                )
            } catch (t: Throwable) {
                // The service never started, so nothing will ever take the descriptor out of the
                // handover map; drop it here rather than leak the caller's open file.
                HANDOVER.remove(jobId)
                throw t
            }
        }
    }
}
