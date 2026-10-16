package com.example.jdcookie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local Quark (夸克) account store. Env: COOKIE_QUARK. */
class QuarkAccountStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun loadAll(): MutableList<QuarkAccount> = synchronized(lock) {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<QuarkAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                QuarkAccount(
                    id = o.getString("id"),
                    nickname = o.optString("nickname", ""),
                    cookie = o.getString("cookie"),
                    pushStatus = runCatching {
                        PushStatus.valueOf(o.optString("pushStatus", "NONE"))
                    }.getOrDefault(PushStatus.NONE),
                    lastPushedAt = o.optLong("lastPushedAt", 0L),
                    lastPushMessage = o.optString("lastPushMessage", ""),
                    qlEnvId = o.optString("qlEnvId", ""),
                    updatedAt = o.optLong("updatedAt", 0L),
                    createdAt = o.optLong("createdAt", 0L)
                )
            )
        }
        list.sortByDescending { it.updatedAt }
        list
    }

    fun getById(id: String): QuarkAccount? = loadAll().find { it.id == id }

    fun upsertByCookie(cookie: String, nickname: String? = null): QuarkAccount =
        synchronized(lock) {
            val normalized = normalizeCookie(cookie)
            val list = loadAll()
            val user = extractField(normalized, "user")
            val existing = list.find { acc ->
                acc.cookie == normalized ||
                    (user.isNotBlank() && extractField(acc.cookie, "user") == user && user.isNotBlank())
            }
            val now = System.currentTimeMillis()
            val account = if (existing != null) {
                existing.cookie = normalized
                existing.updatedAt = now
                existing.pushStatus = PushStatus.NONE
                existing.lastPushMessage = "待推送"
                if (!nickname.isNullOrBlank()) existing.nickname = nickname
                existing
            } else {
                QuarkAccount(
                    id = UUID.randomUUID().toString(),
                    nickname = nickname ?: "",
                    cookie = normalized,
                    pushStatus = PushStatus.NONE,
                    lastPushMessage = "待推送",
                    updatedAt = now,
                    createdAt = now
                ).also { list.add(0, it) }
            }
            persist(list)
            account
        }

    fun update(account: QuarkAccount) = synchronized(lock) {
        val list = loadAll()
        val idx = list.indexOfFirst { it.id == account.id }
        if (idx >= 0) {
            list[idx] = account
            persist(list)
        }
    }

    fun rename(id: String, nickname: String) = synchronized(lock) {
        val list = loadAll()
        list.find { it.id == id }?.let {
            it.nickname = nickname.trim()
            it.updatedAt = System.currentTimeMillis()
            persist(list)
        }
    }

    fun delete(id: String) = synchronized(lock) {
        val list = loadAll()
        list.removeAll { it.id == id }
        persist(list)
    }

    private fun persist(list: List<QuarkAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("nickname", a.nickname)
                    .put("cookie", a.cookie)
                    .put("pushStatus", a.pushStatus.name)
                    .put("lastPushedAt", a.lastPushedAt)
                    .put("lastPushMessage", a.lastPushMessage)
                    .put("qlEnvId", a.qlEnvId)
                    .put("updatedAt", a.updatedAt)
                    .put("createdAt", a.createdAt)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    data class QuarkCreds(
        val kps: String,
        val sign: String,
        val vcode: String,
        val sourceUrl: String? = null
    )

    companion object {

        private const val PREFS = "quark_accounts_v1"
        private const val KEY = "accounts"
        const val ENV_NAME = "COOKIE_QUARK"

        fun extractField(cookie: String, key: String): String {
            val map = parseMap(cookie)
            return map[key].orEmpty()
        }

        /**
         * Accepts:
         * - user=备注; url=https://drive-m.quark.cn/...
         * - user=; kps=; sign=; vcode=
         * Light normalize: trim, collapse newlines, ensure trailing semicolon.
         */
        fun normalizeCookie(raw: String): String {
            var s = raw.trim()
            if (s.startsWith("Cookie:", ignoreCase = true)) {
                s = s.substringAfter(":").trim()
            }
            s = s.replace("\r", "").replace("\n", ";")
            // Collapse duplicate semicolons / spaces
            s = s.split(";").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ")
            if (s.isNotEmpty() && !s.endsWith(";")) s += ";"
            return s
        }

        fun isValidCookie(cookie: String): Boolean {
            val s = normalizeCookie(cookie)
            if (s.isBlank()) return false
            val map = parseMap(s)
            // url= form or kps/sign/vcode form
            if (!map["url"].isNullOrBlank()) return true
            if (!map["kps"].isNullOrBlank() || !map["sign"].isNullOrBlank() || !map["vcode"].isNullOrBlank()) {
                return true
            }
            // Also accept raw that looks like key=value pairs
            return map.isNotEmpty()
        }

        private fun parseMap(cookie: String): Map<String, String> {
            val map = linkedMapOf<String, String>()
            for (part in cookie.split(";")) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val eq = p.indexOf('=')
                if (eq <= 0) continue
                val k = p.substring(0, eq).trim()
                val v = p.substring(eq + 1).trim()
                if (k.isNotEmpty()) map[k] = v
            }
            return map
        }

        fun isCompleteCreds(c: QuarkCreds): Boolean =
            c.kps.isNotBlank() && c.sign.isNotBlank() && c.vcode.isNotBlank()

        /**
         * Parse query without URLDecoder so literal `+`, `=`, `%xx` in kps/sign stay intact
         * (new Quark reward URLs often contain `+` in signed params).
         */
        fun parseQueryRaw(url: String): Map<String, String> {
            val qStart = url.indexOf('?')
            if (qStart < 0) return emptyMap()
            var q = url.substring(qStart + 1)
            val hash = q.indexOf('#')
            if (hash >= 0) q = q.substring(0, hash)
            val map = linkedMapOf<String, String>()
            for (part in q.split("&")) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val k = part.substring(0, eq)
                val v = part.substring(eq + 1)
                if (k.isNotEmpty()) map[k] = v
            }
            return map
        }

        fun urlLooksLikeGrowth(url: String): Boolean {
            val u = url.lowercase()
            return u.contains("drive-m.quark.cn") && (
                u.contains("/growth/") ||
                    u.contains("act/growth") ||
                    u.contains("growth/reward") ||
                    u.contains("growth/info") ||
                    u.contains("growth/sign")
                )
        }

        fun parseCredsFromUrl(url: String): QuarkCreds? {
            if (url.isBlank()) return null
            // Nested url=… inside cookie-like strings
            var target = url.trim()
            if (!target.contains("://") && target.contains("url=")) {
                val nested = extractField(normalizeCookie(target), "url")
                if (nested.isNotBlank()) target = nested
            }
            val map = parseQueryRaw(target)
            val kps = map["kps"].orEmpty()
            val sign = map["sign"].orEmpty()
            val vcode = map["vcode"].orEmpty()
            if (kps.isBlank() && sign.isBlank() && vcode.isBlank()) return null
            val keepUrl = target.startsWith("http", ignoreCase = true) &&
                (urlLooksLikeGrowth(target) || (kps.isNotBlank() && sign.isNotBlank() && vcode.isNotBlank()))
            return QuarkCreds(
                kps = kps,
                sign = sign,
                vcode = vcode,
                sourceUrl = if (keepUrl) target else null
            )
        }

        fun parseCredsFromCookieHeader(raw: String): QuarkCreds? {
            val map = parseMap(raw)
            val kps = map["kps"].orEmpty()
            val sign = map["sign"].orEmpty()
            val vcode = map["vcode"].orEmpty()
            if (kps.isBlank() && sign.isBlank() && vcode.isBlank()) {
                // Sometimes pasted as single cookie value containing url=
                val url = map["url"].orEmpty()
                if (url.isNotBlank()) return parseCredsFromUrl(url)
                return null
            }
            return QuarkCreds(kps, sign, vcode, map["url"])
        }

        /**
         * Prefer `user=昵称; url=完整抓包URL;` when we have a growth/reward URL
         * (preserves device_model/mt/ut/ds/xs etc.). Else `user=; kps=; sign=; vcode=;`.
         */
        fun buildCookie(user: String, creds: QuarkCreds, preferFullUrl: Boolean = true): String {
            val u = user.trim()
            val url = creds.sourceUrl
            val body = if (preferFullUrl && !url.isNullOrBlank() &&
                (urlLooksLikeGrowth(url) || (creds.kps.isNotBlank() && creds.sign.isNotBlank()))
            ) {
                "user=$u; url=$url;"
            } else {
                buildString {
                    append("user=").append(u).append("; ")
                    if (creds.kps.isNotBlank()) append("kps=").append(creds.kps).append("; ")
                    if (creds.sign.isNotBlank()) append("sign=").append(creds.sign).append("; ")
                    if (creds.vcode.isNotBlank()) append("vcode=").append(creds.vcode).append("; ")
                }
            }
            return normalizeCookie(body)
        }

        /** Heuristic: pan.quark.cn session cookies after login. */
        fun looksLoggedIn(rawCookies: String): Boolean {
            if (rawCookies.isBlank()) return false
            val lower = rawCookies.lowercase()
            return listOf("__puus", "__pus", "puus=", "_upc_", "__uid", "x-wpk", "tfstk").any {
                lower.contains(it.lowercase())
            } || rawCookies.split(";").map { it.trim() }.count { it.contains("=") } >= 3
        }

        fun fingerprintFromRaw(raw: String): String {
            if (raw.isBlank()) return ""
            // Stable-ish id from a few known keys
            val map = parseMap(raw)
            val parts = listOf("__puus", "__pus", "__uid", "puus", "kps", "sign", "vcode")
                .mapNotNull { k -> map[k]?.takeIf { it.isNotBlank() }?.let { "$k=$it" } }
            return if (parts.isNotEmpty()) parts.joinToString("|") else raw.take(64)
        }
    }
}
