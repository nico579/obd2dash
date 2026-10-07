package com.nico.obd2dash

/** Main-thread ownership policy. Recording keeps a session alive after the last UI leaves. */
internal class SessionLifetime<T : Any>(
    private val create: () -> T,
    private val destroy: (T) -> Unit,
    private val recording: (T) -> Boolean
) {
    private val owners = mutableSetOf<Any>()
    private var awaitingUiHandover = false
    var current: T? = null
        private set

    fun acquireUi(owner: Any): T {
        owners.add(owner)
        awaitingUiHandover = false
        return current ?: create().also { current = it }
    }

    fun releaseUi(owner: Any, changingConfiguration: Boolean) {
        if (!owners.remove(owner)) return
        awaitingUiHandover = changingConfiguration && owners.isEmpty()
        // Keep the session across the short gap between two configuration instances.
        if (!changingConfiguration) releaseIfIdle()
    }

    fun recordingChanged(session: T) {
        if (current === session) releaseIfIdle()
    }

    private fun releaseIfIdle() {
        val session = current ?: return
        if (owners.isNotEmpty() || awaitingUiHandover || recording(session)) return
        current = null // A late callback must not release a replacement session.
        destroy(session)
    }
}
