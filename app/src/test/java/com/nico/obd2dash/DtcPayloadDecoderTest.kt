package com.nico.obd2dash

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Contrats communs du chemin connecté et du rejeu CAN hors ligne. */
class DtcPayloadDecoderTest {
    @Test
    fun `un slot nul annonce comme code CAN rend la liste incomplete`() {
        for (prefix in listOf("43", "47")) {
            for (data in listOf("010000", "0200870000", "0200000087")) {
                for (exact in listOf(false, true)) {
                    assertThrows("$prefix$data, exact=$exact", IOException::class.java) {
                        DtcPayloadDecoder.parse(prefix + data, prefix, isCan = true, exactCanPayload = exact)
                    }
                }
            }
        }
    }

    @Test
    fun `compteur CAN nul confirme zero sans chercher de code dans le padding`() {
        assertEquals(emptyList<String>(), DtcPayloadDecoder.parse("4300", "43", isCan = true))
        assertEquals(emptyList<String>(), DtcPayloadDecoder.parse("430000AAAA", "43", isCan = true))
        assertEquals(emptyList<String>(), DtcPayloadDecoder.parse("4700", "47", isCan = true, exactCanPayload = true))
    }

    @Test
    fun `un code valide suivi de padding CAN reste lisible en connexion`() {
        assertEquals(listOf("P0087"), DtcPayloadDecoder.parse("430100870000AA", "43", isCan = true))
        assertEquals(listOf("P0087"), DtcPayloadDecoder.parse("43010087", "43", isCan = true, exactCanPayload = true))
        assertThrows(IOException::class.java) {
            DtcPayloadDecoder.parse("430100870000", "43", isCan = true, exactCanPayload = true)
        }
    }

    @Test
    fun `service seul non CAN ne confirme jamais zero codes`() {
        for (prefix in listOf("43", "47")) {
            assertThrows(IOException::class.java) { DtcPayloadDecoder.parse(prefix, prefix, isCan = false) }
            assertThrows(IOException::class.java) { DtcPayloadDecoder.parse(prefix + "00", prefix, isCan = false) }
        }
    }

    @Test
    fun `paires non CAN nulles sont un remplissage confirme et non un service vide`() {
        assertEquals(emptyList<String>(), DtcPayloadDecoder.parse("430000", "43", isCan = false))
        assertEquals(emptyList<String>(), DtcPayloadDecoder.parse("43000000000000", "43", isCan = false))
        assertEquals(listOf("P0133"), DtcPayloadDecoder.parse("43013300000000", "43", isCan = false))
    }
}
