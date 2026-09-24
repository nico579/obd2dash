package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Une capture reelle CAN11 ; tous les scenarios multi-ECU et CAN29 sont synthetiques. */
class CanDtcCaptureAnalysisTest {
    private fun stored(text: String) = CanDtcCaptureAnalysis.analyze(text, CanIdFormat.CAN_11, CanDtcMode.STORED)

    @Test
    fun `la capture reelle 03 de la SEAT donne P0087 attribue a 7E8`() {
        // captures/session_2026-09-10_12-49-29/exchanges.jsonl, lignes 23 et 177.
        val result = stored("7E80443010087\r\r>")
        assertEquals(CanCaptureStatus.COMPLETE, result.status)
        assertEquals(mapOf(0x7E8 to listOf("P0087")), result.codesByEcu)
        assertEquals(listOf("P0087"), result.uniqueCodes)
        assertTrue(result.failuresByEcu.isEmpty())
        assertNull(result.globalFailure)
        assertFalse(result.noCodesInCompleteCapture)
    }

    @Test
    fun `deux reponses positives nulles confirment seulement les reponses de la capture`() {
        val result = stored("7E9024300\r7E8024300")
        assertEquals(CanCaptureStatus.COMPLETE, result.status)
        assertEquals(mapOf(0x7E9 to emptyList<String>(), 0x7E8 to emptyList()), result.codesByEcu)
        assertTrue(result.noCodesInCompleteCapture)
    }

    @Test
    fun `une premiere reponse nulle ne masque pas les defauts du second calculateur`() {
        val result = stored("7E8024300\r7E90443010087")
        assertEquals(CanCaptureStatus.COMPLETE, result.status)
        assertEquals(emptyList<String>(), result.codesByEcu.getValue(0x7E8))
        assertEquals(listOf("P0087"), result.codesByEcu.getValue(0x7E9))
        assertFalse(result.noCodesInCompleteCapture)
    }

    @Test
    fun `les codes restent attribues avec dedoublonnage par ECU et union triee`() {
        // 7E8 annonce trois codes dont P0300 repete ; 7E9 partage P0087 et ajoute P0100.
        val result = stored("7E81008430303000087\r7E906430200870100\r7E8210300")
        assertEquals(CanCaptureStatus.COMPLETE, result.status)
        assertEquals(listOf("P0300", "P0087"), result.codesByEcu.getValue(0x7E8))
        assertEquals(listOf("P0087", "P0100"), result.codesByEcu.getValue(0x7E9))
        assertEquals(listOf("P0087", "P0100", "P0300"), result.uniqueCodes)
        assertFalse(result.noCodesInCompleteCapture)
    }

    @Test
    fun `le mode en attente utilise son propre service positif`() {
        val result = CanDtcCaptureAnalysis.analyze("7E80447010087", CanIdFormat.CAN_11, CanDtcMode.PENDING)
        assertEquals(CanCaptureStatus.COMPLETE, result.status)
        assertEquals(listOf("P0087"), result.codesByEcu.getValue(0x7E8))
        assertEquals(CanDtcFailureReason.UNEXPECTED_SERVICE, stored("7E80447010087").failuresByEcu.getValue(0x7E8).reason)
    }

    @Test
    fun `un NRC exact conserve son code et empeche un diagnostic nul complet`() {
        for ((suffix, nrc) in listOf("11" to 0x11, "21" to 0x21, "78" to 0x78)) {
            val result = stored("7E8024300\r7E9037F03$suffix")
            assertEquals(CanCaptureStatus.PARTIAL, result.status)
            assertEquals(mapOf(0x7E8 to emptyList<String>()), result.codesByEcu)
            assertEquals(CanDtcFailure(CanDtcFailureReason.NEGATIVE_RESPONSE, nrc), result.failuresByEcu.getValue(0x7E9))
            assertFalse(result.noCodesInCompleteCapture)
            assertEquals(CanCaptureStatus.INVALID, stored("7E9037F03$suffix").status)
        }
    }

    @Test
    fun `le NRC doit correspondre exactement au mode demande`() {
        val stored = stored("7E8037F0711")
        assertEquals(CanDtcFailure(CanDtcFailureReason.UNEXPECTED_SERVICE), stored.failuresByEcu.getValue(0x7E8))
        val pending = CanDtcCaptureAnalysis.analyze("7E8037F0711", CanIdFormat.CAN_11, CanDtcMode.PENDING)
        assertEquals(CanDtcFailure(CanDtcFailureReason.NEGATIVE_RESPONSE, 0x11), pending.failuresByEcu.getValue(0x7E8))
        assertFalse(stored.noCodesInCompleteCapture)
        assertFalse(pending.noCodesInCompleteCapture)
    }

    @Test
    fun `un NRC trop court ou trop long ne devient pas un refus valide`() {
        for (text in listOf("7E8027F03", "7E8047F031100")) {
            val result = stored(text)
            assertEquals(CanCaptureStatus.INVALID, result.status)
            assertEquals(CanDtcFailure(CanDtcFailureReason.MALFORMED_PAYLOAD), result.failuresByEcu.getValue(0x7E8))
            assertFalse(result.noCodesInCompleteCapture)
        }
    }

    @Test
    fun `une autre reponse positive ne permet pas de conclure zero pour le mode demande`() {
        val result = stored("7E8024300\r7E9024700")
        assertEquals(CanCaptureStatus.PARTIAL, result.status)
        assertEquals(CanDtcFailureReason.UNEXPECTED_SERVICE, result.failuresByEcu.getValue(0x7E9).reason)
        assertFalse(result.noCodesInCompleteCapture)
    }

    @Test
    fun `les compteurs absents incomplets ou contradictoires sont des erreurs de payload`() {
        for (text in listOf("7E80143", "7E80443020087", "7E803430000", "7E80443010000")) {
            val result = stored(text)
            assertEquals(text, CanCaptureStatus.INVALID, result.status)
            assertTrue(result.codesByEcu.isEmpty())
            assertEquals(CanDtcFailureReason.MALFORMED_PAYLOAD, result.failuresByEcu.getValue(0x7E8).reason)
            assertFalse(result.noCodesInCompleteCapture)
        }
    }

    @Test
    fun `un compteur incoherent voisin rend la capture partielle meme avec un zero valide`() {
        val result = stored("7E8024300\r7E90443010000")
        assertEquals(CanCaptureStatus.PARTIAL, result.status)
        assertEquals(mapOf(0x7E8 to emptyList<String>()), result.codesByEcu)
        assertEquals(CanDtcFailureReason.MALFORMED_PAYLOAD, result.failuresByEcu.getValue(0x7E9).reason)
        assertFalse(result.noCodesInCompleteCapture)
    }

    @Test
    fun `le padding CAN hors longueur ISO TP est accepte sans masquer un surplus utile`() {
        assertEquals(listOf("P0087"), stored("7E80443010087AAAAAA").uniqueCodes)
        val result = stored("7E80543010087AA")
        assertEquals(CanCaptureStatus.INVALID, result.status)
        assertEquals(CanDtcFailureReason.MALFORMED_PAYLOAD, result.failuresByEcu.getValue(0x7E8).reason)
    }

    @Test
    fun `un octet utile de trop en multi trame est rejete apres reassemblage`() {
        val result = stored("7E81009430303000087\r7E8210300AA")
        assertEquals(CanCaptureStatus.INVALID, result.status)
        assertEquals(CanDtcFailureReason.MALFORMED_PAYLOAD, result.failuresByEcu.getValue(0x7E8).reason)
        assertTrue(result.codesByEcu.isEmpty())
    }

    @Test
    fun `une trame corrompue voisine empeche un verdict nul`() {
        val result = stored("7E8024300\r7E902430G")
        assertEquals(CanCaptureStatus.PARTIAL, result.status)
        assertEquals(mapOf(0x7E8 to emptyList<String>()), result.codesByEcu)
        assertEquals(CanDtcFailureReason.MALFORMED_FRAME, result.failuresByEcu.getValue(0x7E9).reason)
        assertFalse(result.noCodesInCompleteCapture)
    }

    @Test
    fun `une sequence voisine tronquee empeche un verdict nul`() {
        val result = stored("7E8024300\r7E91008430303000087")
        assertEquals(CanCaptureStatus.PARTIAL, result.status)
        assertEquals(CanDtcFailureReason.INVALID_SEQUENCE, result.failuresByEcu.getValue(0x7E9).reason)
        assertFalse(result.noCodesInCompleteCapture)
    }

    @Test
    fun `NRC provisoire puis positif du meme ECU reste une succession non prise en charge`() {
        val result = stored("7E8037F0378\r7E8024300")
        assertEquals(CanCaptureStatus.INVALID, result.status)
        assertEquals(CanDtcFailureReason.INVALID_SEQUENCE, result.failuresByEcu.getValue(0x7E8).reason)
        assertTrue(result.codesByEcu.isEmpty())
        assertFalse(result.noCodesInCompleteCapture)
    }

    @Test
    fun `une erreur globale conserve les codes avec une reserve sur la capture`() {
        val result = stored("7E80443010087\rCAN ERROR")
        assertEquals(CanCaptureStatus.PARTIAL, result.status)
        assertEquals(listOf("P0087"), result.uniqueCodes)
        assertEquals(CanCaptureGlobalFailure.UNRECOGNIZED_LINE, result.globalFailure)
        assertFalse(result.noCodesInCompleteCapture)
        assertFalse(stored("CAN ERROR\r7E8024300").noCodesInCompleteCapture)
    }

    @Test
    fun `une absence et une capture invalide ne confirment jamais zero`() {
        val absent = stored("\rSEARCHING...\r>")
        val invalid = stored("CAN ERROR\r>")
        assertEquals(CanCaptureStatus.NO_RESPONSE, absent.status)
        assertEquals(CanCaptureStatus.INVALID, invalid.status)
        assertFalse(absent.noCodesInCompleteCapture)
        assertFalse(invalid.noCodesInCompleteCapture)
        assertTrue(absent.uniqueCodes.isEmpty())
        assertTrue(invalid.uniqueCodes.isEmpty())
    }

    @Test
    fun `NO DATA seul est absent mais juxtapose a zero reste partiel`() {
        val absent = stored("SEARCHING...\rNO DATA\r>")
        assertEquals(CanCaptureStatus.NO_RESPONSE, absent.status)
        assertFalse(absent.noCodesInCompleteCapture)
        val partial = stored("NO DATA\r7E8024300\r>")
        assertEquals(CanCaptureStatus.PARTIAL, partial.status)
        assertEquals(CanCaptureGlobalFailure.UNRECOGNIZED_LINE, partial.globalFailure)
        assertFalse(partial.noCodesInCompleteCapture)
    }

    @Test
    fun `des reponses nulles de deux echanges colles ne confirment pas une capture complete`() {
        val result = stored("7E8024300\r>\r7E9024300\r>")
        assertEquals(CanCaptureStatus.PARTIAL, result.status)
        assertEquals(CanCaptureGlobalFailure.UNRECOGNIZED_LINE, result.globalFailure)
        assertFalse(result.noCodesInCompleteCapture)
    }

    @Test
    fun `les calculateurs CAN29 synthetiques gardent leurs identites completes`() {
        val result = CanDtcCaptureAnalysis.analyze(
            "18DAF1100443010087\r18DAF118024300", CanIdFormat.CAN_29, CanDtcMode.STORED
        )
        assertEquals(CanCaptureStatus.COMPLETE, result.status)
        assertEquals(mapOf(0x18DAF110 to listOf("P0087"), 0x18DAF118 to emptyList()), result.codesByEcu)
        assertFalse(result.noCodesInCompleteCapture)
    }

    @Test
    fun `un depassement de taille ne publie pas un debut de capture valide`() {
        val result = stored("7E8024300".padEnd(65_537, ' '))
        assertEquals(CanCaptureStatus.INVALID, result.status)
        assertEquals(CanCaptureGlobalFailure.INPUT_TOO_LARGE, result.globalFailure)
        assertTrue(result.codesByEcu.isEmpty())
        assertFalse(result.noCodesInCompleteCapture)
    }
}
