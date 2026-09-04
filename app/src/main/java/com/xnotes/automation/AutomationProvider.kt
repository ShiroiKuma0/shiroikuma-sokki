package com.xnotes.automation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.xnotes.settings.SokkiBackup

/**
 * The data door: export this app's own state, and put it back, for a caller we can identify.
 *
 * ## Why a provider and not the broadcast receiver next to it
 *
 * **A broadcast cannot tell you who sent it.** v1's answer to that was a shared secret, which
 * cannot survive the wipe this feature exists to recover from. A provider gets the caller's
 * identity from the framework — see [AutomationCallers] for what is actually checked, and why a
 * `shiroikuma.*` prefix would have been weaker than the token it replaces.
 *
 * **And a list needs a synchronous answer.** 応用管理 draws a row per installed app before any
 * export exists; a broadcast round trip per app to fill a list is the wrong shape.
 *
 * ## What does NOT happen here
 *
 * The payload. [call] validates, starts a foreground service and returns — this app can carry
 * imported fonts and a code theme, so tens of megabytes over minutes inside a binder call would
 * block the caller, report no progress, refuse cancellation and die silently if the process were
 * killed. The bytes go through a descriptor the caller opened; the terminal answer comes back on
 * the broadcast the family already proved on EMUI.
 *
 * ## Why a descriptor and not a path
 *
 * A backup is not a stable directory while it is being assembled: 応用管理 writes into a temporary
 * path and renames on commit, and it encrypts and checksums **per file it knows about**. A file
 * this app dropped in itself would be renamed out from under it, would sit in plaintext inside an
 * otherwise encrypted backup, and would be unverified rather than verified-and-failing. A
 * descriptor is also a capability that expires when it is closed.
 *
 * It also means the automation path no longer needs `MANAGE_EXTERNAL_STORAGE` — which this app has
 * always deliberately refused to declare (see `SokkiBackup`, and the SAF-only rule in CLAUDE.md).
 */
class AutomationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * Every method answers a [Bundle] with [KEY_RESULT] — `OK…` or `ERROR:…`, the same vocabulary
     * the broadcast contract uses, so a caller has one grammar to parse rather than two.
     *
     * A refusal is returned, never thrown: an exception across a binder reaches the caller as a
     * `RuntimeException` with our stack trace in it, which tells 白い熊 nothing and tells a
     * misbehaving caller rather more than it should.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context?.applicationContext ?: return fail("ERROR:not ready")

        // WHO, before WHAT. A caller we cannot identify gets the same answer whatever it asked for.
        when (val verdict = AutomationCallers.verify(ctx, callingPackage)) {
            is AutomationCallers.Verdict.Refused -> return fail(verdict.why)
            AutomationCallers.Verdict.Allowed -> Unit
        }
        // Then this app's own switches — a token is ignored unless this app asks for one.
        AutomationAuth.refuse(ctx, extras?.getString(KEY_TOKEN))?.let { return fail(it) }

        return when (method) {
            METHOD_DESCRIBE -> ok(describe(ctx))
            METHOD_EXPORT -> start(ctx, extras, importing = false)
            METHOD_IMPORT -> start(ctx, extras, importing = true)
            METHOD_CANCEL -> {
                AutomationJobs.cancel(extras?.getString(KEY_JOB_ID))
                ok("OK:cancelled")
            }
            else -> fail("ERROR:unknown method: $method")
        }
    }

    /**
     * What this app would export, answered without exporting anything.
     *
     * Returned from the call rather than written into the archive: 応用管理 must draw a row before
     * an export exists, and at restore must judge compatibility **before** streaming megabytes into
     * an app that would reject them — which it cannot do if the header is buried inside an
     * encrypted archive.
     */
    private fun describe(ctx: Context): String {
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION") val code = pkg.versionCode
        val contains = containsStrings().joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        return "OK:" + """
            {"app_id":"${ctx.packageName}","version_code":$code,
             "version_name":"${pkg.versionName}","format":$FORMAT,
             "min_format_readable":$MIN_FORMAT_READABLE,"requires_launch_first":false,
             "contains":[$contains]}
        """.trimIndent().replace("\n", "")
    }

    /**
     * The `contains` list, which 応用管理 renders **verbatim** to 白い熊 — so it says what the
     * archive holds in words, and says what it does not.
     *
     * The last entry is the one that matters. 白い熊 速記's notebooks are `.xnote` files in the
     * folder picked in the explorer (or in this app's own external files directory when internal
     * storage was chosen), and `SokkiBackup` has never carried them: it backs up everything
     * *settable*, plus imported fonts and the code theme. Handwriting is authored text — the one
     * thing no device can re-supply and the whole reason a clean-phone restore exists — so a row
     * that let 白い熊 believe the notes were inside would be the most expensive kind of wrong.
     */
    private fun containsStrings(): List<String> =
        SokkiBackup.Cat.defaultSet.map { it.label } + (
            "NOT included: the handwritten notes themselves (.xnote files) — they live in the " +
                "folder picked in the explorer and must be backed up from there"
            )

    /**
     * Hand the descriptor to a foreground service and get out of the way.
     *
     * The descriptor is **duplicated** before it leaves this method. The one in [extras] belongs to
     * the binder transaction and is closed when `call()` returns; a service reading it afterwards
     * would find it shut — a bug you only see under load, so it is not left to the service to
     * remember.
     */
    private fun start(ctx: Context, extras: Bundle?, importing: Boolean): Bundle {
        @Suppress("DEPRECATION")
        val fd = extras?.getParcelable<ParcelFileDescriptor>(KEY_FD)
            ?: return fail("ERROR:no descriptor")
        val dup = runCatching { fd.dup() }.getOrNull() ?: return fail("ERROR:descriptor unusable")
        val jobId = AutomationJobs.begin()
        runCatching { AutomationDataService.start(ctx, jobId, dup, importing, extras) }
            .onFailure {
                // Nothing will close the copy if the service never starts, and a leaked descriptor
                // holds the caller's file open — which is precisely what stops it checksumming it.
                runCatching { dup.close() }
                AutomationJobs.finish(jobId)
                return fail("ERROR:${it.message ?: it.javaClass.simpleName}")
            }
        return ok("OK:$jobId")
    }

    private fun ok(result: String) = Bundle().apply { putString(KEY_RESULT, result) }
    private fun fail(why: String) = Bundle().apply { putString(KEY_RESULT, why) }

    // A provider that is only ever `call()`ed still has to answer these. Refusing loudly beats
    // returning an empty cursor, which reads downstream as "there is no data" rather than "wrong
    // door".
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? =
        throw UnsupportedOperationException("automation is call() only")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("automation is call() only")
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    companion object {
        const val METHOD_DESCRIBE = "describe"
        const val METHOD_EXPORT = "export"
        const val METHOD_IMPORT = "import"
        const val METHOD_CANCEL = "cancel"

        const val KEY_RESULT = "result"
        const val KEY_FD = "fd"
        const val KEY_TOKEN = "token"
        const val KEY_JOB_ID = "job_id"
        const val KEY_ITEMS = "items"
        const val KEY_REPLY_ACTION = "reply_action"
        const val KEY_REPLY_PACKAGE = "reply_package"
        const val KEY_PROGRESS_ACTION = "progress_action"

        /** This app's archive format — `SokkiBackup.VERSION`, so there is one number, not two. */
        const val FORMAT = SokkiBackup.VERSION

        /**
         * The oldest archive this build can still read.
         *
         * Version skew has a direction: old data into a newer app is normally fine, because an app
         * migrates its own storage; newer data into an older app is not. This is what lets a caller
         * refuse the second case at discovery time, before anything is streamed.
         */
        const val MIN_FORMAT_READABLE = 1
    }
}
