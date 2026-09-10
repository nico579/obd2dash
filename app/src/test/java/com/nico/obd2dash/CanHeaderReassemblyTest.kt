package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Couvre l'hypothèse de format headers-on (voir CanHeaderReassembly), construite à partir
 * du format documenté par python-OBD, PAS encore vérifiée sur un véhicule réel. Ces cas
 * sont synthétiques (aucune capture réelle disponible) : ils prouvent la mécanique de
 * regroupement par ECU et de suivi de séquence, pas la conformité au véhicule de test.
 */
class CanHeaderReassemblyTest {

    // --- parseCanFrame ---

    @Test
    fun `parseCanFrame trame unique`() {
        val frame = CanHeaderReassembly.parseCanFrame("7E8064100BE1FA813")
        assertEquals(CanHeaderReassembly.Frame(0x7E8, "4100BE1FA813", null), frame)
    }

    @Test
    fun `parseCanFrame premiere trame multi-trame`() {
        // PCI "1008" = premiere trame, longueur totale annoncee 0x008 = 8 octets.
        // sequenceIndex = -1 : sentinel distinct des numeros de sequence reels (0-15).
        val frame = CanHeaderReassembly.parseCanFrame("7E81008112233445566")
        assertEquals(CanHeaderReassembly.Frame(0x7E8, "112233445566", -1, totalLength = 8), frame)
    }

    @Test
    fun `parseCanFrame trame de suite`() {
        val frame = CanHeaderReassembly.parseCanFrame("7E8217788")
        assertEquals(CanHeaderReassembly.Frame(0x7E8, "7788", 1), frame)
    }

    @Test
    fun `parseCanFrame ligne trop courte renvoie null`() {
        assertNull(CanHeaderReassembly.parseCanFrame("7E8"))
    }

    @Test
    fun `parseCanFrame PCI non reconnu renvoie null`() {
        assertNull(CanHeaderReassembly.parseCanFrame("7E83..."))
    }

    // --- reassembleByEcu ---

    @Test
    fun `reassembleByEcu une seule trame unique`() {
        val result = CanHeaderReassembly.reassembleByEcu("7E8064100BE1FA813")
        assertEquals(mapOf(0x7E8 to "4100BE1FA813"), result)
    }

    @Test
    fun `reassembleByEcu deux calculateurs en trame unique - impossible en headers-off`() {
        // Le scenario meme du finding 3 original : deux ECU repondent au meme "0100".
        // Sans headers, reassembleHex() ne pouvait en garder qu'un. Avec l'ID CAN, les
        // deux sont recuperes separement.
        val response = "7E8064100BE1FA813\r7E906410000000001"
        val result = CanHeaderReassembly.reassembleByEcu(response)
        assertEquals(mapOf(0x7E8 to "4100BE1FA813", 0x7E9 to "410000000001"), result)
    }

    @Test
    fun `reassembleByEcu multi-trame un seul calculateur`() {
        // Longueur annoncee 0x00F = 15 octets, exactement FF(6) + CF1(7) + CF2(2).
        val response = "7E8100FAABBCCDDEEFF\r7E82100112233445566\r7E8227788"
        val result = CanHeaderReassembly.reassembleByEcu(response)
        assertEquals(mapOf(0x7E8 to "AABBCCDDEEFF001122334455667788"), result)
    }

    @Test
    fun `reassembleByEcu tronque le remplissage au-dela de la longueur annoncee`() {
        // Longueur annoncee 8 (FF=6 + 2 attendus), mais la CF apporte 3 octets (un de trop,
        // remplissage AA typique d'une derniere trame CAN). Doit etre coupe a 8 exactement.
        val response = "7E81008112233445566\r7E8217788AA"
        val result = CanHeaderReassembly.reassembleByEcu(response)
        assertEquals(mapOf(0x7E8 to "1122334455667788"), result)
    }

    @Test
    fun `reassembleByEcu reponse incomplete par rapport a la longueur annoncee est rejetee`() {
        // Longueur annoncee 20, mais seulement 8 octets reellement recus : trame tronquee,
        // pas juste "moins de remplissage que prevu".
        val response = "7E81014AABBCCDDEEFF\r7E8217788"
        val result = CanHeaderReassembly.reassembleByEcu(response)
        assertEquals(emptyMap<Int, String>(), result)
    }

    @Test
    fun `reassembleByEcu multi-trame de deux calculateurs entrelacees`() {
        // La ou le suivi de sequence global echouerait (deux "premieres trames" puis deux
        // "trames de suite numero 1" entremelees), le regroupement par ECU d'abord separe
        // proprement les deux flux avant de les reassembler chacun independamment.
        val response = "7E81008112233445566\r7E91008AABBCCDDEEFF\r7E8217788\r7E9210011"
        val result = CanHeaderReassembly.reassembleByEcu(response)
        assertEquals(
            mapOf(0x7E8 to "1122334455667788", 0x7E9 to "AABBCCDDEEFF0011"),
            result
        )
    }

    @Test
    fun `reassembleByEcu sequence incomplete est ignoree plutot que fausse`() {
        // Trame de suite numero 2 sans jamais avoir vu la numero 1 : sequence incoherente.
        val response = "7E81014AABBCCDDEEFF\r7E8227788"
        val result = CanHeaderReassembly.reassembleByEcu(response)
        assertEquals(emptyMap<Int, String>(), result)
    }

    @Test
    fun `reassembleByEcu boucle la sequence apres F pour un seul emetteur`() {
        // 17 trames de suite : la 16e boucle de F a 0, comme pour reassembleHex (R4).
        val firstData = "AABBCCDDEEFF" // 6 octets
        val cfCount = 17 // 1 octet chacune : 6 + 17 = 23 octets au total
        val totalBytes = firstData.length / 2 + cfCount
        val header = "7E81" + "%03X".format(totalBytes) // longueur exacte, pas une constante approximative
        val lines = mutableListOf("$header$firstData")
        val expected = StringBuilder(firstData)
        for (k in 1..cfCount) {
            val seq = (k % 16).toString(16).uppercase()
            val data = "%02X".format(k)
            lines.add("7E82$seq$data")
            expected.append(data)
        }
        val result = CanHeaderReassembly.reassembleByEcu(lines.joinToString("\r"))
        assertEquals(mapOf(0x7E8 to expected.toString()), result)
    }
}
