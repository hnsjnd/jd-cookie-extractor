package com.example.jdcookie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local-only multi-account store. Never leaves the device unless user pushes to QingLong. */
class AccountStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun loadAll(): MutableList<Account> = synchronized(lock) {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<Account>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Account(
                    id = o.getString("id"),
                    nickname = o.optString("nickname", ""),
                    ptPin = o.getString("ptPin"),
                    ptKey = o.getString("ptKey"),
                    status = runCatching {
                        AccountStatus.valueOf(o.optString("status", "UNKNOWN"))
                    }.getOrDefault(AccountStatus.UNKNOWN),
                    lastCheckedAt = o.optLong("lastCheckedAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                    createdAt = o.optLong("createdAt", 0L),
                    lastMessage = o.optString("lastMessage", "")
                )
            )
        }
        list.sortByDescending { it.updatedAt }
        list
    }

    fun getById(id: String): Account? = loadAll().find { it.id == id }

    fun upsertByPin(ptKey: String, ptPin: String, nickname: String? = null): Account =
        synchronized(lock) {
            val list = loadAll()
            val existing = list.find { it.ptPin == ptPin }
            val now = System.currentTimeMillis()
            val account = if (existing != null) {
                existing.ptKey = ptKey
                existing.updatedAt = now
                existing.status = AccountStatus.UNKNOWN
                existing.lastMessage = "刚提取，待检测"
                if (!nickname.isNullOrBlank()) existing.nickname = nickname
                existing
            } else {
                Account(
                    id = UUID.randomUUID().toString(),
                    nickname = nickname ?: "",
                    ptPin = ptPin,
                    ptKey = ptKey,
                    status = AccountStatus.UNKNOWN,
                    updatedAt = now,
                    createdAt = now,
                    lastMessage = "刚提取，待检测"
                ).also { list.add(0, it) }
            }
            persist(list)
            account
        }

    fun update(account: Account) = synchronized(lock) {
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

    private fun persist(list: List<Account>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("nickname", a.nickname)
                    .put("ptPin", a.ptPin)
                    .put("ptKey", a.ptKey)
                    .put("status", a.status.name)
                    .put("lastCheckedAt", a.lastCheckedAt)
                    .put("updatedAt", a.updatedAt)
                    .put("createdAt", a.createdAt)
                    .put("lastMessage", a.lastMessage)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "jd_accounts_v2"
        private const val KEY = "accounts"
    }
}
