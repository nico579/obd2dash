package com.nico.obd2dash

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Couvre les échelles conditionnelles PID0B (pression admission) et PID10 (débit d'air),
 * corrigées en A4 : leur formule standard 1:1 ne s'applique que si le véhicule n'annonce
 * pas de dépassement de plage via PID4F/PID50 (SAE J1979-DA, tables B60-B61). Les valeurs
 * attendues viennent des exemples normatifs rejoués dans l'audit du 10 septembre 2026,
 * pas d'une capture véhicule (aucun véhicule de test n'annonce ces octets non nuls).
 *
 * PidCatalog est un singleton à état mutable partagé entre connexions : chaque test
 * remet cet état au repli par défaut avant et après, pour ne pas dépendre de l'ordre
 * d'exécution des tests.
 */
class PidCatalogTest {

    private fun def(pid: Int) = PidCatalog.defs.first { it.pid == pid }

    @Before
    fun resetScales() {
        PidCatalog.o2MaxRatio = 2.0
        PidCatalog.o2MaxVoltage = 8.0
        PidCatalog.mapMaxKpa = null
        PidCatalog.mafMaxGramsPerSec = null
    }

    @After
    fun tearDown() = resetScales()

    // --- PID0B : pression admission ---

    @Test
    fun `PID0B sans contexte PID4F garde la formule standard 1 pour 1`() {
        // Séparateur décimal français (locale par défaut du JVM/Android, cf. csvRow dans
        // ObdViewModelTest) : les formules PidCatalog n'imposent pas de Locale explicite.
        assertEquals("127,0 kPa", def(0x0B).decode(listOf(127)))
    }

    @Test
    fun `PID0B avec contexte PID4F applique l'echelle annoncee`() {
        // Exemple normatif de l'audit (A4) : brut 127, PID4F octet D = 77 -> 383,49 kPa
        // (contre 127 kPa affiches par la formule standard, une sous-estimation d'un
        // facteur 3 sur un moteur suralimente).
        PidCatalog.mapMaxKpa = 77.0 * 10.0
        assertEquals("383,5 kPa", def(0x0B).decode(listOf(127)))
    }

    // --- PID10 : debit d'air (MAF) ---

    @Test
    fun `PID10 sans contexte PID50 garde la formule standard`() {
        assertEquals("580,00 g/s", def(0x10).decode(listOf(0xE2, 0x90))) // 58000 / 100
    }

    @Test
    fun `PID10 avec contexte PID50 applique l'echelle annoncee`() {
        // Exemple normatif de l'audit (A4) : brut 58000, PID50 octet A = 100 -> 885,02 g/s
        // (contre 580 g/s affiches par la formule standard).
        PidCatalog.mafMaxGramsPerSec = 100.0 * 10.0
        assertEquals("885,02 g/s", def(0x10).decode(listOf(0xE2, 0x90)))
    }

    // --- PID24 : sonde O2 large bande (repli par octet, pas tout ou rien) ---

    @Test
    fun `PID24 repli par octet quand un seul maximum PID4F est nul`() {
        // Avant A4 : un octet nul a zero ecrasait o2MaxRatio/o2MaxVoltage directement,
        // mettant la composante correspondante a zero au lieu de garder son repli
        // historique. Bruts au maximum (0xFFFF) sur les deux composantes : le resultat
        // affiche alors exactement le maximum applique a chacune, sans calcul intermediaire
        // a verifier a la main. Ici seul le ratio a un contexte reel (10) ; la tension doit
        // garder son repli (8.0), pas tomber a 0 comme avant ce correctif.
        PidCatalog.o2MaxRatio = 10.0
        PidCatalog.o2MaxVoltage = 8.0 // repli : PID4F octet B etait nul, pas assigne
        val bytes = listOf(0xFF, 0xFF, 0xFF, 0xFF)
        assertEquals("10,000 · 8,000 V", def(0x24).decode(bytes))
    }

    // --- extractLeadingNumber : lecture du graphique dans le texte deja affiche ---

    @Test
    fun `extractLeadingNumber lit un entier avec unite`() {
        assertEquals(1305.0, extractLeadingNumber("1305 rpm"))
    }

    @Test
    fun `extractLeadingNumber convertit la virgule francaise`() {
        assertEquals(94.20, extractLeadingNumber("94,20 V"))
    }

    @Test
    fun `extractLeadingNumber garde le signe negatif`() {
        assertEquals(-2.3, extractLeadingNumber("-2,3 %"))
    }

    @Test
    fun `extractLeadingNumber sur un champ composite ne reprend que le premier nombre`() {
        // Sonde O2 large bande (PID24) : "ratio · tension", on ne graphe que le ratio.
        assertEquals(1.000, extractLeadingNumber("1,000 · 8,961 V"))
    }

    @Test
    fun `extractLeadingNumber sans aucun nombre rend null`() {
        assertNull(extractLeadingNumber("--"))
    }
}
