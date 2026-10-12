package com.example.jdcookie

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Shared UI helpers for QingLong panel URL / settings / open WebView. */
object QingLongPanel {

    const val DEFAULT_BASE_URL = "https://ql.xiaowo1.xyz"

    fun ensureDefaultUrl(config: QingLongConfig) {
        val cur = config.load()
        if (cur.baseUrl.isBlank()) {
            config.save(cur.copy(baseUrl = DEFAULT_BASE_URL))
        }
    }

    fun openPanel(activity: Activity, path: String = "/") {
        val config = QingLongConfig(activity)
        ensureDefaultUrl(config)
        val cfg = config.load()
        val base = QingLongConfig.normalizeUrl(cfg.baseUrl)
        if (base == null) {
            promptSettings(activity, openAfterSave = true, path = path)
            return
        }
        val url = QingLongConfig.joinPath(base, path)
        activity.startActivity(
            Intent(activity, QingLongWebActivity::class.java)
                .putExtra(QingLongWebActivity.EXTRA_URL, url)
        )
    }

    fun promptSettings(
        activity: Activity,
        openAfterSave: Boolean = false,
        path: String = "/"
    ) {
        val config = QingLongConfig(activity)
        ensureDefaultUrl(config)
        val cfg = config.load()
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_qinglong, null)
        val etUrl = view.findViewById<EditText>(R.id.etQlUrl)
        val etId = view.findViewById<EditText>(R.id.etQlClientId)
        val etSecret = view.findViewById<EditText>(R.id.etQlClientSecret)
        etUrl.setText(cfg.baseUrl.ifBlank { DEFAULT_BASE_URL })
        etId.setText(cfg.clientId)
        etSecret.setText(cfg.clientSecret)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.qinglong_settings)
            .setMessage(R.string.qinglong_settings_hint)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val normalized = QingLongConfig.normalizeUrl(etUrl.text.toString())
                if (normalized == null) {
                    Toast.makeText(activity, R.string.ql_url_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                config.save(
                    QingLongSettings(
                        baseUrl = normalized,
                        clientId = etId.text.toString(),
                        clientSecret = etSecret.text.toString()
                    )
                )
                Toast.makeText(activity, R.string.saved, Toast.LENGTH_SHORT).show()
                if (openAfterSave) {
                    openPanel(activity, path)
                }
            }
            .show()
    }
}
