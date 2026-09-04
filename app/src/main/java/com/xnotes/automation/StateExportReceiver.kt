package com.xnotes.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.xnotes.settings.SokkiBackup

/**
 * The 保存復元 wire contract (白い熊 自由作業盤 drives every sister app's backup in one run).
 *
 * The receiver does nothing but gate and hand off: run the one gate of v2 §2, validate
 * `items`, start the foreground service, return. Running the export here would risk an ANR inside
 * Android's broadcast window and take the process down mid-write.
 */
class StateExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pkg = app.packageName
        val token = intent.getStringExtra("token")

        when (intent.action) {
            "$pkg.action.EXPORT_STATE" -> {
                val replyAction = intent.getStringExtra("reply_action")
                val replyPackage = intent.getStringExtra("reply_package")
                val replyId = intent.getStringExtra("reply_id")
                // One gate, in one place — and a token sent to an app that does not ask for one
                // is ignored rather than refused (v2 §2).
                AutomationAuth.refuse(app, token)?.let {
                    reply(app, replyAction, replyPackage, replyId, it); return
                }
                val items = intent.getStringExtra("items")
                if (!items.isNullOrBlank()) {
                    val unknown = items.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        .filter { SokkiBackup.Cat.byId(it) == null }
                    if (unknown.isNotEmpty()) {
                        reply(app, replyAction, replyPackage, replyId, "ERROR:unknown category in items: $items"); return
                    }
                }
                val svc = Intent(app, StateExportService::class.java).apply {
                    putExtra("path", intent.getStringExtra("path"))
                    putExtra("items", items)
                    putExtra("progress_action", intent.getStringExtra("progress_action"))
                    putExtra("reply_action", replyAction)
                    putExtra("reply_package", replyPackage)
                    putExtra("reply_id", replyId)
                }
                ContextCompat.startForegroundService(app, svc)
            }

            "$pkg.action.LIST_CATEGORIES" -> {
                val replyAction = intent.getStringExtra("reply_action")
                val replyPackage = intent.getStringExtra("reply_package")
                val replyId = intent.getStringExtra("reply_id")
                AutomationAuth.refuse(app, token)?.let {
                    reply(app, replyAction, replyPackage, replyId, it); return
                }
                // id<TAB>label<TAB>parent<TAB>on|off — our parts are flat, so the parent is empty.
                val lines = SokkiBackup.Cat.entries.joinToString("\n") { c ->
                    "${c.id}\t${c.label}\t\t${if (c.defaultSelected) "on" else "off"}"
                }
                reply(app, replyAction, replyPackage, replyId, "OK:$lines")
            }

            "$pkg.action.CANCEL_EXPORT" -> {
                // Fire-and-forget: answers nothing at all, and is a silent no-op when idle.
                if (AutomationAuth.refuse(app, token) != null) return
                StateExportService.requestCancel(app)
            }
        }
    }

    /** The only reply channel that survives EMUI: a fresh broadcast, never a live Binder. */
    private fun reply(
        context: Context,
        action: String?,
        pkg: String?,
        replyId: String?,
        result: String,
    ) {
        if (action.isNullOrBlank()) return
        context.sendBroadcast(
            Intent(action).apply {
                pkg?.let { setPackage(it) }
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("reply_id", replyId)
                putExtra("result", result)
            },
        )
    }
}
