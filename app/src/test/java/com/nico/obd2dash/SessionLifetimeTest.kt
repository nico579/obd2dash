package com.nico.obd2dash

import org.junit.Assert.*
import org.junit.Test

class SessionLifetimeTest {
    private class Capture(var recording: Boolean = false, var closed: Boolean = false)
    private fun lifetime() = SessionLifetime(create = { Capture() }, destroy = { it.closed = true }, recording = { it.recording })

    @Test fun `finishing UI during REC retains acquisition and reopening rejoins it`() {
        val owner = lifetime()
        val firstUi = Any()
        val capture = owner.acquireUi(firstUi)
        capture.recording = true
        owner.releaseUi(firstUi, false)
        assertSame(capture, owner.current)
        assertFalse(capture.closed)
        val secondUi = Any()
        assertSame(capture, owner.acquireUi(secondUi))
        capture.recording = false
        owner.recordingChanged(capture)
        assertFalse(capture.closed) // UI still owns it.
        owner.releaseUi(secondUi, false)
        assertTrue(capture.closed)
        assertNull(owner.current)
    }

    @Test fun `notification stop with no UI releases exact recording session`() {
        val owner = lifetime()
        val ui = Any()
        val capture = owner.acquireUi(ui)
        capture.recording = true
        owner.releaseUi(ui, false)
        capture.recording = false
        owner.recordingChanged(capture)
        assertTrue(capture.closed)
        assertNull(owner.current)
    }

    @Test fun `configuration handover preserves session without REC`() {
        val owner = lifetime()
        val firstUi = Any()
        val capture = owner.acquireUi(firstUi)
        owner.releaseUi(firstUi, true)
        owner.recordingChanged(capture) // A callback during the gap must preserve handover.
        assertFalse(capture.closed)
        val replacementUi = Any()
        assertSame(capture, owner.acquireUi(replacementUi))
        owner.releaseUi(replacementUi, false)
        assertTrue(capture.closed)
    }

    @Test fun `late recording callback cannot destroy replacement session`() {
        val owner = lifetime()
        val ui = Any()
        val old = owner.acquireUi(ui)
        owner.releaseUi(ui, false)
        val replacementUi = Any()
        val next = owner.acquireUi(replacementUi)
        next.recording = true
        owner.releaseUi(replacementUi, false)
        owner.recordingChanged(old)
        assertSame(next, owner.current)
        assertFalse(next.closed)
    }

    @Test fun `multiple UI owners and duplicate detach do not end a live session`() {
        val owner = lifetime()
        val a = Any()
        val b = Any()
        val session = owner.acquireUi(a)
        assertSame(session, owner.acquireUi(b))
        owner.releaseUi(a, false)
        owner.releaseUi(a, false)
        assertFalse(session.closed)
        owner.releaseUi(b, false)
        assertTrue(session.closed)
    }
}
