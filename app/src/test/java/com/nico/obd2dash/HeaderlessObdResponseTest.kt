package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cas synthétiques ATH0 : aucun accès à une sonde ni attribution supposée à un ECU. */
class HeaderlessObdResponseTest {

    @Test
    fun `deux statuts MIL differents ne confirment pas un voyant eteint`() {
        for (response in listOf(
            "410100000000\r410181000000",
            "410181000000\r410100000000"
        )) {
            assertEquals("", HeaderlessObdResponse.parse(response).select("4101"))
        }
    }

    @Test
    fun `zero DTC voisin de P0087 ne devient pas un diagnostic sans defaut`() {
        for (response in listOf("4300\r43010087", "43010087\r4300")) {
            assertEquals("", HeaderlessObdResponse.parse(response).select("43"))
        }
    }

    @Test
    fun `deux regimes differents ne sont pas departages par leur ordre`() {
        assertEquals("", HeaderlessObdResponse.parse("410C1AF8\r410C2EE0").select("410C"))
    }

    @Test
    fun `des doublons identiques restent lisibles apres normalisation`() {
        val result = HeaderlessObdResponse.parse("41 0c 1a f8\r\n\t410C1AF8\r410c1af8")
        assertTrue(result.isComplete)
        assertEquals("410C1AF8", result.select("410c"))
    }

    @Test
    fun `un autre PID ou numero de freeze frame ne cree pas une collision`() {
        val result = HeaderlessObdResponse.parse("410C1AF8\r420C011AF8\r420C002EE0")
        assertEquals("410C1AF8", result.select("410C"))
        assertEquals("420C002EE0", result.select("420C00"))
    }

    @Test
    fun `un NRC UDS correle est conserve apres un echo residuel`() {
        val result = HeaderlessObdResponse.parse("22114E\r7f 22 31")
        assertEquals("7F2231", result.select("62114E"))
    }

    @Test
    fun `des NRC identiques peuvent etre dedupliques mais pas des refus differents`() {
        assertEquals("7F2231", HeaderlessObdResponse.parse("7F2231\r7F2231").select("62114E"))
        assertEquals("", HeaderlessObdResponse.parse("7F2231\r7F2211").select("62114E"))
    }

    @Test
    fun `une reponse positive et un refus du meme service restent indetermines`() {
        for ((response, prefix) in listOf(
            "410100000000\r7F0112" to "4101",
            "4300\r7F0311" to "43",
            "62114E01\r7F2231" to "62114E"
        )) {
            assertEquals("", HeaderlessObdResponse.parse(response).select(prefix))
        }
    }

    @Test
    fun `un NRC a un autre service ne masque pas la reponse attendue`() {
        assertEquals("62114E01", HeaderlessObdResponse.parse("7F0112\r62114E01").select("62114E"))
    }

    @Test
    fun `les lignes simples avant entre et apres les trames sont conservees`() {
        val result = HeaderlessObdResponse.parse(
            "410C1AF8\r0:62114E010203\r4300\r1:04050607\r410D00"
        )
        assertTrue(result.isComplete)
        assertEquals("410C1AF8", result.select("410C"))
        assertEquals("4300", result.select("43"))
        assertEquals("410D00", result.select("410D"))
        assertEquals("62114E01020304050607", result.select("62114E"))
    }

    @Test
    fun `une ligne simple hexadecimale ne devient pas une longueur de sequence`() {
        val result = HeaderlessObdResponse.parse("410C1AF8\r0:62114E01\r1:02")
        assertTrue(result.isComplete)
        assertEquals("410C1AF8", result.select("410C"))
        assertEquals("62114E0102", result.select("62114E"))
    }

    @Test
    fun `un DTC simple et un DTC numerote differents ne masquent pas le defaut`() {
        val result = HeaderlessObdResponse.parse("4300\r004\r0:43010087")
        assertTrue(result.isComplete)
        assertEquals("", result.select("43"))
        assertEquals(setOf("4300", "43010087"), result.payloads.toSet())
    }

    @Test
    fun `la longueur annoncee survit aux statuts et retire le remplissage`() {
        val result = HeaderlessObdResponse.parse(
            "00A\rSEARCHING...\r\n0:62 11 4E 01 02 03\r1:04 05 06 07 AA"
        )
        assertTrue(result.isComplete)
        assertEquals("62114E01020304050607", result.select("62114E"))
    }

    @Test
    fun `une sequence tronquee invalide aussi un resultat simple voisin`() {
        val result = HeaderlessObdResponse.parse("4300\r00A\r0:62114E010203")
        assertFalse(result.isComplete)
        assertEquals("", result.select("62114E"))
        assertEquals("", result.select("43"))
    }

    @Test
    fun `une longueur sans trames n est pas une reponse utile`() {
        val result = HeaderlessObdResponse.parse("4300\r00A")
        assertFalse(result.isComplete)
        assertEquals("", result.select("43"))
    }

    @Test
    fun `longueur nulle double ou apparue apres le debut invalide la sequence`() {
        for (response in listOf(
            "000\r0:4300",
            "002\r002\r0:4300",
            "0:4300\r002"
        )) {
            assertFalse(response, HeaderlessObdResponse.parse(response).isComplete)
        }
    }

    @Test
    fun `un bitmap malforme voisin reste visible et ne confirme pas zero PID`() {
        for (malformed in listOf("410000", "4100000", "4100ZZ000000")) {
            val result = HeaderlessObdResponse.parse("410000000000\r$malformed")
            assertTrue(result.payloads.contains(malformed))
            assertEquals("", result.select("4100"))
        }
    }

    @Test
    fun `un NRC malforme est conserve pour que le decodeur puisse le rejeter`() {
        val result = HeaderlessObdResponse.parse("7F2231GG")
        assertEquals(listOf("7F2231GG"), result.payloads)
    }

    @Test
    fun `un saut de numero ou un doublon invalide toute la reponse`() {
        for (response in listOf(
            "4300\r0:62114E01\r2:0203",
            "4300\r0:62114E01\r0:62114E01",
            "4300\r0:62114E01\r0:62114E02",
            "4300\r1:62114E01"
        )) {
            val result = HeaderlessObdResponse.parse(response)
            assertFalse(response, result.isComplete)
            assertEquals("", result.select("43"))
        }
    }

    @Test
    fun `deux sequences successives ne sont pas concatenees`() {
        val result = HeaderlessObdResponse.parse("0:62114E01\r1:0203\r0:62114E04\r1:0506")
        assertFalse(result.isComplete)
        assertEquals("", result.select("62114E"))
    }

    @Test
    fun `un index sur deux chiffres ne disparait pas comme un statut`() {
        val result = HeaderlessObdResponse.parse("4300\r0:62114E01\r10:0203")
        assertFalse(result.isComplete)
        assertEquals("", result.select("43"))
    }

    @Test
    fun `une ligne de trame avec index vide ou non hexadecimal invalide la sequence`() {
        for (invalidFrame in listOf(":0203", "G:0203")) {
            val result = HeaderlessObdResponse.parse("4300\r0:62114E01\r$invalidFrame")
            assertFalse(invalidFrame, result.isComplete)
            assertEquals("", result.select("43"))
        }
    }

    @Test
    fun `une donnee numerotee vide impaire ou non hexadecimale est rejetee`() {
        for (data in listOf("", "62114E0", "62114EZZ")) {
            val result = HeaderlessObdResponse.parse("4300\r0:$data")
            assertFalse(data, result.isComplete)
            assertEquals("", result.select("43"))
        }
    }

    @Test
    fun `un long message conserve le bouclage de F a zero`() {
        val response = (0..16).joinToString("\r") { index ->
            "${(index % 16).toString(16)}:${index.toString(16).padStart(2, '0')}"
        }
        val expected = (0..16).joinToString("") { it.toString(16).padStart(2, '0') }.uppercase()
        val result = HeaderlessObdResponse.parse(response)
        assertTrue(result.isComplete)
        assertEquals(listOf(expected), result.payloads)
        assertEquals(expected, result.select("00"))
    }

    @Test
    fun `des statuts seuls ne deviennent pas le payload attendu`() {
        val result = HeaderlessObdResponse.parse("SEARCHING...\rNO DATA\rUNABLE TO CONNECT")
        assertEquals("", result.select("4100"))
        assertEquals("", result.select("62114E"))
    }
}
