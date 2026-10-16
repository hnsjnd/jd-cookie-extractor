package com.example.jdcookie

import android.webkit.CookieManager
import android.webkit.WebView

/**
 * JD / Meituan login session helpers.
 * CookieManager is process-wide within a process. QingLong panel runs in :qlpanel
 * with its own data directory, so the main process may safely wipe all cookies.
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

    private val MEITUAN_URLS = listOf(
        "https://i.meituan.com",
        "https://www.meituan.com",
        "https://meituan.com",
        "https://passport.meituan.com",
        "https://m.meituan.com",
        "https://waimai.meituan.com",
        "https://h5.waimai.meituan.com",
        "https://open.meituan.com",
        "http://i.meituan.com",
        "http://meituan.com"
    )

    /** Clear only JD-domain cookies so QingLong panel login stays intact. */
    fun clearJdCookies(webView: WebView?) {
        clearDomainCookies(JD_URLS)
        webView?.apply {
            clearCache(true)
            clearHistory()
            clearFormData()
        }
    }

    fun clearMeituanCookies(webView: WebView?) {
        clearDomainCookies(MEITUAN_URLS)
        webView?.apply {
            clearCache(true)
            clearHistory()
            clearFormData()
        }
    }

    private fun clearDomainCookies(urls: List<String>) {
        val cm = CookieManager.getInstance()
        for (url in urls) {
            val raw = cm.getCookie(url) ?: continue
            raw.split(";").forEach { part ->
                val name = part.trim().substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cm.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/")
                }
            }
        }
        cm.flush()
    }

    /**
     * Main process only holds JD/Meituan cookies (QL panel runs in :qlpanel with its own
     * data dir), so wiping everything is safe. Calls [onDone] on main thread
     * after removal + flush completed.
     */
    fun clearAllAsync(webView: WebView?, onDone: () -> Unit) {
        val cm = CookieManager.getInstance()
        webView?.apply { clearCache(true); clearHistory(); clearFormData() }
        android.webkit.WebStorage.getInstance().deleteAllData()
        cm.removeAllCookies {
            cm.removeSessionCookies {
                cm.flush()
                onDone()
            }
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

    fun rawMeituanCookies(): String {
        val cm = CookieManager.getInstance()
        val parts = linkedSetOf<String>()
        for (url in MEITUAN_URLS) {
            cm.getCookie(url)?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.let { parts.addAll(it) }
        }
        return parts.joinToString("; ")
    }

    fun parsePt(cookies: String): Pair<String?, String?> {
        val ptKey = Regex("pt_key=([^;]+)").find(cookies)?.groupValues?.get(1)
        val ptPin = Regex("pt_pin=([^;]+)").find(cookies)?.groupValues?.get(1)
        return ptKey to ptPin
    }

    /** Extract Meituan token cookie value (raw, without token=). */
    fun parseMeituanToken(cookies: String): String? {
        val m = Regex("(?:^|[;\\s])token=([^;\\s]+)", RegexOption.IGNORE_CASE)
            .find(cookies) ?: return null
        val v = m.groupValues[1].trim()
        return v.ifBlank { null }
    }
}
