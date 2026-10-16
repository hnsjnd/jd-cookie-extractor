package com.example.jdcookie

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
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
 * Quark WebView login → intercept growth/reward (or any drive-m URL with kps/sign/vcode)
 * → COOKIE_QUARK → auto-push. Native-app signed params often missing in WebView; paste fallback.
 */
class QuarkLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tvStep: TextView
    private lateinit var tvError: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var store: QuarkAccountStore
    private lateinit var qlConfig: QingLongConfig

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    @Volatile private var extractStarted = false
    @Volatile private var baselineFingerprint: String? = null
    @Volatile private var pushCancelled = false
    @Volatile private var triedGrowthNav = false
    @Volatile private var loginLikely = false
    private val pushing = AtomicBoolean(false)
    private var pendingAccount: QuarkAccount? = null
    private var pendingNickname: String? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (extractStarted || !polling) return
            tryExtractFromCookiesAndPage()
            maybeNavigateGrowth()
            if (!extractStarted) handler.postDelayed(this, 1500L)
        }
    }

    companion object {
        /**
         * Official Quark cloud H5 phone login (from pan.js h5LoginPage).
         * pan.quark.cn on mobile UA redirects to App download (h5.sm.cn);
         * this CAS page stays in WebView. redirect_uri seeds pan session via
         * account/info (JSON, no download landing), then growth probe runs.
         */
        private const val LOGIN_URL =
            "https://uop.quark.cn/cas/custom/login" +
                "?custom_login_type=mobile&client_id=503&display=mobile" +
                "&redirect_uri=https%3A%2F%2Fpan.quark.cn%2Faccount%2Finfo"
        /**
         * Soft probe after login. PC WebView usually uses Cookie auth (no kps);
         * mobile growth/reward URLs with kps/sign/vcode are still preferred when intercepted.
         */
        private const val GROWTH_INFO_HINT =
            "https://drive-m.quark.cn/1/clouddrive/capacity/growth/info?pr=ucpro&fr=android"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S9280) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36 Quark/7.9.0.700"

        private const val BRIDGE = "QuarkBridge"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        store = QuarkAccountStore(this)
        qlConfig = QingLongConfig(this)
        QingLongPanel.ensureDefaultUrl(qlConfig)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = getString(R.string.quark_login_title)

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
            baselineFingerprint = QuarkAccountStore.fingerprintFromRaw(
                WebViewSession.rawQuarkCookies()
            )
            setStep(getString(R.string.quark_step_wait_login), true)
            webView.loadUrl(LOGIN_URL)
            polling = true
            handler.postDelayed(pollRunnable, 2500L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
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
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onUrl(url: String?) {
                if (url.isNullOrBlank()) return
                handler.post { considerCredentialUrl(url) }
            }

            @JavascriptInterface
            fun onCreds(kps: String?, sign: String?, vcode: String?, sourceUrl: String?) {
                handler.post {
                    val creds = QuarkAccountStore.QuarkCreds(
                        kps = kps.orEmpty(),
                        sign = sign.orEmpty(),
                        vcode = vcode.orEmpty(),
                        sourceUrl = sourceUrl?.takeIf { it.isNotBlank() }
                    )
                    if (QuarkAccountStore.isCompleteCreds(creds)) {
                        onCredsFound(creds)
                    }
                }
            }

            @JavascriptInterface
            fun onNickname(name: String?) {
                if (!name.isNullOrBlank()) pendingNickname = name.trim()
            }
        }, BRIDGE)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                request?.url?.toString()?.let { considerCredentialUrl(it) }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                request?.let { req ->
                    val url = req.url?.toString().orEmpty()
                    if (url.isNotBlank()) {
                        handler.post { considerCredentialUrl(url) }
                    }
                    // Native-style headers (when present)
                    val hKps = req.requestHeaders?.entries?.find {
                        it.key.equals("x-u-kps-wg", true) || it.key.equals("kps", true)
                    }?.value
                    val hSign = req.requestHeaders?.entries?.find {
                        it.key.equals("x-u-sign-wg", true) || it.key.equals("sign", true)
                    }?.value
                    val hVcode = req.requestHeaders?.entries?.find {
                        it.key.equals("x-u-vcode", true) || it.key.equals("vcode", true)
                    }?.value
                    if (!hKps.isNullOrBlank() || !hSign.isNullOrBlank() || !hVcode.isNullOrBlank()) {
                        val fromUrl = QuarkAccountStore.parseCredsFromUrl(url)
                        val creds = QuarkAccountStore.QuarkCreds(
                            kps = hKps ?: fromUrl?.kps.orEmpty(),
                            sign = hSign ?: fromUrl?.sign.orEmpty(),
                            vcode = hVcode ?: fromUrl?.vcode.orEmpty(),
                            sourceUrl = url.takeIf {
                                it.contains("drive-m.quark.cn", true) ||
                                    QuarkAccountStore.urlLooksLikeGrowth(it)
                            } ?: fromUrl?.sourceUrl
                        )
                        if (QuarkAccountStore.isCompleteCreds(creds)) {
                            handler.post { onCredsFound(creds) }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                android.webkit.CookieManager.getInstance().flush()
                url?.let { considerCredentialUrl(it) }
                injectHooks()
                tryExtractFromCookiesAndPage()
                maybeNavigateGrowth()
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

    private fun injectHooks() {
        if (extractStarted) return
        val js = """
            (function(){
              try {
                if (window.__quarkHooked) return;
                window.__quarkHooked = true;
                var B = window.QuarkBridge;
                function report(u){
                  try { if (u && B) B.onUrl(String(u)); } catch(e){}
                }
                function dig(store){
                  if (!store) return;
                  var kps='', sign='', vcode='';
                  for (var i=0;i<store.length;i++){
                    var key = store.key(i);
                    var val = store.getItem(key) || '';
                    if ((key === 'kps' || /(?:^|[_\.])kps$/i.test(key)) && val) kps = val;
                    if ((key === 'sign' || /(?:^|[_\.])sign$/i.test(key)) && val) sign = val;
                    if ((key === 'vcode' || /vcode/i.test(key)) && val) vcode = val;
                    if (/drive-m\.quark\.cn/i.test(val) && /kps=/i.test(val)) report(val);
                    try {
                      var o = JSON.parse(val);
                      if (o) {
                        if (o.kps) kps = o.kps;
                        if (o.sign) sign = o.sign;
                        if (o.vcode) vcode = o.vcode;
                        if (o.url) report(o.url);
                      }
                    } catch(e){}
                  }
                  if (kps && sign && vcode) {
                    try { B.onCreds(kps, sign, vcode, ''); } catch(e){}
                  }
                }
                try { dig(window.localStorage); dig(window.sessionStorage); } catch(e){}
                var oOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(m,u){
                  try { report(u); } catch(e){}
                  return oOpen.apply(this, arguments);
                };
                if (window.fetch) {
                  var oFetch = window.fetch;
                  window.fetch = function(input, init){
                    try {
                      var u = (typeof input === 'string') ? input : (input && input.url);
                      report(u);
                    } catch(e){}
                    return oFetch.apply(this, arguments);
                  };
                }
                try {
                  var el = document.querySelector('[class*="nick"],[class*="user-name"],[class*="username"]');
                  if (el && el.textContent && B) B.onNickname(el.textContent.trim());
                } catch(e){}
              } catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun considerCredentialUrl(url: String) {
        if (extractStarted) return
        val creds = QuarkAccountStore.parseCredsFromUrl(url) ?: return
        if (!QuarkAccountStore.isCompleteCreds(creds)) return
        onCredsFound(creds)
    }

    private fun tryExtractFromCookiesAndPage() {
        if (extractStarted) return
        val raw = WebViewSession.rawQuarkCookies()
        val fp = QuarkAccountStore.fingerprintFromRaw(raw)
        if (fp.isNotBlank() && fp != baselineFingerprint && QuarkAccountStore.looksLoggedIn(raw)) {
            loginLikely = true
        }
        // Rare: kps/sign/vcode appear as cookie fields
        val fromCookie = QuarkAccountStore.parseCredsFromCookieHeader(raw)
        if (fromCookie != null && QuarkAccountStore.isCompleteCreds(fromCookie)) {
            if (baselineFingerprint != null && fp == baselineFingerprint) return
            onCredsFound(fromCookie)
        }
    }

    private fun maybeNavigateGrowth() {
        if (extractStarted || triedGrowthNav || !loginLikely) return
        triedGrowthNav = true
        // Soft navigation: may 401 without kps; still useful if page JS attaches params
        handler.postDelayed({
            if (extractStarted || isFinishing) return@postDelayed
            setStep(getString(R.string.quark_step_probe_growth), true)
            webView.loadUrl(GROWTH_INFO_HINT)
        }, 1200L)
    }

    private fun onCredsFound(creds: QuarkAccountStore.QuarkCreds) {
        if (extractStarted) return
        // Guard leftovers identical to cleared baseline cookie fingerprint only for cookie-sourced
        synchronized(this) {
            if (extractStarted) return
            extractStarted = true
        }
        polling = false
        handler.removeCallbacks(pollRunnable)
        setStep(getString(R.string.step_extracting), true)

        val nick = pendingNickname
        val cookie = QuarkAccountStore.buildCookie(nick.orEmpty(), creds, preferFullUrl = true)
        thread {
            val account = store.upsertByCookie(cookie, nickname = nick)
            pendingAccount = account
            runOnUiThread {
                if (!isFinishing) pushToQingLong(account)
            }
        }
    }

    private fun pushToQingLong(account: QuarkAccount) {
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
                runOnUiThread { setStep(getString(R.string.quark_step_pushing), true) }
                val api = QingLongApi(qlConfig.load())
                api.ensureToken()
                if (pushCancelled) return@thread
                val result = api.pushOrUpdateQuark(account)
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
        try {
            webView.removeJavascriptInterface(BRIDGE)
        } catch (_: Exception) {
        }
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
