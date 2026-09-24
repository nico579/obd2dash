package com.nico.obd2dash

import java.io.IOException

/** Mode attesté par la requête du journal ; choisir n'envoie aucune commande. */
internal enum class CanDtcMode(val requestService: String, val responseService: String) {
    STORED("03", "43"), PENDING("07", "47")
}

internal enum class CanDtcFailureReason {
    MALFORMED_FRAME, INVALID_SEQUENCE, UNEXPECTED_SERVICE, NEGATIVE_RESPONSE, MALFORMED_PAYLOAD
}

internal data class CanDtcFailure(val reason: CanDtcFailureReason, val nrc: Int? = null)

internal data class CanDtcCaptureSummary(
    val codesByEcu: Map<Int, List<String>>,
    val failuresByEcu: Map<Int, CanDtcFailure>,
    val globalFailure: CanCaptureGlobalFailure?
) {
    val status: CanCaptureStatus get() = captureStatus(
        codesByEcu.isNotEmpty(), failuresByEcu.isNotEmpty() || globalFailure != null
    )
    val uniqueCodes: List<String> get() = codesByEcu.values.flatten().distinct().sorted()
    /** Aucun code dans les réponses fournies, pas un verdict actuel sur tout le véhicule. */
    val noCodesInCompleteCapture: Boolean get() = status == CanCaptureStatus.COMPLETE && uniqueCodes.isEmpty()
}

/** Analyse locale d'une seule réponse enregistrée, séparée du diagnostic et de l'historique actifs. */
internal object CanDtcCaptureAnalysis {
    fun analyze(text: String, format: CanIdFormat, mode: CanDtcMode): CanDtcCaptureSummary {
        val reassembled = CanHeaderReassembly.analyze(text, format)
        val codes = linkedMapOf<Int, List<String>>()
        val failures = reassembled.failuresByEcu.mapValuesTo(linkedMapOf()) { (_, failure) ->
            CanDtcFailure(when (failure) {
                CanFrameFailure.MALFORMED_FRAME -> CanDtcFailureReason.MALFORMED_FRAME
                CanFrameFailure.INVALID_SEQUENCE -> CanDtcFailureReason.INVALID_SEQUENCE
            })
        }
        for ((ecuId, payload) in reassembled.payloadsByEcu) {
            when {
                payload.startsWith("7F${mode.requestService}") -> {
                    failures[ecuId] = if (payload.length == 6) CanDtcFailure(
                        CanDtcFailureReason.NEGATIVE_RESPONSE, payload.takeLast(2).toInt(16)
                    ) else CanDtcFailure(CanDtcFailureReason.MALFORMED_PAYLOAD)
                }
                !payload.startsWith(mode.responseService) -> {
                    failures[ecuId] = CanDtcFailure(CanDtcFailureReason.UNEXPECTED_SERVICE)
                }
                else -> {
                    try {
                        // L'ISO-TP a déjà retiré tout padding de trame : aucune donnée
                        // au-delà du compteur ne doit être jetée silencieusement ici.
                        codes[ecuId] = DtcPayloadDecoder.parse(
                            payload, mode.responseService, isCan = true, exactCanPayload = true
                        ).distinct()
                    } catch (_: IOException) {
                        failures[ecuId] = CanDtcFailure(CanDtcFailureReason.MALFORMED_PAYLOAD)
                    }
                }
            }
        }
        return CanDtcCaptureSummary(codes.toMap(), failures.toMap(), reassembled.globalFailure)
    }
}
