package com.nico.obd2dash

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DiagnosticReadSequenceTest {
    @Test fun `refus MIL n empeche pas codes ni capture et ne publie aucun faux succes`() = runBlocking {
        var errors = emptyMap<DiagnosticRead, String>()
        val reads = DiagnosticReadSequence({ true }) { errors = it }
        assertTrue(reads.read(DiagnosticRead.MIL) { throw IOException("7F0112") }.isFailure)
        assertEquals(listOf("P0087"), reads.read(DiagnosticRead.STORED) { listOf("P0087") }.getOrThrow())
        assertTrue(reads.read(DiagnosticRead.PENDING) { throw IOException("7F0711") }.isFailure)
        assertNull(reads.read(DiagnosticRead.FREEZE_FRAME) { null }.getOrThrow())
        assertEquals(setOf(DiagnosticRead.MIL, DiagnosticRead.PENDING), errors.keys)
        assertFalse(reads.isComplete)
        assertTrue(reads.errorMessage.orEmpty().contains("7F0112"))
    }

    @Test fun `annulation ne devient pas une erreur de service recuperable`() = runBlocking {
        val reads = DiagnosticReadSequence({ true }) { fail("Erreur publiée pour une annulation") }
        try { reads.read(DiagnosticRead.MIL) { throw CancellationException() }; fail("Annulation avalée") }
        catch (_: CancellationException) { }
    }

    @Test fun `perte du transport interrompt la sequence`() = runBlocking {
        val reads = DiagnosticReadSequence({ false }) { }
        try { reads.read(DiagnosticRead.STORED) { throw IOException("déconnecté") }; fail("Perte ignorée") }
        catch (_: IOException) { }
    }
}
