package com.nico.obd2dash.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Couvre formatGraphValue, seule logique pure de ce fichier (le reste dessine avec
 * Canvas/Compose, hors de portée d'un test JVM sans Robolectric). Rendue internal pour
 * être testée directement, comme PidCatalog.extractLeadingNumber.
 */
class GraphScreenTest {

    @Test
    fun `entier affiche sans decimales`() {
        assertEquals("100", formatGraphValue(100.0))
        assertEquals("0", formatGraphValue(0.0))
        assertEquals("-40", formatGraphValue(-40.0))
    }

    @Test
    fun `non entier affiche a 2 decimales avec virgule francaise`() {
        assertEquals("3,14", formatGraphValue(3.14159))
        assertEquals("-2,50", formatGraphValue(-2.5))
    }

    @Test
    fun `virgule francaise garantie meme sous une locale par defaut differente`() {
        // Meme classe de bug que Elm327ClientTest.decodeDtc (audit du 12 septembre 2026) :
        // Locale.FRANCE est passe explicitement dans formatGraphValue, donc le resultat ne
        // doit pas suivre la locale par defaut de la JVM qui execute le test.
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("3,14", formatGraphValue(3.14159))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
