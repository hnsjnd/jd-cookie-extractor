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
    }
}
