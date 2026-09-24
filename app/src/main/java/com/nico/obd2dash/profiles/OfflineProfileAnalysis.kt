package com.nico.obd2dash.profiles

import java.util.Locale

data class OfflineProfileSummary(
    val ecuAddress: String,
    val testerAddress: String,
    val diagnosticVersion: Int,
    val supplier: String,
    val software: String,
    val version: String,
    val matchedProfileId: String?,
    val plannedReadCount: Int,
    val profileStatus: ProfileMatchStatus
)

/** Analyse explicite d'une capture STD_A. Aucun accès au client ELM ou à l'état du véhicule. */
object OfflineProfileAnalysis {
    fun analyze(frameHex: String, protocol: ProfileProtocol? = null): OfflineProfileSummary {
        require(frameHex.length <= 2048) { "Capture trop longue" }
        require(protocol == null || protocol in setOf(ProfileProtocol.KWP_FAST, ProfileProtocol.KWP_SLOW)) {
            "La capture STD_A nécessite un protocole KWP"
        }
        val identity = KwpCaptureIdentity.parse(frameHex)
        val address = "%02X".format(Locale.ROOT, identity.sourceAddress)
        // La forme de la trame seule ne prouve pas le mode d'initialisation.
        val selection = if (protocol == null) ProfileSelection(ProfileMatchStatus.INCOMPLETE_IDENTITY)
        else ManufacturerProfiles.installed.select(EcuFingerprint(
            EcuEndpoint(protocol, address, address),
            "renault_std_a_candidate",
            mapOf(
                "diagnostic_version" to identity.diagnosticVersion.toString(),
                "supplier" to identity.supplier,
                "software" to identity.software,
                "version" to identity.version
            )
        ))
        return OfflineProfileSummary(address, "%02X".format(Locale.ROOT, identity.testerAddress),
            identity.diagnosticVersion, identity.supplier, identity.software, identity.version,
            selection.profileId.takeIf { selection.status == ProfileMatchStatus.READY_FOR_REPLAY },
            selection.reads.size, selection.status)
    }
}
