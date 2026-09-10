package com.nico.obd2dash

/**
 * Réassemblage headers-on (ATH1), préparé à l'avance à partir du format documenté par
 * python-OBD (bibliothèque OBD mature, https://github.com/barracuda-fsh/pyobd) : chaque
 * ligne commence par l'ID CAN sur 3 caractères hexadécimaux (adressage 11 bits), suivi de
 * l'octet (ou des octets) PCI ISO-TP puis des données, sans espaces (ATS0 déjà actif dans
 * ce projet). Grouper les trames PAR CALCULATEUR D'ABORD, puis réassembler chaque groupe
 * indépendamment, évite la collision et le faux positif de bouclage de séquence rencontrés
 * avec le réassemblage global sans identité ECU (voir Elm327Client.reassembleHex et le
 * finding R4 de l'audit) : deux calculateurs ne voient alors jamais les numéros de
 * séquence l'un de l'autre.
 *
 * PAS ENCORE ACTIVÉ : `ATH1` reste éteint dans `Elm327Client.connect()`, ce format n'a pas
 * été vérifié sur notre sonde/véhicule (cf. `Elm327Client.probeHeaderFormat`). Si le format
 * réel diffère de l'hypothèse ci-dessous, seuls `parseCanFrame` et le décodage du PCI
 * doivent changer ; le regroupement par ECU et le suivi de séquence par calculateur
 * restent valables quel que soit le détail exact du PCI.
 */
internal object CanHeaderReassembly {

    /** Une trame décodée : calculateur d'origine et position dans sa séquence (voir [parseCanFrame]). */
    data class Frame(val ecuId: Int, val data: String, val sequenceIndex: Int?)

    /**
     * Découpe une ligne "IDPCIdonnées" (headers-on, sans espaces) en trame décodée.
     *
     * PCI ISO-TP (premier(s) caractère(s) après l'ID CAN de 3 caractères) :
     * - `0N` : trame unique, N = longueur en octets (0-7). `sequenceIndex = null`.
     * - `1XYZ` : première trame d'une séquence multi-trame (XYZ = longueur totale du
     *   message sur 12 bits, non utilisée ici : la fin réelle vient de la dernière trame
     *   de suite, pas d'un comptage d'octets). `sequenceIndex = 0` (convention de ce
     *   fichier, pas la trame ISO-TP : sert à la retrouver dans [reassembleByEcu]).
     * - `2N` : trame de suite, N = numéro de séquence ISO-TP réel, cycle 1..F puis 0..F
     *   après la première trame (jamais 0 pour la toute première trame de suite).
     */
    fun parseCanFrame(line: String): Frame? {
        if (line.length < 4) return null
        val ecuId = line.substring(0, 3).toIntOrNull(16) ?: return null
        val rest = line.substring(3)
        if (rest.isEmpty()) return null
        return when (rest[0]) {
            '0' -> {
                if (rest.length < 2) return null
                val len = rest[1].digitToIntOrNull(16) ?: return null
                val data = rest.substring(2)
                if (data.length < len * 2) return null
                Frame(ecuId, data.substring(0, len * 2), sequenceIndex = null)
            }
            '1' -> {
                if (rest.length < 4) return null
                // -1 : sentinel "première trame", distinct de 0 qui est une valeur légitime
                // du numéro de séquence réel des trames de suite (celui-ci boucle 1..F puis
                // 0..F). Les confondre faisait échouer le réassemblage sur toute séquence
                // d'au moins 16 trames de suite (la 16e a justement pour numéro réel 0).
                Frame(ecuId, rest.substring(4), sequenceIndex = FIRST_FRAME_MARKER)
            }
            '2' -> {
                if (rest.length < 2) return null
                val seq = rest[1].digitToIntOrNull(16) ?: return null
                Frame(ecuId, rest.substring(2), sequenceIndex = seq)
            }
            else -> null
        }
    }

    /**
     * Réassemble une réponse complète (potentiellement plusieurs calculateurs, chacun en
     * une ou plusieurs trames) en une chaîne hexadécimale par ECU (clé = ID CAN). L'absence
     * d'entrée pour un ECU signifie que sa séquence n'a pas pu être reconstruite (première
     * trame manquante, ou numéro de séquence inattendu) plutôt que des données fausses.
     */
    fun reassembleByEcu(response: String): Map<Int, String> {
        val lines = response.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val singles = mutableMapOf<Int, String>()
        val multiFramesByEcu = mutableMapOf<Int, MutableList<Frame>>()

        for (line in lines) {
            val frame = parseCanFrame(line) ?: continue
            if (frame.sequenceIndex == null) {
                singles[frame.ecuId] = frame.data
            } else {
                multiFramesByEcu.getOrPut(frame.ecuId) { mutableListOf() }.add(frame)
            }
        }

        val result = mutableMapOf<Int, String>()
        result.putAll(singles)
        for ((ecuId, frames) in multiFramesByEcu) {
            reassembleSequence(frames)?.let { result[ecuId] = it }
        }
        return result
    }

    private fun reassembleSequence(frames: List<Frame>): String? {
        val firstFrame = frames.firstOrNull { it.sequenceIndex == FIRST_FRAME_MARKER } ?: return null
        val sb = StringBuilder(firstFrame.data)
        var expected = 1
        for (frame in frames) {
            if (frame.sequenceIndex == FIRST_FRAME_MARKER) continue
            if (frame.sequenceIndex != expected) return null
            sb.append(frame.data)
            expected = (expected + 1) % 16
        }
        return sb.toString()
    }

    private const val FIRST_FRAME_MARKER = -1
}
