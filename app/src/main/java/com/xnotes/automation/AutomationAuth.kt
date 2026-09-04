package com.xnotes.automation

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The gate on the 保存復元 automation contract: a master switch (default ON) and a token that is
 * only asked for when 白い熊 asks for it (default OFF).
 *
 * ## Why the defaults inverted in v2
 *
 * v1 shipped every app closed — the switch was off and a caller also had to present a 48-character
 * secret pasted from this app's settings into the caller's. **A pasted secret cannot survive a
 * wipe**, and the case this family now exists to serve is 応用管理 restoring apps *and their data*
 * onto a clean phone, where nothing has been configured and nobody has pasted anything. A gate that
 * only works once the phone is already set up is no gate for setting the phone up.
 *
 * The switch stays, because it is the only way to close this one app off and a feature that can be
 * turned on but never off is one 白い熊 cannot retreat from. The identity check that replaces the
 * token lives in [AutomationCallers], on the door that actually moves data.
 *
 * All three keys live in their OWN SharedPreferences file, deliberately outside every backup
 * category — a token that travelled in an export would be handed to anyone the archive is shared
 * with.
 */
object AutomationAuth {
    private const val PREFS = "sokki_automation"
    private const val KEY_ENABLED = "automation_enabled"
    private const val KEY_REQUIRE_TOKEN = "automation_require_token"
    private const val KEY_TOKEN = "automation_token"

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** Whether a caller must also present the token. Off by default, per v2 of the contract. */
    fun requireToken(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUIRE_TOKEN, false)

    fun setRequireToken(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_TOKEN, on).apply()
    }

    /**
     * The one gate, in one place: `null` means proceed, anything else is the exact `ERROR:` line to
     * answer with.
     *
     * Written once rather than at each entry point because two checks spelled out separately in a
     * receiver, a provider and a service is how "disabled" and "bad token" drift apart across
     * forty-two apps.
     *
     * **A token handed to an app that does not require one is IGNORED, never refused.** Tokens live
     * in task arguments and workspace variables that outlive the setting they were pasted for, and
     * a caller still sending one — because it was configured last year, or because another app on
     * the batch does want one — must be served. Refusing it would turn "白い熊 turned a switch off"
     * into "half the batch mysteriously fails", which is the friction the switch exists to remove.
     */
    fun refuse(context: Context, candidate: String?): String? = when {
        !enabled(context) -> "ERROR:automation disabled"
        requireToken(context) && !isTokenValid(context, candidate) -> "ERROR:bad token"
        else -> null
    }

    /** The token, minted lazily on first read so the settings row always has a value to show. */
    fun token(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return regenerate(context)
    }

    fun regenerate(context: Context): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY_TOKEN, hex).apply()
        return hex
    }

    /** Constant-time compare — a token check must not leak its answer through timing. */
    fun isTokenValid(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        return MessageDigest.isEqual(candidate.toByteArray(), token(context).toByteArray())
    }

    /** `80922d8c…4c49a87c` — what the settings row shows instead of the full 48 characters. */
    fun abbreviated(token: String): String =
        if (token.length <= 20) token else "${token.take(8)}…${token.takeLast(8)}"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
