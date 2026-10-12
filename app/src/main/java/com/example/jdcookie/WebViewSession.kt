package com.example.jdcookie

import android.webkit.CookieManager
import android.webkit.WebView

/** Clears process-wide WebView cookies so each login session is isolated. */
object WebViewSession {
    fun clearAll(webView: WebView?) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        webView?.apply {
            clearCache(true)
            clearHistory()
            clearFormData()
        }
    }

    fun rawJdCookies(): String {
        val cm = CookieManager.getInstance()
        return cm.getCookie("https://m.jd.com")
            ?: cm.getCookie("https://jd.com")
            ?: cm.getCookie("https://plogin.m.jd.com")
            ?: ""
    }

    fun parsePt(cookies: String): Pair<String?, String?> {
        val ptKey = Regex("pt_key=([^;]+)").find(cookies)?.groupValues?.get(1)
        val ptPin = Regex("pt_pin=([^;]+)").find(cookies)?.groupValues?.get(1)
        return ptKey to ptPin
    }
}
