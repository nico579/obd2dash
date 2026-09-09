package com.nico.obd2dash

/**
 * Catalogue des PID mode 01 que le dashboard sait afficher, avec leur formule de
 * décodage (SAE J1979). Seuls ceux réellement supportés par le véhicule connecté
 * (cf. Elm327Client.discoverSupportedPids) sont affichés : deux véhicules peuvent
 * exposer des PID différents.
 */
object PidCatalog {

    data class Def(val pid: Int, val label: String, val decode: (List<Int>) -> String)

    /** RPM, vitesse, température moteur : affichés en priorité, en plus grand. */
    val PRIMARY_PIDS = setOf(0x0C, 0x0D, 0x05)

    val defs: List<Def> = listOf(
        Def(0x0C, "Régime moteur") { b -> "%.0f rpm".format(((b[0] * 256) + b[1]) / 4.0) },
        Def(0x0D, "Vitesse") { b -> "${b[0]} km/h" },
        Def(0x05, "Température moteur") { b -> "${b[0] - 40} °C" },
        Def(0x04, "Charge moteur") { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x10, "Débit d'air (MAF)") { b -> "%.2f g/s".format(((b[0] * 256) + b[1]) / 100.0) },
        Def(0x42, "Tension calculateur") { b -> "%.2f V".format(((b[0] * 256) + b[1]) / 1000.0) },
        Def(0x0F, "Température admission") { b -> "${b[0] - 40} °C" },
        Def(0x0B, "Pression admission") { b -> "${b[0]} kPa" },
        Def(0x11, "Position papillon") { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x2F, "Niveau carburant") { b -> "%.1f %%".format(b[0] / 2.55) },
        Def(0x46, "Température ambiante") { b -> "${b[0] - 40} °C" },
        Def(0x1F, "Temps depuis démarrage") { b -> "${(b[0] * 256) + b[1]} s" },
    )
}
