package com.nico.obd2dash

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Vérifie que les parseurs appelés par le client propagent l'échange incomplet. */
class DiagnosticResponseIntegrityTest {
    private val client = Elm327Client("127.0.0.1")

    @Test
    fun `slot CAN incoherent voisin d un ECU sans code ne certifie pas une liste`() {
        for (incomplete in listOf("43010000", "430200870000")) {
            for (response in listOf("4300\r$incomplete", "$incomplete\r4300")) {
                assertThrows(IOException::class.java) { client.parseDtcResponses(response, "43", isCan = true) }
            }
        }
    }

    @Test
    fun `erreur ELM empeche de publier aucun DTC et MIL eteint`() {
        for (failure in listOf("BUFFER FULL", "CAN ERROR", "DATA ERROR", "STOPPED", "NO DATA")) {
            for (response in listOf("4300\r$failure", "$failure\r4300", "4300\r$failure\r43010087")) {
                assertThrows(response, IOException::class.java) { client.parseDtcResponses(response, "43", isCan = true) }
            }
            assertThrows(IOException::class.java) { client.parseMilStatus("410100000000\r$failure") }
        }
    }

    @Test
    fun `erreur ELM ne laisse pas survivre des mesures groupees partielles`() {
        assertThrows(IOException::class.java) {
            client.parseMultiPidResponse("410C0BB80D28\rBUFFER FULL", setOf(0x0C, 0x0D))
        }
    }

    @Test
    fun `statuts informatifs et padding valides conservent les lectures completes`() {
        assertEquals(emptyList<String>(), client.parseDtcResponses("03\rSEARCHING...\r4300", "43", isCan = true))
        assertEquals(listOf("P0087"), client.parseDtcResponses("430100870000", "43", isCan = true))
        assertEquals(false to 0, client.parseMilStatus("0101\rSEARCHING...\r410100000000"))
    }
}
