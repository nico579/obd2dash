package com.nico.obd2dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollingHealthTest {
    private var timeMs = 0L
    private fun health(measurements: Boolean) = PollingHealth(measurements, 15_000L) { timeMs }

    @Test
    fun `MIL failure cannot disconnect while engine measurements still arrive`() {
        val health = health(measurements = true)
        assertFalse(health.milRead(true))
        timeMs = 30_000
        assertFalse(health.measurementsRead(true))
        assertFalse(health.milRead(false))
        timeMs = 60_000
        assertFalse(health.measurementsRead(true))
        assertFalse(health.milRead(false))
    }

    @Test
    fun `normal MIL interval and one failure do not cause a false disconnect`() {
        val health = health(measurements = false)
        assertFalse(health.milRead(true))
        timeMs = 30_000
        assertFalse(health.milRead(true))
        timeMs = 60_000
        assertFalse(health.milRead(false))
        timeMs = 90_000
        assertFalse(health.milRead(true))
    }

    @Test
    fun `MIL only repeated failures trigger reconnect on the next scheduled read`() {
        val health = health(measurements = false)
        assertFalse(health.milRead(false))
        timeMs = 30_000
        assertTrue(health.milRead(false))
    }

    @Test
    fun `successful MIL clears a previous failure sequence`() {
        val health = health(measurements = false)
        assertFalse(health.milRead(false))
        timeMs = 30_000
        assertFalse(health.milRead(true))
        timeMs = 60_000
        assertFalse(health.milRead(false))
    }

    @Test
    fun `diagnostic pause clears MIL failure sequence`() {
        val health = health(measurements = false)
        assertFalse(health.milRead(false))
        timeMs = 60_000
        health.diagnosticPause()
        assertFalse(health.milRead(false))
        timeMs = 90_000
        assertTrue(health.milRead(false))
    }

    @Test
    fun `MIL success does not mask the loss of expected measurements`() {
        val health = health(measurements = true)
        assertFalse(health.measurementsRead(true))
        timeMs = 15_001
        assertFalse(health.milRead(true))
        assertTrue(health.measurementsRead(false))
    }

    @Test
    fun `measurement success and diagnostic pause restart the measurement deadline`() {
        val health = health(measurements = true)
        timeMs = 14_000
        assertFalse(health.measurementsRead(true))
        timeMs = 16_000
        assertFalse(health.measurementsRead(false))
        timeMs = 60_000
        health.diagnosticPause()
        assertFalse(health.measurementsRead(false))
        timeMs = 75_001
        assertTrue(health.measurementsRead(false))
    }

    @Test
    fun `empty measurement cycles on a MIL only session never imply a lost vehicle`() {
        val health = health(measurements = false)
        timeMs = 120_000
        assertFalse(health.measurementsRead(false))
        assertFalse(health.milRead(true))
    }
}
