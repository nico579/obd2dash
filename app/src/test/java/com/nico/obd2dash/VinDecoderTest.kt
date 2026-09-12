package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // Vehicule deja utilise dans ce depot (voir audit/memoire) : B en position 10 doit
        // donner 2011, pas 1981 (voir VinDecoder, desambiguisation par le 7e caractere
        // volontairement pas utilisee : elle donnerait le mauvais cycle pour ce VIN precis).
        assertEquals(2011, VinDecoder.decode("VSSZZZ6JZBR000000").modelYear)
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
    fun `annee-modele A vaut 2010 et Y vaut 2030`() {
        assertEquals(2010, VinDecoder.decode("VSSZZZ6JZAR000000").modelYear)
        assertEquals(2030, VinDecoder.decode("VSSZZZ6JZYR000000").modelYear)
    }

    @Test
    fun `annee-modele chiffre 1 vaut 2031`() {
        assertEquals(2031, VinDecoder.decode("VSSZZZ6JZ1R000000").modelYear)
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
