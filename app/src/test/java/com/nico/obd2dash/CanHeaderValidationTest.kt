package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Scénarios synthétiques de validation ; aucun échange avec une sonde. */
class CanHeaderValidationTest {
    @Test
    fun `CAN11 accepte les bornes de son identifiant`() {
        assertEquals(0, CanHeaderReassembly.parseCanFrame("00001AA")?.ecuId)
        assertEquals(0x7FF, CanHeaderReassembly.parseCanFrame("7FF01AA")?.ecuId)
        assertInvalidFrames("80001AA", "FFF01AA")
    }

    @Test
    fun `identifiant et donnees doivent etre des chiffres hexadecimaux ASCII`() {
        assertInvalidFrames(
            "+E801AA", "-E801AA", "７E801AA", "7E80١AA",
            "7E801ＡA", "7E80341GG00", "7E801AAZZ"
        )
    }

    @Test
    fun `les donnees sont normalisees en majuscules`() {
        assertEquals(
            CanHeaderReassembly.Frame(0x7E8, "AAFF", null),
            CanHeaderReassembly.parseCanFrame("7e802aAff")
        )
    }

    @Test
    fun `octet incomplet et octet PCI seul sont rejetes`() {
        assertInvalidFrames("7E8", "7E80", "7E801", "7E801A", "7E821")
    }

    @Test
    fun `SF doit annoncer de un a sept octets presents`() {
        assertInvalidFrames("7E800AA", "7E802AA", "7E80811223344556677", "7E80FAA")
        assertEquals("AA", CanHeaderReassembly.parseCanFrame("7E801AA")?.data)
        assertEquals("11223344556677", CanHeaderReassembly.parseCanFrame("7E80711223344556677")?.data)
    }

    @Test
    fun `SF retire uniquement le remplissage valide`() {
        assertEquals("AA", CanHeaderReassembly.parseCanFrame("7E801AA001122334455")?.data)
        assertInvalidFrames("7E801AA00112233445Z")
    }

    @Test
    fun `CAN classique refuse plus de huit octets PCI compris`() {
        assertInvalidFrames(
            "7E801AA00112233445566",
            "7E8100811223344556677",
            "7E8211122334455667788"
        )
    }

    @Test
    fun `le format avec DLC ou espaces internes est refuse`() {
        assertInvalidFrames("7E88064100BE1FA813", "7E8 06 41 00 BE 1F A8 13")
    }

    @Test
    fun `PCI non pris en charge ne produit pas de donnees`() {
        assertInvalidFrames("7E83000000000000000", "7E8400", "7E8F0AA")
    }

    @Test
    fun `FF exige six octets et une longueur totale de huit a 4095`() {
        assertEquals(8, CanHeaderReassembly.parseCanFrame("7E81008112233445566")?.totalLength)
        assertEquals(4095, CanHeaderReassembly.parseCanFrame("7E81FFF112233445566")?.totalLength)
        assertInvalidFrames(
            "7E81000112233445566", "7E81007112233445566",
            "7E810081122334455", "7E8100811223344556677"
        )
    }

    @Test
    fun `une seule CF courte suffit a terminer le message`() {
        assertPayload("1122334455667788", FF_EIGHT, "7E8217788")
    }

    @Test
    fun `derniere CF pleine conserve les donnees et retire son remplissage`() {
        assertPayload("1122334455667700", FF_EIGHT, "7E8217700AAAAAAAAAA")
    }

    @Test
    fun `CF courte avant la derniere est rejetee meme si la longueur totale est atteinte`() {
        // FF(6) + CF1(2) + CF2(7) = 15 ; la longueur seule ne valide pas CF1.
        assertRejected("7E8100F112233445566", "7E8217788", "7E82299AABBCCDDEEFF")
    }

    @Test
    fun `derniere CF insuffisante et absence de CF sont rejetees`() {
        assertRejected(FF_EIGHT, "7E82177")
        assertRejected(FF_EIGHT)
    }

    @Test
    fun `longueur maximale 4095 accepte plusieurs bouclages puis une derniere CF courte`() {
        val lines = mutableListOf("7E81FFF" + "11".repeat(6))
        for (index in 1..584) {
            val sequence = (index % 16).toString(16).uppercase()
            lines.add("7E82$sequence" + "AA".repeat(7))
        }
        // 6 + 584 * 7 + 1 = 4095 ; la 585e CF porte le numéro 9.
        lines.add("7E829BC")
        assertPayload("11".repeat(6) + "AA".repeat(4088) + "BC", *lines.toTypedArray())
    }

    @Test
    fun `CF sans FF ou placee avant la FF est rejetee`() {
        assertRejected("7E8217788")
        assertRejected("7E8217788", FF_EIGHT)
    }

    @Test
    fun `une deuxieme FF identique ou differente invalide le message`() {
        assertRejected(FF_EIGHT, FF_EIGHT, "7E8217788")
        assertRejected(FF_EIGHT, "7E81008AABBCCDDEEFF", "7E8217788")
    }

    @Test
    fun `premiere CF zero ou saut de numero sont rejetes`() {
        assertRejected(FF_EIGHT, "7E8207788")
        assertRejected(FF_EIGHT, "7E8227788")
        assertRejected("7E8100F112233445566", "7E821778899AABBCCDD", "7E823EEFF")
    }

    @Test
    fun `repetition de numero CF invalide le message`() {
        assertRejected("7E81014112233445566", "7E821778899AABBCCDD", "7E821EEFF0011223344")
    }

    @Test
    fun `aucune CF ne peut suivre la completion`() {
        assertRejected(FF_EIGHT, "7E8217788", "7E82299")
    }

    @Test
    fun `SF et FF du meme calculateur sont incompatibles dans les deux ordres`() {
        assertRejected(SINGLE, FF_EIGHT, "7E8217788")
        assertRejected(FF_EIGHT, "7E8217788", SINGLE)
        assertRejected(FF_EIGHT, SINGLE, "7E8217788")
    }

    @Test
    fun `SF identiques restent equivalentes malgre la casse et le remplissage`() {
        assertPayload("410C00", "7E803410C00", "7e803410c00aabbccdd", "7E803410C0000000000")
    }

    @Test
    fun `deux SF contradictoires du meme calculateur sont rejetees`() {
        assertRejected("7E803410C00", "7E803410CFF")
    }

    @Test
    fun `reponse provisoire puis positive ne devient pas une reponse unique`() {
        assertRejected("7E8037F0178", "7E806410000000000")
        assertRejected("7E8037F0978", "7E81008490201414243", "7E8214445")
    }

    @Test
    fun `trame malformee invalide son calculateur avant ou apres une SF valide`() {
        for (bad in listOf("7E80341GG00", "7E803410C0", "7E801AAZZ")) {
            for (lines in listOf(listOf(bad, SINGLE), listOf(SINGLE, bad))) {
                assertEquals(
                    bad,
                    mapOf(0x7E9 to "410D28"),
                    CanHeaderReassembly.reassembleByEcu((lines + "7E903410D28").joinToString("\r"))
                )
            }
        }
    }

    @Test
    fun `trame malformee apres une sequence complete invalide seulement son calculateur`() {
        val response = listOf(FF_EIGHT, "7E8217788", "7E82Z99", "7E903410D28").joinToString("\r")
        assertEquals(mapOf(0x7E9 to "410D28"), CanHeaderReassembly.reassembleByEcu(response))
    }

    @Test
    fun `un calculateur avec une sequence incoherente ne masque pas une SF saine`() {
        val response = "$FF_EIGHT\r7E8227788\r7E903410D28"
        assertEquals(mapOf(0x7E9 to "410D28"), CanHeaderReassembly.reassembleByEcu(response))
    }

    @Test
    fun `ligne non attribuable ou erreur adaptateur invalide lechange`() {
        for (unknown in listOf("CAN ERROR", "BUFFER FULL", "garbage", "ZZZ01AA", "80001AA")) {
            assertEquals(unknown, emptyMap<Int, String>(), CanHeaderReassembly.reassembleByEcu("$SINGLE\r$unknown"))
        }
    }

    @Test
    fun `seuls espaces exterieurs lignes vides recherche et prompt sont toleres`() {
        val response = "\r\n SEARCHING... \r\n  $SINGLE  \r\n\r\n>\r"
        assertEquals(mapOf(0x7E8 to "410D28"), CanHeaderReassembly.reassembleByEcu(response))
    }

    @Test
    fun `reponse depassant la borne est rejetee sans troncature`() {
        val atLimit = SINGLE + "\r".repeat(65_536 - SINGLE.length)
        assertEquals(mapOf(0x7E8 to "410D28"), CanHeaderReassembly.reassembleByEcu(atLimit))
        assertEquals(emptyMap<Int, String>(), CanHeaderReassembly.reassembleByEcu("$atLimit\r"))
    }

    @Test
    fun `CAN29 doit etre demande explicitement et ne detecte pas CAN11`() {
        val response29 = "18DAF11003410D28"
        assertNull(CanHeaderReassembly.parseCanFrame(response29))
        assertEquals(emptyMap<Int, String>(), CanHeaderReassembly.reassembleByEcu(response29))
        assertEquals(
            CanHeaderReassembly.Frame(0x18DAF110, "410D28", null),
            CanHeaderReassembly.parseCanFrame(response29, CanIdFormat.CAN_29)
        )
        assertNull(CanHeaderReassembly.parseCanFrame(SINGLE, CanIdFormat.CAN_29))
    }

    @Test
    fun `CAN29 accepte ses bornes et refuse les identifiants hors plage`() {
        assertEquals(0, CanHeaderReassembly.parseCanFrame("0000000001AA", CanIdFormat.CAN_29)?.ecuId)
        assertEquals(0x1FFFFFFF, CanHeaderReassembly.parseCanFrame("1FFFFFFF01AA", CanIdFormat.CAN_29)?.ecuId)
        for (line in listOf("2000000001AA", "7FFFFFFF01AA", "FFFFFFFF01AA", "+8DAF11001AA", "１８DAF11001AA")) {
            assertNull(line, CanHeaderReassembly.parseCanFrame(line, CanIdFormat.CAN_29))
        }
    }

    @Test
    fun `deux calculateurs CAN29 entrelaces sont reassembles separement`() {
        val response = listOf(
            "18DAF1101008112233445566", "18DAF111100FAABBCCDDEEFF",
            "18DAF1112100112233445566", "18DAF110217788", "18DAF111227788"
        ).joinToString("\r")
        assertEquals(
            mapOf(0x18DAF110 to "1122334455667788", 0x18DAF111 to "AABBCCDDEEFF001122334455667788"),
            CanHeaderReassembly.reassembleByEcu(response, CanIdFormat.CAN_29)
        )
    }

    @Test
    fun `CAN29 trame malformee ne supprime pas lautre calculateur`() {
        val response = "18DAF11003410D28\r18DAF11103410D32\r18DAF11003GGGGGG"
        assertEquals(
            mapOf(0x18DAF111 to "410D32"),
            CanHeaderReassembly.reassembleByEcu(response, CanIdFormat.CAN_29)
        )
    }

    private fun assertInvalidFrames(vararg lines: String) {
        for (line in lines) assertNull(line, CanHeaderReassembly.parseCanFrame(line))
    }

    private fun assertRejected(vararg lines: String) {
        assertEquals(emptyMap<Int, String>(), CanHeaderReassembly.reassembleByEcu(lines.joinToString("\r")))
    }

    private fun assertPayload(expected: String, vararg lines: String) {
        assertEquals(mapOf(0x7E8 to expected), CanHeaderReassembly.reassembleByEcu(lines.joinToString("\r")))
    }

    private companion object {
        const val FF_EIGHT = "7E81008112233445566"
        const val SINGLE = "7E803410D28"
    }
}
