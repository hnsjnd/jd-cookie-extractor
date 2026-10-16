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
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AccountListActivity : AppCompatActivity(), AccountAdapter.Listener, MeituanAccountAdapter.Listener, ElmAccountAdapter.Listener, QuarkAccountAdapter.Listener, TelecomAccountAdapter.Listener {

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
    private lateinit var tabZones: TabLayout
    private lateinit var zoneJd: View
    private lateinit var zoneMeituan: View
    private lateinit var zoneMicrosoft: View
    private lateinit var mtStore: MeituanAccountStore
    private lateinit var mtAdapter: MeituanAccountAdapter
    private lateinit var rvMeituan: RecyclerView
    private lateinit var tvEmptyMt: TextView
    private lateinit var elmStore: ElmAccountStore
    private lateinit var elmAdapter: ElmAccountAdapter
    private lateinit var rvElm: RecyclerView
    private lateinit var tvEmptyElm: TextView
    private lateinit var zoneElm: View
    private lateinit var quarkStore: QuarkAccountStore
    private lateinit var quarkAdapter: QuarkAccountAdapter
    private lateinit var rvQuark: RecyclerView
    private lateinit var tvEmptyQuark: TextView
    private lateinit var zoneQuark: View
    private lateinit var telecomStore: TelecomAccountStore
    private lateinit var telecomAdapter: TelecomAccountAdapter
    private lateinit var rvTelecom: RecyclerView
    private lateinit var tvEmptyTelecom: TextView
    private lateinit var zoneTelecom: View
    private lateinit var fabAdd: ExtendedFloatingActionButton

    private var currentZone = ZONE_JD
    private var beanById: MutableMap<String, JdBeanApi.AccountBeanStats> = linkedMapOf()
    private var beanFetchedAt: Long = 0L
    private var refreshing = false

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshList() }

    private val mtLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshMeituanList() }

    private val elmLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshElmList() }

    private val quarkLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshQuarkList() }

    companion object {
        private const val ZONE_JD = 0
        private const val ZONE_MEITUAN = 1
        private const val ZONE_ELM = 2
        private const val ZONE_QUARK = 3
        private const val ZONE_TELECOM = 4
        private const val ZONE_MICROSOFT = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_list)

        store = AccountStore(this)
        mtStore = MeituanAccountStore(this)
        elmStore = ElmAccountStore(this)
        quarkStore = QuarkAccountStore(this)
        telecomStore = TelecomAccountStore(this)
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
                    when (currentZone) {
                        ZONE_MEITUAN -> pushAllMeituan()
                        ZONE_ELM -> pushAllElm()
                        ZONE_QUARK -> pushAllQuark()
                        ZONE_TELECOM -> pushAllTelecom()
                        else -> pushAll()
                    }
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

        fabAdd = findViewById(R.id.fabAdd)
        fabAdd.setOnClickListener { onFabClick() }

        zoneJd = findViewById(R.id.zoneJd)
        zoneMeituan = findViewById(R.id.zoneMeituan)
        zoneElm = findViewById(R.id.zoneElm)
        zoneQuark = findViewById(R.id.zoneQuark)
        zoneTelecom = findViewById(R.id.zoneTelecom)
        zoneMicrosoft = findViewById(R.id.zoneMicrosoft)
        rvMeituan = findViewById(R.id.rvMeituan)
        tvEmptyMt = findViewById(R.id.tvEmptyMt)
        mtAdapter = MeituanAccountAdapter(mutableListOf(), this)
        rvMeituan.layoutManager = LinearLayoutManager(this)
        rvMeituan.adapter = mtAdapter
        rvElm = findViewById(R.id.rvElm)
        tvEmptyElm = findViewById(R.id.tvEmptyElm)
        elmAdapter = ElmAccountAdapter(mutableListOf(), this)
        rvElm.layoutManager = LinearLayoutManager(this)
        rvElm.adapter = elmAdapter
        rvQuark = findViewById(R.id.rvQuark)
        tvEmptyQuark = findViewById(R.id.tvEmptyQuark)
        quarkAdapter = QuarkAccountAdapter(mutableListOf(), this)
        rvQuark.layoutManager = LinearLayoutManager(this)
        rvQuark.adapter = quarkAdapter
        rvTelecom = findViewById(R.id.rvTelecom)
        tvEmptyTelecom = findViewById(R.id.tvEmptyTelecom)
        telecomAdapter = TelecomAccountAdapter(mutableListOf(), this)
        rvTelecom.layoutManager = LinearLayoutManager(this)
        rvTelecom.adapter = telecomAdapter

        tabZones = findViewById(R.id.tabZones)
        tabZones.addTab(tabZones.newTab().setText(R.string.zone_jd))
        tabZones.addTab(tabZones.newTab().setText(R.string.zone_meituan))
        tabZones.addTab(tabZones.newTab().setText(R.string.zone_elm))
        tabZones.addTab(tabZones.newTab().setText(R.string.zone_quark))
        tabZones.addTab(tabZones.newTab().setText(R.string.zone_telecom))
        tabZones.addTab(tabZones.newTab().setText(R.string.zone_microsoft))
        tabZones.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                showZone(tab?.position ?: ZONE_JD)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        if (!QingLongPanel.isReady(this)) {
            Toast.makeText(this, R.string.firstrun_need_ql, Toast.LENGTH_LONG).show()
            QingLongPanel.promptSettings(this, requireCredentials = true)
        }

        loadCachedBeans()
        refreshList()
        refreshMeituanList()
        refreshElmList()
        refreshQuarkList()
        refreshTelecomList()
        showZone(ZONE_JD)
    }

    private fun onFabClick() {
        when (currentZone) {
            ZONE_JD -> QingLongPanel.ensureReady(this) {
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            }
            ZONE_MEITUAN -> showAddMeituanChooser()
            ZONE_ELM -> showAddElmChooser()
            ZONE_QUARK -> showAddQuarkChooser()
            ZONE_TELECOM -> showPasteTelecomDialog()
            else -> { /* microsoft: no fab action */ }
        }
    }

    private fun showZone(zone: Int) {
        currentZone = zone
        zoneJd.visibility = if (zone == ZONE_JD) View.VISIBLE else View.GONE
        zoneMeituan.visibility = if (zone == ZONE_MEITUAN) View.VISIBLE else View.GONE
        zoneElm.visibility = if (zone == ZONE_ELM) View.VISIBLE else View.GONE
        zoneQuark.visibility = if (zone == ZONE_QUARK) View.VISIBLE else View.GONE
        zoneTelecom.visibility = if (zone == ZONE_TELECOM) View.VISIBLE else View.GONE
        zoneMicrosoft.visibility = if (zone == ZONE_MICROSOFT) View.VISIBLE else View.GONE
        fabAdd.visibility = if (zone == ZONE_MICROSOFT) View.GONE else View.VISIBLE
        when (zone) {
            ZONE_JD -> {
                fabAdd.text = getString(R.string.add_and_push)
                findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.accounts_title)
            }
            ZONE_MEITUAN -> {
                fabAdd.text = getString(R.string.add_meituan)
                findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.zone_meituan_title)
                refreshMeituanList()
            }
            ZONE_ELM -> {
                fabAdd.text = getString(R.string.add_elm)
                findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.zone_elm_title)
                refreshElmList()
            }
            ZONE_QUARK -> {
                fabAdd.text = getString(R.string.add_quark)
                findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.zone_quark_title)
                refreshQuarkList()
            }
            ZONE_TELECOM -> {
                fabAdd.text = getString(R.string.add_telecom)
                findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.zone_telecom_title)
                refreshTelecomList()
            }
            ZONE_MICROSOFT -> {
                findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.zone_microsoft)
            }
        }
        updateMenuForZone()
    }

    private fun updateMenuForZone() {
        val menu = findViewById<MaterialToolbar>(R.id.toolbar).menu
        val jdOnly = currentZone == ZONE_JD
        val pushZones = currentZone == ZONE_JD || currentZone == ZONE_MEITUAN || currentZone == ZONE_ELM ||
            currentZone == ZONE_QUARK || currentZone == ZONE_TELECOM
        menu.findItem(R.id.action_refresh_beans)?.isVisible = jdOnly
        menu.findItem(R.id.action_push_all)?.isVisible = pushZones
        menu.findItem(R.id.action_tasks)?.isVisible = jdOnly
        menu.findItem(R.id.action_check_all)?.isVisible = jdOnly
        menu.findItem(R.id.action_copy_all)?.isVisible = jdOnly
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        if (currentZone == ZONE_MEITUAN) refreshMeituanList()
        if (currentZone == ZONE_ELM) refreshElmList()
        if (currentZone == ZONE_QUARK) refreshQuarkList()
        if (currentZone == ZONE_TELECOM) refreshTelecomList()
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

    private fun copy(text: String, label: String = "JD_COOKIE") {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    // region Meituan

    private fun refreshMeituanList() {
        val list = mtStore.loadAll()
        mtAdapter.replaceAll(list)
        tvEmptyMt.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        rvMeituan.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAddMeituanChooser() {
        val labels = arrayOf(
            getString(R.string.meituan_add_webview),
            getString(R.string.meituan_add_paste)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_meituan)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> QingLongPanel.ensureReady(this) {
                        mtLoginLauncher.launch(Intent(this, MeituanLoginActivity::class.java))
                    }
                    1 -> showPasteMeituanDialog()
                }
            }
            .show()
    }

    private fun showPasteMeituanDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_paste_meituan, null)
        val etNick = view.findViewById<EditText>(R.id.etMtNickname)
        val etToken = view.findViewById<EditText>(R.id.etMtToken)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.meituan_paste_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val token = MeituanAccountStore.normalizeToken(etToken.text.toString())
                if (token.isBlank()) {
                    Toast.makeText(this, R.string.meituan_token_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val nick = etNick.text.toString().trim()
                val account = mtStore.upsertByToken(token, nick.ifBlank { null })
                refreshMeituanList()
                QingLongPanel.ensureReady(this) {
                    pushMeituanAccount(account)
                }
            }
            .show()
    }

    override fun onCopy(account: MeituanAccount) = copy(account.token, "meituanCookie")

    override fun onPushQl(account: MeituanAccount) {
        QingLongPanel.ensureReady(this) { pushMeituanAccount(account) }
    }

    override fun onRename(account: MeituanAccount) {
        val input = EditText(this).apply {
            setText(account.nickname.ifBlank { account.displayName })
            setSelection(text.length)
            hint = getString(R.string.rename_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                mtStore.rename(account.id, input.text.toString())
                refreshMeituanList()
            }
            .show()
    }

    override fun onDelete(account: MeituanAccount) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_ql_title)
            .setMessage(getString(R.string.confirm_delete_mt_ql_msg, account.displayName))
            .setNeutralButton(R.string.delete_local_only) { _, _ ->
                mtStore.delete(account.id)
                refreshMeituanList()
            }
            .setNegativeButton(R.string.delete_and_disable_ql) { _, _ ->
                deleteMeituanLocalAndQl(account, remove = false)
            }
            .setPositiveButton(R.string.delete_and_remove_ql) { _, _ ->
                deleteMeituanLocalAndQl(account, remove = true)
            }
            .show()
    }

    private fun deleteMeituanLocalAndQl(account: MeituanAccount, remove: Boolean) {
        mtStore.delete(account.id)
        refreshMeituanList()
        if (!QingLongPanel.isReady(this)) {
            Toast.makeText(this, R.string.ql_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                val api = QingLongApi(qlConfig.load())
                val envId = account.qlEnvId.ifBlank {
                    api.findMeituanEnv(account)?.idString.orEmpty()
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

    private fun pushMeituanAccount(account: MeituanAccount) {
        account.pushStatus = PushStatus.PUSHING
        mtStore.update(account)
        refreshMeituanList()
        thread {
            try {
                val result = QingLongApi(qlConfig.load()).pushOrUpdateMeituan(account)
                account.qlEnvId = result.envId
                account.pushStatus = PushStatus.SYNCED
                account.lastPushedAt = System.currentTimeMillis()
                account.lastPushMessage = result.message
                mtStore.update(account)
                runOnUiThread {
                    refreshMeituanList()
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                account.pushStatus = PushStatus.FAILED
                account.lastPushMessage = e.message ?: "失败"
                mtStore.update(account)
                runOnUiThread {
                    refreshMeituanList()
                    Toast.makeText(this, "推送失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pushAllMeituan() {
        QingLongPanel.ensureReady(this) {
            val accounts = mtStore.loadAll()
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
                        mtStore.update(account)
                        val result = api.pushOrUpdateMeituan(account)
                        account.qlEnvId = result.envId
                        account.pushStatus = PushStatus.SYNCED
                        account.lastPushedAt = System.currentTimeMillis()
                        account.lastPushMessage = result.message
                        mtStore.update(account)
                        ok++
                    } catch (e: Exception) {
                        account.pushStatus = PushStatus.FAILED
                        account.lastPushMessage = e.message ?: "失败"
                        mtStore.update(account)
                        fail++
                    }
                    runOnUiThread { refreshMeituanList() }
                }
                runOnUiThread {
                    refreshMeituanList()
                    Toast.makeText(
                        this,
                        getString(R.string.push_all_done, ok, fail),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // endregion

    // region Ele.me (饿了么)

    private fun refreshElmList() {
        val list = elmStore.loadAll()
        elmAdapter.replaceAll(list)
        tvEmptyElm.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        rvElm.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAddElmChooser() {
        val labels = arrayOf(
            getString(R.string.elm_add_webview),
            getString(R.string.elm_add_paste)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_elm)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> QingLongPanel.ensureReady(this) {
                        elmLoginLauncher.launch(Intent(this, ElmLoginActivity::class.java))
                    }
                    1 -> showPasteElmDialog()
                }
            }
            .show()
    }

    private fun showPasteElmDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_paste_elm, null)
        val etNick = view.findViewById<EditText>(R.id.etElmNickname)
        val etCookie = view.findViewById<EditText>(R.id.etElmCookie)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.elm_paste_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val raw = etCookie.text.toString()
                if (!ElmAccountStore.isValidCookie(raw)) {
                    Toast.makeText(this, R.string.elm_cookie_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val nick = etNick.text.toString().trim()
                val account = elmStore.upsertByCookie(raw, nick.ifBlank { null })
                refreshElmList()
                QingLongPanel.ensureReady(this) {
                    pushElmAccount(account)
                }
            }
            .show()
    }

    override fun onCopy(account: ElmAccount) = copy(account.cookie, "elmck")

    override fun onPushQl(account: ElmAccount) {
        QingLongPanel.ensureReady(this) { pushElmAccount(account) }
    }

    override fun onRename(account: ElmAccount) {
        val input = EditText(this).apply {
            setText(account.nickname.ifBlank { account.displayName })
            setSelection(text.length)
            hint = getString(R.string.rename_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                elmStore.rename(account.id, input.text.toString())
                refreshElmList()
            }
            .show()
    }

    override fun onDelete(account: ElmAccount) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_ql_title)
            .setMessage(getString(R.string.confirm_delete_elm_ql_msg, account.displayName))
            .setNeutralButton(R.string.delete_local_only) { _, _ ->
                elmStore.delete(account.id)
                refreshElmList()
            }
            .setNegativeButton(R.string.delete_and_disable_ql) { _, _ ->
                deleteElmLocalAndQl(account, remove = false)
            }
            .setPositiveButton(R.string.delete_and_remove_ql) { _, _ ->
                deleteElmLocalAndQl(account, remove = true)
            }
            .show()
    }

    private fun deleteElmLocalAndQl(account: ElmAccount, remove: Boolean) {
        elmStore.delete(account.id)
        refreshElmList()
        if (!QingLongPanel.isReady(this)) {
            Toast.makeText(this, R.string.ql_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                val api = QingLongApi(qlConfig.load())
                val envId = account.qlEnvId.ifBlank {
                    api.findElmEnv(account)?.idString.orEmpty()
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

    private fun pushElmAccount(account: ElmAccount) {
        account.pushStatus = PushStatus.PUSHING
        elmStore.update(account)
        refreshElmList()
        thread {
            try {
                val result = QingLongApi(qlConfig.load()).pushOrUpdateElm(account)
                account.qlEnvId = result.envId
                account.pushStatus = PushStatus.SYNCED
                account.lastPushedAt = System.currentTimeMillis()
                account.lastPushMessage = result.message
                elmStore.update(account)
                runOnUiThread {
                    refreshElmList()
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                account.pushStatus = PushStatus.FAILED
                account.lastPushMessage = e.message ?: "失败"
                elmStore.update(account)
                runOnUiThread {
                    refreshElmList()
                    Toast.makeText(this, "推送失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pushAllElm() {
        QingLongPanel.ensureReady(this) {
            val accounts = elmStore.loadAll()
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
                        elmStore.update(account)
                        val result = api.pushOrUpdateElm(account)
                        account.qlEnvId = result.envId
                        account.pushStatus = PushStatus.SYNCED
                        account.lastPushedAt = System.currentTimeMillis()
                        account.lastPushMessage = result.message
                        elmStore.update(account)
                        ok++
                    } catch (e: Exception) {
                        account.pushStatus = PushStatus.FAILED
                        account.lastPushMessage = e.message ?: "失败"
                        elmStore.update(account)
                        fail++
                    }
                    runOnUiThread { refreshElmList() }
                }
                runOnUiThread {
                    refreshElmList()
                    Toast.makeText(
                        this,
                        getString(R.string.push_all_done, ok, fail),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // endregion

    // region Quark (夸克)

    private fun refreshQuarkList() {
        val list = quarkStore.loadAll()
        quarkAdapter.replaceAll(list)
        tvEmptyQuark.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        rvQuark.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAddQuarkChooser() {
        // Paste first: H5 WebView SMS often fails (Aliyun captcha / risk control).
        val labels = arrayOf(
            getString(R.string.quark_add_paste),
            getString(R.string.quark_add_webview)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_quark)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showPasteQuarkDialog()
                    1 -> QingLongPanel.ensureReady(this) {
                        quarkLoginLauncher.launch(Intent(this, QuarkLoginActivity::class.java))
                    }
                }
            }
            .show()
    }

    private fun showPasteQuarkDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_paste_quark, null)
        val etNick = view.findViewById<EditText>(R.id.etQuarkNickname)
        val etCookie = view.findViewById<EditText>(R.id.etQuarkCookie)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quark_paste_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val raw = etCookie.text.toString()
                if (!QuarkAccountStore.isValidCookie(raw)) {
                    Toast.makeText(this, R.string.quark_cookie_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val nick = etNick.text.toString().trim()
                val account = quarkStore.upsertByCookie(raw, nick.ifBlank { null })
                refreshQuarkList()
                QingLongPanel.ensureReady(this) {
                    pushQuarkAccount(account)
                }
            }
            .show()
    }

    override fun onCopy(account: QuarkAccount) = copy(account.cookie, QuarkAccountStore.ENV_NAME)

    override fun onPushQl(account: QuarkAccount) {
        QingLongPanel.ensureReady(this) { pushQuarkAccount(account) }
    }

    override fun onRename(account: QuarkAccount) {
        val input = EditText(this).apply {
            setText(account.nickname.ifBlank { account.displayName })
            setSelection(text.length)
            hint = getString(R.string.rename_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                quarkStore.rename(account.id, input.text.toString())
                refreshQuarkList()
            }
            .show()
    }

    override fun onDelete(account: QuarkAccount) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_ql_title)
            .setMessage(getString(R.string.confirm_delete_quark_ql_msg, account.displayName))
            .setNeutralButton(R.string.delete_local_only) { _, _ ->
                quarkStore.delete(account.id)
                refreshQuarkList()
            }
            .setNegativeButton(R.string.delete_and_disable_ql) { _, _ ->
                deleteQuarkLocalAndQl(account, remove = false)
            }
            .setPositiveButton(R.string.delete_and_remove_ql) { _, _ ->
                deleteQuarkLocalAndQl(account, remove = true)
            }
            .show()
    }

    private fun deleteQuarkLocalAndQl(account: QuarkAccount, remove: Boolean) {
        quarkStore.delete(account.id)
        refreshQuarkList()
        if (!QingLongPanel.isReady(this)) {
            Toast.makeText(this, R.string.ql_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                val api = QingLongApi(qlConfig.load())
                val envId = account.qlEnvId.ifBlank {
                    api.findQuarkEnv(account)?.idString.orEmpty()
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

    private fun pushQuarkAccount(account: QuarkAccount) {
        account.pushStatus = PushStatus.PUSHING
        quarkStore.update(account)
        refreshQuarkList()
        thread {
            try {
                val result = QingLongApi(qlConfig.load()).pushOrUpdateQuark(account)
                account.qlEnvId = result.envId
                account.pushStatus = PushStatus.SYNCED
                account.lastPushedAt = System.currentTimeMillis()
                account.lastPushMessage = result.message
                quarkStore.update(account)
                runOnUiThread {
                    refreshQuarkList()
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                account.pushStatus = PushStatus.FAILED
                account.lastPushMessage = e.message ?: "失败"
                quarkStore.update(account)
                runOnUiThread {
                    refreshQuarkList()
                    Toast.makeText(this, "推送失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pushAllQuark() {
        QingLongPanel.ensureReady(this) {
            val accounts = quarkStore.loadAll()
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
                        quarkStore.update(account)
                        val result = api.pushOrUpdateQuark(account)
                        account.qlEnvId = result.envId
                        account.pushStatus = PushStatus.SYNCED
                        account.lastPushedAt = System.currentTimeMillis()
                        account.lastPushMessage = result.message
                        quarkStore.update(account)
                        ok++
                    } catch (e: Exception) {
                        account.pushStatus = PushStatus.FAILED
                        account.lastPushMessage = e.message ?: "失败"
                        quarkStore.update(account)
                        fail++
                    }
                    runOnUiThread { refreshQuarkList() }
                }
                runOnUiThread {
                    refreshQuarkList()
                    Toast.makeText(
                        this,
                        getString(R.string.push_all_done, ok, fail),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // endregion

    // region Telecom (电信)

    private fun refreshTelecomList() {
        val list = telecomStore.loadAll()
        telecomAdapter.replaceAll(list)
        tvEmptyTelecom.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        rvTelecom.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showPasteTelecomDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_paste_telecom, null)
        val etNick = view.findViewById<EditText>(R.id.etTelecomNickname)
        val etCred = view.findViewById<EditText>(R.id.etTelecomCredential)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.telecom_paste_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val raw = etCred.text.toString()
                if (!TelecomAccountStore.isValidCredential(raw)) {
                    Toast.makeText(this, R.string.telecom_credential_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val nick = etNick.text.toString().trim()
                val account = telecomStore.upsertByCredential(raw, nick.ifBlank { null })
                refreshTelecomList()
                QingLongPanel.ensureReady(this) {
                    pushTelecomAccount(account)
                }
            }
            .show()
    }

    override fun onCopy(account: TelecomAccount) = copy(account.credential, TelecomAccountStore.ENV_NAME)

    override fun onPushQl(account: TelecomAccount) {
        QingLongPanel.ensureReady(this) { pushTelecomAccount(account) }
    }

    override fun onRename(account: TelecomAccount) {
        val input = EditText(this).apply {
            setText(account.nickname.ifBlank { account.displayName })
            setSelection(text.length)
            hint = getString(R.string.telecom_nick_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                telecomStore.rename(account.id, input.text.toString())
                refreshTelecomList()
            }
            .show()
    }

    override fun onDelete(account: TelecomAccount) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_ql_title)
            .setMessage(getString(R.string.confirm_delete_telecom_ql_msg, account.displayName))
            .setNeutralButton(R.string.delete_local_only) { _, _ ->
                telecomStore.delete(account.id)
                refreshTelecomList()
            }
            .setNegativeButton(R.string.delete_and_disable_ql) { _, _ ->
                deleteTelecomLocalAndQl(account, remove = false)
            }
            .setPositiveButton(R.string.delete_and_remove_ql) { _, _ ->
                deleteTelecomLocalAndQl(account, remove = true)
            }
            .show()
    }

    private fun deleteTelecomLocalAndQl(account: TelecomAccount, remove: Boolean) {
        telecomStore.delete(account.id)
        refreshTelecomList()
        if (!QingLongPanel.isReady(this)) {
            Toast.makeText(this, R.string.ql_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                val api = QingLongApi(qlConfig.load())
                val envId = account.qlEnvId.ifBlank {
                    api.findTelecomEnv(account)?.idString.orEmpty()
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

    private fun pushTelecomAccount(account: TelecomAccount) {
        account.pushStatus = PushStatus.PUSHING
        telecomStore.update(account)
        refreshTelecomList()
        thread {
            try {
                val result = QingLongApi(qlConfig.load()).pushOrUpdateTelecom(account)
                account.qlEnvId = result.envId
                account.pushStatus = PushStatus.SYNCED
                account.lastPushedAt = System.currentTimeMillis()
                account.lastPushMessage = result.message
                telecomStore.update(account)
                runOnUiThread {
                    refreshTelecomList()
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                account.pushStatus = PushStatus.FAILED
                account.lastPushMessage = e.message ?: "失败"
                telecomStore.update(account)
                runOnUiThread {
                    refreshTelecomList()
                    Toast.makeText(this, "推送失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pushAllTelecom() {
        QingLongPanel.ensureReady(this) {
            val accounts = telecomStore.loadAll()
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
                        telecomStore.update(account)
                        val result = api.pushOrUpdateTelecom(account)
                        account.qlEnvId = result.envId
                        account.pushStatus = PushStatus.SYNCED
                        account.lastPushedAt = System.currentTimeMillis()
                        account.lastPushMessage = result.message
                        telecomStore.update(account)
                        ok++
                    } catch (e: Exception) {
                        account.pushStatus = PushStatus.FAILED
                        account.lastPushMessage = e.message ?: "失败"
                        telecomStore.update(account)
                        fail++
                    }
                    runOnUiThread { refreshTelecomList() }
                }
                runOnUiThread {
                    refreshTelecomList()
                    Toast.makeText(
                        this,
                        getString(R.string.push_all_done, ok, fail),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // endregion
}
