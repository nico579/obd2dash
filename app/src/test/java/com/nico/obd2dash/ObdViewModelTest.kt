package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- A6 : le rapport exporte ne doit pas masquer un echec ou une valeur perimee ---

    @Test
    fun `buildDiagnosticReport jamais lu avec succes reste explicite`() {
        assertTrue(buildDiagnosticReport(ObdUiState()).contains("Dernière lecture DTC réussie : jamais"))
    }

    @Test
    fun `buildDiagnosticReport date la derniere lecture DTC reussie et signale un echec ulterieur`() {
        // Les deux peuvent cohabiter : un succes passe encore affiche, puis un refresh
        // plus recent qui a echoue (dtcError), sans que l'un efface la trace de l'autre.
        val ts = 1_700_000_000_000L
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.FRANCE)
        val state = ObdUiState(dtcLastSuccessAtMs = ts, dtcError = "Lecture DTC échouée")
        val report = buildDiagnosticReport(state)
        assertTrue(report.contains("Dernière lecture DTC réussie : ${format.format(java.util.Date(ts))}"))
        assertTrue(report.contains("Dernière tentative de lecture DTC en échec : Lecture DTC échouée"))
    }

    @Test
    fun `buildDiagnosticReport marque une valeur live perimee`() {
        val old = GaugeValue("830 rpm", updatedAtMs = 0L) // horodatage tres ancien par construction
        val state = ObdUiState(values = mapOf(0x0C to old))
        assertTrue(buildDiagnosticReport(state).contains("830 rpm (périmé)"))
    }

    @Test
    fun `buildDiagnosticReport n'affiche jamais perime pour un CONTEXT_ONLY_PID`() {
        // Bug reel constate sur capture le 11 septembre : PID4F est lu une seule fois a la
        // connexion (voir PidCatalog.CONTEXT_ONLY_PIDS et ObdViewModel.startPolling), donc
        // son age depasse VALUE_UNAVAILABLE_AFTER_MS des les 10 premieres secondes de CHAQUE
        // session sans que la valeur soit fausse. "10 / 10" etait marque perime a tort.
        val old = GaugeValue("10 / 10", updatedAtMs = 0L)
        val state = ObdUiState(values = mapOf(0x4F to old))
        val report = buildDiagnosticReport(state)
        assertTrue(report.contains("10 / 10"))
        assertFalse(report.contains("10 / 10 (périmé)"))
    }

    // --- csvRow / csvEscape : le delimiteur point-virgule et les valeurs deja formatees
    // (virgule decimale francaise, ex. "94,20 V") ne doivent jamais se confondre.

    @Test
    fun `csvRow separe les colonnes par point-virgule`() {
        assertEquals("\"a\";\"b\";\"c\"", csvRow(listOf("a", "b", "c")))
    }

    @Test
    fun `csvRow protege une valeur contenant une virgule decimale francaise`() {
        val row = csvRow(listOf("2026-09-10 13:00:00", "94,20 V", "845 rpm"))
        // Le point-virgule ne doit apparaitre qu'entre les champs, jamais a l'interieur.
        assertEquals(2, row.count { it == ';' })
        assertTrue(row.contains("\"94,20 V\""))
    }

    @Test
    fun `csvEscape double les guillemets internes`() {
        assertEquals("\"a\"\"b\"", csvEscape("a\"b"))
    }
}
