package com.nico.obd2dash

import kotlinx.coroutines.CancellationException

enum class DiagnosticRead(val label: String) {
    MIL("Voyant moteur"),
    STORED("Codes stockés"),
    PENDING("Codes en attente"),
    READINESS("Moniteurs"),
    FREEZE_FRAME("Capture du défaut")
}

/**
 * Un refus de service ne préjuge pas des autres services. Une perte du transport,
 * en revanche, arrête immédiatement la séquence : aucune commande après un timeout
 * ou une réponse temporaire dont la suite pourrait encore arriver.
 */
internal class DiagnosticReadSequence(
    private val isConnected: () -> Boolean,
    private val onFailure: (Map<DiagnosticRead, String>) -> Unit
) {
    private val failures = linkedMapOf<DiagnosticRead, String>()
    val isComplete: Boolean get() = failures.isEmpty()
    val errorMessage: String? get() = failures.takeIf { it.isNotEmpty() }
        ?.entries?.joinToString("\n") { "${it.key.label} : ${it.value}" }

    suspend fun <T> read(service: DiagnosticRead, block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures[service] = e.message ?: "Lecture impossible"
            onFailure(failures.toMap())
            if (!isConnected()) throw e
            Result.failure(e)
        }
    }
}
