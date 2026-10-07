package com.nico.obd2dash.updates

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class UpdateDownloadTest {
    private val data = ByteArray(100_000) { (it % 255).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    @Test fun completeDownloadMustMatchSizeAndDigest() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        copyVerifiedUpdate(data.inputStream(), output, data.size.toLong(), hash, { progress.add(it) }, {})
        assertArrayEquals(data, output.toByteArray())
        assertEquals(data.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a < b })
    }
    @Test fun truncatedDownloadIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            copyVerifiedUpdate(data.copyOf(data.size - 1).inputStream(), ByteArrayOutputStream(), data.size.toLong(), hash, {}, {})
        }
    }
    @Test fun oversizedDownloadIsStoppedBeforeWritingExtraBytes() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            copyVerifiedUpdate((data + byteArrayOf(1)).inputStream(), output, data.size.toLong(), hash, {}, {})
        }
        assertTrue(output.size() <= data.size)
    }
    @Test fun corruptedDownloadIsRejected() {
        val corrupted = data.copyOf().apply { this[50] = 42 }
        assertThrows(IllegalArgumentException::class.java) {
            copyVerifiedUpdate(corrupted.inputStream(), ByteArrayOutputStream(), data.size.toLong(), hash, {}, {})
        }
    }
    @Test fun cancellationInterruptsCopyAndCannotReportComplete() {
        val output = ByteArrayOutputStream()
        var checks = 0
        assertThrows(CancellationException::class.java) {
            copyVerifiedUpdate(data.inputStream(), output, data.size.toLong(), hash, {}, {
                if (++checks == 2) throw CancellationException()
            })
        }
        assertTrue(output.size() in 1 until data.size)
    }
    @Test fun invalidSizeIsRejectedBeforeReading() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            copyVerifiedUpdate(data.inputStream(), output, 0, hash, {}, {})
        }
        assertEquals(0, output.size())
    }
}
