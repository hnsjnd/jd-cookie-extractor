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
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.concurrent.thread

/**
 * Isolated login session: clears WebView cookies before load so accounts never mix.
 * Auto-detects pt_key/pt_pin after login — no manual extract button.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tvStep: TextView
    private lateinit var store: AccountStore

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    @Volatile private var finished = false
    private var targetAccountId: String? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (finished || !polling) return
            tryExtract(auto = true)
            if (!finished) handler.postDelayed(this, 1500L)
        }
    }

    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_MODE = "mode" // add | relogin
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
        targetAccountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = if (targetAccountId != null) {
            getString(R.string.relogin_title)
        } else {
            getString(R.string.add_account_title)
        }

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        tvStep = findViewById(R.id.tvStep)

        setStep(getString(R.string.step_wait_login), indeterminate = true)
        setupWebView()

        // Isolate: wipe any previous account cookies before this login
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
                tryExtract(auto = true)
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

    private fun tryExtract(auto: Boolean) {
        if (finished) return
        val cookies = WebViewSession.rawJdCookies()
        val (ptKey, ptPin) = WebViewSession.parsePt(cookies)
        if (ptKey.isNullOrBlank() || ptPin.isNullOrBlank()) {
            if (!auto) setStep(getString(R.string.step_wait_login), true)
            return
        }
        // Found cookies — stop polling and finalize on background thread
        synchronized(this) {
            if (finished) return
            finished = true
        }
        polling = false
        handler.removeCallbacks(pollRunnable)
        setStep(getString(R.string.step_detecting), true)

        thread {
            runOnUiThread { setStep(getString(R.string.step_extracting), true) }
            val combined = "pt_key=$ptKey;pt_pin=$ptPin;"
            val check = JdApi.checkCookie(combined)
            val nick = check.nickname
            runOnUiThread {
                setStep(getString(R.string.step_saving), true)
                val account = store.upsertByPin(ptKey, ptPin, nick)
                if (check.status == AccountStatus.VALID) {
                    account.status = AccountStatus.VALID
                    account.lastCheckedAt = System.currentTimeMillis()
                    account.lastMessage = "正常"
                    if (!nick.isNullOrBlank()) account.nickname = nick
                    store.update(account)
                }
                // If relogin targeted a different id with same pin, upsert already merged by pin
                setStep(getString(R.string.step_saved), false)
                progress.isIndeterminate = false
                progress.progress = 100
                handler.postDelayed({
                    setResult(RESULT_OK)
                    finish()
                }, 600L)
            }
        }
    }

    private fun setStep(text: String, indeterminate: Boolean) {
        tvStep.text = text
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = indeterminate
        if (!indeterminate) progress.progress = 100
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        // Leave WebView clean so next session starts fresh
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

/** Small helper for third-party cookie accept. */
object CookieManagerCompat {
    fun accept(webView: WebView) {
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
    }
}
