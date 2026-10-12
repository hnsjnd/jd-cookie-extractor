package com.example.jdcookie

import android.content.Context

data class QingLongSettings(
    val baseUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = ""
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && clientId.isNotBlank() && clientSecret.isNotBlank()
}

class QingLongConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): QingLongSettings = QingLongSettings(
        baseUrl = prefs.getString("baseUrl", "") ?: "",
        clientId = prefs.getString("clientId", "") ?: "",
        clientSecret = prefs.getString("clientSecret", "") ?: ""
    )

    fun save(settings: QingLongSettings) {
        prefs.edit()
            .putString("baseUrl", settings.baseUrl.trim().trimEnd('/'))
            .putString("clientId", settings.clientId.trim())
            .putString("clientSecret", settings.clientSecret.trim())
            .apply()
    }

    companion object {
        private const val PREFS = "qinglong_config"
    }
}
