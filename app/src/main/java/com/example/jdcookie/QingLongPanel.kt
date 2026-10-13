package com.example.jdcookie

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.concurrent.thread

/** QingLong settings / first-run / open panel helpers. */
object QingLongPanel {

    const val DEFAULT_BASE_URL = "https://ql.xiaowo1.xyz"

    fun ensureDefaultUrl(config: QingLongConfig) {
        val cur = config.load()
        if (cur.baseUrl.isBlank()) {
            config.save(cur.copy(baseUrl = DEFAULT_BASE_URL))
        }
    }

    fun isReady(activity: Activity): Boolean {
        val config = QingLongConfig(activity)
        ensureDefaultUrl(config)
        return config.load().isConfigured
    }

    fun isNurtureMode(activity: Activity): Boolean =
        QingLongConfig(activity).isNurtureMode()

    fun openPanel(activity: Activity, path: String = "/") {
        val config = QingLongConfig(activity)
        ensureDefaultUrl(config)
        val cfg = config.load()
        val base = QingLongConfig.normalizeUrl(cfg.baseUrl)
        if (base == null) {
            promptSettings(activity)
            return
        }
        val url = QingLongConfig.joinPath(base, path)
        activity.startActivity(
            Intent(activity, QingLongWebActivity::class.java)
                .putExtra(QingLongWebActivity.EXTRA_URL, url)
        )
    }

    /**
     * @param requireCredentials when true (first-run / before push), emphasize client_id/secret
     * @param onSaved called on UI thread after successful save
     */
    fun promptSettings(
        activity: Activity,
        requireCredentials: Boolean = false,
        onSaved: (() -> Unit)? = null
    ) {
        val config = QingLongConfig(activity)
        ensureDefaultUrl(config)
        val cfg = config.load()
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_qinglong, null)
        val etUrl = view.findViewById<EditText>(R.id.etQlUrl)
        val etId = view.findViewById<EditText>(R.id.etQlClientId)
        val etSecret = view.findViewById<EditText>(R.id.etQlClientSecret)
        val switchNurture = view.findViewById<MaterialSwitch>(R.id.switchNurtureMode)
        val tvNurtureTip = view.findViewById<TextView>(R.id.tvNurtureTip)
        val btnTest = view.findViewById<MaterialButton>(R.id.btnTestConnection)
        etUrl.setText(cfg.baseUrl.ifBlank { DEFAULT_BASE_URL })
        etId.setText(cfg.clientId)
        etSecret.setText(cfg.clientSecret)
        switchNurture.isChecked = cfg.nurtureMode
        tvNurtureTip.visibility = if (cfg.nurtureMode) View.VISIBLE else View.GONE
        switchNurture.setOnCheckedChangeListener { _, checked ->
            tvNurtureTip.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.qinglong_settings)
            .setMessage(R.string.qinglong_howto)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            btnTest.setOnClickListener {
                val normalized = QingLongConfig.normalizeUrl(etUrl.text.toString())
                if (normalized == null) {
                    Toast.makeText(activity, R.string.ql_url_invalid, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val id = etId.text.toString().trim()
                val secret = etSecret.text.toString().trim()
                if (id.isEmpty() || secret.isEmpty()) {
                    Toast.makeText(activity, R.string.ql_need_credentials, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                btnTest.isEnabled = false
                btnTest.setText(R.string.ql_testing)
                thread {
                    try {
                        val msg = QingLongApi(
                            QingLongSettings(normalized, id, secret, switchNurture.isChecked)
                        ).testConnection()
                        activity.runOnUiThread {
                            btnTest.isEnabled = true
                            btnTest.setText(R.string.ql_test_connection)
                            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        activity.runOnUiThread {
                            btnTest.isEnabled = true
                            btnTest.setText(R.string.ql_test_connection)
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.ql_test_fail, e.message ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = QingLongConfig.normalizeUrl(etUrl.text.toString())
                if (normalized == null) {
                    Toast.makeText(activity, R.string.ql_url_invalid, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val id = etId.text.toString().trim()
                val secret = etSecret.text.toString().trim()
                if (requireCredentials && (id.isEmpty() || secret.isEmpty())) {
                    Toast.makeText(activity, R.string.ql_need_credentials, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                config.save(
                    QingLongSettings(
                        baseUrl = normalized,
                        clientId = id,
                        clientSecret = secret,
                        nurtureMode = switchNurture.isChecked
                    )
                )
                Toast.makeText(activity, R.string.saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                onSaved?.invoke()
            }
        }
        dialog.show()
    }

    /** Ensure OpenAPI credentials exist; if not, prompt then invoke onReady. */
    fun ensureReady(activity: Activity, onReady: () -> Unit) {
        if (isReady(activity)) {
            onReady()
        } else {
            promptSettings(activity, requireCredentials = true, onSaved = onReady)
        }
    }
}
