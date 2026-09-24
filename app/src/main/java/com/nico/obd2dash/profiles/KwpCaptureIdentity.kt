package com.nico.obd2dash.profiles

/** Fields interpreted using the Renault STD_A layout, without confirming an ECU profile. */
data class CapturedStdAIdentity(
    val sourceAddress: Int,
    val testerAddress: Int,
    val diagnosticVersion: Int,
    val supplier: String,
    val software: String,
    val version: String
)

/**
 * Offline parser for one complete, physically addressed ATH1 KWP frame saved in response to 2180.
 *
 * The caller must establish that the saved exchange belongs to 2180 and completed. A checksum
 * cannot establish exchange ordering, the diagnostic session or compatibility with a profile.
 * This parser never sends requests, joins frames, strips adapter status text or reads a VIN.
 * Interpreting the Renault STD_A layout is an explicit choice, not universal ECU identification.
 */
object KwpCaptureIdentity {
    private const val MAX_FRAME_BYTES = 260 // Four header bytes + 255 payload bytes + checksum.

    /**
     * Returns a candidate signature. Expected addresses are checked only when supplied.
     * Throws [IllegalArgumentException] for an invalid frame, refusal or pending response.
     * Error messages do not contain the captured payload.
     */
    fun parse(
        frameHex: String,
        expectedSource: Int? = null,
        expectedDestination: Int? = null
    ): CapturedStdAIdentity {
        require(expectedSource == null || expectedSource in 0..255) {
            "L’adresse source attendue doit être un octet entre 0 et 255."
        }
        require(expectedDestination == null || expectedDestination in 0..255) {
            "L’adresse du testeur attendue doit être un octet entre 0 et 255."
        }
        val bytes = parseHex(frameHex)
        require(bytes.size >= 3) { "En-tête KWP tronqué." }
        require((bytes[0] and 0xC0) == 0x80) { "Format d’adressage KWP non pris en charge." }

        var headerLength = 3
        var payloadLength = bytes[0] and 0x3F
        if (payloadLength == 0) {
            require(bytes.size >= 4) { "En-tête KWP à longueur explicite tronqué." }
            headerLength = 4
            payloadLength = bytes[3]
            require(payloadLength > 0) { "La réponse KWP contient une charge utile vide." }
        }
        val expectedLength = headerLength + payloadLength + 1
        require(bytes.size >= expectedLength) { "Trame KWP tronquée : longueur déclarée non atteinte." }
        require(bytes.size == expectedLength) { "Octets supplémentaires après la trame KWP." }
        val checksum = bytes.dropLast(1).sum() and 0xFF
        require(bytes.last() == checksum) { "Checksum KWP incorrect." }
        require(expectedDestination == null || bytes[1] == expectedDestination) {
            "L’adresse de destination KWP ne correspond pas au testeur attendu."
        }
        require(expectedSource == null || bytes[2] == expectedSource) {
            "L’adresse source KWP ne correspond pas au calculateur attendu."
        }

        val service = bytes[headerLength]
        if (service == 0x7F) {
            require(payloadLength == 3) { "Réponse négative KWP mal formée." }
            require(bytes[headerLength + 1] == 0x21) {
                "La réponse négative concerne un autre service que la lecture 2180."
            }
            val code = bytes[headerLength + 2]
            require(code != 0x78) { "Réponse KWP en attente : aucune identité disponible." }
            throw IllegalArgumentException("Lecture d’identité KWP refusée (code ${hexByte(code)}).")
        }
        require(service == 0x61) { "La réponse KWP ne concerne pas le service de lecture 21." }
        require(payloadLength >= 2) { "Réponse positive KWP tronquée." }
        require(bytes[headerLength + 1] == 0x80) {
            "La réponse KWP ne concerne pas l’identifiant 80 attendu."
        }
        require(payloadLength >= 20) { "Réponse d’identité trop courte pour le format Renault STD_A." }
        val supplierBytes = bytes.subList(headerLength + 8, headerLength + 11)
        require(supplierBytes.all { it in 0x20..0x7E }) {
            "Le champ fournisseur STD_A doit contenir trois caractères ASCII imprimables."
        }
        return CapturedStdAIdentity(
            sourceAddress = bytes[2],
            testerAddress = bytes[1],
            diagnosticVersion = bytes[headerLength + 7],
            supplier = supplierBytes.map { it.toChar() }.joinToString(""),
            software = hexByte(bytes[headerLength + 16]) + hexByte(bytes[headerLength + 17]),
            version = hexByte(bytes[headerLength + 18]) + hexByte(bytes[headerLength + 19])
        )
    }

    private fun parseHex(frameHex: String): List<Int> {
        val text = frameHex.trim(' ', '\t')
        require(text.isNotEmpty()) { "La trame hexadécimale est vide." }
        val spaced = text.any { it == ' ' || it == '\t' }
        val bytes = ArrayList<Int>(minOf(text.length / 2, MAX_FRAME_BYTES))
        var offset = 0
        while (offset < text.length) {
            require(bytes.size < MAX_FRAME_BYTES) { "La trame KWP dépasse 260 octets." }
            require(offset + 1 < text.length) { "Octet hexadécimal incomplet." }
            bytes.add((hexDigit(text[offset]) shl 4) or hexDigit(text[offset + 1]))
            offset += 2
            if (spaced && offset < text.length) {
                require(text[offset] == ' ' || text[offset] == '\t') {
                    "Utilisez une trame compacte ou des octets hexadécimaux séparés par des espaces."
                }
                while (offset < text.length && (text[offset] == ' ' || text[offset] == '\t')) offset++
            }
        }
        return bytes
    }

    private fun hexDigit(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'A'..'F' -> char - 'A' + 10
        in 'a'..'f' -> char - 'a' + 10
        else -> throw IllegalArgumentException("Une seule trame hexadécimale est attendue, sans texte ni prompt.")
    }

    private fun hexByte(value: Int): String = value.toString(16).uppercase().padStart(2, '0')
}
