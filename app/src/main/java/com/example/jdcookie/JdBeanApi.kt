package com.example.jdcookie

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * JingBean (京豆) APIs used by common QL scripts (jd_bean_info / jd_bean_change):
 *
 * 1) Balance: GET me-api.jd.com/.../GetJDUserInfoUnion → data.assetInfo.beanNum
 * 2) Detail / today: POST api.m.jd.com/...functionId=getJingBeanBalanceDetail
 * 3) Expire: GET wq.jd.com/activep3/singjd/queryexpirejingdou (JSONP) → expirejingdou[]
 */
object JdBeanApi {

    private val tz = TimeZone.getTimeZone("Asia/Shanghai")
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { timeZone = tz }
    private val ua =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

    data class ExpireBucket(
        val dayLabel: String,   // e.g. 明天 / 2026-10-15
        val dayKey: String,     // yyyy-MM-dd
        val amount: Int,
        val epochSec: Long
    )

    data class AccountBeanStats(
        val accountId: String,
        val displayName: String,
        val ptPin: String,
        val totalBeans: Int? = null,
        val todayIncome: Int? = null,
        val todayExpense: Int? = null,
        val expireTomorrow: Int? = null,
        val expireIn7Days: Int? = null,
        val expireBuckets: List<ExpireBucket> = emptyList(),
        val expireAvailable: Boolean = false,
        val ok: Boolean = false,
        val message: String = ""
    )

    fun fetchForAccount(account: Account): AccountBeanStats {
        val cookie = account.combined
        return try {
            val balance = fetchBalance(cookie)
            if (balance.error != null && balance.beans == null) {
                return AccountBeanStats(
                    accountId = account.id,
                    displayName = account.displayName,
                    ptPin = account.decodedPin,
                    ok = false,
                    message = balance.error
                )
            }
            val detail = fetchTodayFromDetail(cookie)
            val expire = fetchExpire(cookie)
            AccountBeanStats(
                accountId = account.id,
                displayName = account.displayName,
                ptPin = account.decodedPin,
                totalBeans = balance.beans,
                todayIncome = detail.income,
                todayExpense = detail.expense,
                expireTomorrow = expire.tomorrow,
                expireIn7Days = expire.in7Days,
                expireBuckets = expire.buckets,
                expireAvailable = expire.available,
                ok = true,
                message = listOfNotNull(
                    balance.error,
                    detail.error,
                    if (!expire.available) expire.error else null
                ).joinToString("；").ifBlank { "OK" }
            )
        } catch (e: Exception) {
            AccountBeanStats(
                accountId = account.id,
                displayName = account.displayName,
                ptPin = account.decodedPin,
                ok = false,
                message = e.message ?: "失败"
            )
        }
    }

    private data class BalanceResult(val beans: Int?, val error: String? = null)
    private data class DetailResult(val income: Int?, val expense: Int?, val error: String? = null)
    private data class ExpireResult(
        val available: Boolean,
        val tomorrow: Int?,
        val in7Days: Int?,
        val buckets: List<ExpireBucket>,
        val error: String? = null
    )

    private fun fetchBalance(cookie: String): BalanceResult {
        return try {
            val body = httpGet(
                "https://me-api.jd.com/user_new/info/GetJDUserInfoUnion",
                cookie,
                referer = "https://home.m.jd.com/myJd/newhome.action"
            )
            val json = JSONObject(body)
            val ret = json.optString("retcode", "")
            when {
                ret == "0" -> {
                    val bean = json.optJSONObject("data")
                        ?.optJSONObject("assetInfo")
                        ?.optString("beanNum")
                        ?.toIntOrNull()
                    if (bean != null) BalanceResult(bean)
                    else BalanceResult(null, "未返回 beanNum")
                }
                ret == "13" || ret == "1001" -> BalanceResult(null, "Cookie 已掉线")
                else -> BalanceResult(null, "余额接口异常($ret)")
            }
        } catch (e: Exception) {
            BalanceResult(null, "余额请求失败: ${e.message}")
        }
    }

    /** Sum today's income/expense from getJingBeanBalanceDetail (first few pages). */
    private fun fetchTodayFromDetail(cookie: String): DetailResult {
        return try {
            val today = dayFmt.format(Date())
            var income = 0
            var expense = 0
            var foundOlder = false
            var page = 1
            while (page <= 5 && !foundOlder) {
                val resp = postJingBeanDetail(cookie, page)
                if (resp == null) {
                    return if (page == 1) DetailResult(null, null, "明细接口无数据")
                    else DetailResult(income, expense)
                }
                if (resp.optString("code") != "0") {
                    return if (page == 1) DetailResult(null, null, "明细接口 code=${resp.optString("code")}")
                    else DetailResult(income, expense)
                }
                val list = resp.optJSONArray("detailList") ?: JSONArray()
                if (list.length() == 0) break
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val dateStr = item.optString("date") // "yyyy-MM-dd HH:mm:ss"
                    val day = dateStr.take(10)
                    val amount = item.optString("amount").toIntOrNull()
                        ?: item.optInt("amount", 0)
                    val msg = item.optString("eventMassage") // JD typo
                    if (day < today) {
                        foundOlder = true
                        break
                    }
                    if (day == today) {
                        // Skip refund/deduct-gift noise like QL scripts
                        if (msg.contains("退还") || msg.contains("扣赠")) continue
                        if (amount > 0) income += amount
                        else if (amount < 0) expense += amount
                    }
                }
                page++
            }
            DetailResult(income, expense)
        } catch (e: Exception) {
            DetailResult(null, null, "明细失败: ${e.message}")
        }
    }

    private fun postJingBeanDetail(cookie: String, page: Int): JSONObject? {
        val bodyJson = JSONObject()
            .put("pageSize", "20")
            .put("page", page.toString())
            .toString()
        val form = "body=" + URLEncoder.encode(bodyJson, "UTF-8") + "&appid=ld"
        val text = httpPost(
            "https://api.m.jd.com/client.action?functionId=getJingBeanBalanceDetail",
            cookie,
            form,
            host = "api.m.jd.com",
            contentType = "application/x-www-form-urlencoded"
        )
        if (text.isBlank()) return null
        return JSONObject(text)
    }

    /**
     * Real expire schedule from JD: each item has unix `time` (seconds) and `expireamount`.
     * JSONP wrapper stripped by locating outermost JSON object.
     */
    private fun fetchExpire(cookie: String): ExpireResult {
        return try {
            val url =
                "https://wq.jd.com/activep3/singjd/queryexpirejingdou?_=${System.currentTimeMillis()}&g_login_type=1&sceneval=2"
            val raw = httpGet(
                url,
                cookie,
                referer = "https://wqs.jd.com/promote/201801/bean/mybean.html",
                host = "wq.jd.com"
            )
            val jsonText = extractJson(raw)
            if (jsonText.isNullOrBlank()) {
                return ExpireResult(false, null, null, emptyList(), "过期接口返回非 JSON")
            }
            val json = JSONObject(jsonText)
            if (json.optInt("ret", -1) != 0) {
                return ExpireResult(
                    false, null, null, emptyList(),
                    "过期接口 ret=${json.opt("ret")}"
                )
            }
            val arr = json.optJSONArray("expirejingdou") ?: JSONArray()
            val cal = Calendar.getInstance(tz)
            val todayStart = dayStart(cal)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val tomorrowKey = dayFmt.format(cal.time)
            cal.timeInMillis = todayStart
            cal.add(Calendar.DAY_OF_YEAR, 7)
            val end7 = cal.timeInMillis

            var tomorrow = 0
            var in7 = 0
            val buckets = mutableListOf<ExpireBucket>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val amount = item.optInt("expireamount", 0)
                if (amount <= 0) continue
                val epoch = item.optLong("time", 0L)
                val ms = if (epoch < 10_000_000_000L) epoch * 1000 else epoch
                val dayKey = dayFmt.format(Date(ms))
                val label = when (dayKey) {
                    dayFmt.format(Date(todayStart)) -> "今天"
                    tomorrowKey -> "明天"
                    else -> dayKey
                }
                buckets.add(ExpireBucket(label, dayKey, amount, epoch))
                if (dayKey == tomorrowKey) tomorrow += amount
                if (ms in todayStart until end7) in7 += amount
            }
            buckets.sortBy { it.epochSec }
            ExpireResult(true, tomorrow, in7, buckets)
        } catch (e: Exception) {
            ExpireResult(false, null, null, emptyList(), "过期查询失败: ${e.message}")
        }
    }

    private fun dayStart(cal: Calendar): Long {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private fun httpGet(
        urlStr: String,
        cookie: String,
        referer: String,
        host: String? = null /* URL host used; header omitted */
    ): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Cookie", cookie)
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "*/*")
        }
        return read(conn)
    }

    private fun httpPost(
        urlStr: String,
        cookie: String,
        formBody: String,
        host: String,
        contentType: String
    ): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Cookie", cookie)
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "*/*")
            outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
        }
        return read(conn)
    }

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299 && text.isBlank()) {
            throw IllegalStateException("HTTP $code")
        }
        return text
    }
}
