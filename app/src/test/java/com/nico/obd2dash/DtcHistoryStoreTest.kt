package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Couvre mergeHistory, fonction pure partagée par le chemin persisté (véhicule identifié)
 * et le chemin en mémoire (véhicule inconnu, voir DtcHistoryStore.sessionHistory et l'audit
 * "Deux véhicules et historique").
 */
class DtcHistoryStoreTest {

    @Test
    fun `code nouveau prend firstSeen et lastSeen a maintenant`() {
        val result = mergeHistory(emptyMap(), listOf("P0087"), "2026-09-11 08:00")
        assertEquals(DtcHistoryEntry("P0087", "2026-09-11 08:00", "2026-09-11 08:00", true), result["P0087"])
    }

    @Test
    fun `code toujours actif garde son firstSeen d'origine et avance lastSeen`() {
        val previous = mapOf("P0087" to DtcHistoryEntry("P0087", "2026-09-10 08:00", "2026-09-10 08:00", true))
        val result = mergeHistory(previous, listOf("P0087"), "2026-09-11 08:00")
        assertEquals(DtcHistoryEntry("P0087", "2026-09-10 08:00", "2026-09-11 08:00", true), result["P0087"])
    }

    @Test
    fun `code disparu de la lecture actuelle devient inactif sans avancer lastSeen`() {
        // lastSeen doit rester la dernière fois où le code était RÉELLEMENT présent, pas
        // la date de cette lecture qui ne le contient plus.
        val previous = mapOf("P0087" to DtcHistoryEntry("P0087", "2026-09-10 08:00", "2026-09-10 08:00", true))
        val result = mergeHistory(previous, emptyList(), "2026-09-11 08:00")
        assertEquals(DtcHistoryEntry("P0087", "2026-09-10 08:00", "2026-09-10 08:00", false), result["P0087"])
    }

    @Test
    fun `un code inactif qui reapparait redevient actif et avance lastSeen`() {
        val previous = mapOf("P0087" to DtcHistoryEntry("P0087", "2026-09-10 08:00", "2026-09-10 08:00", false))
        val result = mergeHistory(previous, listOf("P0087"), "2026-09-11 08:00")
        assertEquals(DtcHistoryEntry("P0087", "2026-09-10 08:00", "2026-09-11 08:00", true), result["P0087"])
    }
}
