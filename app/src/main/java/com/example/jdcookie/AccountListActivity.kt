package com.example.jdcookie

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.concurrent.thread

class AccountListActivity : AppCompatActivity(), AccountAdapter.Listener {

    private lateinit var store: AccountStore
    private lateinit var adapter: AccountAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var rv: RecyclerView
    private lateinit var qlConfig: QingLongConfig

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_list)

        store = AccountStore(this)
        qlConfig = QingLongConfig(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_tasks -> {
                    startActivity(Intent(this, TaskProgressActivity::class.java))
                    true
                }
                R.id.action_check_all -> {
                    startActivity(Intent(this, TaskProgressActivity::class.java).putExtra("autostart", true))
                    true
                }
                R.id.action_copy_all -> {
                    showCopyAllDialog()
                    true
                }
                R.id.action_qinglong -> {
                    showQingLongSettings()
                    true
                }
                else -> false
            }
        }

        tvEmpty = findViewById(R.id.tvEmpty)
        rv = findViewById(R.id.rvAccounts)
        adapter = AccountAdapter(mutableListOf(), this)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = store.loadAll()
        adapter.replaceAll(list)
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onCopy(account: Account) {
        copy(account.combined)
    }

    override fun onRelogin(account: Account) {
        val i = Intent(this, LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_ACCOUNT_ID, account.id)
            .putExtra(LoginActivity.EXTRA_MODE, "relogin")
        loginLauncher.launch(i)
    }

    override fun onRename(account: Account) {
        val input = EditText(this).apply {
            setText(account.nickname.ifBlank { account.decodedPin })
            setSelection(text.length)
            hint = getString(R.string.rename_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                store.rename(account.id, input.text.toString())
                refresh()
            }
            .show()
    }

    override fun onDelete(account: Account) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.confirm_delete_msg, account.displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                store.delete(account.id)
                refresh()
            }
            .show()
    }

    override fun onCheck(account: Account) {
        account.status = AccountStatus.CHECKING
        store.update(account)
        refresh()
        thread {
            val result = JdApi.checkCookie(account.combined)
            account.status = result.status
            account.lastCheckedAt = System.currentTimeMillis()
            account.lastMessage = result.message
            if (!result.nickname.isNullOrBlank()) account.nickname = result.nickname
            store.update(account)
            runOnUiThread {
                refresh()
                Toast.makeText(this, "${account.displayName}: ${result.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPushQl(account: Account) {
        val cfg = qlConfig.load()
        if (!cfg.isConfigured) {
            Toast.makeText(this, R.string.ql_not_configured, Toast.LENGTH_SHORT).show()
            showQingLongSettings()
            return
        }
        Toast.makeText(this, R.string.ql_pushing, Toast.LENGTH_SHORT).show()
        thread {
            try {
                val msg = QingLongApi(cfg).pushOrUpdateCookie(account)
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "青龙推送失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onShowQr(account: Account) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_qr, null)
        val iv = view.findViewById<ImageView>(R.id.ivQr)
        try {
            iv.setImageBitmap(QrHelper.encode(account.combined))
        } catch (e: Exception) {
            Toast.makeText(this, "二维码失败: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qr_title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showCopyAllDialog() {
        val accounts = store.loadAll()
        if (accounts.isEmpty()) {
            Toast.makeText(this, R.string.no_accounts, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = arrayOf(
            getString(R.string.copy_join_amp),
            getString(R.string.copy_join_newline),
            getString(R.string.copy_join_amp_valid_only)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.copy_all)
            .setItems(labels) { _, which ->
                val src = when (which) {
                    2 -> accounts.filter { it.status == AccountStatus.VALID }.ifEmpty { accounts }
                    else -> accounts
                }
                val sep = if (which == 1) "\n" else "&"
                copy(src.joinToString(sep) { it.combined })
            }
            .show()
    }

    private fun showQingLongSettings() {
        val cfg = qlConfig.load()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_qinglong, null)
        val etUrl = view.findViewById<EditText>(R.id.etQlUrl)
        val etId = view.findViewById<EditText>(R.id.etQlClientId)
        val etSecret = view.findViewById<EditText>(R.id.etQlClientSecret)
        etUrl.setText(cfg.baseUrl)
        etId.setText(cfg.clientId)
        etSecret.setText(cfg.clientSecret)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qinglong_settings)
            .setView(view)
            .setMessage(R.string.qinglong_settings_hint)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                qlConfig.save(
                    QingLongSettings(
                        baseUrl = etUrl.text.toString(),
                        clientId = etId.text.toString(),
                        clientSecret = etSecret.text.toString()
                    )
                )
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("JD_COOKIE", text))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }
}
