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
import kotlin.concurrent.thread

/**
 * Main flow: JD login → extract cookie → auto-push to QingLong OpenAPI.
 * Steps: 等待登录 → 提取中 → 连接青龙 → 推送中 → 已推送
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tvStep: TextView
    private lateinit var tvError: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var store: AccountStore
    private lateinit var qlConfig: QingLongConfig

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    @Volatile private var finished = false
    private var pendingAccount: Account? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (finished || !polling) return
            tryExtract()
            if (!finished) handler.postDelayed(this, 1500L)
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
        btnRetry.setOnClickListener {
            val acc = pendingAccount
            if (acc != null) {
                hideError()
                pushToQingLong(acc)
            }
        }

        // Credentials first so push can succeed after login
        if (!QingLongPanel.isReady(this)) {
            QingLongPanel.promptSettings(this, requireCredentials = true)
        }

        setStep(getString(R.string.step_wait_login), true)
        setupWebView()
        WebViewSession.clearJdCookies(webView)
        webView.loadUrl(LOGIN_URL)

        polling = true
        handler.postDelayed(pollRunnable, 2000L)
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
                tryExtract()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (finished) return
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

    private fun tryExtract() {
        if (finished) return
        val cookies = WebViewSession.rawJdCookies()
        val (ptKey, ptPin) = WebViewSession.parsePt(cookies)
        if (ptKey.isNullOrBlank() || ptPin.isNullOrBlank()) return

        synchronized(this) {
            if (finished) return
            finished = true
        }
        polling = false
        handler.removeCallbacks(pollRunnable)
        setStep(getString(R.string.step_extracting), true)

        thread {
            val combined = "pt_key=$ptKey;pt_pin=$ptPin;"
            val check = JdApi.checkCookie(combined)
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
            runOnUiThread { pushToQingLong(account) }
        }
    }

    private fun pushToQingLong(account: Account) {
        if (!QingLongPanel.isReady(this)) {
            showError(getString(R.string.ql_need_credentials))
            QingLongPanel.promptSettings(this, requireCredentials = true) {
                hideError()
                pushToQingLong(account)
            }
            return
        }

        setStep(getString(R.string.step_connect_ql), true)
        account.pushStatus = PushStatus.PUSHING
        account.lastPushMessage = "推送中"
        store.update(account)
        btnRetry.visibility = View.GONE

        thread {
            try {
                runOnUiThread { setStep(getString(R.string.step_pushing), true) }
                val api = QingLongApi(qlConfig.load())
                // touch token first for clearer step semantics
                api.ensureToken()
                runOnUiThread { setStep(getString(R.string.step_pushing), true) }
                val result = api.pushOrUpdateCookie(account)
                account.pushStatus = PushStatus.SYNCED
                account.lastPushedAt = System.currentTimeMillis()
                account.lastPushMessage = result.message
                account.qlEnvId = result.envId
                store.update(account)
                runOnUiThread {
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
                account.pushStatus = PushStatus.FAILED
                account.lastPushMessage = e.message ?: "推送失败"
                store.update(account)
                runOnUiThread {
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
        WebViewSession.clearJdCookies(webView)
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
