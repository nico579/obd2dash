package com.nico.obd2dash

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Décodage SAE synthétique : aucune connexion ni attribution supposée au combiné. */
class StandardWarningLightsTest {
    private val client = Elm327Client("127.0.0.1")

    private fun nox(a: String = "01", b: String = "01") = "4194$a$b" + "00".repeat(10)

    private fun rejected(light: StandardWarningLight, response: String) {
        try {
            client.parseStandardWarning(response, light)
            fail("Une réponse inconnue ne doit pas confirmer un voyant éteint : $response")
        } catch (_: IOException) { }
    }

    @Test
    fun `le prechauffage depend du support A3 puis de B3 seulement`() {
        val light = StandardWarningLight.GLOW_PLUG
        assertEquals(StandardWarningStatus.ON, client.parseStandardWarning("41 65 0F 08", light))
        assertEquals(StandardWarningStatus.OFF, client.parseStandardWarning("41650807", light))
        assertEquals(StandardWarningStatus.NOT_SUPPORTED, client.parseStandardWarning("41650708", light))
        assertEquals(StandardWarningStatus.NOT_SUPPORTED, client.parseStandardWarning("41650000", light))
    }

    @Test
    fun `une alerte NOx est active seulement si A0 la prend en charge`() {
        val light = StandardWarningLight.NOX_WARNING
        assertEquals(StandardWarningStatus.ON, client.parseStandardWarning(nox(), light))
        assertEquals(StandardWarningStatus.OFF, client.parseStandardWarning(nox(b = "7E"), light))
        assertEquals(StandardWarningStatus.NOT_SUPPORTED, client.parseStandardWarning(nox(a = "3E"), light))
        assertEquals("active", standardWarningText(light, StandardWarningStatus.ON))
        assertEquals("inactive", standardWarningText(light, StandardWarningStatus.OFF))
    }

    @Test
    fun `les modes MI vehicule et calculateur utilisent leurs bits respectifs`() {
        val statuses = mapOf(0 to StandardWarningStatus.OFF, 1 to StandardWarningStatus.ON_DEMAND,
            2 to StandardWarningStatus.SHORT, 3 to StandardWarningStatus.CONTINUOUS, 15 to StandardWarningStatus.UNAVAILABLE)
        for ((mode, expected) in statuses) {
            val vehicleByte = 0xC3 or (mode shl 2) // Readiness, stratégie et réservés ne changent pas le mode.
            assertEquals(expected, client.parseStandardWarning("4190%02X0000".format(vehicleByte), StandardWarningLight.WWH_VEHICLE_MI))
            assertEquals(expected, client.parseStandardWarning("4191%02X00000000".format(0xA0 or mode), StandardWarningLight.WWH_ECU_MI))
        }
    }

    @Test
    fun `un mode MI en erreur ou reserve ne devient pas eteint`() {
        for (mode in 4..14) {
            rejected(StandardWarningLight.WWH_VEHICLE_MI, "4190%02X0000".format(mode shl 2))
            rejected(StandardWarningLight.WWH_ECU_MI, "4191%02X00000000".format(mode))
        }
    }

    @Test
    fun `tous les octets obligatoires sont requis meme ceux des compteurs ignores`() {
        val valid = mapOf(StandardWarningLight.GLOW_PLUG to "41650800",
            StandardWarningLight.WWH_VEHICLE_MI to "4190000000",
            StandardWarningLight.WWH_ECU_MI to "41910000000000", StandardWarningLight.NOX_WARNING to nox(b = "00"))
        for ((light, response) in valid) {
            for (bad in listOf(response.dropLast(2), response.dropLast(1), response + "00", response.dropLast(2) + "GG")) {
                rejected(light, bad)
                rejected(light, "$response\r$bad")
                rejected(light, "$bad\r$response")
            }
        }
        rejected(StandardWarningLight.NOX_WARNING, "41940100") // Un état apparent ne suffit pas.
    }

    @Test
    fun `une absence de reponse ou un refus ne devient jamais eteint`() {
        for (light in StandardWarningLight.entries) {
            for (response in listOf("", "NO DATA", "STOPPED", "7F0111", "7F0121", "7F0178", "410C0000")) rejected(light, response)
        }
        rejected(StandardWarningLight.GLOW_PLUG, "41650800\r7F0112")
        rejected(StandardWarningLight.GLOW_PLUG, "7F01GG\r41650800")
        assertEquals(StandardWarningStatus.OFF, client.parseStandardWarning("7F0311\r41650800", StandardWarningLight.GLOW_PLUG))
    }

    @Test
    fun `le premier repondant ne masque ni un etat contradictoire ni un mode indisponible`() {
        val pairs = mapOf(StandardWarningLight.GLOW_PLUG to ("41650800" to "41650808"),
            StandardWarningLight.NOX_WARNING to (nox(b = "00") to nox()),
            StandardWarningLight.WWH_VEHICLE_MI to ("4190000000" to "41903C0000"),
            StandardWarningLight.WWH_ECU_MI to ("41910000000000" to "41910300000000"))
        for ((light, pair) in pairs) {
            rejected(light, "${pair.first}\r${pair.second}")
            rejected(light, "${pair.second}\r${pair.first}")
        }
    }

    @Test
    fun `un ECU sans la fonction et les doublons ne masquent pas celui qui la fournit`() {
        for (reply in listOf("41650000\r41650808", "41650808\r41650000", "41650808\r41 65 08 08")) {
            assertEquals(StandardWarningStatus.ON, client.parseStandardWarning(reply, StandardWarningLight.GLOW_PLUG))
        }
    }

    @Test
    fun `NOx multi trame retire le remplissage et refuse une trame manquante`() {
        val hex = nox()
        val framed = "00E\r0:${hex.take(12)}\r1:${hex.substring(12, 26)}\r2:${hex.substring(26)}000000000000"
        assertEquals(StandardWarningStatus.ON, client.parseStandardWarning(framed, StandardWarningLight.NOX_WARNING))
        rejected(StandardWarningLight.NOX_WARNING, "00E\r0:${hex.take(12)}\r2:${hex.substring(26)}")
        rejected(StandardWarningLight.NOX_WARNING, "$hex\r00E\r0:${hex.take(12)}")
    }

    @Test
    fun `le CSV distingue valeur connue date de lecture et echec recent`() {
        val light = StandardWarningLight.GLOW_PLUG
        val reading = StandardWarningReading(StandardWarningStatus.OFF, 1000, 7000, "NO DATA")
        assertEquals(listOf("éteint", "1000", "7000", "NO DATA"),
            recordingWarningFields(listOf(light), mapOf(light to reading)) { it.toString() })
        assertEquals(listOf("non lu", "", "", ""), recordingWarningFields(listOf(light), emptyMap()) { it.toString() })
        assertEquals(listOf("Préchauffage", "Lecture Préchauffage", "Tentative Préchauffage", "Erreur Préchauffage"),
            recordingWarningHeaders(listOf(light)))
    }

    @Test
    fun `la cadence rapproche les lectures seulement pendant l'enregistrement`() {
        assertTrue(warningPollDue(null, 1, false))
        assertFalse(warningPollDue(1000, 5999, true))
        assertTrue(warningPollDue(1000, 6000, true))
        assertFalse(warningPollDue(1000, 6000, false))
        assertFalse(warningPollDue(1000, 30999, false))
        assertTrue(warningPollDue(1000, 31000, false))
    }

    @Test
    fun `les colonnes automatiques suivent les annonces sans inventer ABS ou airbag`() {
        assertTrue(supportedStandardWarningLights(emptySet()).isEmpty())
        assertEquals(listOf(StandardWarningLight.GLOW_PLUG), supportedStandardWarningLights(setOf(0x01, 0x65, 0x8B)))
        assertEquals(StandardWarningLight.entries, supportedStandardWarningLights(setOf(0x94, 0x91, 0x65, 0x90)))
    }
}
