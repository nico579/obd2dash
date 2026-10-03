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
    fun `le rapport distingue une relecture stockee du dernier scan complet`() {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.FRANCE)
        val fullAt = 1_700_000_000_000L
        val storedAt = fullAt + 600_000
        val milAt = storedAt + 30_000
        val report = buildDiagnosticReport(ObdUiState(
            milOn = false, storedDtcs = listOf("P0087"), pendingDtcs = emptyList(),
            dtcLastSuccessAtMs = fullAt, storedDtcsLastSuccessAtMs = storedAt, milLastSuccessAtMs = milAt
        ))
        assertTrue(report.contains("Dernière lecture DTC réussie : ${format.format(java.util.Date(fullAt))}"))
        assertTrue(report.contains("Dernière lecture des codes stockés réussie : ${format.format(java.util.Date(storedAt))}"))
        assertTrue(report.contains("Dernière lecture MIL réussie : ${format.format(java.util.Date(milAt))}"))
    }

    @Test
    fun `la lecture automatique des codes ne pretend pas avoir lu les pending`() {
        val state = ObdUiState(storedDtcs = listOf("P0087"), storedDtcsLastSuccessAtMs = 1_700_000_000_000L)
        assertTrue(buildDiagnosticReport(state).contains("Dernière lecture DTC réussie : jamais"))
        assertEquals(null, state.pendingDtcs)
    }

    @Test
    fun `le CSV date les etats MIL et codes sans les rajeunir a l'export`() {
        val state = ObdUiState(milOn = false, storedDtcs = listOf("P0087"),
            milLastSuccessAtMs = 30_000, storedDtcsLastSuccessAtMs = 10_000, dtcLastSuccessAtMs = 5_000)
        assertEquals(listOf("éteint", "P0087", "30000", "10000"), recordingDiagnosticFields(state) { it.toString() })
        assertEquals(listOf("non lu", "", "", ""), recordingDiagnosticFields(ObdUiState()) { it.toString() })
    }

    @Test
    fun `une tentative MIL ratee conserve la date du resultat precedent dans la capture`() {
        val state = ObdUiState(milOn = false, milLastSuccessAtMs = 1000, milLastAttemptAtMs = 7000, milReadError = "NO DATA")
        assertEquals(listOf("éteint", "", "1000", ""), recordingDiagnosticFields(state) { it.toString() })
        assertEquals(listOf("7000", "NO DATA"), recordingMilAttemptFields(state) { it.toString() })
        assertTrue(buildDiagnosticReport(state).contains("Dernière tentative MIL en échec : NO DATA"))
    }

    @Test
    fun `le rapport inclut uniquement les alertes annoncees et leurs echecs dates`() {
        val light = StandardWarningLight.NOX_WARNING
        val state = ObdUiState(supportedPids = setOf(light.pid), standardWarnings = mapOf(light to
            StandardWarningReading(StandardWarningStatus.ON, 1000, 7000, "Réponse tronquée")))
        val report = buildDiagnosticReport(state)
        assertTrue(report.contains("Alerte NOx : active"))
        assertTrue(report.contains("Dernière lecture Alerte NOx réussie :"))
        assertTrue(report.contains("Dernière tentative Alerte NOx en échec : Réponse tronquée"))
        assertFalse(report.contains("Préchauffage"))
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

    // --- Seuils de peremption par PID (captures Bluetooth du 24/09) ---

    @Test
    fun `une temperature relue lentement n'est pas perimee au seuil des mesures rapides`() {
        val now = 100_000L
        val value = GaugeValue("48 °C", updatedAtMs = now - 12_000L)
        assertFalse(isUnavailable(0x05, value, now)) // PID lent : seuil 20s
        assertTrue(isUnavailable(0x0C, value, now)) // PID rapide : seuil 10s
    }

    @Test
    fun `une temperature vraiment ancienne reste signalee perimee`() {
        val now = 100_000L
        assertTrue(isUnavailable(0x05, GaugeValue("48 °C", updatedAtMs = now - 25_000L), now))
    }

    @Test
    fun `buildDiagnosticReport indique le code declencheur du freeze frame`() {
        val state = ObdUiState(freezeFrame = mapOf(0x0C to "1305 rpm"), freezeFrameDtc = "P0087")
        assertTrue(buildDiagnosticReport(state).contains("Code déclencheur : P0087"))
    }

    // --- DtcDictionary ---

    @Test
    fun `DtcDictionary connait P0087`() {
        assertEquals("Pression rampe/système carburant trop basse", DtcDictionary.describe("P0087"))
    }

    @Test
    fun `DtcDictionary classe un code inconnu d'apres sa structure`() {
        assertEquals(DtcDictionary.DtcOrigin.GENERIC, DtcDictionary.classify("P0999"))
        assertEquals(DtcDictionary.DtcOrigin.GENERIC, DtcDictionary.classify("P2FFF"))
        assertEquals(DtcDictionary.DtcOrigin.MANUFACTURER, DtcDictionary.classify("P1234"))
        assertEquals(DtcDictionary.DtcOrigin.MANUFACTURER, DtcDictionary.classify("P3000"))
        assertEquals(DtcDictionary.DtcOrigin.GENERIC, DtcDictionary.classify("P3400"))
        assertEquals(DtcDictionary.DtcOrigin.MANUFACTURER, DtcDictionary.classify("U1100"))
        assertEquals(DtcDictionary.DtcOrigin.GENERIC, DtcDictionary.classify("U0100"))
        assertEquals(null, DtcDictionary.classify("X0100"))
        assertTrue(DtcDictionary.describe("P1234").contains("constructeur"))
    }

    @Test
    fun `buildDiagnosticReport identifie l'adaptateur et ses optimisations`() {
        val state = ObdUiState(
            adapterInfo = AdapterInfo("ELM327 v1.5", null, null, null),
            supportsResponseCount = true,
            supportsMultiPid = false
        )
        val report = buildDiagnosticReport(state)
        assertTrue(report.contains("Adaptateur : ELM327 v1.5 · pas de puce STN"))
        assertTrue(report.contains("Optimisations de lecture : réponse unique oui, groupage non"))
    }

    @Test
    fun `buildDiagnosticReport sans connexion n'invente pas d'adaptateur`() {
        val report = buildDiagnosticReport(ObdUiState())
        assertTrue(report.contains("Adaptateur : non identifié"))
        assertFalse(report.contains("Optimisations de lecture"))
    }
}
