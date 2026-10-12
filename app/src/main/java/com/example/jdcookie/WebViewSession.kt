package com.example.jdcookie

import android.webkit.CookieManager
import android.webkit.WebView

/**
 * JD login session helpers.
 * CookieManager is process-wide, so we never call removeAllCookies() —
 * that would also log the user out of the in-app QingLong panel.
 */
object WebViewSession {

    private val JD_URLS = listOf(
        "https://m.jd.com",
        "https://www.jd.com",
        "https://jd.com",
        "http://m.jd.com",
        "http://jd.com",
        "https://plogin.m.jd.com",
        "https://home.m.jd.com",
        "https://me-api.jd.com",
        "https://api.m.jd.com",
        "https://wq.jd.com",
        "https://passport.jd.com"
    )

    /** Clear only JD-domain cookies so QingLong panel login stays intact. */
    fun clearJdCookies(webView: WebView?) {
        val cm = CookieManager.getInstance()
        for (url in JD_URLS) {
            val raw = cm.getCookie(url) ?: continue
            raw.split(";").forEach { part ->
                val name = part.trim().substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cm.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/")
                }
            }
        }
        cm.flush()
        webView?.apply {
            clearCache(true)
            clearHistory()
            clearFormData()
        }
    }

    @Deprecated("Use clearJdCookies", ReplaceWith("clearJdCookies(webView)"))
    fun clearAll(webView: WebView?) = clearJdCookies(webView)

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
