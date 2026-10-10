package com.example.jdcookie

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvCookie: TextView
    private lateinit var btnExtract: Button
    private lateinit var btnCopy: Button

    private var extractedCookie = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        tvCookie = findViewById(R.id.tvCookie)
        btnExtract = findViewById(R.id.btnExtract)
        btnCopy = findViewById(R.id.btnCopy)

        setupWebView()
        webView.loadUrl("https://m.jd.com/")

        btnExtract.setOnClickListener { extractCookie() }
        btnCopy.setOnClickListener { copyToClipboard() }
    }

    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; SM-S9280) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        webView.webViewClient = object : WebViewClient() {}
    }

    private fun extractCookie() {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie("https://m.jd.com")
            ?: cookieManager.getCookie("https://jd.com")
            ?: ""

        if (cookies.isEmpty()) {
            tvCookie.text = "未获取到 Cookie，请先登录"
            Toast.makeText(this, "请先完成登录", Toast.LENGTH_SHORT).show()
            return
        }

        val ptKey = Regex("pt_key=([^;]+)").find(cookies)?.groupValues?.get(1)
        val ptPin = Regex("pt_pin=([^;]+)").find(cookies)?.groupValues?.get(1)

        if (ptKey != null && ptPin != null) {
            extractedCookie = "pt_key=$ptKey;pt_pin=$ptPin;"
            tvCookie.text = extractedCookie
            Toast.makeText(this, "提取成功", Toast.LENGTH_SHORT).show()
        } else {
            tvCookie.text = "未找到 pt_key / pt_pin\n原始 Cookie：\n$cookies"
        }
    }

    private fun copyToClipboard() {
        if (extractedCookie.isEmpty()) {
            Toast.makeText(this, "请先提取 Cookie", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("JD_COOKIE", extractedCookie))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
