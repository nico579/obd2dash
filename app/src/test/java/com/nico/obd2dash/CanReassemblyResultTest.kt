package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contrat du resultat structure ; aucun acces a une sonde. */
class CanReassemblyResultTest {
    private fun analyze(text: String) = CanHeaderReassembly.analyze(text, CanIdFormat.CAN_11)

    @Test
    fun `une capture sans trame est absente et non invalide`() {
        for (text in listOf("", " \r\n\t", "SEARCHING...\r\r>", "NO DATA", "SEARCHING...\rNO DATA\r>")) {
            val result = analyze(text)
            assertEquals(text, CanCaptureStatus.NO_RESPONSE, result.status)
            assertTrue(result.payloadsByEcu.isEmpty())
            assertTrue(result.failuresByEcu.isEmpty())
            assertNull(result.globalFailure)
        }
    }

    @Test
    fun `une trame valide conserve son calculateur et un statut complet`() {
        val result = analyze("7E80443010087\r\r>")
        assertEquals(CanCaptureStatus.COMPLETE, result.status)
        assertEquals(mapOf(0x7E8 to "43010087"), result.payloadsByEcu)
        assertTrue(result.failuresByEcu.isEmpty())
        assertNull(result.globalFailure)
    }

    @Test
    fun `une trame mal formee identifie le calculateur rejete`() {
        val result = analyze("7E802430G")
        assertEquals(CanCaptureStatus.INVALID, result.status)
        assertTrue(result.payloadsByEcu.isEmpty())
        assertEquals(mapOf(0x7E8 to CanFrameFailure.MALFORMED_FRAME), result.failuresByEcu)
        assertNull(result.globalFailure)
    }

    @Test
    fun `une suite orpheline est une sequence invalide`() {
        val result = analyze("7E8210087")
        assertEquals(CanCaptureStatus.INVALID, result.status)
        assertEquals(mapOf(0x7E8 to CanFrameFailure.INVALID_SEQUENCE), result.failuresByEcu)
    }

    @Test
    fun `un calculateur incomplet ne fait pas disparaitre la reponse valide voisine`() {
        val result = analyze("7E8024300\r7E91008430303000087")
        assertEquals(CanCaptureStatus.PARTIAL, result.status)
        assertEquals(mapOf(0x7E8 to "4300"), result.payloadsByEcu)
        assertEquals(mapOf(0x7E9 to CanFrameFailure.INVALID_SEQUENCE), result.failuresByEcu)
    }

    @Test
    fun `une corruption du meme calculateur invalide aussi sa trame valide`() {
        for (text in listOf("7E8024300\r7E802430G", "7E802430G\r7E8024300")) {
            val result = analyze(text)
            assertEquals(CanCaptureStatus.INVALID, result.status)
            assertTrue(result.payloadsByEcu.isEmpty())
            assertEquals(mapOf(0x7E8 to CanFrameFailure.MALFORMED_FRAME), result.failuresByEcu)
        }
    }

    @Test
    fun `deux messages differents du meme calculateur sont signales`() {
        val result = analyze("7E8024300\r7E80443010087")
        assertEquals(CanCaptureStatus.INVALID, result.status)
        assertTrue(result.payloadsByEcu.isEmpty())
        assertEquals(mapOf(0x7E8 to CanFrameFailure.INVALID_SEQUENCE), result.failuresByEcu)
    }

    @Test
    fun `une erreur globale garde les donnees valides quel que soit son emplacement`() {
        for (text in listOf("CAN ERROR\r7E80443010087", "7E80443010087\rCAN ERROR")) {
            val result = analyze(text)
            assertEquals(CanCaptureStatus.PARTIAL, result.status)
            assertEquals(mapOf(0x7E8 to "43010087"), result.payloadsByEcu)
            assertTrue(result.failuresByEcu.isEmpty())
            assertEquals(CanCaptureGlobalFailure.UNRECOGNIZED_LINE, result.globalFailure)
            // L'ancien contrat reste prudent car il ne peut pas exposer cette reserve.
            assertTrue(CanHeaderReassembly.reassembleByEcu(text).isEmpty())
        }
    }

    @Test
    fun `une erreur globale seule est invalide et ne devient pas une absence`() {
        val result = analyze("UNABLE TO CONNECT\r>")
        assertEquals(CanCaptureStatus.INVALID, result.status)
        assertTrue(result.payloadsByEcu.isEmpty())
        assertEquals(CanCaptureGlobalFailure.UNRECOGNIZED_LINE, result.globalFailure)
    }

    @Test
    fun `NO DATA juxtapose a des trames pose une reserve globale`() {
        for (text in listOf("NO DATA\r7E8024300", "7E8024300\rNO DATA\r>")) {
            val result = analyze(text)
            assertEquals(CanCaptureStatus.PARTIAL, result.status)
            assertEquals(mapOf(0x7E8 to "4300"), result.payloadsByEcu)
            assertEquals(CanCaptureGlobalFailure.UNRECOGNIZED_LINE, result.globalFailure)
        }
    }

    @Test
    fun `une ligne apres le prompt interdit de fusionner deux echanges complets`() {
        for (suffix in listOf("7E9024300", "SEARCHING...", "NO DATA", ">")) {
            val result = analyze("7E8024300\r>\r$suffix")
            assertEquals(suffix, CanCaptureStatus.PARTIAL, result.status)
            assertEquals("4300", result.payloadsByEcu[0x7E8])
            assertEquals(CanCaptureGlobalFailure.UNRECOGNIZED_LINE, result.globalFailure)
        }
        assertEquals(CanCaptureStatus.COMPLETE, analyze("7E8024300\r>\r\n\t").status)
    }

    @Test
    fun `reserve globale et rejet local restent visibles simultanement`() {
        val result = analyze("CAN ERROR\r7E8024300\r7E902430G")
        assertEquals(CanCaptureStatus.PARTIAL, result.status)
        assertEquals(mapOf(0x7E8 to "4300"), result.payloadsByEcu)
        assertEquals(mapOf(0x7E9 to CanFrameFailure.MALFORMED_FRAME), result.failuresByEcu)
        assertEquals(CanCaptureGlobalFailure.UNRECOGNIZED_LINE, result.globalFailure)
    }

    @Test
    fun `le format CAN29 explicite preserve les adresses completes synthetiques`() {
        val result = CanHeaderReassembly.analyze("18DAF110024300\r18DAF1180443010087", CanIdFormat.CAN_29)
        assertEquals(CanCaptureStatus.COMPLETE, result.status)
        assertEquals(mapOf(0x18DAF110 to "4300", 0x18DAF118 to "43010087"), result.payloadsByEcu)
        assertTrue(result.failuresByEcu.isEmpty())
    }

    @Test
    fun `la limite de capture est inclusive et un depassement ne publie rien`() {
        val atLimit = "7E8024300".padEnd(65_536, ' ')
        assertEquals(CanCaptureStatus.COMPLETE, analyze(atLimit).status)
        val result = analyze(atLimit + " ")
        assertEquals(CanCaptureStatus.INVALID, result.status)
        assertEquals(CanCaptureGlobalFailure.INPUT_TOO_LARGE, result.globalFailure)
        assertTrue(result.payloadsByEcu.isEmpty())
        assertTrue(result.failuresByEcu.isEmpty())
    }
}
