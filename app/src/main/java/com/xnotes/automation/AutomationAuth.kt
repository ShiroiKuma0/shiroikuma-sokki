package com.xnotes.automation

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The gate on the 保存復元 automation contract: a master switch (default OFF) and a shared token.
 *
 * Both live in their OWN SharedPreferences file, deliberately outside every backup category — a
 * token that travelled in an export would be handed to anyone the archive is shared with.
 */
object AutomationAuth {
    private const val PREFS = "sokki_automation"
    private const val KEY_ENABLED = "automation_enabled"
    private const val KEY_TOKEN = "automation_token"

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
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
