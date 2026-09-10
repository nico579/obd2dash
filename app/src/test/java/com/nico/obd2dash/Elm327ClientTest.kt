package com.nico.obd2dash

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Couvre la logique de décodage pure d'Elm327Client (pas d'accès réseau : sendRaw et le
 * reste de la couche IO ne sont pas testés ici). Les cas viennent directement des exemples
 * rejoués dans l'audit du 9 septembre 2026, pour éviter une régression sur exactement le
 * bug qui a motivé cet audit (décalage d'un octet dans le décodage DTC CAN).
 */
class Elm327ClientTest {

    private val client = Elm327Client("192.168.0.10")

    // --- decodeDtc ---

    @Test
    fun `decodeDtc P0087 pression rail`() {
        assertEquals("P0087", client.decodeDtc(0x00, 0x87))
    }

    @Test
    fun `decodeDtc P0100 debit d'air`() {
        assertEquals("P0100", client.decodeDtc(0x01, 0x00))
    }

    @Test
    fun `decodeDtc P0300 rates d'allumage`() {
        assertEquals("P0300", client.decodeDtc(0x03, 0x00))
    }

    // --- parseDtcResponse (CAN) : capture reelle de l'audit, avant le fix lisait "P0100" ---

    @Test
    fun `parseDtcResponse CAN un seul DTC prend en compte l'octet compteur`() {
        assertEquals(listOf("P0087"), client.parseDtcResponse("43010087", "43", isCan = true))
    }

    @Test
    fun `parseDtcResponse CAN deux DTC`() {
        assertEquals(listOf("P0100", "P0300"), client.parseDtcResponse("430201000300", "43", isCan = true))
    }

    @Test
    fun `parseDtcResponse CAN zero DTC confirme`() {
        assertEquals(emptyList<String>(), client.parseDtcResponse("4300", "43", isCan = true))
    }

    @Test
    fun `parseDtcResponse CAN trop courte pour contenir un compteur leve une erreur`() {
        assertThrows(IOException::class.java) { client.parseDtcResponse("43", "43", isCan = true) }
    }

    @Test
    fun `parseDtcResponse CAN compteur promet plus de codes que la trame n'en contient`() {
        // Compteur = 2 mais une seule paire de donnees derriere : trame tronquee.
        assertThrows(IOException::class.java) { client.parseDtcResponse("43020100", "43", isCan = true) }
    }

    // --- parseDtcResponse (non-CAN, K-Line/KWP2000) : pas de compteur, paires enchainees ---

    @Test
    fun `parseDtcResponse non-CAN deux DTC sans compteur`() {
        assertEquals(listOf("P0087", "P0300"), client.parseDtcResponse("4300870300", "43", isCan = false))
    }

    @Test
    fun `parseDtcResponse non-CAN zero DTC confirme`() {
        assertEquals(emptyList<String>(), client.parseDtcResponse("43", "43", isCan = false))
    }

    @Test
    fun `parseDtcResponse non-CAN paire incomplete leve une erreur`() {
        // 3 caracteres apres le prefixe : ni un DTC complet, ni vide.
        assertThrows(IOException::class.java) { client.parseDtcResponse("4300870", "43", isCan = false) }
    }

    // --- parseDtcResponse : remplissage 0000, exemples du fabricant (audit du 10 sept.) ---

    @Test
    fun `parseDtcResponse non-CAN remplissage seul ne produit aucun DTC`() {
        assertEquals(emptyList<String>(), client.parseDtcResponse("43000000000000", "43", isCan = false))
    }

    @Test
    fun `parseDtcResponse non-CAN un DTC suivi de remplissage`() {
        // Exemple ELM327 (doc. fabricant p.35) : P0133 puis deux paires 0000 de remplissage,
        // pas trois defauts distincts.
        assertEquals(listOf("P0133"), client.parseDtcResponse("43013300000000", "43", isCan = false))
    }

    // --- reassembleHex ---

    @Test
    fun `reassembleHex une reponse non hexadecimale ne produit rien`() {
        assertEquals("", client.reassembleHex("CAN ERROR", "43"))
        assertEquals("", client.reassembleHex("UNABLE TO CONNECT", "43"))
    }

    @Test
    fun `reassembleHex prefere la ligne qui correspond au prefixe attendu`() {
        // Deux lignes hexadecimales valides (ex: deux ECU) ; celle qui correspond au PID
        // demande doit etre choisie meme si elle n'est pas la premiere de la reponse.
        val response = "410100000000\r410C1AF8"
        assertEquals("410C1AF8", client.reassembleHex(response, "410C"))
    }

    @Test
    fun `reassembleHex recolle les trames multi-frame dans l'ordre`() {
        val response = "0:490201313233\r1:34353637"
        assertEquals("49020131323334353637", client.reassembleHex(response, "4902"))
    }

    @Test
    fun `reassembleHex detecte une collision multi-frame entre deux ECU et echoue proprement`() {
        // Meme numero de sequence (0), contenu different : deux calculateurs qui
        // repondraient tous deux en multi-trame sans qu'on puisse les distinguer
        // (headers desactives). Ne doit ni ecraser l'un par l'autre, ni les concatener.
        val response = "0:490201313233\r0:4902013AB233"
        assertEquals("", client.reassembleHex(response, "4902"))
    }

    @Test
    fun `reassembleHex un seul emetteur peut boucler l'index de sequence apres F`() {
        // Le numero de sequence ISO-TP tient sur 4 bits : au-dela de 16 trames, un seul
        // repondant boucle legitimement de F a 0. Ne doit pas etre confondu avec une
        // collision entre deux calculateurs (cf. R4, regression introduite par le fix
        // precedent de la collision multi-ECU).
        val frameCount = 17
        val lines = (0 until frameCount).joinToString("\r") { k ->
            val seqIndex = (k % 16).toString(16).uppercase()
            val label = "%02X".format(k)
            "$seqIndex:$label"
        }
        val expected = (0 until frameCount).joinToString("") { "%02X".format(it) }
        assertEquals(expected, client.reassembleHex(lines, "XX"))
    }

    @Test
    fun `reassembleHex un index hors ordre est rejete comme une collision`() {
        // Un vrai flux a flux controle sur un seul repondant ne peut pas sauter d'index :
        // 0 suivi directement de 2 (sans 1) est incompatible avec un seul emetteur.
        val response = "0:490201313233\r2:34353637"
        assertEquals("", client.reassembleHex(response, "4902"))
    }

    // --- parseHexPayload ---

    @Test
    fun `parseHexPayload longueur impaire rejetee`() {
        // "410C0CE" moins le prefixe "410C" laisse "0CE" : un demi-octet, trame tronquee.
        assertNull(client.parseHexPayload("410C0CE", "410C"))
    }

    @Test
    fun `parseHexPayload longueur paire acceptee`() {
        assertEquals(listOf(0x0C), client.parseHexPayload("410C0C", "410C"))
    }

    @Test
    fun `parseHexPayload prefixe absent renvoie null`() {
        assertNull(client.parseHexPayload("7F0311", "410C"))
    }

    // --- parseUdsResponse ---

    @Test
    fun `parseUdsResponse reponse positive renvoie les octets de donnees`() {
        val result = client.parseUdsResponse("62114E01A3", 0x114E)
        assertEquals(UdsDidResult.Positive(listOf(0x01, 0xA3)), result)
    }

    @Test
    fun `parseUdsResponse reponse positive sans donnees mais DID reconnu`() {
        // Echo exact du DID, aucune donnee derriere : distinct d'une non-reponse.
        val result = client.parseUdsResponse("62114E", 0x114E)
        assertEquals(UdsDidResult.Positive(emptyList()), result)
    }

    @Test
    fun `parseUdsResponse reponse negative extrait le NRC`() {
        // Exemple reel de capture : 22114E rejete avec NRC 0x31 (hors plage).
        val result = client.parseUdsResponse("7F2231", 0x114E)
        assertEquals(UdsDidResult.Negative(0x31), result)
    }

    @Test
    fun `parseUdsResponse DID different de celui demande n'est pas confondu avec une reponse positive`() {
        // Echo d'un autre DID (ex: mauvaise ligne retenue par reassembleHex) : pas positif.
        assertEquals(UdsDidResult.NoResponse, client.parseUdsResponse("62114F01A3", 0x114E))
    }

    @Test
    fun `parseUdsResponse chaine vide (timeout ou reponse non hexadecimale) est une non-reponse`() {
        assertEquals(UdsDidResult.NoResponse, client.parseUdsResponse("", 0x114E))
    }

    @Test
    fun `parseUdsResponse longueur de donnees impaire est une non-reponse`() {
        assertEquals(UdsDidResult.NoResponse, client.parseUdsResponse("62114EA", 0x114E))
    }

    // --- nrcDescription ---

    @Test
    fun `nrcDescription code connu`() {
        assertEquals("hors plage (identifiant probablement inexistant sur cet ECU)", nrcDescription(0x31))
    }

    @Test
    fun `nrcDescription code inconnu retombe sur l'hexadecimal brut`() {
        assertEquals("code 0x99", nrcDescription(0x99))
    }
}
