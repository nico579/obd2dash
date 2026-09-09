package com.nico.obd2dash

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DtcHistoryEntry(
    val code: String,
    val firstSeen: String,
    val lastSeen: String,
    val active: Boolean
)

/**
 * Historique local des DTC, par véhicule connu de ce téléphone via cette app. Contrairement
 * à un instantané, on garde par code sa première et dernière apparition : utile pour savoir
 * si un défaut est nouveau, persistant, ou résolu depuis la dernière connexion.
 */
class DtcHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("obd2dash", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE)

    fun record(currentCodes: List<String>): List<DtcHistoryEntry> {
        val now = dateFormat.format(Date())
        val obj = JSONObject(prefs.getString(KEY, null) ?: "{}")
        val currentSet = currentCodes.toSet()

        val allCodes = mutableSetOf<String>()
        obj.keys().forEach { allCodes.add(it) }
        allCodes.addAll(currentSet)

        for (code in allCodes) {
            val existing = obj.optJSONObject(code)
            val isActiveNow = code in currentSet
            val entry = JSONObject()
            entry.put("firstSeen", existing?.optString("firstSeen").takeUnless { it.isNullOrEmpty() } ?: now)
            entry.put("lastSeen", if (isActiveNow) now else (existing?.optString("lastSeen") ?: now))
            entry.put("active", isActiveNow)
            obj.put(code, entry)
        }

        prefs.edit().putString(KEY, obj.toString()).apply()
        return load()
    }

    fun load(): List<DtcHistoryEntry> {
        val obj = JSONObject(prefs.getString(KEY, null) ?: "{}")
        val list = mutableListOf<DtcHistoryEntry>()
        obj.keys().forEach { code ->
            val e = obj.getJSONObject(code)
            list.add(DtcHistoryEntry(code, e.optString("firstSeen"), e.optString("lastSeen"), e.optBoolean("active")))
        }
        return list.sortedByDescending { it.lastSeen }
    }

    companion object {
        private const val KEY = "dtc_history"
    }
}
