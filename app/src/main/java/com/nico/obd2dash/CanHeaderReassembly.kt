package com.nico.obd2dash

import java.util.Locale

/** Largeur connue par le protocole de la capture, jamais devinée depuis ses octets. */
internal enum class CanIdFormat(val hexDigits: Int, val maxId: Int) {
    CAN_11(3, 0x7FF),
    CAN_29(8, 0x1FFFFFFF)
}

internal enum class CanCaptureStatus { NO_RESPONSE, COMPLETE, PARTIAL, INVALID }
internal enum class CanCaptureGlobalFailure { INPUT_TOO_LARGE, UNRECOGNIZED_LINE }
internal enum class CanFrameFailure { MALFORMED_FRAME, INVALID_SEQUENCE }

/** Complet décrit les lignes fournies, pas un inventaire de tous les ECU du véhicule. */
internal data class CanReassemblyResult(
    val payloadsByEcu: Map<Int, String>,
    val failuresByEcu: Map<Int, CanFrameFailure>,
    val globalFailure: CanCaptureGlobalFailure? = null
) {
    val status: CanCaptureStatus get() = captureStatus(
        payloadsByEcu.isNotEmpty(), failuresByEcu.isNotEmpty() || globalFailure != null
    )
}

internal fun captureStatus(hasValidResponse: Boolean, hasFailure: Boolean): CanCaptureStatus = when {
    hasValidResponse && hasFailure -> CanCaptureStatus.PARTIAL
    hasValidResponse -> CanCaptureStatus.COMPLETE
    hasFailure -> CanCaptureStatus.INVALID
    else -> CanCaptureStatus.NO_RESPONSE
}

/**
 * Réassemblage local ATH1/ATS0, sans DLC, CAN classique et adressage ISO-TP normal.
 * Format : ID CAN, PCI puis données ; voir documentation ELM327, pages 44–45.
 * https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf
 *
 * Le format CAN11 a été observé sur la sonde SEAT le 10 septembre 2026, répondant
 * 7E8. Les réponses 0904 et 090A sont rejouées dans les tests ; les cas CAN29 et
 * multi-ECU restent synthétiques. CAN FD, adressage étendu ISO-TP, J1939 et affichage
 * avec DLC ne sont pas pris en charge par ce parseur.
 *
 * Le réassemblage par ECU n'est pas activé dans les lectures connectées. Seule la
 * lecture d'une SF est utilisée pour le garde NRC de la capture manuelle ATH1,
 * avec la largeur CAN établie par le protocole ; aucun choix depuis les octets.
 * Une seule réponse distincte par ECU est admise, pas une succession de messages
 * (par exemple NRC provisoire puis réponse positive). L'absence d'un ECU dans le
 * résultat signifie réponse inutilisable ou absente, jamais « aucun défaut ».
 */
internal object CanHeaderReassembly {
    data class Frame(val ecuId: Int, val data: String, val sequenceIndex: Int?, val totalLength: Int? = null)

    /** SF : index null ; FF : -1 ; CF : numéro ISO-TP 0..F. Remplissage SF retiré. */
    fun parseCanFrame(line: String, format: CanIdFormat = CanIdFormat.CAN_11): Frame? {
        val ecuId = parseEcuId(line, format) ?: return null
        val rest = line.substring(format.hexDigits)
        // Au plus huit octets de CAN classique, PCI compris. Vérifier aussi le
        // remplissage : une donnée corrompue ne doit pas disparaître à la troncature.
        if (rest.length !in 4..16 || rest.length % 2 != 0 || !rest.isAsciiHex()) return null
        val upper = rest.uppercase(Locale.ROOT)
        return when (upper[0]) {
            '0' -> {
                val length = upper[1].digitToInt(16)
                val data = upper.substring(2)
                if (length !in 1..7 || data.length < length * 2) return null
                Frame(ecuId, data.take(length * 2), sequenceIndex = null)
            }
            '1' -> {
                val totalLength = upper.substring(1, 4).toInt(16)
                // Deux octets PCI, six premiers octets utiles, message trop long
                // pour une SF. Le format de longueur étendue n'est pas accepté.
                if (upper.length != 16 || totalLength !in 8..4095) return null
                Frame(ecuId, upper.substring(4), FIRST_FRAME_MARKER, totalLength)
            }
            '2' -> Frame(ecuId, upper.substring(2), upper[1].digitToInt(16))
            else -> null // FC, RTR ou format non pris en charge : pas de payload
        }
    }

    /** Compatibilité des rejeux historiques ; préférer [analyze] pour tout diagnostic. */
    fun reassembleByEcu(response: String, format: CanIdFormat = CanIdFormat.CAN_11): Map<Int, String> {
        val result = analyze(response, format)
        return if (result.globalFailure == null) result.payloadsByEcu else emptyMap()
    }

    fun analyze(response: String, format: CanIdFormat): CanReassemblyResult {
        if (response.length > MAX_RESPONSE_CHARS) return CanReassemblyResult(
            emptyMap(), emptyMap(), CanCaptureGlobalFailure.INPUT_TOO_LARGE
        )
        val framesByEcu = linkedMapOf<Int, MutableList<Frame>>()
        val failuresByEcu = linkedMapOf<Int, CanFrameFailure>()
        var globalFailure: CanCaptureGlobalFailure? = null
        var promptSeen = false
        var noDataSeen = false
        for (rawLine in response.split('\r', '\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (promptSeen) globalFailure = CanCaptureGlobalFailure.UNRECOGNIZED_LINE
            if (line == ">") {
                promptSeen = true
                continue
            }
            if (line.equals("SEARCHING...", ignoreCase = true)) continue
            if (line.equals("NO DATA", ignoreCase = true)) {
                noDataSeen = true
                continue
            }
            // Sans adresse lisible, impossible de circonscrire une ligne inconnue
            // ou une erreur d'adaptateur : conserver une réserve sur l'échange entier.
            val ecuId = parseEcuId(line, format)
            if (ecuId == null) {
                globalFailure = CanCaptureGlobalFailure.UNRECOGNIZED_LINE
                continue
            }
            val frame = parseCanFrame(line, format)
            if (frame == null) failuresByEcu[ecuId] = CanFrameFailure.MALFORMED_FRAME
            else framesByEcu.getOrPut(ecuId) { mutableListOf() }.add(frame)
        }
        if (noDataSeen && (framesByEcu.isNotEmpty() || failuresByEcu.isNotEmpty())) {
            globalFailure = CanCaptureGlobalFailure.UNRECOGNIZED_LINE
        }
        val result = linkedMapOf<Int, String>()
        for ((ecuId, frames) in framesByEcu) {
            if (ecuId in failuresByEcu) continue
            val payload = reassembleSequence(frames)
            if (payload == null) failuresByEcu[ecuId] = CanFrameFailure.INVALID_SEQUENCE
            else result[ecuId] = payload
        }
        return CanReassemblyResult(result.toMap(), failuresByEcu.toMap(), globalFailure)
    }

    private fun reassembleSequence(frames: List<Frame>): String? {
        val first = frames.firstOrNull() ?: return null
        if (first.sequenceIndex == null) {
            // Répétitions identiques permises, y compris avec un padding différent.
            return first.data.takeIf { data -> frames.all { it.sequenceIndex == null && it.data == data } }
        }
        if (first.sequenceIndex != FIRST_FRAME_MARKER) return null
        val expectedLength = (first.totalLength ?: return null) * 2
        val data = StringBuilder(first.data)
        var expectedIndex = 1
        for (frame in frames.drop(1)) {
            val remaining = expectedLength - data.length
            // Ni nouvelle FF, ni SF, ni CF après la fin de ce message.
            if (remaining <= 0 || frame.sequenceIndex != expectedIndex) return null
            // Une CF intermédiaire doit remplir ses sept octets. Seule la dernière
            // peut être courte ou contenir du remplissage après les données utiles.
            if (frame.data.length < minOf(remaining, 14)) return null
            data.append(frame.data.take(remaining))
            expectedIndex = (expectedIndex + 1) % 16
        }
        return data.toString().takeIf { it.length == expectedLength }
    }

    private fun parseEcuId(line: String, format: CanIdFormat): Int? {
        if (line.length < format.hexDigits) return null
        val text = line.take(format.hexDigits)
        if (!text.isAsciiHex()) return null
        return text.toIntOrNull(16)?.takeIf { it <= format.maxId }
    }

    private fun String.isAsciiHex(): Boolean = isNotEmpty() && all {
        it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f'
    }

    private const val FIRST_FRAME_MARKER = -1
    private const val MAX_RESPONSE_CHARS = 65_536
}
