package com.nico.obd2dash

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecordingSessionIdsTest {
    private class Owner {
        fun beginCapture(): Long = RecordingSessionIds.next()
    }

    @Test fun `a replacement owner never reuses a previous owners capture token`() {
        val previous = Owner()
        val first = previous.beginCapture()
        val second = previous.beginCapture()
        val replacement = Owner()
        val next = replacement.beginCapture()
        assertTrue(first > 0L)
        assertTrue(second > first)
        assertTrue(next > second)
        assertNotEquals(first, next)
        assertNotEquals(second, next)
    }

    @Test fun `stop action identity keeps old notifications separate from replacement captures`() {
        val previous = Owner().beginCapture()
        val replacement = Owner().beginCapture()
        val previousAction = recordingStopActionUri(previous)
        val replacementAction = recordingStopActionUri(replacement)
        assertNotEquals(previousAction, replacementAction)
        assertEquals(previousAction, recordingStopActionUri(previous))
        assertEquals("obd2dash://recording/stop/$replacement", replacementAction)
    }

    @Test fun `concurrent token allocations remain distinct`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 64).map { executor.submit(Callable { RecordingSessionIds.next() }) }
            val tokens = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(tokens.size, tokens.toSet().size)
            assertTrue(tokens.all { it > 0L })
        } finally { executor.shutdownNow() }
    }
}
