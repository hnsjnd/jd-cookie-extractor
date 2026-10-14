package com.example.jdcookie

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Isolated JD login → extract → auto-push.
 *
 * Guards against false-positive extraction from leftover cookies:
 * 1) Fully clear WebView cookies (await callback) before loading login.
 * 2) Snapshot cookies at session start; only accept a NEW pt_key.
 * 3) Require navigation away from the login URL before extraction.
 * 4) WebView stays visible and fills remaining screen while waiting.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tvStep: TextView
    private lateinit var tvError: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var store: AccountStore
    private lateinit var qlConfig: QingLongConfig

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    @Volatile private var extractStarted = false
    @Volatile private var loginPageReady = false
    @Volatile private var leftLoginPage = false
    @Volatile private var baselinePtKey: String? = null
    @Volatile private var baselinePtPin: String? = null
    @Volatile private var pushCancelled = false
    private val pushing = AtomicBoolean(false)
    private var pendingAccount: Account? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (extractStarted || !polling) return
            tryExtract()
            if (!extractStarted) handler.postDelayed(this, 1500L)
        }
    }

    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_MODE = "mode"
        private const val LOGIN_URL =
            "https://plogin.m.jd.com/login/login?appid=300&returnurl=https%3A%2F%2Fm.jd.com%2F"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S9280) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        store = AccountStore(this)
        qlConfig = QingLongConfig(this)
        QingLongPanel.ensureDefaultUrl(qlConfig)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = if (intent.getStringExtra(EXTRA_ACCOUNT_ID) != null) {
            getString(R.string.relogin_title)
        } else {
            getString(R.string.add_account_title)
        }

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        tvStep = findViewById(R.id.tvStep)
        tvError = findViewById(R.id.tvError)
        btnRetry = findViewById(R.id.btnRetryPush)
        btnCancel = findViewById(R.id.btnCancelPush)

        btnRetry.setOnClickListener {
            val acc = pendingAccount ?: return@setOnClickListener
            hideError()
            pushCancelled = false
            pushToQingLong(acc)
        }
        btnCancel.setOnClickListener {
            pushCancelled = true
            pushing.set(false)
            btnCancel.visibility = View.GONE
            setStep(getString(R.string.step_push_failed) + "（已取消）", false)
            showError(getString(R.string.cancel_push))
            btnRetry.visibility = View.VISIBLE
            // Keep account locally; user can retry push
            setResult(RESULT_OK)
        }

        if (!QingLongPanel.isReady(this)) {
            QingLongPanel.promptSettings(this, requireCredentials = true)
        }

        setStep(getString(R.string.step_preparing), true)
        webView.visibility = View.VISIBLE
        setupWebView()

        // Robust clear: main process only has JD cookies (QL panel is :qlpanel)
        WebViewSession.clearAllAsync(webView) {
            if (isFinishing) return@clearAllAsync
            // Snapshot after clear — should be empty; used as baseline
            val (k0, p0) = WebViewSession.parsePt(WebViewSession.rawJdCookies())
            baselinePtKey = k0
            baselinePtPin = p0
            setStep(getString(R.string.step_wait_login), true)
            webView.loadUrl(LOGIN_URL)
            polling = true
            handler.postDelayed(pollRunnable, 2500L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManagerCompat.accept(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = UA
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

            override fun onPageFinished(view: WebView?, url: String?) {
                android.webkit.CookieManager.getInstance().flush()
                val u = url.orEmpty()
                if (isLoginUrl(u)) {
                    loginPageReady = true
                } else if (loginPageReady && u.isNotBlank() && !isLoginUrl(u)) {
                    leftLoginPage = true
                }
                tryExtract()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (extractStarted) return
                if (newProgress in 1..99) {
                    progress.visibility = View.VISIBLE
                    if (!progress.isIndeterminate) {
                        progress.isIndeterminate = false
                        progress.progress = newProgress
                    }
                }
            }
        }
    }

    private fun isLoginUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("plogin.m.jd.com") ||
            u.contains("passport.jd.com") ||
            (u.contains("login") && u.contains("jd.com") && !u.contains("m.jd.com/#"))
    }

    private fun tryExtract() {
        if (extractStarted) return
        if (!loginPageReady || !leftLoginPage) return

        val cookies = WebViewSession.rawJdCookies()
        val (ptKey, ptPin) = WebViewSession.parsePt(cookies)
        if (ptKey.isNullOrBlank() || ptPin.isNullOrBlank()) return

        // Must be a NEW cookie vs session baseline (guards async clear races / leftovers)
        if (baselinePtKey != null && ptKey == baselinePtKey) return
        if (baselinePtPin != null && ptPin == baselinePtPin) return

        synchronized(this) {
            if (extractStarted) return
            extractStarted = true
        }
        polling = false
        handler.removeCallbacks(pollRunnable)
        setStep(getString(R.string.step_extracting), true)

        thread {
            val check = JdApi.checkCookie("pt_key=$ptKey;pt_pin=$ptPin;")
            val nick = check.nickname
            val account = store.upsertByPin(ptKey, ptPin, nick)
            if (check.status == AccountStatus.VALID) {
                account.status = AccountStatus.VALID
                account.lastCheckedAt = System.currentTimeMillis()
                account.lastMessage = "正常"
                if (!nick.isNullOrBlank()) account.nickname = nick
                store.update(account)
            }
            pendingAccount = account
            runOnUiThread {
                if (!isFinishing) pushToQingLong(account)
            }
        }
    }

    private fun pushToQingLong(account: Account) {
        if (pushCancelled) return
        if (!QingLongPanel.isReady(this)) {
            showError(getString(R.string.ql_need_credentials))
            QingLongPanel.promptSettings(this, requireCredentials = true) {
                hideError()
                pushToQingLong(account)
            }
            return
        }
        if (!pushing.compareAndSet(false, true)) return

        setStep(getString(R.string.step_connect_ql), true)
        btnCancel.visibility = View.VISIBLE
        btnRetry.visibility = View.GONE
        account.pushStatus = PushStatus.PUSHING
        account.lastPushMessage = "推送中"
        store.update(account)

        thread {
            try {
                if (pushCancelled) return@thread
                runOnUiThread { setStep(getString(R.string.step_pushing), true) }
                val cfg = qlConfig.load()
                val api = QingLongApi(cfg)
                api.ensureToken()
                if (pushCancelled) return@thread
                runOnUiThread { setStep(getString(R.string.step_pushing), true) }
                val result = api.pushOrUpdateCookie(account, cfg.nurtureMode)
                if (pushCancelled) return@thread
                account.qlEnvId = result.envId
                if (result.disabledLikelyInvalid) {
                    account.pushStatus = PushStatus.FAILED
                    account.lastPushMessage = result.message
                    account.status = AccountStatus.EXPIRED
                    account.lastMessage = getString(R.string.ck_invalid_ql_disabled)
                    store.update(account)
                    runOnUiThread {
                        CkInvalidAlert.notifyIfNeeded(this@LoginActivity, listOf(account))
                    }
                } else {
                    account.pushStatus = PushStatus.SYNCED
                    account.lastPushedAt = System.currentTimeMillis()
                    account.lastPushMessage = result.message
                    store.update(account)
                }
                runOnUiThread {
                    pushing.set(false)
                    btnCancel.visibility = View.GONE
                    setStep(getString(R.string.step_pushed), false)
                    progress.isIndeterminate = false
                    progress.progress = 100
                    hideError()
                    handler.postDelayed({
                        setResult(RESULT_OK)
                        finish()
                    }, 800L)
                }
            } catch (e: Exception) {
                if (pushCancelled) return@thread
                account.pushStatus = PushStatus.FAILED
                account.lastPushMessage = e.message ?: "推送失败"
                store.update(account)
                runOnUiThread {
                    pushing.set(false)
                    btnCancel.visibility = View.GONE
                    setStep(getString(R.string.step_push_failed), false)
                    showError(e.message ?: getString(R.string.step_push_failed))
                    btnRetry.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setStep(text: String, indeterminate: Boolean) {
        tvStep.text = text
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = indeterminate
        if (!indeterminate) progress.progress = 100
    }

    private fun showError(msg: String) {
        tvError.visibility = View.VISIBLE
        tvError.text = msg
    }

    private fun hideError() {
        tvError.visibility = View.GONE
        tvError.text = ""
        btnRetry.visibility = View.GONE
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        pushCancelled = true
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}

object CookieManagerCompat {
    fun accept(webView: WebView) {
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
    }
}
