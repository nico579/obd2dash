package com.nico.obd2dash

/** Détecte une perte persistante à partir des lectures réellement tentées. */
internal class PollingHealth(
    private val measurementsExpected: Boolean,
    private val failureTimeoutMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private var lastMeasurementAtMs = nowMs()
    private var firstMilFailureAtMs: Long? = null

    /** Une pause de diagnostic ne constitue pas une série de lectures ratées. */
    fun diagnosticPause() {
        lastMeasurementAtMs = nowMs()
        firstMilFailureAtMs = null
    }

    /** Retourne true si les mesures attendues ont cessé de répondre durablement. */
    fun measurementsRead(success: Boolean): Boolean {
        if (!measurementsExpected) return false
        val now = nowMs()
        if (success) lastMeasurementAtMs = now
        return !success && now - lastMeasurementAtMs > failureTimeoutMs
    }

    /**
     * PID01 seul : compter depuis le premier échec, pas depuis le dernier succès.
     * Les 30 s entre deux contrôles MIL normaux dépassent déjà le seuil de 15 s.
     * Lorsque d'autres mesures sont attendues, elles seules pilotent ce verdict.
     */
    fun milRead(success: Boolean): Boolean {
        if (measurementsExpected) return false
        if (success) {
            firstMilFailureAtMs = null
            return false
        }
        val now = nowMs()
        val firstFailure = firstMilFailureAtMs ?: now.also { firstMilFailureAtMs = it }
        return now - firstFailure > failureTimeoutMs
    }
}
