package com.example.jdcookie

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.concurrent.thread

/**
 * Task progress screen.
 *
 * What it actually does:
 * 1) Local batch job: check each account's cookie via JD GetJDUserInfoUnion
 *    (same as ql-jd-self), with overall + per-account progress bars and results.
 * 2) Optional: if QingLong is configured, fetch cron task list and show
 *    enabled/disabled + last run status (not live log streaming).
 */
class TaskProgressActivity : AppCompatActivity() {

    private lateinit var store: AccountStore
    private lateinit var qlConfig: QingLongConfig
    private lateinit var overallBar: LinearProgressIndicator
    private lateinit var tvOverall: TextView
    private lateinit var tvSectionCk: TextView
    private lateinit var tvSectionQl: TextView
    private lateinit var adapter: TaskItemAdapter
    private lateinit var qlAdapter: TaskItemAdapter
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_progress)

        store = AccountStore(this)
        qlConfig = QingLongConfig(this)
        CkInvalidAlert.ensureChannel(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        overallBar = findViewById(R.id.overallProgress)
        tvOverall = findViewById(R.id.tvOverall)
        tvSectionCk = findViewById(R.id.tvSectionCk)
        tvSectionQl = findViewById(R.id.tvSectionQl)

        adapter = TaskItemAdapter(mutableListOf())
        val rvTasks = findViewById<RecyclerView>(R.id.rvTasks)
        rvTasks.layoutManager = LinearLayoutManager(this)
        rvTasks.adapter = adapter

        qlAdapter = TaskItemAdapter(mutableListOf())
        val rvQl = findViewById<RecyclerView>(R.id.rvQlTasks)
        rvQl.layoutManager = LinearLayoutManager(this)
        rvQl.adapter = qlAdapter

        findViewById<MaterialButton>(R.id.btnStartCheck).setOnClickListener { startCookieCheck() }
        findViewById<MaterialButton>(R.id.btnQlOpenHome).setOnClickListener {
            QingLongPanel.openPanel(this, "/")
        }
        findViewById<MaterialButton>(R.id.btnQlOpenCrontab).setOnClickListener {
            QingLongPanel.openPanel(this, "/crontab")
        }
        findViewById<MaterialButton>(R.id.btnQlOpenLog).setOnClickListener {
            QingLongPanel.openPanel(this, "/log")
        }
        findViewById<MaterialButton>(R.id.btnLoadQl).setOnClickListener { loadQingLongCrons() }
        findViewById<MaterialButton>(R.id.btnPushAll).setOnClickListener { pushAllToQingLong() }

        prepareRows()
        updateQlButtons()
        updateNurtureTip()

        if (intent.getBooleanExtra("autostart", false)) {
            startCookieCheck()
        }
    }

    private fun updateNurtureTip() {
        val tip = findViewById<TextView>(R.id.tvNurtureTaskTip)
        val on = qlConfig.isNurtureMode()
        tip.visibility = if (on) View.VISIBLE else View.GONE
        // Soften push-all suggestion when nurturing
        findViewById<MaterialButton>(R.id.btnPushAll).alpha = if (on) 0.55f else 1f
    }

    private fun updateQlButtons() {
        val ok = qlConfig.load().isConfigured
        findViewById<MaterialButton>(R.id.btnLoadQl).isEnabled = ok
        findViewById<MaterialButton>(R.id.btnPushAll).isEnabled = ok
        tvSectionQl.text = if (ok) {
            getString(R.string.section_ql_crons)
        } else {
            getString(R.string.section_ql_not_configured)
        }
    }

    private fun prepareRows() {
        val accounts = store.loadAll()
        val rows = accounts.map {
            TaskRow(
                id = it.id,
                title = it.displayName,
                subtitle = "pt_pin: ${it.decodedPin}",
                progress = 0,
                result = getString(R.string.task_pending),
                done = false
            )
        }
        adapter.replaceAll(rows)
        tvSectionCk.text = getString(R.string.section_ck_check, accounts.size)
        tvOverall.text = getString(R.string.overall_idle, accounts.size)
        overallBar.isIndeterminate = false
        overallBar.progress = 0
    }

    private fun startCookieCheck() {
        if (running) return
        val accounts = store.loadAll()
        if (accounts.isEmpty()) {
            Toast.makeText(this, R.string.no_accounts, Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        prepareRows()
        overallBar.isIndeterminate = false
        findViewById<MaterialButton>(R.id.btnStartCheck).isEnabled = false

        thread {
            var doneCount = 0
            val total = accounts.size
            for (account in accounts) {
                runOnUiThread {
                    adapter.update(account.id) {
                        it.progress = 10
                        it.result = getString(R.string.status_checking)
                        it.done = false
                    }
                    account.status = AccountStatus.CHECKING
                    store.update(account)
                }

                val result = JdApi.checkCookie(account.combined)
                account.status = result.status
                account.lastCheckedAt = System.currentTimeMillis()
                account.lastMessage = result.message
                if (!result.nickname.isNullOrBlank() && account.nickname.isBlank()) {
                    account.nickname = result.nickname
                }
                store.update(account)

                doneCount++
                val overall = (doneCount * 100) / total
                runOnUiThread {
                    adapter.update(account.id) {
                        it.progress = 100
                        it.done = true
                        it.result = when (result.status) {
                            AccountStatus.VALID -> "✓ ${result.message}" +
                                (result.nickname?.let { n -> " · $n" } ?: "")
                            AccountStatus.EXPIRED -> "✗ ${result.message}"
                            else -> "! ${result.message}"
                        }
                        if (!result.nickname.isNullOrBlank()) {
                            it.title = result.nickname
                        }
                    }
                    overallBar.progress = overall
                    tvOverall.text = getString(R.string.overall_progress, doneCount, total, overall)
                }
            }
            runOnUiThread {
                running = false
                findViewById<MaterialButton>(R.id.btnStartCheck).isEnabled = true
                val all = store.loadAll()
                val valid = all.count { it.status == AccountStatus.VALID }
                val expired = all.count { it.status == AccountStatus.EXPIRED }
                tvOverall.text = getString(R.string.overall_done, valid, expired, total)
                val invalid = CkInvalidAlert.expiredOf(all)
                if (invalid.isNotEmpty()) {
                    CkInvalidAlert.notifyIfNeeded(this, invalid)
                }
                Toast.makeText(this, R.string.check_finished, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadQingLongCrons() {
        val cfg = qlConfig.load()
        if (!cfg.isConfigured) {
            Toast.makeText(this, R.string.ql_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        tvSectionQl.text = getString(R.string.section_ql_loading)
        thread {
            try {
                val crons = QingLongApi(cfg).listCrons()
                val rows = crons.take(50).map { c ->
                    val state = when {
                        c.isDisabled == 1 -> "已禁用"
                        c.status == 0 -> "运行中/排队"
                        else -> "空闲"
                    }
                    val last = when (c.lastStatus) {
                        0 -> "上次成功"
                        1 -> "上次失败"
                        else -> "无上次状态"
                    }
                    TaskRow(
                        id = c.id?.toString() ?: c.name,
                        title = c.name,
                        subtitle = c.schedule.ifBlank { "-" } + " · $state",
                        progress = if (c.status == 0 && c.isDisabled != 1) 50 else 100,
                        result = last,
                        done = c.status != 0 || c.isDisabled == 1
                    )
                }
                runOnUiThread {
                    qlAdapter.replaceAll(rows)
                    tvSectionQl.text = getString(R.string.section_ql_crons_count, rows.size)
                    findViewById<RecyclerView>(R.id.rvQlTasks).visibility =
                        if (rows.isEmpty()) View.GONE else View.VISIBLE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvSectionQl.text = getString(R.string.section_ql_error, e.message ?: "")
                    Toast.makeText(this, "青龙: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pushAllToQingLong() {
        val cfg = qlConfig.load()
        if (!cfg.isConfigured) {
            Toast.makeText(this, R.string.ql_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        val accounts = store.loadAll()
        if (accounts.isEmpty()) {
            Toast.makeText(this, R.string.no_accounts, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.ql_pushing, Toast.LENGTH_SHORT).show()
        thread {
            val api = QingLongApi(cfg)
            var ok = 0
            var fail = 0
            val invalidFromQl = mutableListOf<Account>()
            for (a in accounts) {
                try {
                    val result = api.pushOrUpdateCookie(a, cfg.nurtureMode)
                    if (result.disabledLikelyInvalid) {
                        a.pushStatus = PushStatus.FAILED
                        a.lastPushMessage = result.message
                        if (a.status != AccountStatus.EXPIRED) {
                            a.status = AccountStatus.EXPIRED
                            a.lastMessage = getString(R.string.ck_invalid_ql_disabled)
                        }
                        store.update(a)
                        invalidFromQl.add(a)
                        fail++
                    } else {
                        a.pushStatus = PushStatus.SYNCED
                        a.lastPushedAt = System.currentTimeMillis()
                        a.lastPushMessage = result.message
                        a.qlEnvId = result.envId
                        store.update(a)
                        ok++
                    }
                } catch (_: Exception) {
                    fail++
                }
            }
            runOnUiThread {
                if (invalidFromQl.isNotEmpty()) {
                    CkInvalidAlert.notifyIfNeeded(this, invalidFromQl)
                }
                Toast.makeText(this, "推送完成：成功 $ok，失败 $fail", Toast.LENGTH_LONG).show()
            }
        }
    }
}
