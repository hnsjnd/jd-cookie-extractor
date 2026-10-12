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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 京豆统计：余额 + 今日增减 + 即将过期（官方 queryexpirejingdou）。
 * Concurrency limited to 2 accounts at a time.
 */
class BeanStatsActivity : AppCompatActivity() {

    private lateinit var store: AccountStore
    private lateinit var cache: BeanStatsCache
    private lateinit var adapter: BeanStatsAdapter
    private lateinit var tvOverview: TextView
    private lateinit var tvFetchedAt: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var btnRefresh: MaterialButton
    private var running = false

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bean_stats)

        store = AccountStore(this)
        cache = BeanStatsCache(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        tvOverview = findViewById(R.id.tvOverview)
        tvFetchedAt = findViewById(R.id.tvFetchedAt)
        tvProgress = findViewById(R.id.tvBeanProgress)
        progress = findViewById(R.id.beanProgress)
        btnRefresh = findViewById(R.id.btnRefreshBeans)

        adapter = BeanStatsAdapter(mutableListOf())
        val rv = findViewById<RecyclerView>(R.id.rvBeanAccounts)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnRefresh.setOnClickListener { refresh(force = true) }

        val (t, rows) = cache.load()
        if (rows.isNotEmpty()) {
            showRows(rows, t)
        } else {
            tvOverview.text = getString(R.string.bean_overview_empty)
            tvFetchedAt.text = getString(R.string.bean_never_fetched)
        }

        // Auto refresh on open if no cache or stale (>6h)
        if (rows.isEmpty() || System.currentTimeMillis() - t > 6 * 3600_000L) {
            refresh(force = true)
        }
    }

    private fun refresh(force: Boolean) {
        if (running) return
        val accounts = store.loadAll()
        if (accounts.isEmpty()) {
            Toast.makeText(this, R.string.no_accounts, Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        btnRefresh.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.max = accounts.size
        progress.progress = 0
        tvProgress.visibility = View.VISIBLE
        tvProgress.text = getString(R.string.bean_progress, 0, accounts.size)

        val pool = Executors.newFixedThreadPool(2)
        val done = AtomicInteger(0)
        val results = arrayOfNulls<JdBeanApi.AccountBeanStats>(accounts.size)

        accounts.forEachIndexed { index, account ->
            pool.execute {
                val stats = JdBeanApi.fetchForAccount(account)
                results[index] = stats
                val n = done.incrementAndGet()
                runOnUiThread {
                    progress.progress = n
                    tvProgress.text = getString(R.string.bean_progress, n, accounts.size)
                }
                if (n == accounts.size) {
                    pool.shutdown()
                    val rows = results.filterNotNull()
                    val now = System.currentTimeMillis()
                    cache.save(now, rows)
                    runOnUiThread {
                        running = false
                        btnRefresh.isEnabled = true
                        progress.visibility = View.GONE
                        tvProgress.visibility = View.GONE
                        showRows(rows, now)
                        Toast.makeText(this, R.string.bean_refresh_done, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showRows(rows: List<JdBeanApi.AccountBeanStats>, fetchedAt: Long) {
        adapter.replaceAll(rows)
        val okRows = rows.filter { it.ok && it.totalBeans != null }
        val sumBeans = okRows.sumOf { it.totalBeans ?: 0 }
        val sumToday = okRows.sumOf { it.todayIncome ?: 0 }
        val sumExpire7 = okRows.filter { it.expireAvailable }.sumOf { it.expireIn7Days ?: 0 }
        val expireNote = if (okRows.any { it.expireAvailable }) {
            getString(R.string.bean_overview_expire7, sumExpire7)
        } else {
            getString(R.string.bean_overview_expire_na)
        }
        tvOverview.text = getString(R.string.bean_overview_fmt, sumBeans, sumToday) + "\n" + expireNote
        tvFetchedAt.text = if (fetchedAt > 0) {
            getString(R.string.bean_fetched_at, timeFmt.format(Date(fetchedAt)))
        } else {
            getString(R.string.bean_never_fetched)
        }
    }
}
