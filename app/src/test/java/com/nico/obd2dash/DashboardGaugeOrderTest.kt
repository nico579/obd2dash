package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardGaugeOrderTest {
    @Test
    fun `le CSV existant conserve ses choix et leur ordre`() {
        assertEquals(listOf(0x42, 0x0D, 0x0C), DashboardGaugeOrder.restore("66,13,12"))
        assertEquals(PidCatalog.PRIMARY_PIDS.toList(), DashboardGaugeOrder.restore(null))
        assertEquals(emptyList<Int>(), DashboardGaugeOrder.restore(""))
    }

    @Test
    fun `des preferences corrompues ne creent ni doublon ni cadran inconnu`() {
        assertEquals(listOf(0x0D, 0x0C), DashboardGaugeOrder.restore("13,erreur,13,999,-1, 12"))
        assertEquals(6, DashboardGaugeOrder.restore("12,13,5,4,66,11,15,16").size)
    }

    @Test
    fun `les cadrans masques ne sont pas perdus par une reorganisation`() {
        val selected = listOf(0x0C, 0x42, 0x0D, 0x05)
        val moved = DashboardGaugeOrder.reorder(selected, setOf(0x0C, 0x0D, 0x05), listOf(0x05, 0x0C, 0x0D))
        assertEquals(listOf(0x05, 0x42, 0x0C, 0x0D), moved)
        assertEquals(moved, DashboardGaugeOrder.restore(moved.joinToString(",")))
    }

    @Test
    fun `un geste obsolete ou incomplet ne remplace pas la selection`() {
        val selected = listOf(0x0C, 0x0D, 0x05)
        for (order in listOf(listOf(0x0C, 0x0D), listOf(0x05, 0x05, 0x0C), listOf(0x05, 0x0C, 0x42))) {
            assertEquals(selected, DashboardGaugeOrder.reorder(selected, selected.toSet(), order))
        }
        assertEquals(selected, DashboardGaugeOrder.reorder(selected, emptySet(), selected.reversed()))
    }
}
