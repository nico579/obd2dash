package com.nico.obd2dash

/** Alertes du service 01 documentées ; aucune déduction depuis un DTC ou une mesure. */
enum class StandardWarningLight(val pid: Int, val dataBytes: Int, val csvLabel: String) {
    GLOW_PLUG(0x65, 2, "Préchauffage"),
    WWH_VEHICLE_MI(0x90, 3, "Mode MI véhicule WWH-OBD"),
    WWH_ECU_MI(0x91, 5, "Mode MI calculateur WWH-OBD"),
    NOX_WARNING(0x94, 12, "Alerte NOx")
}

enum class StandardWarningStatus(val csvText: String) {
    OFF("éteint"), ON("allumé"), ON_DEMAND("à la demande"),
    SHORT("mode court"), CONTINUOUS("mode continu"),
    NOT_SUPPORTED("non pris en charge"), UNAVAILABLE("indisponible")
}

/** La dernière valeur réussie reste datée, même après une tentative en échec. */
data class StandardWarningReading(
    val status: StandardWarningStatus? = null,
    val lastSuccessAtMs: Long? = null,
    val lastAttemptAtMs: Long? = null,
    val error: String? = null
)

internal fun supportedStandardWarningLights(pids: Set<Int>): List<StandardWarningLight> =
    StandardWarningLight.entries.filter { it.pid in pids }

internal fun standardWarningText(light: StandardWarningLight, status: StandardWarningStatus?): String =
    when {
        status == null -> "non lu"
        // PID94 rapporte un système d'alerte actif, pas l'ampoule AdBlue du combiné.
        light == StandardWarningLight.NOX_WARNING && status == StandardWarningStatus.ON -> "active"
        light == StandardWarningLight.NOX_WARNING && status == StandardWarningStatus.OFF -> "inactive"
        else -> status.csvText
    }

internal const val RECORDING_WARNING_INTERVAL_MS = 5_000L
internal const val NORMAL_WARNING_INTERVAL_MS = 30_000L

internal fun warningPollDue(lastAttemptAtMs: Long?, nowMs: Long, recording: Boolean): Boolean =
    lastAttemptAtMs == null || nowMs - lastAttemptAtMs >=
        if (recording) RECORDING_WARNING_INTERVAL_MS else NORMAL_WARNING_INTERVAL_MS

internal fun recordingWarningHeaders(lights: List<StandardWarningLight>): List<String> =
    lights.flatMap { light ->
        listOf(light.csvLabel, "Lecture ${light.csvLabel}", "Tentative ${light.csvLabel}", "Erreur ${light.csvLabel}")
    }

internal fun recordingWarningFields(
    lights: List<StandardWarningLight>,
    readings: Map<StandardWarningLight, StandardWarningReading>,
    formatTime: (Long) -> String
): List<String> = lights.flatMap { light ->
    val reading = readings[light]
    listOf(
        standardWarningText(light, reading?.status),
        reading?.lastSuccessAtMs?.let(formatTime).orEmpty(),
        reading?.lastAttemptAtMs?.let(formatTime).orEmpty(),
        reading?.error.orEmpty()
    )
}
