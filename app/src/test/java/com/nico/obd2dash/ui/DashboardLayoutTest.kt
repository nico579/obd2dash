package com.nico.obd2dash.ui

import com.nico.obd2dash.GaugeValue
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLayoutTest {
    @Test
    fun `un cadran peut traverser les rangees dans les deux sens`() {
        val pids = listOf(12, 13, 5, 4, 66, 11)
        assertEquals(listOf(13, 5, 4, 66, 11, 12), moveDashboardGauge(pids, 0, 5))
        assertEquals(listOf(11, 12, 13, 5, 4, 66), moveDashboardGauge(pids, 5, 0))
        assertEquals(pids, moveDashboardGauge(pids, 0, -1))
        assertEquals(pids, moveDashboardGauge(pids, 6, 0))
    }

    @Test
    fun `le glissement trouve aussi le dernier cadran en rangee incomplete`() {
        val slots = dashboardSlots(5, 344f, 668f)
        assertEquals(5, slots.size)
        assertEquals(slots.first().width, slots.last().width, .001f)
        assertEquals(172f, slots.last().center.x, .001f)
        assertEquals(4, slots.indexOfFirst { it.contains(slots.last().center) })
        assertEquals(-1, slots.indexOfFirst { it.contains(Offset(172f, 20f)) })
        assertEquals(-1, slots.indexOfFirst { it.contains(Offset(-1f, 500f)) })
        assertTrue(dashboardSlots(0, 344f, 668f).isEmpty())
    }

    @Test
    fun `tous les cadrans gardent le meme diametre sans deborder ni se chevaucher`() {
        for ((width, height) in listOf(320f to 540f, 344f to 668f, 784f to 308f, 560f to 220f)) {
            for (count in 1..6) {
                val slots = dashboardSlots(count, width, height)
                assertEquals(count, slots.size)
                for ((index, slot) in slots.withIndex()) {
                    assertEquals(slots.first().width, slot.width, .001f)
                    assertEquals(slot.width, slot.height, .001f)
                    assertTrue(slot.left >= -.001f && slot.top >= -.001f)
                    assertTrue(slot.right <= width + .001f && slot.bottom <= height + .001f)
                    assertTrue(slots.take(index).none { it.overlaps(slot) })
                }
            }
        }
    }

    @Test
    fun `six cadrans utilisent trois colonnes en paysage et deux en portrait`() {
        assertEquals(DashboardGrid(3, 2), dashboardGrid(6, 784f, 308f))
        assertEquals(DashboardGrid(2, 3), dashboardGrid(6, 344f, 668f))
    }

    @Test
    fun `trois cadrans choisissent le plus grand diametre commun`() {
        assertEquals(DashboardGrid(1, 3), dashboardGrid(3, 344f, 668f))
        assertEquals(DashboardGrid(3, 1), dashboardGrid(3, 784f, 308f))
    }

    @Test
    fun `deux cadrans suivent la forme de la surface disponible`() {
        assertEquals(DashboardGrid(2, 1), dashboardGrid(2, 784f, 308f))
        assertEquals(DashboardGrid(1, 2), dashboardGrid(2, 344f, 668f))
    }

    @Test
    fun `une faible hauteur repartit les cadrans sur une seule rangee`() {
        assertEquals(DashboardGrid(6, 1), dashboardGrid(6, 784f, 120f))
    }

    @Test
    fun `les dispositions couvrent tous les choix sans rangee vide`() {
        for ((width, height) in listOf(320f to 540f, 784f to 308f, 560f to 220f, 1f to 1f)) {
            for (count in 1..6) {
                val grid = dashboardGrid(count, width, height)
                assertTrue(grid.columns in 1..count)
                assertTrue(grid.rows > 0)
                assertTrue(grid.columns * grid.rows >= count)
                assertTrue(grid.columns * (grid.rows - 1) < count)
            }
        }
        assertEquals(DashboardGrid(1, 1), dashboardGrid(0, 0f, 0f))
    }

    @Test
    fun `la valeur et les unites sont conservees avec la virgule francaise`() {
        val reading = dialReading("-2,3 %")
        assertEquals("-2,3", reading.number)
        assertEquals("%", reading.detail)
        assertEquals(-2.3, reading.value!!, .00001)
        assertEquals("tr/min", dialReading("1728 rpm").detail)
    }

    @Test
    fun `une lecture composite conserve ses autres informations`() {
        val reading = dialReading("0,450 V · -5,0 %")
        assertEquals("0,450", reading.number)
        assertEquals("V · -5,0 %", reading.detail)
        assertEquals(.45, reading.value!!, .00001)
    }

    @Test
    fun `une lecture absente ou un message ne devient pas zero`() {
        for (text in listOf("--", "NO DATA", "NO DATA 0", "", "N/A")) {
            assertNull(dialReading(text).value)
        }
    }

    @Test
    fun `les mesures hors echelle sont incluses sans plafonner la valeur`() {
        for ((pid, value) in listOf(0x42 to 28.8, 0x0C to 10_500.0, 0x0B to 400.0, 0x0F to -45.0)) {
            val scale = dialScale(pid)!!.including(value)
            assertTrue(value in scale.min..scale.max)
            assertTrue(scale.fraction(value) in 0f..1f)
        }
        assertNull(dialScale(0x4F))
        assertNull(dialScale(0x1F))
    }

    @Test
    fun `une valeur expiree ne peut plus positionner une aiguille`() {
        val now = 30_000L
        val (text, stale) = staleness(0x0D, GaugeValue("120 km/h", now - 10_001L), now)
        assertEquals("--", text)
        assertFalse(stale)
        assertNull(dialReading(text).value)
        assertEquals("--" to false, staleness(0x0D, null, now))
    }

    @Test
    fun `une lecture ancienne reste signalee avant son expiration`() {
        val now = 30_000L
        assertEquals("120 km/h" to true, staleness(0x0D, GaugeValue("120 km/h", now - 3_001L), now))
        assertEquals("75 °C" to false, staleness(0x05, GaugeValue("75 °C", now - 10_000L), now))
        assertEquals("75 °C" to true, staleness(0x05, GaugeValue("75 °C", now - 12_001L), now))
        assertEquals("--" to false, staleness(0x05, GaugeValue("75 °C", now - 20_001L), now))
        assertEquals("10 / 10" to false, staleness(0x4F, GaugeValue("10 / 10", 0L), now))
    }
}
