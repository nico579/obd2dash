package com.nico.obd2dash

/**
 * Catalogue des PID mode 01 que le dashboard sait afficher, avec leur formule de
 * décodage (SAE J1979). Seuls ceux réellement supportés par le véhicule connecté
 * (cf. Elm327Client.discoverSupportedPids) sont affichés : deux véhicules peuvent
 * exposer des PID différents.
 */
object PidCatalog {

    // expectedBytes : nombre d'octets que la formule lit réellement (index max + 1).
    // Une réponse plus courte (trame tronquée) est rejetée avant d'appeler decode,
    // au lieu de planter sur un accès hors limites ou de deviner une valeur fausse.
    data class Def(val pid: Int, val label: String, val expectedBytes: Int, val decode: (List<Int>) -> String)

    /** RPM, vitesse, température moteur : affichés en priorité, en plus grand. */
    val PRIMARY_PIDS = setOf(0x0C, 0x0D, 0x05)

    // Échelles réelles du PID24 (ratio/tension max), annoncées par PID4F pour LE VÉHICULE
    // CONNECTÉ. Ce ne sont pas des constantes universelles : SAE J1979-DA (table B60) exige
    // d'utiliser les maxima non nuls annoncés par PID4F quand il est supporté, faute de quoi
    // 2/8 est l'hypothèse de repli historique (pré-PID4F). Sur le véhicule de test, PID4F
    // annonce 10/10 : utiliser 2/8 sans vérifier produit une tension plausible mais fausse
    // (confirmé sur capture réelle : 7,20 V affiché contre 9,01 V correct). Mis à jour une
    // fois par connexion par ObdViewModel (ce n'est pas une mesure dynamique), remis à
    // l'hypothèse de repli si PID4F n'est pas supporté par LE PROCHAIN véhicule connecté.
    var o2MaxRatio: Double = 2.0
    var o2MaxVoltage: Double = 8.0

    // Même mécanisme PID4F (octet D) pour la pression admission (PID0B), et PID50 (octet A)
    // pour le débit d'air (PID10) : SAE J1979-DA prévoit ces octets pour les véhicules dont
    // la valeur dépasse la plage 1:1 standard (suralimentation notamment). null = aucune
    // annonce (contexte non supporté OU octet à zéro, voir A4 : un maximum nul ne veut pas
    // dire "plafonner à zéro", mais "garder la formule standard") : la formule 1:1
    // habituelle s'applique. Non nul = kPa/(g/s) réels au max de l'échelle brute (0-255 ou
    // 0-65535), remis à jour à chaque connexion comme o2MaxRatio/o2MaxVoltage ci-dessus.
    var mapMaxKpa: Double? = null
    var mafMaxGramsPerSec: Double? = null

    val defs: List<Def> = listOf(
        Def(0x0C, "Régime moteur", 2) { b -> "%.0f rpm".format(((b[0] * 256) + b[1]) / 4.0) },
        Def(0x0D, "Vitesse", 1) { b -> "${b[0]} km/h" },
        Def(0x05, "Température moteur", 1) { b -> "${b[0] - 40} °C" },
        Def(0x04, "Charge moteur", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x10, "Débit d'air (MAF)", 2) { b ->
            val raw = (b[0] * 256) + b[1]
            val max = mafMaxGramsPerSec
            val gramsPerSec = if (max != null) raw * max / 65535.0 else raw / 100.0
            "%.2f g/s".format(gramsPerSec)
        },
        Def(0x42, "Tension calculateur", 2) { b -> "%.2f V".format(((b[0] * 256) + b[1]) / 1000.0) },
        Def(0x0F, "Température admission", 1) { b -> "${b[0] - 40} °C" },
        Def(0x0B, "Pression admission", 1) { b ->
            val max = mapMaxKpa
            val kpa = if (max != null) b[0] * max / 255.0 else b[0].toDouble()
            "%.1f kPa".format(kpa)
        },
        Def(0x11, "Position papillon", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x2F, "Niveau carburant", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x46, "Température ambiante", 1) { b -> "${b[0] - 40} °C" },
        Def(0x1F, "Temps depuis démarrage", 2) { b -> "${(b[0] * 256) + b[1]} s" },

        // Le reste du catalogue standard SAE J1979 : chaque véhicule ne montrera que ce
        // qu'il annonce réellement supporter (cf. discoverSupportedPids), donc l'étendre
        // ne fait qu'augmenter ce qu'on peut afficher, sans jamais rien supposer sur un
        // véhicule en particulier. Regroupé par thème, pas par ordre de PID.

        // Régulation carburant (essence à boucle fermée, souvent absent sur diesel)
        Def(0x06, "Correction carburant CT (banc 1)", 1) { b -> "%.1f %%".format((b[0] - 128) * 100.0 / 128.0) },
        Def(0x07, "Correction carburant LT (banc 1)", 1) { b -> "%.1f %%".format((b[0] - 128) * 100.0 / 128.0) },
        Def(0x08, "Correction carburant CT (banc 2)", 1) { b -> "%.1f %%".format((b[0] - 128) * 100.0 / 128.0) },
        Def(0x09, "Correction carburant LT (banc 2)", 1) { b -> "%.1f %%".format((b[0] - 128) * 100.0 / 128.0) },
        Def(0x0A, "Pression carburant", 1) { b -> "${b[0] * 3} kPa" },
        Def(0x52, "Taux éthanol carburant", 1) { b -> "%.1f %%".format(b[0] * 100.0 / 255.0) },
        Def(0x5E, "Débit carburant moteur", 2) { b -> "%.2f L/h".format(((b[0] * 256) + b[1]) / 20.0) },

        // Sondes O2 classiques (jusqu'à 4 : 2 bancs x 2 sondes). Octet trim = 0xFF si non
        // utilisé par ce capteur (sonde à large bande, ou position sans mesure de trim).
        Def(0x14, "Sonde O2 (banc 1, sonde 1)", 2) { b -> formatO2(b) },
        Def(0x15, "Sonde O2 (banc 1, sonde 2)", 2) { b -> formatO2(b) },
        Def(0x16, "Sonde O2 (banc 2, sonde 1)", 2) { b -> formatO2(b) },
        Def(0x17, "Sonde O2 (banc 2, sonde 2)", 2) { b -> formatO2(b) },
        Def(0x24, "Sonde O2 large bande (ratio/V)", 4) { b ->
            val ratio = ((b[0] * 256) + b[1]) * o2MaxRatio / 65535.0
            val voltage = ((b[2] * 256) + b[3]) * o2MaxVoltage / 65535.0
            "%.3f · %.3f V".format(ratio, voltage)
        },
        Def(0x4F, "Ratio/tension O2 max annoncés", 4) { b -> "${b[0]} / ${b[1]}" },
        Def(0x50, "Débit d'air max annoncé (contexte)", 1) { b -> "${b[0] * 10} g/s" },

        // Allumage / injection
        Def(0x0E, "Avance à l'allumage", 1) { b -> "%.1f °".format((b[0] - 128) / 2.0) },

        // Diesel / injection directe
        Def(0x22, "Pression rail / dépression collecteur", 2) { b -> "%.1f kPa".format(((b[0] * 256) + b[1]) * 0.079) },
        Def(0x23, "Pression rail carburant (direct/diesel)", 2) { b -> "${((b[0] * 256) + b[1]) * 10} kPa" },
        Def(0x2C, "EGR commandé", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x2D, "EGR erreur", 1) { b -> "%.1f %%".format((b[0] - 128) * 100.0 / 128.0) },

        // EVAP (essence)
        Def(0x2E, "Purge EVAP commandée", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x32, "Pression vapeur EVAP", 2) { b ->
            val raw = (b[0] shl 8) or b[1]
            val signed = if (raw >= 32768) raw - 65536 else raw
            "%.2f Pa".format(signed / 4.0)
        },

        // Historique diagnostic / conditions ambiantes
        Def(0x21, "Distance parcourue MIL allumé", 2) { b -> "${(b[0] * 256) + b[1]} km" },
        Def(0x30, "Cycles depuis effacement codes", 1) { b -> "${b[0]}" },
        Def(0x31, "Distance depuis effacement codes", 2) { b -> "${(b[0] * 256) + b[1]} km" },
        Def(0x33, "Pression atmosphérique", 1) { b -> "${b[0]} kPa" },
        Def(0x5C, "Température huile moteur", 1) { b -> "${b[0] - 40} °C" },

        // Pédale / papillon (positions relatives et redondantes)
        Def(0x45, "Position papillon relative", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x49, "Position pédale accélérateur D", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x4A, "Position pédale accélérateur E", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x4C, "Commande actionneur papillon", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x5A, "Position pédale relative", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
    )

    /** Sonde O2 classique : tension (toujours présente) + trim court-terme (0xFF = absent). */
    private fun formatO2(b: List<Int>): String {
        val voltage = "%.3f V".format(b[0] / 200.0)
        if (b[1] == 0xFF) return voltage
        return voltage + " · " + "%.1f %%".format((b[1] - 128) * 100.0 / 128.0)
    }
}
