package com.nico.obd2dash

import org.junit.Assert.assertTrue
import org.junit.Test

/** Couvre buildDiagnosticReport, fonction pure de l'état (pas de ViewModel ni de contexte Android nécessaire). */
class ObdViewModelTest {

    @Test
    fun `buildDiagnosticReport etat par defaut n'affirme rien de faux`() {
        val report = buildDiagnosticReport(ObdUiState())
        assertTrue(report.contains("VIN : inconnu"))
        assertTrue(report.contains("MIL : non lu"))
        assertTrue(report.contains("Non lu")) // sections DTC stockes/en attente
    }

    @Test
    fun `buildDiagnosticReport etat rempli inclut le code et sa description`() {
        val state = ObdUiState(
            vin = "VSSZZZ6JZBR000000",
            protocol = "A6",
            milOn = true,
            storedDtcs = listOf("P0087"),
            pendingDtcs = emptyList(),
            readiness = listOf(ReadinessMonitor("Suralimentation", ready = true))
        )
        val report = buildDiagnosticReport(state)
        assertTrue(report.contains("VSSZZZ6JZBR000000"))
        assertTrue(report.contains("Protocole : A6"))
        assertTrue(report.contains("MIL : allumé"))
        assertTrue(report.contains("P0087"))
        assertTrue(report.contains(DtcDictionary.describe("P0087")))
        assertTrue(report.contains("Aucun")) // DTC en attente, liste vide confirmee
        assertTrue(report.contains("Suralimentation"))
    }
}
