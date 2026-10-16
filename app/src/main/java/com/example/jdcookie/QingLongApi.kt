package com.example.jdcookie

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * QingLong OpenAPI client aligned with whyour/qinglong back/api/env.ts (2.x):
 *
 * POST /open/envs  body: [ { value, name, remarks? } ]  — no extra fields
 * PUT  /open/envs  body: { value, name, remarks?, id:number } — no _id/extra
 * PUT  /open/envs/enable|disable  body: number[]
 * DELETE /open/envs  body: number[]
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
        val status: Int,
        val isDisabled: Int
    ) {
        /** Numeric id for modern QL (2.x). */
        val numericId: Long?
            get() = when (id) {
                is Number -> id.toLong()
                is String -> id.toLongOrNull()
                else -> id?.toString()?.toLongOrNull()
            }

        val idString: String get() = id?.toString() ?: ""
    }

    data class PushResult(val message: String, val envId: String, val disabledLikelyInvalid: Boolean = false)

    private var token: String? = null

    private fun base(): String = settings.baseUrl.trim().trimEnd('/')

    fun testConnection(): String {
        token = null
        val t = ensureToken()
        val envs = listEnvs("JD_COOKIE")
        return "连接成功（token 已获取，JD_COOKIE 共 ${envs.size} 条）· ${t.take(8)}…"
    }

    fun ensureToken(): String {
        token?.let { return it }
        val url = URL(
            "${base()}/open/auth/token?client_id=" +
                URLEncoder.encode(settings.clientId, "UTF-8") +
                "&client_secret=" + URLEncoder.encode(settings.clientSecret, "UTF-8")
        )
        val body = http("GET", url, auth = false)
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(formatErr("获取青龙 Token 失败", body))
        }
        val data = json.optJSONObject("data")
            ?: throw IllegalStateException(formatErr("Token 响应无 data", body))
        val t = data.optString("token").ifBlank {
            throw IllegalStateException(formatErr("Token 为空", body))
        }
        token = t
        return t
    }

    fun listEnvs(search: String = "JD_COOKIE"): List<EnvItem> {
        ensureToken()
        val url = URL(
            "${base()}/open/envs?searchValue=" + URLEncoder.encode(search, "UTF-8")
        )
        val body = http("GET", url)
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(formatErr("获取环境变量失败", body))
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
                    remarks = o.optString("remarks", ""),
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

    /**
     * Upsert JD_COOKIE. Cookie value must be like: pt_key=xxx;pt_pin=yyy;
     * Strict celebrate schemas — only allowed keys.
     */
    fun pushOrUpdateCookie(account: Account, nurtureMode: Boolean = false): PushResult {
        ensureToken()
        val match = findCookieEnv(account)
        val remarks = QingLongConfig.buildRemarks(account, nurtureMode)
        val value = normalizeCookie(account.combined)

        // Already disabled on QL before push — often because scripts marked CK invalid
        val wasDisabled = match != null && (match.status == 1 || match.isDisabled == 1)

        val result = if (match != null) {
            updateEnv(match, value, remarks)
        } else {
            createEnv(value, remarks)
        }

        // Re-check env status after enable attempt
        val after = findCookieEnv(account)
        val stillDisabled = after != null && (after.status == 1 || after.isDisabled == 1)
        val msgHint = result.message
        val looksInvalid = wasDisabled || stillDisabled ||
            msgHint.contains("失效") || msgHint.contains("无效") || msgHint.contains("禁用")
        return if (looksInvalid && (wasDisabled || stillDisabled)) {
            result.copy(
                message = if (stillDisabled || wasDisabled) {
                    "青龙变量已禁用（可能因 Cookie 失效）· ${result.message}"
                } else result.message,
                disabledLikelyInvalid = true
            )
        } else {
            result
        }
    }

    private fun createEnv(value: String, remarks: String, envName: String = "JD_COOKIE"): PushResult {
        // POST body: array of { value, name, remarks? } — nothing else
        val item = JSONObject()
            .put("value", value)
            .put("name", envName)
            .put("remarks", remarks)
        val payload = JSONArray().put(item)
        val body = http("POST", URL("${base()}/open/envs"), payload.toString())
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(formatErr("创建环境变量失败", body))
        }
        val newId = extractCreatedId(json) ?: findByValue(value, envName)?.idString.orEmpty()
        newId.toLongOrNull()?.let { tryEnable(it) }
        return PushResult("已新建青龙 $envName", newId)
    }

    private fun updateEnv(
        match: EnvItem,
        value: String,
        remarks: String,
        envName: String = "JD_COOKIE"
    ): PushResult {
        val numId = match.numericId
        if (numId != null) {
            // Modern QL (2.x): PUT { value, name, remarks?, id:number } — NO _id
            val payload = JSONObject()
                .put("value", value)
                .put("name", envName)
                .put("remarks", remarks)
                .put("id", numId)
            val body = http("PUT", URL("${base()}/open/envs"), payload.toString())
            val json = JSONObject(body)
            if (json.optInt("code") == 200) {
                tryEnable(numId)
                return PushResult("已更新并启用青龙 $envName", numId.toString())
            }
            // Fallback: try string id (very old) then recreate path message
            val alt = tryPutWithIdKey(value, remarks, numId, useUnderscore = false, envName = envName)
                ?: tryPutWithIdKey(value, remarks, match.idString, useUnderscore = true, envName = envName)
            if (alt != null) return alt
            throw IllegalStateException(formatErr("更新环境变量失败", body))
        }

        // No numeric id — try _id string (legacy Mongo)
        val alt = tryPutWithIdKey(value, remarks, match.idString, useUnderscore = true, envName = envName)
        if (alt != null) return alt

        // Last resort: create new (may duplicate); prefer fail clearly
        throw IllegalStateException(
            "环境变量 id 无法解析为数字（got=${match.id}），当前青龙需 number id。请在面板删除旧变量后重试。"
        )
    }

    private fun tryPutWithIdKey(
        value: String,
        remarks: String,
        idVal: Any,
        useUnderscore: Boolean,
        envName: String = "JD_COOKIE"
    ): PushResult? {
        return try {
            val payload = JSONObject()
                .put("value", value)
                .put("name", envName)
                .put("remarks", remarks)
            if (useUnderscore) {
                payload.put("_id", idVal.toString())
            } else {
                when (idVal) {
                    is Number -> payload.put("id", idVal.toLong())
                    else -> {
                        val n = idVal.toString().toLongOrNull() ?: return null
                        payload.put("id", n)
                    }
                }
            }
            val body = http("PUT", URL("${base()}/open/envs"), payload.toString())
            val json = JSONObject(body)
            if (json.optInt("code") != 200) return null
            val idStr = idVal.toString()
            idStr.toLongOrNull()?.let { tryEnable(it) }
            PushResult("已更新青龙 $envName（兼容模式）", idStr)
        } catch (_: Exception) {
            null
        }
    }

    fun disableEnv(envId: Any): String {
        ensureToken()
        val id = requireNumericId(envId)
        val body = http(
            "PUT",
            URL("${base()}/open/envs/disable"),
            JSONArray().put(id).toString()
        )
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(formatErr("禁用失败", body))
        }
        return "已在青龙禁用该环境变量"
    }

    fun deleteEnv(envId: Any): String {
        ensureToken()
        val id = requireNumericId(envId)
        val body = http(
            "DELETE",
            URL("${base()}/open/envs"),
            JSONArray().put(id).toString()
        )
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(formatErr("删除失败", body))
        }
        return "已在青龙删除该环境变量"
    }

    fun listCrons(): List<CronTask> {
        ensureToken()
        val body = http("GET", URL("${base()}/open/crons"))
        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw IllegalStateException(formatErr("获取定时任务失败", body))
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

    private fun tryEnable(envId: Long) {
        try {
            http(
                "PUT",
                URL("${base()}/open/envs/enable"),
                JSONArray().put(envId).toString()
            )
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun requireNumericId(envId: Any): Long {
        return when (envId) {
            is Number -> envId.toLong()
            else -> envId.toString().toLongOrNull()
                ?: throw IllegalStateException("环境变量 id 不是数字: $envId")
        }
    }

    private fun normalizeCookie(raw: String): String {
        var s = raw.trim()
        // Ensure pt_key=...;pt_pin=...; shape (trailing semicolon)
        if (!s.endsWith(";")) s += ";"
        return s
    }

    private fun findByValue(value: String, envName: String = "JD_COOKIE"): EnvItem? =
        listEnvs(envName).find {
            it.name == envName && (it.value == value || it.value.contains(value.trimEnd(';')))
        }

    fun findMeituanEnv(account: MeituanAccount): EnvItem? {
        val envs = listEnvs(MeituanAccountStore.ENV_NAME)
        if (account.qlEnvId.isNotBlank()) {
            envs.find { it.idString == account.qlEnvId && it.name == MeituanAccountStore.ENV_NAME }
                ?.let { return it }
        }
        val token = account.token
        return envs.find { env ->
            env.name == MeituanAccountStore.ENV_NAME && (
                env.value == token ||
                    env.value.trim() == token ||
                    (account.nickname.isNotBlank() && env.remarks == account.nickname) ||
                    (account.displayName.isNotBlank() && env.remarks == account.displayName)
                )
        }
    }

    /**
     * Upsert meituanCookie. Value must be raw token only (e.g. Ag…), NOT token=…
     * One env per Meituan account, mirroring JD_COOKIE pattern.
     */

    fun findElmEnv(account: ElmAccount): EnvItem? {
        val envs = listEnvs(ElmAccountStore.ENV_NAME)
        if (account.qlEnvId.isNotBlank()) {
            envs.find { it.idString == account.qlEnvId && it.name == ElmAccountStore.ENV_NAME }
                ?.let { return it }
        }
        val cookie = account.cookie
        val uid = account.userId
        return envs.find { env ->
            env.name == ElmAccountStore.ENV_NAME && (
                env.value == cookie ||
                    env.value.trim() == cookie.trim() ||
                    (uid.isNotBlank() && (
                        env.value.contains("USERID=$uid") ||
                            env.value.contains("unb=$uid")
                        )) ||
                    (account.nickname.isNotBlank() && env.remarks == account.nickname) ||
                    (account.displayName.isNotBlank() && env.remarks == account.displayName)
                )
        }
    }

    /**
     * Upsert elmck. Value is full cookie string (cookie2/unb/USERID/SID/…).
     * One env per Ele.me account, mirroring meituanCookie / JD_COOKIE.
     */
    fun pushOrUpdateElm(account: ElmAccount): PushResult {
        ensureToken()
        val envName = ElmAccountStore.ENV_NAME
        val match = findElmEnv(account)
        val remarks = account.displayName.ifBlank { account.cookiePreview }
        val value = ElmAccountStore.normalizeCookie(account.cookie)
        return if (match != null) {
            updateEnv(match, value, remarks, envName)
        } else {
            createEnv(value, remarks, envName)
        }
    }

    fun pushOrUpdateMeituan(account: MeituanAccount): PushResult {
        ensureToken()
        val envName = MeituanAccountStore.ENV_NAME
        val match = findMeituanEnv(account)
        val remarks = account.displayName.ifBlank { account.tokenPreview }
        val value = MeituanAccountStore.normalizeToken(account.token)
        return if (match != null) {
            updateEnv(match, value, remarks, envName)
        } else {
            createEnv(value, remarks, envName)
        }
    }


    fun findQuarkEnv(account: QuarkAccount): EnvItem? {
        val envs = listEnvs(QuarkAccountStore.ENV_NAME)
        if (account.qlEnvId.isNotBlank()) {
            envs.find { it.idString == account.qlEnvId && it.name == QuarkAccountStore.ENV_NAME }
                ?.let { return it }
        }
        val cookie = account.cookie
        val user = account.userField
        return envs.find { env ->
            env.name == QuarkAccountStore.ENV_NAME && (
                env.value == cookie ||
                    env.value.trim() == cookie.trim() ||
                    (user.isNotBlank() && (
                        env.value.contains("user=$user") ||
                            env.remarks == user
                        )) ||
                    (account.nickname.isNotBlank() && env.remarks == account.nickname) ||
                    (account.displayName.isNotBlank() && env.remarks == account.displayName)
                )
        }
    }

    /**
     * Upsert COOKIE_QUARK. One env per Quark account.
     * Value: user=…; url=… or user=; kps=; sign=; vcode=
     */
    fun pushOrUpdateQuark(account: QuarkAccount): PushResult {
        ensureToken()
        val envName = QuarkAccountStore.ENV_NAME
        val match = findQuarkEnv(account)
        val remarks = account.displayName.ifBlank { account.cookiePreview }
        val value = QuarkAccountStore.normalizeCookie(account.cookie)
        return if (match != null) {
            updateEnv(match, value, remarks, envName)
        } else {
            createEnv(value, remarks, envName)
        }
    }

    fun findTelecomEnv(account: TelecomAccount): EnvItem? {
        val envs = listEnvs(TelecomAccountStore.ENV_NAME)
        if (account.qlEnvId.isNotBlank()) {
            envs.find { it.idString == account.qlEnvId && it.name == TelecomAccountStore.ENV_NAME }
                ?.let { return it }
        }
        val phone = account.phone
        val cred = account.credential
        return envs.find { env ->
            env.name == TelecomAccountStore.ENV_NAME && (
                env.value == cred ||
                    env.value.trim() == cred.trim() ||
                    (phone.isNotBlank() && (
                        env.value.startsWith("$phone#") ||
                            env.value.contains("$phone#") ||
                            env.remarks == phone
                        )) ||
                    (account.nickname.isNotBlank() && env.remarks == account.nickname) ||
                    (account.displayName.isNotBlank() && env.remarks == account.displayName)
                )
        }
    }

    /**
     * Upsert chinaTelecomAccount. Value: 手机号#服务密码.
     * One env per account; remarks prefer phone.
     */
    fun pushOrUpdateTelecom(account: TelecomAccount): PushResult {
        ensureToken()
        val envName = TelecomAccountStore.ENV_NAME
        val match = findTelecomEnv(account)
        val remarks = account.phone.ifBlank { account.displayName }
        val value = TelecomAccountStore.normalizeCredential(account.credential)
        return if (match != null) {
            updateEnv(match, value, remarks, envName)
        } else {
            createEnv(value, remarks, envName)
        }
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
                val first = data.opt(0)
                if (first is JSONObject) {
                    return (first.opt("id") ?: first.opt("_id"))?.toString()
                }
                return first?.toString()
            }
            is JSONObject -> return (data.opt("id") ?: data.opt("_id"))?.toString()
        }
        return null
    }

    private fun formatErr(prefix: String, body: String): String {
        val trimmed = body.trim().take(800)
        return if (trimmed.isEmpty()) prefix else "$prefix | $trimmed"
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
        if (code !in 200..299) {
            // Surface celebrate "Validation failed" bodies to the UI
            throw IllegalStateException(formatErr("HTTP $code", text.ifBlank { "(empty body)" }))
        }
        return text
    }
}
