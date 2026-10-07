package com.nico.obd2dash

import java.util.concurrent.atomic.AtomicLong

/** Tokens shared across ViewModels; an old service must not match a replacement owner. */
internal object RecordingSessionIds {
    private val sequence = AtomicLong()

    fun next(): Long = sequence.incrementAndGet().also {
        check(it > 0L) { "Recording session identifiers exhausted" }
    }
}

/** Intent extras are not part of PendingIntent identity; its data must retain this token. */
internal fun recordingStopActionUri(sessionId: Long): String = "obd2dash://recording/stop/$sessionId"
