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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlin.concurrent.thread

class AccountListActivity : AppCompatActivity(), AccountAdapter.Listener {

    private lateinit var store: AccountStore
    private lateinit var adapter: AccountAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var rv: RecyclerView
    private lateinit var qlConfig: QingLongConfig

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_list)

        store = AccountStore(this)
        qlConfig = QingLongConfig(this)
        QingLongPanel.ensureDefaultUrl(qlConfig)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_push_all -> {
                    pushAll()
                    true
                }
                R.id.action_bean_stats -> {
                    startActivity(Intent(this, BeanStatsActivity::class.java))
                    true
                }
                R.id.action_tasks -> {
                    startActivity(Intent(this, TaskProgressActivity::class.java))
                    true
                }
                R.id.action_check_all -> {
                    startActivity(
                        Intent(this, TaskProgressActivity::class.java).putExtra("autostart", true)
                    )
                    true
                }
                R.id.action_copy_all -> {
                    showCopyAllDialog()
                    true
                }
                R.id.action_qinglong -> {
                    QingLongPanel.promptSettings(this, requireCredentials = true)
                    true
                }
                R.id.action_open_panel -> {
                    QingLongPanel.openPanel(this)
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

        findViewById<ExtendedFloatingActionButton>(R.id.fabAdd).setOnClickListener {
            QingLongPanel.ensureReady(this) {
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            }
        }

        // First-run: prompt for OpenAPI credentials
        if (!QingLongPanel.isReady(this)) {
            Toast.makeText(this, R.string.firstrun_need_ql, Toast.LENGTH_LONG).show()
            QingLongPanel.promptSettings(this, requireCredentials = true)
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

    override fun onCopy(account: Account) = copy(account.combined)

    override fun onRelogin(account: Account) {
        QingLongPanel.ensureReady(this) {
            loginLauncher.launch(
                Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_ACCOUNT_ID, account.id)
                    .putExtra(LoginActivity.EXTRA_MODE, "relogin")
            )
        }
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
            .setTitle(R.string.confirm_delete_ql_title)
            .setMessage(getString(R.string.confirm_delete_ql_msg, account.displayName))
            .setNeutralButton(R.string.delete_local_only) { _, _ ->
                store.delete(account.id)
                refresh()
            }
            .setNegativeButton(R.string.delete_and_disable_ql) { _, _ ->
                deleteLocalAndQl(account, remove = false)
            }
            .setPositiveButton(R.string.delete_and_remove_ql) { _, _ ->
                deleteLocalAndQl(account, remove = true)
            }
            .show()
    }

    private fun deleteLocalAndQl(account: Account, remove: Boolean) {
        store.delete(account.id)
        refresh()
        if (!QingLongPanel.isReady(this)) {
            Toast.makeText(this, R.string.ql_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                val api = QingLongApi(qlConfig.load())
                val envId = account.qlEnvId.ifBlank {
                    api.findCookieEnv(account)?.idString.orEmpty()
                }
                if (envId.isBlank()) {
                    runOnUiThread {
                        Toast.makeText(this, "本机已删除；青龙上未找到对应变量", Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }
                val msg = if (remove) api.deleteEnv(envId) else api.disableEnv(envId)
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "本机已删除；青龙操作失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
        QingLongPanel.ensureReady(this) {
            account.pushStatus = PushStatus.PUSHING
            store.update(account)
            refresh()
            thread {
                try {
                    val result = QingLongApi(qlConfig.load()).pushOrUpdateCookie(account)
                    account.pushStatus = PushStatus.SYNCED
                    account.lastPushedAt = System.currentTimeMillis()
                    account.lastPushMessage = result.message
                    account.qlEnvId = result.envId
                    store.update(account)
                    runOnUiThread {
                        refresh()
                        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    account.pushStatus = PushStatus.FAILED
                    account.lastPushMessage = e.message ?: "失败"
                    store.update(account)
                    runOnUiThread {
                        refresh()
                        Toast.makeText(this, "推送失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
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

    private fun pushAll() {
        QingLongPanel.ensureReady(this) {
            val accounts = store.loadAll()
            if (accounts.isEmpty()) {
                Toast.makeText(this, R.string.no_accounts, Toast.LENGTH_SHORT).show()
                return@ensureReady
            }
            thread {
                var ok = 0
                var fail = 0
                val api = QingLongApi(qlConfig.load())
                accounts.forEachIndexed { index, account ->
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.push_all_progress, index + 1, accounts.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    try {
                        account.pushStatus = PushStatus.PUSHING
                        store.update(account)
                        val result = api.pushOrUpdateCookie(account)
                        account.pushStatus = PushStatus.SYNCED
                        account.lastPushedAt = System.currentTimeMillis()
                        account.lastPushMessage = result.message
                        account.qlEnvId = result.envId
                        store.update(account)
                        ok++
                    } catch (e: Exception) {
                        account.pushStatus = PushStatus.FAILED
                        account.lastPushMessage = e.message ?: "失败"
                        store.update(account)
                        fail++
                    }
                    runOnUiThread { refresh() }
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.push_all_done, ok, fail),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("JD_COOKIE", text))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }
}
