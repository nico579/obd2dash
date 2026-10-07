package com.nico.obd2dash

import java.util.Locale

/** Payloads ATH0 : aucune adresse ECU ne peut être déduite de leur ordre. */
internal data class HeaderlessObdResponse(val payloads: List<String>, val isComplete: Boolean) {
    /** Une valeur unique, jamais le premier de plusieurs résultats contradictoires. */
    fun select(expectedPrefix: String): String {
        if (!isComplete) return ""
        val prefix = expectedPrefix.uppercase(Locale.ROOT)
        val matching = payloads.filter { it.startsWith(prefix) }.distinct()
        val requestService = prefix.take(2).toIntOrNull(16)?.minus(0x40)
        val negativePrefix = requestService?.takeIf { it in 0..0x3F }
            ?.let { "7F%02X".format(Locale.ROOT, it) }
        val negatives = payloads.filter { negativePrefix != null && it.startsWith(negativePrefix) }.distinct()
        if (matching.isNotEmpty()) {
            // Un refus d'un autre répondant ne prouve pas un diagnostic complet.
            if (negatives.isNotEmpty()) return ""
            return matching.singleOrNull()?.takeIf { hex.matches(it) && it.length % 2 == 0 }.orEmpty()
        }
        // Conserver notamment le NRC UDS, même après un écho résiduel de commande.
        if (negatives.isNotEmpty()) return negatives.singleOrNull()
            ?.takeIf { hex.matches(it) && it.length == 6 }.orEmpty()
        return payloads.filter { hex.matches(it) && it.length % 2 == 0 }.distinct().singleOrNull().orEmpty()
    }

    companion object {
        private val hex = Regex("^[0-9A-F]+$")
        private val numbered = Regex("^([0-9A-F]):(.*)$")
        // Ces statuts concernent l'échange entier. Une ligne positive survivante
        // après une perte de données ou une interruption n'en prouve pas la fin.
        private val elmFailures = setOf(
            "?", "ERROR", "NODATA", "UNABLETOCONNECT", "STOPPED", "BUFFERFULL",
            "BUSBUSY", "BUSERROR", "CANERROR", "DATAERROR", "RXERROR", "FBERROR", "LVRESET",
            "LPALERT", "!LPALERT", "ACTALERT", "!ACTALERT"
        )
        private val elmInternalError = Regex("^ERR[0-9A-F]{2}$")

        /**
         * Conserve les lignes simples voisines d'une séquence ELM numérotée. Sans
         * identité, deux séquences entrelacées ne sont pas séparables : tout index
         * incohérent invalide l'échange. Une seule séquence numérotée est acceptée.
         * L'absence de longueur reste admise pour les formats déjà pris en charge.
         */
        fun parse(response: String): HeaderlessObdResponse {
            val singles = mutableListOf<String>()
            val frames = mutableListOf<String>()
            var expectedIndex = 0
            var declaredLength: Int? = null
            var complete = true
            for (rawLine in response.split('\r', '\n')) {
                val line = rawLine.trim().replace(" ", "").replace("\t", "").uppercase(Locale.ROOT)
                if (line.isEmpty()) continue
                if (line in elmFailures || elmInternalError.matches(line) || line.contains("<DATAERROR") ||
                    (line.startsWith("BUSINIT:") && line.endsWith("ERROR"))) {
                    complete = false
                    continue
                }
                val frame = numbered.matchEntire(line)
                if (frame != null) {
                    val data = frame.groupValues[2]
                    if (frame.groupValues[1].toInt(16) != expectedIndex ||
                        data.length % 2 != 0 || !hex.matches(data)) complete = false
                    frames.add(data)
                    expectedIndex = (expectedIndex + 1) % 16
                } else if (line.length == 3 && hex.matches(line)) {
                    if (declaredLength != null || frames.isNotEmpty()) complete = false
                    declaredLength = line.toInt(16)
                    if (declaredLength == 0) complete = false
                } else if (line.contains(':') &&
                    (line.substringBefore(':').length <= 2 || hex.matches(line.substringBefore(':')))) {
                    complete = false // index absent du format connu, pas une ligne à ignorer
                } else if (line.length >= 2 && hex.matches(line.take(2))) {
                    // Garder aussi un payload mal formé : il ne doit pas disparaître
                    // derrière une autre réponse valide au même service/PID.
                    singles.add(line)
                }
            }
            if (frames.isNotEmpty()) {
                val joined = frames.joinToString("")
                val expectedLength = declaredLength?.times(2)
                if (expectedLength != null && joined.length < expectedLength) complete = false
                if (complete) singles.add(if (expectedLength == null) joined else joined.take(expectedLength))
            } else if (declaredLength != null) complete = false
            return HeaderlessObdResponse(singles, complete)
        }
    }
}
