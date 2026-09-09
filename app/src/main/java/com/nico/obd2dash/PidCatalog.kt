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

    val defs: List<Def> = listOf(
        Def(0x0C, "Régime moteur", 2) { b -> "%.0f rpm".format(((b[0] * 256) + b[1]) / 4.0) },
        Def(0x0D, "Vitesse", 1) { b -> "${b[0]} km/h" },
        Def(0x05, "Température moteur", 1) { b -> "${b[0] - 40} °C" },
        Def(0x04, "Charge moteur", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x10, "Débit d'air (MAF)", 2) { b -> "%.2f g/s".format(((b[0] * 256) + b[1]) / 100.0) },
        Def(0x42, "Tension calculateur", 2) { b -> "%.2f V".format(((b[0] * 256) + b[1]) / 1000.0) },
        Def(0x0F, "Température admission", 1) { b -> "${b[0] - 40} °C" },
        Def(0x0B, "Pression admission", 1) { b -> "${b[0]} kPa" },
        Def(0x11, "Position papillon", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x2F, "Niveau carburant", 1) { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x46, "Température ambiante", 1) { b -> "${b[0] - 40} °C" },
        Def(0x1F, "Temps depuis démarrage", 2) { b -> "${(b[0] * 256) + b[1]} s" },
    )
}
