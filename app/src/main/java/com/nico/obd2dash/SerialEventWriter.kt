package com.nico.obd2dash

import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One writer/formatter owner, including crashes. Normal callers never perform disk IO. */
internal class SerialEventWriter(private val writer: BufferedWriter) : AutoCloseable {
    @Volatile private var worker: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "obd-event-log").apply { isDaemon = true; worker = this }
    }
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)

    fun log(message: String, atMs: Long = System.currentTimeMillis()) {
        runCatching { executor.execute { append(message, atMs) } }
    }

    fun crash(thread: Thread, throwable: Throwable, timeoutMs: Long = 2_000): Boolean {
        val message = "PLANTAGE sur le thread \"${thread.name}\" :\n${throwable.stackTraceToString()}"
        val atMs = System.currentTimeMillis()
        // No deadlock if the worker itself is the thread being terminated.
        if (Thread.currentThread() === worker) return append(message, atMs)
        return runCatching {
            executor.submit<Boolean> { append(message, atMs) }.get(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
    }

    private fun append(message: String, atMs: Long): Boolean = runCatching {
        writer.write("${format.format(Date(atMs))}  $message")
        writer.newLine()
        writer.flush()
        true
    }.getOrDefault(false)

    /** Wait for queued writes and close their owner. Not used on the Android UI thread. */
    override fun close() {
        runCatching { executor.submit { writer.close() }.get(2, TimeUnit.SECONDS) }
        executor.shutdown()
    }
}
