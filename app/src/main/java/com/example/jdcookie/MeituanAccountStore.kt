package com.example.jdcookie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local Meituan account store, separate from JD AccountStore. */
class MeituanAccountStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun loadAll(): MutableList<MeituanAccount> = synchronized(lock) {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<MeituanAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                MeituanAccount(
                    id = o.getString("id"),
                    nickname = o.optString("nickname", ""),
                    token = o.getString("token"),
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

    fun getById(id: String): MeituanAccount? = loadAll().find { it.id == id }

    fun upsertByToken(token: String, nickname: String? = null): MeituanAccount =
        synchronized(lock) {
            val normalized = normalizeToken(token)
            val list = loadAll()
            val existing = list.find { it.token == normalized }
            val now = System.currentTimeMillis()
            val account = if (existing != null) {
                existing.token = normalized
                existing.updatedAt = now
                existing.pushStatus = PushStatus.NONE
                existing.lastPushMessage = "待推送"
                if (!nickname.isNullOrBlank()) existing.nickname = nickname
                existing
            } else {
                MeituanAccount(
                    id = UUID.randomUUID().toString(),
                    nickname = nickname ?: "",
                    token = normalized,
                    pushStatus = PushStatus.NONE,
                    lastPushMessage = "待推送",
                    updatedAt = now,
                    createdAt = now
                ).also { list.add(0, it) }
            }
            persist(list)
            account
        }

    fun update(account: MeituanAccount) = synchronized(lock) {
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

    private fun persist(list: List<MeituanAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("nickname", a.nickname)
                    .put("token", a.token)
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
        private const val PREFS = "meituan_accounts_v1"
        private const val KEY = "accounts"
        const val ENV_NAME = "meituanCookie"

        /** Strip token= prefix if user pasted full cookie fragment. */
        fun normalizeToken(raw: String): String {
            var s = raw.trim()
            if (s.contains("token=", ignoreCase = true)) {
                val m = Regex("(?:^|[;\\s])token=([^;\\s]+)", RegexOption.IGNORE_CASE)
                    .find(s)
                if (m != null) s = m.groupValues[1]
                else if (s.startsWith("token=", ignoreCase = true)) {
                    s = s.substringAfter("=").substringBefore(";").trim()
                }
            }
            return s.trim()
        }
    }
}
