package com.example.jdcookie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Local China Telecom (电信) account store.
 * Env: chinaTelecomAccount. Value: 手机号#服务密码. One env per account preferred.
 */
class TelecomAccountStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun loadAll(): MutableList<TelecomAccount> = synchronized(lock) {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<TelecomAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                TelecomAccount(
                    id = o.getString("id"),
                    nickname = o.optString("nickname", ""),
                    credential = o.getString("credential"),
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

    fun getById(id: String): TelecomAccount? = loadAll().find { it.id == id }

    fun upsertByCredential(credential: String, nickname: String? = null): TelecomAccount =
        synchronized(lock) {
            val normalized = normalizeCredential(credential)
            val list = loadAll()
            val phone = extractPhone(normalized)
            val existing = list.find { acc ->
                acc.credential == normalized ||
                    (phone.isNotBlank() && extractPhone(acc.credential) == phone)
            }
            val now = System.currentTimeMillis()
            val account = if (existing != null) {
                existing.credential = normalized
                existing.updatedAt = now
                existing.pushStatus = PushStatus.NONE
                existing.lastPushMessage = "待推送"
                if (!nickname.isNullOrBlank()) {
                    existing.nickname = nickname
                } else if (existing.nickname.isBlank() && phone.isNotBlank()) {
                    existing.nickname = phone
                }
                existing
            } else {
                TelecomAccount(
                    id = UUID.randomUUID().toString(),
                    nickname = nickname ?: phone,
                    credential = normalized,
                    pushStatus = PushStatus.NONE,
                    lastPushMessage = "待推送",
                    updatedAt = now,
                    createdAt = now
                ).also { list.add(0, it) }
            }
            persist(list)
            account
        }

    fun update(account: TelecomAccount) = synchronized(lock) {
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

    private fun persist(list: List<TelecomAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("nickname", a.nickname)
                    .put("credential", a.credential)
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
        private const val PREFS = "telecom_accounts_v1"
        private const val KEY = "accounts"
        const val ENV_NAME = "chinaTelecomAccount"

        fun extractPhone(credential: String): String {
            val s = credential.trim()
            val hash = s.indexOf('#')
            return if (hash > 0) s.substring(0, hash).trim() else s.trim()
        }

        /** Normalize: trim; take first 手机号#服务密码 if multi joined by &. */
        fun normalizeCredential(raw: String): String {
            var s = raw.trim().replace("\r", "").replace("\n", "")
            // If user pasted multi accounts joined by &, take first only (one env per account)
            if (s.contains("&")) {
                s = s.split("&").map { it.trim() }.firstOrNull { it.contains("#") } ?: s.substringBefore("&").trim()
            }
            return s.trim()
        }

        fun isValidCredential(credential: String): Boolean {
            val s = normalizeCredential(credential)
            val hash = s.indexOf('#')
            if (hash <= 0 || hash >= s.length - 1) return false
            val phone = s.substring(0, hash).trim()
            val pass = s.substring(hash + 1).trim()
            return phone.isNotBlank() && pass.isNotBlank()
        }
    }
}
