package com.nico.obd2dash

/** A response proves communication, not the availability of a decoded measurement. */
enum class ObdDataAvailability {
    NOT_CHECKED,
    NO_VEHICLE_RESPONSE,
    NO_STANDARD_MEASUREMENTS,
    STANDARD_MEASUREMENTS_AVAILABLE
}

internal fun standardObdAvailability(
    vehicleResponseObserved: Boolean,
    supportedPids: Set<Int>
): ObdDataAvailability = when {
    !vehicleResponseObserved -> ObdDataAvailability.NO_VEHICLE_RESPONSE
    PidCatalog.defs.none { it.pid in supportedPids } -> ObdDataAvailability.NO_STANDARD_MEASUREMENTS
    else -> ObdDataAvailability.STANDARD_MEASUREMENTS_AVAILABLE
}

/** Only announced measurements have a reason to be polled automatically. */
internal fun hasAutomaticObdReads(supportedPids: Set<Int>): Boolean =
    0x01 in supportedPids || PidCatalog.defs.any {
        it.pid in supportedPids && it.pid !in PidCatalog.CONTEXT_ONLY_PIDS
    }

/** The current application has no validated manufacturer measurement profile. */
internal const val UNVALIDATED_MANUFACTURER_PROBES_ENABLED = false
