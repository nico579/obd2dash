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

    fun record(vehicleId: String, currentCodes: List<String>): List<DtcHistoryEntry> {
        migrateLegacyIfNeeded()
        val key = keyFor(vehicleId)
        val now = dateFormat.format(Date())
        val obj = JSONObject(prefs.getString(key, null) ?: "{}")
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

        prefs.edit().putString(key, obj.toString()).apply()
        return load(vehicleId)
    }

    fun load(vehicleId: String): List<DtcHistoryEntry> {
        migrateLegacyIfNeeded()
        val obj = JSONObject(prefs.getString(keyFor(vehicleId), null) ?: "{}")
        val list = mutableListOf<DtcHistoryEntry>()
        obj.keys().forEach { code ->
            val e = obj.getJSONObject(code)
            list.add(DtcHistoryEntry(code, e.optString("firstSeen"), e.optString("lastSeen"), e.optBoolean("active")))
        }
        return list.sortedByDescending { it.lastSeen }
    }

    private fun keyFor(vehicleId: String) = "dtc_history_$vehicleId"

    // L'historique était à l'origine sous une seule clé globale, commune à tous les
    // véhicules (avant que le VIN ne soit lu). On le rattache une seule fois au profil
    // "véhicule inconnu" plutôt que de le perdre silencieusement.
    private fun migrateLegacyIfNeeded() {
        val legacy = prefs.getString(LEGACY_KEY, null) ?: return
        val unknownKey = keyFor(UNKNOWN_VEHICLE)
        if (prefs.getString(unknownKey, null) == null) {
            prefs.edit().putString(unknownKey, legacy).apply()
        }
        prefs.edit().remove(LEGACY_KEY).apply()
    }

    companion object {
        private const val LEGACY_KEY = "dtc_history"
        const val UNKNOWN_VEHICLE = "inconnu"
    }
}
