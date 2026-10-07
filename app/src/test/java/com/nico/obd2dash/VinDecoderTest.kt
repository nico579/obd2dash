package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VinDecoderTest {

    @Test
    fun `WMI connu renvoie le constructeur`() {
        val info = VinDecoder.decode("VSSZZZ6JZBR000000")
        assertEquals("SEAT", info.manufacturer)
        assertNull(info.region)
    }

    @Test
    fun `annee-modele du VIN synthetique de test`() {
        // VIN synthétique : conserver la candidate récente 2011, mais le VIN
        // seul ne prouve pas son cycle. Ne pas lui appliquer la règle US du caractère 7.
        val info = VinDecoder.decode("VSSZZZ6JZBR000000", referenceYear = 2026)
        assertEquals(2011, info.modelYear)
        assertEquals(listOf(1981, 2011), info.modelYearCandidates)
        assertTrue(info.modelYearIsEstimate)
    }

    @Test
    fun `WMI inconnu retombe sur la region`() {
        // "JXX" : prefixe Asie (J), pas dans la table des constructeurs connus.
        val info = VinDecoder.decode("JXX00000000000000")
        assertNull(info.manufacturer)
        assertEquals("Asie", info.region)
    }

    @Test
    fun `prefixe hors de toute plage connue ne renvoie ni constructeur ni region`() {
        // "F" et "G" ne sont assignes a aucune region d'apres la reference utilisee.
        val info = VinDecoder.decode("FXX00000000000000")
        assertNull(info.manufacturer)
        assertNull(info.region)
    }

    @Test
    fun `cycle recent impossible est exclu sans effacer le cycle anterieur`() {
        assertEquals(2010, VinDecoder.decode("VSSZZZ6JZAR000000", referenceYear = 2026).modelYear)
        val y = VinDecoder.decode("VSSZZZ6JZYR000000", referenceYear = 2026)
        assertEquals(2000, y.modelYear)
        assertEquals(listOf(2000), y.modelYearCandidates)
        assertTrue(y.modelYearIsEstimate)
    }

    @Test
    fun `chiffres 1 a 9 ne sont plus forces en 2031 a 2039`() {
        for (digit in '1'..'9') {
            val info = VinDecoder.decode("VSSZZZ6JZ${digit}R000000", referenceYear = 2026)
            assertEquals(2000 + digit.digitToInt(), info.modelYear)
            assertEquals(listOf(2000 + digit.digitToInt()), info.modelYearCandidates)
            assertTrue(info.modelYearIsEstimate)
        }
    }

    @Test
    fun `le code 6 ne donne pas 2036 en 2026`() {
        assertEquals(2006, VinDecoder.decode("1D4GP00R56B123456", referenceYear = 2026).modelYear)
    }

    @Test
    fun `annee modele suivante admise et annees plus lointaines exclues`() {
        val next = VinDecoder.decode("VSSZZZ6JZVR000000", referenceYear = 2026)
        assertEquals(2027, next.modelYear)
        assertEquals(listOf(1997, 2027), next.modelYearCandidates)
        assertTrue(next.modelYearIsEstimate)
        val later = VinDecoder.decode("VSSZZZ6JZWR000000", referenceYear = 2026)
        assertEquals(1998, later.modelYear)
        assertEquals(listOf(1998), later.modelYearCandidates)
    }

    @Test
    fun `cycles de trente ans conserves sans restriction OBD inventee`() {
        val info = VinDecoder.decode("1D4GP00R56B123456", referenceYear = 2036)
        assertEquals(2036, info.modelYear)
        assertEquals(listOf(2006, 2036), info.modelYearCandidates)
        assertTrue(info.modelYearIsEstimate)
    }

    @Test
    fun `caractere annee non code reste inconnu`() {
        for (code in listOf('0', 'U', 'Z')) {
            val info = VinDecoder.decode("VSSZZZ6JZ${code}R000000", referenceYear = 2026)
            assertNull(info.modelYear)
            assertTrue(info.modelYearCandidates.isEmpty())
            org.junit.Assert.assertFalse(info.modelYearIsEstimate)
        }
    }

    @Test
    fun `vin de longueur incorrecte ne renvoie rien`() {
        val info = VinDecoder.decode("TROPCOURT")
        assertNull(info.manufacturer)
        assertNull(info.region)
        assertNull(info.modelYear)
    }

    // --- fileTag : prefixe de nom de fichier (voir ObdViewModel.startRecording/startFapScan) ---

    @Test
    fun `fileTag constructeur connu suivi des 6 derniers caracteres du VIN`() {
        assertEquals("SEAT-000000", VinDecoder.fileTag("VSSZZZ6JZBR000000"))
    }

    @Test
    fun `fileTag sans vin renvoie un repli explicite`() {
        assertEquals("vehicule_inconnu", VinDecoder.fileTag(null))
    }

    @Test
    fun `fileTag constructeur inconnu retombe sur vehicule generique`() {
        assertEquals("vehicule-000000", VinDecoder.fileTag("JXX00000000000000"))
    }

    @Test
    fun `fileTag assainit un nom de constructeur contenant un caractere invalide`() {
        // "Opel/Vauxhall" : le slash casserait un chemin de fichier, remplace par un
        // tiret bas (voir VinDecoder.sanitizeForFilename).
        assertEquals("Opel_Vauxhall-000000", VinDecoder.fileTag("W0L00000000000000"))
    }
}
