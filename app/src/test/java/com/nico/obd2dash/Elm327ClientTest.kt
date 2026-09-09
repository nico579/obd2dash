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

    // --- parseDtcResponse : capture reelle de l'audit, avant le fix lisait "P0100" ---

    @Test
    fun `parseDtcResponse un seul DTC prend en compte l'octet compteur`() {
        assertEquals(listOf("P0087"), client.parseDtcResponse("43010087", "43"))
    }

    @Test
    fun `parseDtcResponse deux DTC`() {
        assertEquals(listOf("P0100", "P0300"), client.parseDtcResponse("430201000300", "43"))
    }

    @Test
    fun `parseDtcResponse zero DTC confirme`() {
        assertEquals(emptyList<String>(), client.parseDtcResponse("4300", "43"))
    }

    @Test
    fun `parseDtcResponse trop courte pour contenir un compteur leve une erreur`() {
        assertThrows(IOException::class.java) { client.parseDtcResponse("43", "43") }
    }

    @Test
    fun `parseDtcResponse compteur promet plus de codes que la trame n'en contient`() {
        // Compteur = 2 mais une seule paire de donnees derriere : trame tronquee.
        assertThrows(IOException::class.java) { client.parseDtcResponse("43020100", "43") }
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
}
