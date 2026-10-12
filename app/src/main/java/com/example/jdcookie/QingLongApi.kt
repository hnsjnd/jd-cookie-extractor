package com.example.jdcookie

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * QingLong OpenAPI: token, JD_COOKIE env upsert/enable/disable/delete, crons.
 * Handles both id and _id response shapes across QL versions.
 */
class QingLongApi(private val settings: QingLongSettings) {

    data class CronTask(
        val id: Any?,
        val name: String,
        val schedule: String,
        val status: Int,
        val isDisabled: Int,
        val lastRunningTime: Long,
        val lastStatus: Int
    )

    data class EnvItem(
        val id: Any?,
        val name: String,
        val value: String,
        val remarks: String,
        val status: Int,      // 0 enabled / 1 disabled varies by version
        val isDisabled: Int
    ) {
        val idString: String get() = id?.toString() ?: ""
    }

    data class PushResult(val message: String, val envId: String)

    private var token: String? = null

    fun testConnection(): String {
        token = null
        val t = ensureToken()
        val envs = listEnvs("JD_COOKIE")
        return "连接成功（token 已获取，JD_COOKIE 共 ${envs.size} 条）· ${t.take(8)}…"
    }

    fun ensureToken(): String {
        token?.let { return it }
        val base = settings.baseUrl.trim().trimEnd('/')
        val url = URL(
            "$base/open/auth/token?client_id=" +
                URLEncoder.encode(settings.clientId, "UTF-8") +
                "&client_secret=" + URLEncoder.encode(settings.clientSecret, "UTF-8")
        )
        val body = http("GET", url, auth = false)
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(json.optString("message", "获取青龙 Token 失败"))
        }
        val data = json.optJSONObject("data")
            ?: throw IllegalStateException("Token 响应无 data")
        val t = data.optString("token").ifBlank {
            throw IllegalStateException("Token 为空")
        }
        token = t
        return t
    }

    fun listEnvs(search: String = "JD_COOKIE"): List<EnvItem> {
        ensureToken()
        val base = settings.baseUrl.trim().trimEnd('/')
        val url = URL(
            "$base/open/envs?searchValue=" + URLEncoder.encode(search, "UTF-8")
        )
        val body = http("GET", url)
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(json.optString("message", "获取环境变量失败"))
        }
        val arr = extractArray(json.opt("data"))
        val list = mutableListOf<EnvItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                EnvItem(
                    id = o.opt("id") ?: o.opt("_id"),
                    name = o.optString("name"),
                    value = o.optString("value"),
                    remarks = o.optString("remarks"),
                    status = o.optInt("status", 0),
                    isDisabled = o.optInt("isDisabled", 0)
                )
            )
        }
        return list
    }

    fun findCookieEnv(account: Account): EnvItem? {
        val pin = account.ptPin
        val decoded = account.decodedPin
        return listEnvs("JD_COOKIE").find { env ->
            env.name == "JD_COOKIE" && (
                env.value.contains("pt_pin=$pin") ||
                    env.value.contains("pt_pin=$decoded") ||
                    env.remarks.contains(decoded) ||
                    env.remarks.contains(pin)
                )
        }
    }

    fun pushOrUpdateCookie(account: Account): PushResult {
        ensureToken()
        val base = settings.baseUrl.trim().trimEnd('/')
        val match = findCookieEnv(account)
        val remarks = account.displayName.ifBlank { account.decodedPin }

        return if (match != null && match.id != null) {
            val payload = JSONObject()
                .put("id", match.id)
                .put("_id", match.id)
                .put("name", "JD_COOKIE")
                .put("value", account.combined)
                .put("remarks", remarks)
            val body = http("PUT", URL("$base/open/envs"), payload.toString())
            val json = JSONObject(body)
            if (json.optInt("code") != 200) {
                throw IllegalStateException(json.optString("message", "更新环境变量失败"))
            }
            // Enable if disabled (best-effort; QL versions differ)
            tryEnable(match.id)
            PushResult("已更新并启用青龙 JD_COOKIE", match.idString)
        } else {
            val payload = JSONArray().put(
                JSONObject()
                    .put("name", "JD_COOKIE")
                    .put("value", account.combined)
                    .put("remarks", remarks)
            )
            val body = http("POST", URL("$base/open/envs"), payload.toString())
            val json = JSONObject(body)
            if (json.optInt("code") != 200) {
                throw IllegalStateException(json.optString("message", "创建环境变量失败"))
            }
            val newId = extractCreatedId(json) ?: findCookieEnv(account)?.idString.orEmpty()
            if (newId.isNotBlank()) tryEnable(newId)
            PushResult("已新建青龙 JD_COOKIE", newId)
        }
    }

    fun disableEnv(envId: Any): String {
        ensureToken()
        val base = settings.baseUrl.trim().trimEnd('/')
        val body = http("PUT", URL("$base/open/envs/disable"), JSONArray().put(coerceId(envId)).toString())
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(json.optString("message", "禁用失败"))
        }
        return "已在青龙禁用该环境变量"
    }

    fun deleteEnv(envId: Any): String {
        ensureToken()
        val base = settings.baseUrl.trim().trimEnd('/')
        // DELETE with JSON body
        val body = http("DELETE", URL("$base/open/envs"), JSONArray().put(coerceId(envId)).toString())
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(json.optString("message", "删除失败"))
        }
        return "已在青龙删除该环境变量"
    }

    fun listCrons(): List<CronTask> {
        ensureToken()
        val base = settings.baseUrl.trim().trimEnd('/')
        val body = http("GET", URL("$base/open/crons"))
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(json.optString("message", "获取定时任务失败"))
        }
        val arr = extractArray(json.opt("data"))
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

    private fun tryEnable(envId: Any) {
        try {
            val base = settings.baseUrl.trim().trimEnd('/')
            http("PUT", URL("$base/open/envs/enable"), JSONArray().put(coerceId(envId)).toString())
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun coerceId(id: Any): Any {
        val s = id.toString()
        return s.toLongOrNull() ?: s
    }

    private fun extractArray(data: Any?): JSONArray = when (data) {
        is JSONArray -> data
        is JSONObject -> data.optJSONArray("data")
            ?: data.optJSONArray("list")
            ?: JSONArray()
        else -> JSONArray()
    }

    private fun extractCreatedId(json: JSONObject): String? {
        val data = json.opt("data") ?: return null
        when (data) {
            is JSONArray -> if (data.length() > 0) {
                val o = data.optJSONObject(0) ?: return data.opt(0)?.toString()
                return (o.opt("id") ?: o.opt("_id"))?.toString()
            }
            is JSONObject -> return (data.opt("id") ?: data.opt("_id"))?.toString()
        }
        return null
    }

    private fun http(method: String, url: URL, body: String? = null, auth: Boolean = true): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            setRequestProperty("Accept", "application/json")
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
