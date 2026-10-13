package com.example.jdcookie

import android.content.Context
import java.net.URL

data class QingLongSettings(
    val baseUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    /** 养号模式：推送备注加 [养号] 前缀，并减少激进任务提示。 */
    val nurtureMode: Boolean = false
) {
    /** OpenAPI push/cron needs all three. */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && clientId.isNotBlank() && clientSecret.isNotBlank()

    /** In-app panel WebView only needs a valid URL. */
    val hasPanelUrl: Boolean
        get() = baseUrl.isNotBlank()
}

class QingLongConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): QingLongSettings = QingLongSettings(
        baseUrl = prefs.getString("baseUrl", "") ?: "",
        clientId = prefs.getString("clientId", "") ?: "",
        clientSecret = prefs.getString("clientSecret", "") ?: "",
        nurtureMode = prefs.getBoolean("nurtureMode", false)
    )

    fun save(settings: QingLongSettings) {
        prefs.edit()
            .putString("baseUrl", normalizeUrl(settings.baseUrl) ?: settings.baseUrl.trim().trimEnd('/'))
            .putString("clientId", settings.clientId.trim())
            .putString("clientSecret", settings.clientSecret.trim())
            .putBoolean("nurtureMode", settings.nurtureMode)
            .apply()
    }

    fun isNurtureMode(): Boolean = prefs.getBoolean("nurtureMode", false)

    companion object {
        private const val PREFS = "qinglong_config"
        const val NURTURE_PREFIX = "[养号]"

        /** Returns normalized base URL or null if not http/https. */
        fun normalizeUrl(raw: String): String? {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty()) return null
            if (!t.startsWith("http://", ignoreCase = true) &&
                !t.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }
            return try {
                val u = URL(t)
                if (u.host.isNullOrBlank()) null else t
            } catch (_: Exception) {
                null
            }
        }

        fun joinPath(baseUrl: String, path: String): String {
            val base = baseUrl.trim().trimEnd('/')
            if (path.isBlank() || path == "/") return base
            val p = if (path.startsWith("/")) path else "/$path"
            return base + p
        }

        /** Build QingLong remarks; nurture mode prefixes [养号], otherwise strips it. */
        fun buildRemarks(account: Account, nurtureMode: Boolean): String {
            val raw = (account.displayName.ifBlank { account.decodedPin }).ifBlank { "" }
            val base = raw.removePrefix(NURTURE_PREFIX).trim()
            return if (nurtureMode) {
                if (base.isBlank()) NURTURE_PREFIX else "$NURTURE_PREFIX$base"
            } else {
                base
            }
        }
    }
}
