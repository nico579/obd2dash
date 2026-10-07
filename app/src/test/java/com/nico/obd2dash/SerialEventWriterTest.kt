package com.nico.obd2dash

import java.io.BufferedWriter
import java.io.StringWriter
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class SerialEventWriterTest {
    @Test fun `concurrent logging and crash share one complete ordered writer`() {
        val output = StringWriter()
        val owners = mutableSetOf<String>()
        val sink = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) {
                owners.add(Thread.currentThread().name)
                output.write(chars, offset, length)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        SerialEventWriter(BufferedWriter(sink)).use { log ->
            val start = CountDownLatch(1)
            val callers = (0..7).map { caller -> thread {
                start.await()
                repeat(25) { log.log("entry-$caller-$it", 1_700_000_000_000L) }
            } }
            start.countDown()
            callers.forEach { it.join(2_000); assertFalse(it.isAlive) }
            assertTrue(log.crash(Thread.currentThread(), IllegalStateException("crash-marker")))
        }
        val text = output.toString()
        val messages = text.lines().filter { it.contains("  entry-") }
        assertEquals(200, messages.size)
        assertEquals(200, messages.toSet().size)
        assertEquals(setOf("obd-event-log"), owners)
        assertTrue(text.indexOf("PLANTAGE") > text.lastIndexOf("  entry-"))
        assertTrue(text.contains("IllegalStateException: crash-marker"))
    }

    @Test fun `slow storage does not block normal callers and crash wait is bounded`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sink = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val log = SerialEventWriter(BufferedWriter(sink))
        try {
            log.log("blocked-storage")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val callerFinished = CountDownLatch(1)
            val caller = thread { log.log("queued-next"); callerFinished.countDown() }
            assertTrue(callerFinished.await(1, TimeUnit.SECONDS))
            caller.join()
            assertFalse(log.crash(Thread.currentThread(), IllegalStateException("bounded"), timeoutMs = 50))
        } finally {
            release.countDown()
            log.close()
        }
    }
}
