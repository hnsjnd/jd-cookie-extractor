package com.example.jdcookie

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal QingLong OpenAPI client (token + envs + crons).
 * Credentials stay on device; calls go only to the user-configured panel URL.
 */
class QingLongApi(private val settings: QingLongSettings) {

    data class CronTask(
        val id: Any?,
        val name: String,
        val schedule: String,
        val status: Int, // 0 running-ish / enabled conventions vary; we show labels from fields
        val isDisabled: Int,
        val lastRunningTime: Long,
        val lastStatus: Int // 0 success, 1 failure, etc.
    )

    data class EnvItem(
        val id: Any?,
        val name: String,
        val value: String,
        val remarks: String
    )

    private var token: String? = null

    fun ensureToken(): String {
        token?.let { return it }
        val url = URL(
            "${settings.baseUrl}/open/auth/token?client_id=" +
                URLEncoder.encode(settings.clientId, "UTF-8") +
                "&client_secret=" + URLEncoder.encode(settings.clientSecret, "UTF-8")
        )
        val body = http("GET", url, auth = false)
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(json.optString("message", "获取青龙 Token 失败"))
        }
        val t = json.getJSONObject("data").getString("token")
        token = t
        return t
    }

    fun listEnvs(search: String = "JD_COOKIE"): List<EnvItem> {
        ensureToken()
        val url = URL(
            "${settings.baseUrl}/open/envs?searchValue=" + URLEncoder.encode(search, "UTF-8")
        )
        val body = http("GET", url)
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(json.optString("message", "获取环境变量失败"))
        }
        val data = json.opt("data")
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        val list = mutableListOf<EnvItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                EnvItem(
                    id = o.opt("id") ?: o.opt("_id"),
                    name = o.optString("name"),
                    value = o.optString("value"),
                    remarks = o.optString("remarks")
                )
            )
        }
        return list
    }

    fun pushOrUpdateCookie(account: Account): String {
        ensureToken()
        val envs = listEnvs("JD_COOKIE")
        val pin = account.ptPin
        val match = envs.find {
            it.name == "JD_COOKIE" && (
                it.value.contains("pt_pin=$pin") ||
                    it.remarks.contains(account.decodedPin) ||
                    it.remarks.contains(pin)
                )
        }
        val remarks = account.displayName.ifBlank { account.decodedPin }
        return if (match != null && match.id != null) {
            val payload = JSONObject()
                .put("id", match.id)
                .put("name", "JD_COOKIE")
                .put("value", account.combined)
                .put("remarks", remarks)
            val body = http("PUT", URL("${settings.baseUrl}/open/envs"), payload.toString())
            val json = JSONObject(body)
            if (json.optInt("code") != 200) {
                throw IllegalStateException(json.optString("message", "更新环境变量失败"))
            }
            "已更新青龙环境变量"
        } else {
            val payload = JSONArray().put(
                JSONObject()
                    .put("name", "JD_COOKIE")
                    .put("value", account.combined)
                    .put("remarks", remarks)
            )
            val body = http("POST", URL("${settings.baseUrl}/open/envs"), payload.toString())
            val json = JSONObject(body)
            if (json.optInt("code") != 200) {
                throw IllegalStateException(json.optString("message", "创建环境变量失败"))
            }
            "已新建青龙环境变量"
        }
    }

    fun listCrons(): List<CronTask> {
        ensureToken()
        val url = URL("${settings.baseUrl}/open/crons")
        val body = http("GET", url)
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(json.optString("message", "获取定时任务失败"))
        }
        val data = json.opt("data")
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        val list = mutableListOf<CronTask>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name").ifBlank { o.optString("command") }
            list.add(
                CronTask(
                    id = o.opt("id") ?: o.opt("_id"),
                    name = name,
                    schedule = o.optString("schedule"),
                    status = o.optInt("status", 1),
                    isDisabled = o.optInt("isDisabled", 0),
                    lastRunningTime = o.optLong("last_running_time", o.optLong("lastRunningTime", 0L)),
                    lastStatus = o.optInt("last_execution_status", o.optInt("lastStatus", -1))
                )
            )
        }
        return list
    }

    private fun http(method: String, url: URL, body: String? = null, auth: Boolean = true): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            if (auth) setRequestProperty("Authorization", "Bearer ${ensureToken()}")
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
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
