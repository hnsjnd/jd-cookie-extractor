package com.example.jdcookie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local Ele.me (饿了么) account store, separate from JD / Meituan. */
class ElmAccountStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun loadAll(): MutableList<ElmAccount> = synchronized(lock) {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<ElmAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                ElmAccount(
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

    fun getById(id: String): ElmAccount? = loadAll().find { it.id == id }

    fun upsertByCookie(cookie: String, nickname: String? = null): ElmAccount =
        synchronized(lock) {
            val normalized = normalizeCookie(cookie)
            val list = loadAll()
            val uid = extractField(normalized, "USERID")
                .ifBlank { extractField(normalized, "unb") }
            val existing = list.find { acc ->
                acc.cookie == normalized ||
                    (uid.isNotBlank() && (
                        extractField(acc.cookie, "USERID") == uid ||
                            extractField(acc.cookie, "unb") == uid
                        ))
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
                ElmAccount(
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

    fun update(account: ElmAccount) = synchronized(lock) {
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

    private fun persist(list: List<ElmAccount>) {
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
        private const val PREFS = "elm_accounts_v1"
        private const val KEY = "accounts"
        const val ENV_NAME = "elmck"

        /** Fields kept by Shuying-exquisite/elm 吃货豆.py reorder_ck. */
        private val KEEP_KEYS = listOf(
            "cookie2", "sgcookie", "unb", "USERID", "SID",
            "token", "utdid", "deviceId", "umt"
        )

        fun extractField(cookie: String, key: String): String {
            val map = parseMap(cookie)
            return map[key].orEmpty()
        }

        /**
         * Light cleanup: keep script-relevant keys; derive USERID from unb when missing;
         * ensure trailing semicolon. Matches 吃货豆.py reorder_ck order.
         */
        fun normalizeCookie(raw: String): String {
            var s = raw.trim()
            // Strip Cookie: header prefix if pasted from DevTools
            if (s.startsWith("Cookie:", ignoreCase = true)) {
                s = s.substringAfter(":").trim()
            }
            s = s.replace("\r", "").replace("\n", ";")
            val map = parseMap(s).toMutableMap()
            if (map["USERID"].isNullOrBlank() && !map["unb"].isNullOrBlank()) {
                map["USERID"] = map["unb"]!!
            }
            val parts = mutableListOf<String>()
            for (key in KEEP_KEYS) {
                val v = map[key]
                if (!v.isNullOrBlank()) parts.add("$key=$v")
            }
            // If nothing matched KEEP_KEYS but raw looks like a cookie, keep as-is lightly cleaned
            if (parts.isEmpty()) {
                var fallback = s.trim()
                if (fallback.isNotEmpty() && !fallback.endsWith(";")) fallback += ";"
                return fallback
            }
            return parts.joinToString("; ") + ";"
        }

        fun isValidCookie(cookie: String): Boolean {
            val map = parseMap(normalizeCookie(cookie))
            // Need cookie2 or SID at minimum (script uses cookie2 as sid)
            return !map["cookie2"].isNullOrBlank() || !map["SID"].isNullOrBlank()
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
