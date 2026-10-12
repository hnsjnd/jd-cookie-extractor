package com.example.jdcookie

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvCookie: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var toolbar: MaterialToolbar

    private lateinit var historyStore: CookieHistoryStore

    private var currentPtKey: String = ""
    private var currentPtPin: String = ""
    private var currentCombined: String = ""

    companion object {
        private const val HOME_URL = "https://m.jd.com/"
        private const val LOGIN_URL =
            "https://plogin.m.jd.com/login/login?appid=300&returnurl=https%3A%2F%2Fm.jd.com%2F"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S9280) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyStore = CookieHistoryStore(this)

        toolbar = findViewById(R.id.toolbar)
        webView = findViewById(R.id.webView)
        tvCookie = findViewById(R.id.tvCookie)
        tvStatus = findViewById(R.id.tvStatus)
        progress = findViewById(R.id.progress)

        setSupportActionBar(toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_history -> {
                    showHistory()
                    true
                }
                R.id.action_refresh -> {
                    webView.reload()
                    true
                }
                R.id.action_open_login -> {
                    webView.loadUrl(LOGIN_URL)
                    true
                }
                R.id.action_clear -> {
                    confirmClearAndRelogin()
                    true
                }
                else -> false
            }
        }

        setupWebView()
        webView.loadUrl(HOME_URL)

        findViewById<MaterialButton>(R.id.btnExtract).setOnClickListener { extractCookie() }
        findViewById<MaterialButton>(R.id.btnCopyCombined).setOnClickListener {
            copyText(currentCombined, needExtract = true)
        }
        findViewById<MaterialButton>(R.id.btnCopyKey).setOnClickListener {
            copyText(currentPtKey, needExtract = true)
        }
        findViewById<MaterialButton>(R.id.btnCopyPin).setOnClickListener {
            copyText(currentPtPin, needExtract = true)
        }
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener { shareCookie() }
        findViewById<MaterialButton>(R.id.btnQr).setOnClickListener { showQr() }

        refreshLoginStatus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = UA
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
                refreshLoginStatus()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    progress.visibility = View.VISIBLE
                } else {
                    progress.visibility = View.GONE
                }
            }
        }
    }

    private fun rawCookies(): String {
        val cm = CookieManager.getInstance()
        return cm.getCookie("https://m.jd.com")
            ?: cm.getCookie("https://jd.com")
            ?: cm.getCookie("https://plogin.m.jd.com")
            ?: ""
    }

    private fun parsePt(cookies: String): Pair<String?, String?> {
        val ptKey = Regex("pt_key=([^;]+)").find(cookies)?.groupValues?.get(1)
        val ptPin = Regex("pt_pin=([^;]+)").find(cookies)?.groupValues?.get(1)
        return ptKey to ptPin
    }

    private fun refreshLoginStatus() {
        val cookies = rawCookies()
        val (key, pin) = parsePt(cookies)
        if (key != null && pin != null) {
            val display = try {
                java.net.URLDecoder.decode(pin, "UTF-8")
            } catch (_: Exception) {
                pin
            }
            tvStatus.text = getString(R.string.status_logged_in, display)
            tvStatus.setTextColor(getColor(R.color.status_ok))
        } else {
            tvStatus.text = getString(R.string.status_not_logged_in)
            tvStatus.setTextColor(getColor(R.color.status_warn))
        }
    }

    private fun extractCookie() {
        val cookies = rawCookies()
        if (cookies.isEmpty()) {
            tvCookie.text = getString(R.string.toast_need_login)
            toast(R.string.toast_need_login)
            refreshLoginStatus()
            return
        }
        val (ptKey, ptPin) = parsePt(cookies)
        if (ptKey != null && ptPin != null) {
            currentPtKey = ptKey
            currentPtPin = ptPin
            currentCombined = "pt_key=$ptKey;pt_pin=$ptPin;"
            tvCookie.text = currentCombined
            historyStore.save(CookieRecord(ptKey, ptPin))
            toast(R.string.toast_extract_ok)
            refreshLoginStatus()
        } else {
            currentPtKey = ""
            currentPtPin = ""
            currentCombined = ""
            tvCookie.text = getString(R.string.not_found)
            toast(R.string.not_found)
            refreshLoginStatus()
        }
    }

    private fun copyText(text: String, needExtract: Boolean) {
        if (text.isEmpty()) {
            toast(if (needExtract) R.string.toast_need_extract else R.string.toast_need_login)
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("JD_COOKIE", text))
        toast(R.string.toast_copied)
    }

    private fun shareCookie() {
        if (currentCombined.isEmpty()) {
            toast(R.string.toast_need_extract)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentCombined)
            putExtra(Intent.EXTRA_SUBJECT, "JD_COOKIE")
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun showQr() {
        if (currentCombined.isEmpty()) {
            toast(R.string.toast_need_extract)
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_qr, null)
        val iv = view.findViewById<ImageView>(R.id.ivQr)
        try {
            iv.setImageBitmap(QrHelper.encode(currentCombined))
        } catch (e: Exception) {
            toast("二维码生成失败: ${e.message}")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qr_title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun confirmClearAndRelogin() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_clear_title)
            .setMessage(R.string.confirm_clear_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                clearCookiesAndRelogin()
            }
            .show()
    }

    private fun clearCookiesAndRelogin() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        currentPtKey = ""
        currentPtPin = ""
        currentCombined = ""
        tvCookie.text = getString(R.string.hint_login)
        toast(R.string.toast_cleared)
        refreshLoginStatus()
        webView.loadUrl(LOGIN_URL)
    }

    private fun showHistory() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_history, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvHistory)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)
        val items = historyStore.load()
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rv.layoutManager = LinearLayoutManager(this)
        lateinit var adapter: HistoryAdapter
        adapter = HistoryAdapter(
            items,
            onCopy = { record ->
                copyText(record.combined, needExtract = false)
            },
            onDelete = { record ->
                historyStore.delete(record.ptPin)
                val updated = historyStore.load()
                adapter.replaceAll(updated)
                tvEmpty.visibility = if (updated.isEmpty()) View.VISIBLE else View.GONE
            }
        )
        rv.adapter = adapter

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
}
