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
 * Ele.me WebView login → extract elmck cookie → auto-push Qinglong elmck.
 * May fail on sites that block WebView / require native app; use paste fallback.
 */
class ElmLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tvStep: TextView
    private lateinit var tvError: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var store: ElmAccountStore
    private lateinit var qlConfig: QingLongConfig

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    @Volatile private var extractStarted = false
    @Volatile private var baselineCookie2: String? = null
    @Volatile private var baselineSid: String? = null
    @Volatile private var pushCancelled = false
    private val pushing = AtomicBoolean(false)
    private var pendingAccount: ElmAccount? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (extractStarted || !polling) return
            tryExtract()
            if (!extractStarted) handler.postDelayed(this, 1500L)
        }
    }

    companion object {
        /**
         * H5 entry. Passport may redirect under *.ele.me / alipay / taobao;
         * cookies for elmck are collected from ele.me domains after login.
         */
        private const val LOGIN_URL = "https://h5.ele.me/"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S9280) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        store = ElmAccountStore(this)
        qlConfig = QingLongConfig(this)
        QingLongPanel.ensureDefaultUrl(qlConfig)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = getString(R.string.elm_login_title)

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
            setResult(RESULT_OK)
        }

        setStep(getString(R.string.step_preparing), true)
        webView.visibility = View.VISIBLE
        setupWebView()

        WebViewSession.clearAllAsync(webView) {
            if (isFinishing) return@clearAllAsync
            val raw = WebViewSession.rawElmCookies()
            baselineCookie2 = ElmAccountStore.extractField(raw, "cookie2").ifBlank { null }
            baselineSid = ElmAccountStore.extractField(raw, "SID").ifBlank { null }
            setStep(getString(R.string.elm_step_wait_login), true)
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

    private fun tryExtract() {
        if (extractStarted) return
        val cookies = WebViewSession.rawElmCookies()
        if (!ElmAccountStore.isValidCookie(cookies)) return

        val cookie2 = ElmAccountStore.extractField(cookies, "cookie2")
        val sid = ElmAccountStore.extractField(cookies, "SID")
        // Must be NEW vs session baseline (guards leftovers / async clear races)
        if (baselineCookie2 != null && cookie2.isNotBlank() && cookie2 == baselineCookie2) return
        if (baselineSid != null && sid.isNotBlank() && sid == baselineSid) return

        synchronized(this) {
            if (extractStarted) return
            extractStarted = true
        }
        polling = false
        handler.removeCallbacks(pollRunnable)
        setStep(getString(R.string.step_extracting), true)

        thread {
            val account = store.upsertByCookie(cookies, nickname = null)
            pendingAccount = account
            runOnUiThread {
                if (!isFinishing) pushToQingLong(account)
            }
        }
    }

    private fun pushToQingLong(account: ElmAccount) {
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
                runOnUiThread { setStep(getString(R.string.elm_step_pushing), true) }
                val api = QingLongApi(qlConfig.load())
                api.ensureToken()
                if (pushCancelled) return@thread
                val result = api.pushOrUpdateElm(account)
                if (pushCancelled) return@thread
                account.qlEnvId = result.envId
                account.pushStatus = PushStatus.SYNCED
                account.lastPushedAt = System.currentTimeMillis()
                account.lastPushMessage = result.message
                store.update(account)
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
