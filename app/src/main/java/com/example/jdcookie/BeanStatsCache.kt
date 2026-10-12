package com.example.jdcookie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local cache of last 京豆统计 fetch. */
class BeanStatsCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(fetchedAt: Long, rows: List<JdBeanApi.AccountBeanStats>) {
        val arr = JSONArray()
        rows.forEach { r ->
            val buckets = JSONArray()
            r.expireBuckets.forEach { b ->
                buckets.put(
                    JSONObject()
                        .put("dayLabel", b.dayLabel)
                        .put("dayKey", b.dayKey)
                        .put("amount", b.amount)
                        .put("epochSec", b.epochSec)
                )
            }
            arr.put(
                JSONObject()
                    .put("accountId", r.accountId)
                    .put("displayName", r.displayName)
                    .put("ptPin", r.ptPin)
                    .put("totalBeans", r.totalBeans ?: JSONObject.NULL)
                    .put("todayIncome", r.todayIncome ?: JSONObject.NULL)
                    .put("todayExpense", r.todayExpense ?: JSONObject.NULL)
                    .put("expireTomorrow", r.expireTomorrow ?: JSONObject.NULL)
                    .put("expireIn7Days", r.expireIn7Days ?: JSONObject.NULL)
                    .put("expireAvailable", r.expireAvailable)
                    .put("ok", r.ok)
                    .put("message", r.message)
                    .put("expireBuckets", buckets)
            )
        }
        prefs.edit()
            .putLong(KEY_TIME, fetchedAt)
            .putString(KEY_DATA, arr.toString())
            .apply()
    }

    fun load(): Pair<Long, List<JdBeanApi.AccountBeanStats>> {
        val t = prefs.getLong(KEY_TIME, 0L)
        val raw = prefs.getString(KEY_DATA, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<JdBeanApi.AccountBeanStats>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val buckets = mutableListOf<JdBeanApi.ExpireBucket>()
            val ba = o.optJSONArray("expireBuckets") ?: JSONArray()
            for (j in 0 until ba.length()) {
                val b = ba.getJSONObject(j)
                buckets.add(
                    JdBeanApi.ExpireBucket(
                        dayLabel = b.optString("dayLabel"),
                        dayKey = b.optString("dayKey"),
                        amount = b.optInt("amount"),
                        epochSec = b.optLong("epochSec")
                    )
                )
            }
            list.add(
                JdBeanApi.AccountBeanStats(
                    accountId = o.optString("accountId"),
                    displayName = o.optString("displayName"),
                    ptPin = o.optString("ptPin"),
                    totalBeans = o.optIntOrNull("totalBeans"),
                    todayIncome = o.optIntOrNull("todayIncome"),
                    todayExpense = o.optIntOrNull("todayExpense"),
                    expireTomorrow = o.optIntOrNull("expireTomorrow"),
                    expireIn7Days = o.optIntOrNull("expireIn7Days"),
                    expireBuckets = buckets,
                    expireAvailable = o.optBoolean("expireAvailable", false),
                    ok = o.optBoolean("ok", false),
                    message = o.optString("message")
                )
            )
        }
        return t to list
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    companion object {
        private const val PREFS = "bean_stats_cache"
        private const val KEY_TIME = "fetchedAt"
        private const val KEY_DATA = "rows"
    }
}
