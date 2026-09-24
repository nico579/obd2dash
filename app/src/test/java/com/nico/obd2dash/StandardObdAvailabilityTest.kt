package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardObdAvailabilityTest {
    @Test
    fun `Trafic zero bitmap proves communication but schedules no automatic reads`() {
        assertEquals(ObdDataAvailability.NO_STANDARD_MEASUREMENTS,
            standardObdAvailability(vehicleResponseObserved = true, supportedPids = emptySet()))
        assertFalse(hasAutomaticObdReads(emptySet()))
    }

    @Test
    fun `no data never becomes a confirmed vehicle response`() {
        assertEquals(ObdDataAvailability.NO_VEHICLE_RESPONSE,
            standardObdAvailability(vehicleResponseObserved = false, supportedPids = emptySet()))
    }

    @Test
    fun `announced PID outside catalog is not displayed as a supported measurement`() {
        val unknown = (1..255).first { pid -> PidCatalog.defs.none { it.pid == pid } && pid != 1 }
        assertEquals(ObdDataAvailability.NO_STANDARD_MEASUREMENTS,
            standardObdAvailability(true, setOf(unknown)))
        assertFalse(hasAutomaticObdReads(setOf(unknown)))
    }

    @Test
    fun `context measurements alone do not trigger a zombie reconnect polling loop`() {
        assertEquals(ObdDataAvailability.STANDARD_MEASUREMENTS_AVAILABLE,
            standardObdAvailability(true, setOf(0x4F, 0x50)))
        assertFalse(hasAutomaticObdReads(setOf(0x4F, 0x50)))
    }

    @Test
    fun `supported engine speed and MIL retain automatic reads`() {
        assertTrue(hasAutomaticObdReads(setOf(0x0C)))
        assertTrue(hasAutomaticObdReads(setOf(0x01)))
        assertEquals(ObdDataAvailability.STANDARD_MEASUREMENTS_AVAILABLE,
            standardObdAvailability(true, setOf(0x0C)))
    }

    @Test
    fun `empty standard support report does not invent vehicle health`() {
        val report = buildDiagnosticReport(ObdUiState(
            connectionState = ConnectionState.CONNECTED,
            dataAvailability = ObdDataAvailability.NO_STANDARD_MEASUREMENTS,
            vehicleResponseObserved = true,
            mode01BitmapReceived = true,
            pidDiscoveryComplete = true
        ))
        assertTrue(report.contains("véhicule répond, aucune mesure exploitable"))
        assertTrue(report.contains("MIL : non lu"))
        assertTrue(report.contains("DTC stockés (non lu)"))
        assertTrue(report.contains("Bitmap mode 01 reçu : true"))
        assertFalse(UNVALIDATED_MANUFACTURER_PROBES_ENABLED)
    }
}
