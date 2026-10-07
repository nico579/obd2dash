@file:Suppress("UNUSED_PARAMETER")
package com.nico.obd2dash

import android.content.Context

data class DtcHistoryEntry(
    val code: String, val firstSeen: String, val lastSeen: String, val active: Boolean
)
class DtcHistoryStore(context: Context) {
    fun resetSessionHistory() {}
    fun load(id: String): List<DtcHistoryEntry> = emptyList()
    fun record(id: String, codes: List<String>): List<DtcHistoryEntry> = emptyList()
    companion object { const val UNKNOWN_VEHICLE = "inconnu" }
}
class RecordingService {
    companion object { const val EXTRA_SESSION_ID = "recording_session_id" }
}
object EventLog { fun log(message: String) {} }
