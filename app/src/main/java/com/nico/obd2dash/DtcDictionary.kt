package com.nico.obd2dash

/**
 * Descriptions des codes défaut (DTC) génériques SAE les plus courants. Volontairement
 * non exhaustif : la liste complète des codes génériques (et tous les codes propres à
 * chaque constructeur) vit dans des bases payantes, hors de portée d'un scan OBD générique.
 */
object DtcDictionary {

    private val known = mapOf(
        "P0100" to "Circuit débit d'air massique (MAF), signal absent ou hors plage",
        "P0101" to "Débit d'air massique (MAF), plage/performance",
        "P0171" to "Système trop pauvre (banc 1)",
        "P0172" to "Système trop riche (banc 1)",
        "P0174" to "Système trop pauvre (banc 2)",
        "P0175" to "Système trop riche (banc 2)",
        "P0300" to "Ratés d'allumage détectés, cylindre aléatoire/multiple",
        "P0301" to "Raté d'allumage, cylindre 1",
        "P0302" to "Raté d'allumage, cylindre 2",
        "P0303" to "Raté d'allumage, cylindre 3",
        "P0304" to "Raté d'allumage, cylindre 4",
        "P0420" to "Efficacité catalyseur sous le seuil (banc 1)",
        "P0430" to "Efficacité catalyseur sous le seuil (banc 2)",
        "P0440" to "Système EVAP, anomalie générale",
        "P0442" to "Système EVAP, petite fuite détectée",
        "P0455" to "Système EVAP, fuite importante détectée",
        "P0500" to "Capteur de vitesse véhicule",
        "P0505" to "Système de régulation de ralenti",
    )

    fun describe(code: String): String = known[code.uppercase()] ?: "Code générique SAE, non répertorié ici"
}
