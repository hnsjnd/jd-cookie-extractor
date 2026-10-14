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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AccountListActivity : AppCompatActivity(), AccountAdapter.Listener {

    private lateinit var store: AccountStore
    private lateinit var beanCache: BeanStatsCache
    private lateinit var adapter: AccountAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var rv: RecyclerView
    private lateinit var qlConfig: QingLongConfig
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvBeanSummary: TextView
    private lateinit var tvBeanSummaryTime: TextView
    private lateinit var tvRefreshProgress: TextView
    private lateinit var refreshProgress: LinearProgressIndicator

    private var beanById: MutableMap<String, JdBeanApi.AccountBeanStats> = linkedMapOf()
    private var beanFetchedAt: Long = 0L
    private var refreshing = false

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_list)

        store = AccountStore(this)
        beanCache = BeanStatsCache(this)
        qlConfig = QingLongConfig(this)
        QingLongPanel.ensureDefaultUrl(qlConfig)
        CkInvalidAlert.ensureChannel(this)
        if (qlConfig.isNotifyOnInvalid()) {
            CkInvalidAlert.requestPermissionIfNeeded(this)
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh_beans -> {
                    refreshAllAccounts()
                    true
                }
                R.id.action_push_all -> {
                    pushAll()
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
        swipeRefresh = findViewById(R.id.swipeRefresh)
        tvBeanSummary = findViewById(R.id.tvBeanSummary)
        tvBeanSummaryTime = findViewById(R.id.tvBeanSummaryTime)
        tvRefreshProgress = findViewById(R.id.tvRefreshProgress)
        refreshProgress = findViewById(R.id.refreshProgress)

        adapter = AccountAdapter(mutableListOf(), this)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        swipeRefresh.setOnRefreshListener { refreshAllAccounts() }

        findViewById<ExtendedFloatingActionButton>(R.id.fabAdd).setOnClickListener {
            QingLongPanel.ensureReady(this) {
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            }
        }

        if (!QingLongPanel.isReady(this)) {
            Toast.makeText(this, R.string.firstrun_need_ql, Toast.LENGTH_LONG).show()
            QingLongPanel.promptSettings(this, requireCredentials = true)
        }

        loadCachedBeans()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun loadCachedBeans() {
        val (t, rows) = beanCache.load()
        beanFetchedAt = t
        beanById = rows.associateBy { it.accountId }.toMutableMap()
        adapter.setBeanStats(beanById)
        updateBeanSummary()
    }

    private fun refreshList() {
        val list = store.loadAll()
        adapter.replaceAll(list)
        adapter.setBeanStats(beanById)
        adapter.setNurtureMode(qlConfig.isNurtureMode())
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        updateBeanSummary()
        updateNurtureBanner()
        updateInvalidBanner()
    }

    private fun updateBeanSummary() {
        val accounts = store.loadAll()
        val rows = accounts.mapNotNull { beanById[it.id] }
        val okRows = rows.filter { it.ok && it.totalBeans != null }
        if (okRows.isEmpty()) {
            tvBeanSummary.text = getString(R.string.bean_summary_placeholder)
        } else {
            val sumBeans = okRows.sumOf { it.totalBeans ?: 0 }
            val sumToday = okRows.sumOf { it.todayIncome ?: 0 }
            val sumExpire7 = okRows.filter { it.expireAvailable }.sumOf { it.expireIn7Days ?: 0 }
            tvBeanSummary.text = getString(R.string.bean_summary_fmt, sumBeans, sumToday, sumExpire7)
        }
        tvBeanSummaryTime.text = if (beanFetchedAt > 0) {
            getString(R.string.bean_fetched_at, timeFmt.format(Date(beanFetchedAt)))
        } else {
            getString(R.string.bean_never_fetched)
        }
    }

    /**
     * 更新全部账号：CK 有效性 + 京豆（余额/今日/过期）+ 青龙推送状态展示。
     * 并发上限 2；结果写入 BeanStatsCache。
     */
    private fun refreshAllAccounts() {
        if (refreshing) {
            swipeRefresh.isRefreshing = false
            return
        }
        val accounts = store.loadAll()
        if (accounts.isEmpty()) {
            swipeRefresh.isRefreshing = false
            Toast.makeText(this, R.string.no_accounts, Toast.LENGTH_SHORT).show()
            return
        }

        refreshing = true
        swipeRefresh.isRefreshing = true
        refreshProgress.visibility = View.VISIBLE
        refreshProgress.isIndeterminate = false
        refreshProgress.max = accounts.size
        refreshProgress.progress = 0
        tvRefreshProgress.visibility = View.VISIBLE
        tvRefreshProgress.text = getString(R.string.refresh_all_progress, 0, accounts.size)

        // Mark checking
        accounts.forEach {
            it.status = AccountStatus.CHECKING
            store.update(it)
        }
        refreshList()

        thread {
            // Prefetch QingLong envs once (optional) for push-status display
            val qlEnvs: List<QingLongApi.EnvItem>? = try {
                if (QingLongPanel.isReady(this)) {
                    QingLongApi(qlConfig.load()).listEnvs("JD_COOKIE")
                } else null
            } catch (_: Exception) {
                null
            }

            val pool = Executors.newFixedThreadPool(2)
            val done = AtomicInteger(0)
            val results = arrayOfNulls<JdBeanApi.AccountBeanStats>(accounts.size)

            accounts.forEachIndexed { index, account ->
                pool.execute {
                    // 1) CK validity
                    val check = JdApi.checkCookie(account.combined)
                    account.status = check.status
                    account.lastCheckedAt = System.currentTimeMillis()
                    account.lastMessage = check.message
                    if (!check.nickname.isNullOrBlank()) account.nickname = check.nickname

                    // 2) Beans (same APIs as v1.5.0)
                    val stats = JdBeanApi.fetchForAccount(account)
                    results[index] = stats

                    // 3) Push-status display from QL envs if available
                    if (qlEnvs != null) {
                        applyPushStatusFromEnvs(account, qlEnvs)
                    }

                    store.update(account)

                    val n = done.incrementAndGet()
                    runOnUiThread {
                        beanById[account.id] = stats
                        adapter.setBeanStats(beanById)
                        refreshList()
                        refreshProgress.progress = n
                        tvRefreshProgress.text =
                            getString(R.string.refresh_all_progress, n, accounts.size)
                    }

                    if (n == accounts.size) {
                        pool.shutdown()
                        val rows = results.filterNotNull()
                        val now = System.currentTimeMillis()
                        beanCache.save(now, rows)
                        runOnUiThread {
                            beanFetchedAt = now
                            beanById = rows.associateBy { it.accountId }.toMutableMap()
                            refreshing = false
                            swipeRefresh.isRefreshing = false
                            refreshProgress.visibility = View.GONE
                            tvRefreshProgress.visibility = View.GONE
                            refreshList()
                            handleInvalidAlert()
                            Toast.makeText(this, R.string.bean_refresh_done, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
            }
        }
    }

    private fun applyPushStatusFromEnvs(account: Account, envs: List<QingLongApi.EnvItem>) {
        val pin = account.ptPin
        val decoded = account.decodedPin
        val match = envs.find { env ->
            env.name == "JD_COOKIE" && (
                env.value.contains("pt_pin=$pin") ||
                    env.value.contains("pt_pin=$decoded") ||
                    env.remarks.contains(decoded) ||
                    env.remarks.contains(pin)
                )
        }
        if (match == null) {
            if (account.pushStatus == PushStatus.SYNCED) {
                account.pushStatus = PushStatus.NONE
                account.lastPushMessage = "青龙上未找到对应变量"
            }
            return
        }
        account.qlEnvId = match.idString
        val disabled = match.status == 1 || match.isDisabled == 1
        val keyMatch = match.value.contains("pt_key=${account.ptKey}")
        when {
            disabled -> {
                account.pushStatus = PushStatus.FAILED
                account.lastPushMessage = "青龙变量已禁用"
            }
            keyMatch -> {
                account.pushStatus = PushStatus.SYNCED
                if (account.lastPushedAt == 0L) {
                    account.lastPushedAt = System.currentTimeMillis()
                }
                account.lastPushMessage = "已与青龙同步"
            }
            else -> {
                account.pushStatus = PushStatus.FAILED
                account.lastPushMessage = "青龙 Cookie 与本地不一致"
            }
        }
    }


    private fun updateNurtureBanner() {
        val banner = findViewById<TextView>(R.id.tvNurtureBanner)
        val on = qlConfig.isNurtureMode()
        banner.visibility = if (on) View.VISIBLE else View.GONE
        if (on) banner.text = getString(R.string.nurture_mode_banner)
    }

    private fun updateInvalidBanner() {
        val banner = findViewById<TextView>(R.id.tvInvalidBanner)
        val invalid = store.loadAll().filter { it.status == AccountStatus.EXPIRED }
        if (invalid.isEmpty()) {
            banner.visibility = View.GONE
        } else {
            banner.visibility = View.VISIBLE
            banner.text = CkInvalidAlert.formatBanner(this, invalid.map { it.displayName })
        }
    }

    /** After CK check / 更新 / QL 禁用：刷新横幅并按需发通知。 */
    private fun handleInvalidAlert(extraInvalid: List<Account> = emptyList()) {
        updateInvalidBanner()
        val fromStore = CkInvalidAlert.expiredOf(store.loadAll())
        val merged = (fromStore + extraInvalid).distinctBy { it.id }
        if (merged.isNotEmpty()) {
            CkInvalidAlert.notifyIfNeeded(this, merged)
        }
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
                refreshList()
            }
            .show()
    }

    override fun onDelete(account: Account) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_ql_title)
            .setMessage(getString(R.string.confirm_delete_ql_msg, account.displayName))
            .setNeutralButton(R.string.delete_local_only) { _, _ ->
                store.delete(account.id)
                beanById.remove(account.id)
                refreshList()
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
        beanById.remove(account.id)
        refreshList()
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
        refreshList()
        thread {
            val result = JdApi.checkCookie(account.combined)
            account.status = result.status
            account.lastCheckedAt = System.currentTimeMillis()
            account.lastMessage = result.message
            if (!result.nickname.isNullOrBlank()) account.nickname = result.nickname
            store.update(account)
            runOnUiThread {
                refreshList()
                if (result.status == AccountStatus.EXPIRED) {
                    handleInvalidAlert(listOf(account))
                } else {
                    updateInvalidBanner()
                }
                Toast.makeText(this, "${account.displayName}: ${result.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPushQl(account: Account) {
        QingLongPanel.ensureReady(this) {
            account.pushStatus = PushStatus.PUSHING
            store.update(account)
            refreshList()
            thread {
                try {
                    val cfg = qlConfig.load()
                    val result = QingLongApi(cfg).pushOrUpdateCookie(account, cfg.nurtureMode)
                    if (result.disabledLikelyInvalid) {
                        account.pushStatus = PushStatus.FAILED
                        account.lastPushMessage = result.message
                        // Mark CK expired so banner/badge/notification fire
                        if (account.status != AccountStatus.EXPIRED) {
                            account.status = AccountStatus.EXPIRED
                            account.lastMessage = getString(R.string.ck_invalid_ql_disabled)
                        }
                    } else {
                        account.pushStatus = PushStatus.SYNCED
                        account.lastPushedAt = System.currentTimeMillis()
                        account.lastPushMessage = result.message
                    }
                    account.qlEnvId = result.envId
                    store.update(account)
                    runOnUiThread {
                        refreshList()
                        if (result.disabledLikelyInvalid) {
                            handleInvalidAlert(listOf(account))
                        }
                        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    account.pushStatus = PushStatus.FAILED
                    account.lastPushMessage = e.message ?: "失败"
                    store.update(account)
                    runOnUiThread {
                        refreshList()
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
                val cfg = qlConfig.load()
                val api = QingLongApi(cfg)
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
                        val result = api.pushOrUpdateCookie(account, cfg.nurtureMode)
                        account.qlEnvId = result.envId
                        if (result.disabledLikelyInvalid) {
                            account.pushStatus = PushStatus.FAILED
                            account.lastPushMessage = result.message
                            if (account.status != AccountStatus.EXPIRED) {
                                account.status = AccountStatus.EXPIRED
                                account.lastMessage = getString(R.string.ck_invalid_ql_disabled)
                            }
                            store.update(account)
                            fail++
                        } else {
                            account.pushStatus = PushStatus.SYNCED
                            account.lastPushedAt = System.currentTimeMillis()
                            account.lastPushMessage = result.message
                            store.update(account)
                            ok++
                        }
                    } catch (e: Exception) {
                        account.pushStatus = PushStatus.FAILED
                        account.lastPushMessage = e.message ?: "失败"
                        store.update(account)
                        fail++
                    }
                    runOnUiThread { refreshList() }
                }
                runOnUiThread {
                    refreshList()
                    handleInvalidAlert()
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
