package com.nico.obd2dash

import java.io.IOException
import java.util.Locale

/** Décodeur commun sans client, réseau ou état Android. */
internal object DtcPayloadDecoder {
    fun parse(hexstr: String, expectedPrefix: String, isCan: Boolean, exactCanPayload: Boolean = false): List<String> {
        if (!hexstr.startsWith(expectedPrefix, ignoreCase = true) ||
            hexstr.length % 2 != 0 ||
            !hexstr.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) {
            throw IOException("Réponse DTC mal formée")
        }
        val payload = hexstr.substring(expectedPrefix.length)
        val declaredCount: Int?
        val dtcData: String
        if (isCan) {
            if (payload.length < 2) {
                throw IOException("Réponse DTC tronquée (pas de compteur): $hexstr")
            }
            declaredCount = payload.substring(0, 2).toIntOrNull(16)
                ?: throw IOException("Compteur DTC illisible: $hexstr")
            dtcData = payload.substring(2)
            if (exactCanPayload && dtcData.length != declaredCount * 4) {
                throw IOException("Longueur DTC incohérente avec le compteur")
            }
        } else {
            declaredCount = null
            dtcData = payload
            // Le service seul ne prouve pas une liste vide : en non-CAN, il faut
            // au moins une paire (éventuellement le remplissage nul documenté).
            if (dtcData.length < 4) throw IOException("Réponse DTC sans paire complète: $hexstr")
        }

        val codes = mutableListOf<String>()
        var i = 0
        var pairsRead = 0
        while (i + 4 <= dtcData.length && (declaredCount == null || pairsRead < declaredCount)) {
            val b1 = dtcData.substring(i, i + 2).toIntOrNull(16)
            val b2 = dtcData.substring(i + 2, i + 4).toIntOrNull(16)
            i += 4
            if (b1 == null || b2 == null) throw IOException("Octet DTC illisible: $hexstr")
            pairsRead++
            // En non-CAN, 0000 peut être un remplissage documenté (exemple ELM
            // "43013300000000"). En CAN, cette boucle ne parcourt que les slots
            // annoncés comme codes : un slot nul rend le compteur incohérent.
            // Le padding CAN situé après ces slots reste accepté en mode connecté.
            if (b1 == 0 && b2 == 0) {
                if (declaredCount != null) throw IOException("Compteur DTC non nul avec code vide")
                continue
            }
            codes.add(decode(b1, b2))
        }
        if (declaredCount != null) {
            // En CAN, le compteur promettait plus de codes que la trame n'en contenait
            // réellement : trame tronquée, pas "moins de défauts que prévu".
            if (pairsRead != declaredCount) {
                throw IOException("Nombre de DTC incohérent (annoncé $declaredCount, lu $pairsRead): $hexstr")
            }
        } else if (i < dtcData.length) {
            // Non-CAN : pas de compteur pour se caler dessus, donc un reste plus court
            // qu'une paire complète ne peut être qu'une trame tronquée en transmission.
            throw IOException("Trame DTC tronquée (reste incomplet): $hexstr")
        }
        return codes
    }

    fun decode(b1: Int, b2: Int): String {
        val letter = when ((b1 shr 6) and 0b11) {
            0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U"
        }
        val digit1 = (b1 shr 4) and 0b11
        val digit2 = b1 and 0b1111
        return "%s%d%X%02X".format(Locale.ROOT, letter, digit1, digit2, b2)
    }

}
