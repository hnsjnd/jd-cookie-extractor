package com.example.jdcookie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only history of extracted accounts. Never leaves the device.
 */
class CookieHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): MutableList<CookieRecord> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<CookieRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                CookieRecord(
                    ptKey = o.getString("ptKey"),
                    ptPin = o.getString("ptPin"),
                    savedAt = o.optLong("savedAt", 0L)
                )
            )
        }
        return list
    }

    fun save(record: CookieRecord) {
        val list = load()
        // Keep newest unique pin at top; update existing pin
        list.removeAll { it.ptPin == record.ptPin }
        list.add(0, record)
        while (list.size > MAX) list.removeAt(list.lastIndex)
        persist(list)
    }

    fun delete(ptPin: String) {
        val list = load()
        list.removeAll { it.ptPin == ptPin }
        persist(list)
    }

    private fun persist(list: List<CookieRecord>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("ptKey", r.ptKey)
                    .put("ptPin", r.ptPin)
                    .put("savedAt", r.savedAt)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "jd_cookie_history"
        private const val KEY = "records"
        private const val MAX = 20
    }
}
