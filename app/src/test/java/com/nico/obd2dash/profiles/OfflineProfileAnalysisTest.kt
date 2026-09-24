package com.nico.obd2dash.profiles

import org.junit.Assert.*
import org.junit.Test

class OfflineProfileAnalysisTest {
    // Réponse 2180 enregistrée, sans VIN ; aucun profil de mesures n'est déduit de cette fixture.
    private val captured = "9A F1 7A 61 80 82 00 40 25 78 19 30 33 37 82 00 35 51 75 00 CB 12 00 83 A1 7C 01 CA 12 CF"

    @Test
    fun `Trafic recorded identity is recognised and measurements remain unavailable`() {
        val result = OfflineProfileAnalysis.analyze(captured, ProfileProtocol.KWP_FAST)
        assertEquals("7A", result.ecuAddress)
        assertEquals("F1", result.testerAddress)
        assertEquals(25, result.diagnosticVersion)
        assertEquals("037", result.supplier)
        assertEquals("00CB", result.software)
        assertEquals("1200", result.version)
        assertEquals(ProfileMatchStatus.NO_MATCH, result.profileStatus)
        assertNull(result.matchedProfileId)
        assertEquals(0, result.plannedReadCount)
    }

    @Test
    fun `frame alone never implies fast initialization protocol`() {
        val result = OfflineProfileAnalysis.analyze(captured)
        assertEquals(ProfileMatchStatus.INCOMPLETE_IDENTITY, result.profileStatus)
        assertEquals(25, result.diagnosticVersion)
        assertNull(result.matchedProfileId)
        assertEquals(0, result.plannedReadCount)
    }

    @Test
    fun `CAN setting cannot be applied to a KWP capture`() {
        try {
            OfflineProfileAnalysis.analyze(captured, ProfileProtocol.CAN_11)
            fail("Mauvais protocole accepté")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `invalid checksum and oversized text produce no profile summary`() {
        for (text in listOf(captured.dropLast(2) + "00", "A".repeat(2049))) {
            try { OfflineProfileAnalysis.analyze(text); fail("Capture invalide acceptée") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
