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
            // 0000 est le remplissage de fin de trame (CAN comme non-CAN, l'exemple
            // fabricant "43013300000000" en contient), jamais un vrai code : P0000 n'est
            // assigné à aucun défaut. On compte quand même la paire (elle occupe un slot
            // du compteur CAN) mais on ne l'ajoute pas aux DTC retournés.
            if (b1 == 0 && b2 == 0) {
                // Politique prudente du rejeu ISO-TP : un slot annoncé comme code
                // mais vide ne peut pas confirmer un diagnostic sans défaut.
                if (exactCanPayload) throw IOException("Compteur DTC non nul avec code vide")
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
