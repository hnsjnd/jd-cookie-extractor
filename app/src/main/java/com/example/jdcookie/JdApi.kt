package com.example.jdcookie

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cookie validity check — same endpoint as hnsjnd/ql-jd-self (jd_ck_status.js):
 * GET https://me-api.jd.com/user_new/info/GetJDUserInfoUnion
 */
object JdApi {

    data class CheckResult(
        val status: AccountStatus,
        val nickname: String? = null,
        val message: String = ""
    )

    fun checkCookie(cookie: String): CheckResult {
        return try {
            val url = URL("https://me-api.jd.com/user_new/info/GetJDUserInfoUnion")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Cookie", cookie)
                setRequestProperty("Referer", "https://home.m.jd.com/myJd/newhome.action")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
                )
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            parse(body)
        } catch (e: Exception) {
            CheckResult(AccountStatus.ERROR, message = "网络失败: ${e.message}")
        }
    }

    private fun parse(body: String): CheckResult {
        return try {
            val info = JSONObject(body)
            val retcode = info.optString("retcode", "")
            when {
                retcode == "0" && info.optJSONObject("data")?.optJSONObject("userInfo") != null -> {
                    val nick = info.getJSONObject("data")
                        .getJSONObject("userInfo")
                        .optJSONObject("baseInfo")
                        ?.optString("nickname")
                        ?.takeIf { it.isNotBlank() }
                    CheckResult(AccountStatus.VALID, nickname = nick, message = "正常")
                }
                retcode == "13" || retcode == "1001" ->
                    CheckResult(AccountStatus.EXPIRED, message = "已掉线")
                else ->
                    CheckResult(AccountStatus.ERROR, message = "未知($retcode)")
            }
        } catch (e: Exception) {
            CheckResult(AccountStatus.ERROR, message = "解析失败")
        }
    }
}
