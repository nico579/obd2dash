package com.nico.obd2dash

/** Décodage ATH0 du mode 09 PID 02 ; aucune requête ni identité ECU supposée. */
internal object VinPayloadDecoder {
    private const val CHARACTERS = "0123456789ABCDEFGHJKLMNPRSTUVWXYZ"

    fun decode(response: String, isCan: Boolean): String? {
        val parsed = HeaderlessObdResponse.parse(response)
        if (!parsed.isComplete || parsed.payloads.any { it.startsWith("7F09") }) return null
        val payloads = parsed.payloads.filter { it.startsWith("4902") }.distinct()
        if (payloads.isEmpty() || payloads.any { payload ->
                payload.length % 2 != 0 || payload.any { it !in '0'..'9' && it !in 'A'..'F' }
            }) return null
        val bytes = if (isCan) {
            // 49 02, un item annoncé (01), puis exactement les 17 octets du VIN.
            val payload = payloads.singleOrNull() ?: return null
            if (payload.length != 40 || !payload.startsWith("490201")) return null
            payload.drop(6).chunked(2).map { it.toInt(16) }
        } else {
            // Format non-CAN documenté par ELM327DS, p. 43 : cinq segments
            // 49 02 <index 01..05> <4 octets>, avec trois 00 initiaux de remplissage.
            // Un doublon différent pour le même index est ambigu sans adresse ECU.
            if (payloads.size != 5 || payloads.any { it.length != 14 }) return null
            val byIndex = payloads.associateBy { it.substring(4, 6).toInt(16) }
            if (byIndex.keys != (1..5).toSet()) return null
            val assembled = (1..5).flatMap { index ->
                byIndex.getValue(index).drop(6).chunked(2).map { it.toInt(16) }
            }
            if (assembled.take(3) != listOf(0, 0, 0)) return null
            assembled.drop(3)
        }
        // Ne supprimer aucun octet intrus pour fabriquer un VIN de longueur correcte.
        if (bytes.size != 17 || bytes.any { it.toChar() !in CHARACTERS }) return null
        return bytes.map { it.toChar() }.joinToString("")
    }
}
