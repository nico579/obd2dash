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

    // Historique de session pour un véhicule non identifié (VIN absent, notamment tout le
    // chemin non-CAN) : jamais écrit sur le disque ni partagé entre connexions, pour ne pas
    // fusionner deux véhicules différents sous le même identifiant UNKNOWN_VEHICLE (voir
    // audit, "Deux véhicules et historique") — un défaut "non retrouvé" sur le second
    // pouvait sinon effacer, à tort, un défaut réel toujours actif sur le premier. Vidé à
    // chaque nouvelle connexion sans VIN (voir resetSessionHistory, appelée par
    // ObdViewModel.connect), gardé le temps d'UNE connexion pour rester utile pendant une
    // session (plusieurs rafraîchissements DTC de suite construisent quand même un
    // premier/dernier vu cohérent).
    private var sessionHistory: Map<String, DtcHistoryEntry> = emptyMap()

    /** À appeler une fois par connexion dont le véhicule n'est pas identifié (voir ci-dessus). */
    fun resetSessionHistory() {
        sessionHistory = emptyMap()
    }

    fun record(vehicleId: String, currentCodes: List<String>): List<DtcHistoryEntry> {
        if (vehicleId == UNKNOWN_VEHICLE) {
            sessionHistory = mergeHistory(sessionHistory, currentCodes, dateFormat.format(Date()))
            return sessionHistory.values.sortedByDescending { it.lastSeen }
        }
        val key = keyFor(vehicleId)
        val merged = mergeHistory(readPersisted(key), currentCodes, dateFormat.format(Date()))
        prefs.edit().putString(key, writePersisted(merged)).apply()
        return merged.values.sortedByDescending { it.lastSeen }
    }

    fun load(vehicleId: String): List<DtcHistoryEntry> {
        if (vehicleId == UNKNOWN_VEHICLE) {
            return sessionHistory.values.sortedByDescending { it.lastSeen }
        }
        return readPersisted(keyFor(vehicleId)).values.sortedByDescending { it.lastSeen }
    }

    private fun keyFor(vehicleId: String) = "dtc_history_$vehicleId"

    private fun readPersisted(key: String): Map<String, DtcHistoryEntry> {
        val obj = JSONObject(prefs.getString(key, null) ?: "{}")
        return obj.keys().asSequence().associateWith { code ->
            val e = obj.getJSONObject(code)
            DtcHistoryEntry(code, e.optString("firstSeen"), e.optString("lastSeen"), e.optBoolean("active"))
        }
    }

    private fun writePersisted(entries: Map<String, DtcHistoryEntry>): String {
        val obj = JSONObject()
        for ((code, entry) in entries) {
            obj.put(
                code,
                JSONObject()
                    .put("firstSeen", entry.firstSeen)
                    .put("lastSeen", entry.lastSeen)
                    .put("active", entry.active)
            )
        }
        return obj.toString()
    }

    companion object {
        const val UNKNOWN_VEHICLE = "inconnu"
    }
}

/**
 * Fusionne un historique existant avec les codes actuellement lus : fonction pure (aucun
 * accès disque), partagée par le chemin persisté (véhicule identifié) et le chemin
 * en mémoire (véhicule inconnu, voir [DtcHistoryStore.sessionHistory]). [now] est injecté
 * plutôt que recalculé ici pour rester testable sans dépendre de l'horloge système.
 */
internal fun mergeHistory(
    previous: Map<String, DtcHistoryEntry>,
    currentCodes: List<String>,
    now: String
): Map<String, DtcHistoryEntry> {
    val currentSet = currentCodes.toSet()
    val allCodes = previous.keys + currentSet
    return allCodes.associateWith { code ->
        val existing = previous[code]
        val isActiveNow = code in currentSet
        DtcHistoryEntry(
            code = code,
            firstSeen = existing?.firstSeen ?: now,
            lastSeen = if (isActiveNow) now else (existing?.lastSeen ?: now),
            active = isActiveNow
        )
    }
}
