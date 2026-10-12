package com.example.jdcookie

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Full-screen in-app browser for the user's QingLong panel.
 * Cookies persist in CookieManager and are NOT cleared by JD login isolation
 * (JD clear only removes jd.com-family cookies).
 */
class QingLongWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator

    companion object {
        const val EXTRA_URL = "url"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qinglong_web)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { handleBack() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    webView.reload()
                    true
                }
                R.id.action_open_external -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView.url ?: url)))
                    true
                }
                R.id.action_home -> {
                    val base = QingLongConfig(this).load().baseUrl
                    if (base.isNotBlank()) webView.loadUrl(base)
                    true
                }
                else -> false
            }
        }

        progress = findViewById(R.id.progress)
        webView = findViewById(R.id.webView)
        setupWebView()
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            // Allow cleartext / local panel assets
            allowFileAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = userAgentString // keep system UA for panel UI
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                progress.visibility = View.GONE
                findViewById<MaterialToolbar>(R.id.toolbar).subtitle = url
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    progress.visibility = View.VISIBLE
                    progress.isIndeterminate = false
                    progress.progress = newProgress
                } else {
                    progress.visibility = View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    findViewById<MaterialToolbar>(R.id.toolbar).title = title
                }
            }
        }
    }

    private fun handleBack() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }
}
