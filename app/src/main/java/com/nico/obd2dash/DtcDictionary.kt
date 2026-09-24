package com.nico.obd2dash

/**
 * Descriptions des codes défaut (DTC) génériques SAE les plus courants. Volontairement
 * non exhaustif : la liste complète des codes génériques (et tous les codes propres à
 * chaque constructeur) vit dans des bases payantes, hors de portée d'un scan OBD générique.
 *
 * Couvre essence ET diesel : la première version ne listait que des codes essence
 * (catalyseur, EVAP, ratés d'allumage), alors que le véhicule de test est un diesel à
 * rampe commune (P0087 affiché "non répertorié" le 24/09).
 */
object DtcDictionary {

    private val known = mapOf(
        // Alimentation carburant / rampe commune
        "P0087" to "Pression rampe/système carburant trop basse",
        "P0088" to "Pression rampe/système carburant trop élevée",
        "P0089" to "Régulateur de pression carburant 1, performance",
        "P0093" to "Fuite du système carburant détectée (importante)",
        "P0190" to "Capteur de pression rampe carburant A, circuit",
        "P0191" to "Capteur de pression rampe carburant A, plage/performance",
        "P0192" to "Capteur de pression rampe carburant A, signal bas",
        "P0193" to "Capteur de pression rampe carburant A, signal haut",
        "P0201" to "Injecteur cylindre 1, circuit/ouvert",
        "P0202" to "Injecteur cylindre 2, circuit/ouvert",
        "P0203" to "Injecteur cylindre 3, circuit/ouvert",
        "P0204" to "Injecteur cylindre 4, circuit/ouvert",

        // Admission / suralimentation
        "P0045" to "Électrovanne de pression de suralimentation A, circuit ouvert",
        "P0100" to "Circuit débit d'air massique (MAF), signal absent ou hors plage",
        "P0101" to "Débit d'air massique (MAF), plage/performance",
        "P0102" to "Débit d'air massique (MAF), signal bas",
        "P0103" to "Débit d'air massique (MAF), signal haut",
        "P0106" to "Pression admission (MAP), plage/performance",
        "P0107" to "Pression admission (MAP), signal bas",
        "P0108" to "Pression admission (MAP), signal haut",
        "P0110" to "Capteur de température d'air d'admission, circuit",
        "P0234" to "Suralimentation excessive (turbo A)",
        "P0299" to "Suralimentation insuffisante (turbo A)",

        // Température moteur / capteurs de position
        "P0115" to "Capteur de température moteur, circuit",
        "P0116" to "Capteur de température moteur, plage/performance",
        "P0117" to "Capteur de température moteur, signal bas",
        "P0118" to "Capteur de température moteur, signal haut",
        "P0335" to "Capteur de position vilebrequin A, circuit",
        "P0340" to "Capteur de position arbre à cames A, circuit (banc 1)",

        // Préchauffage (diesel)
        "P0380" to "Bougies de préchauffage, circuit A",
        "P0670" to "Module de préchauffage, circuit de commande",
        "P0671" to "Bougie de préchauffage cylindre 1, circuit",
        "P0672" to "Bougie de préchauffage cylindre 2, circuit",
        "P0673" to "Bougie de préchauffage cylindre 3, circuit",
        "P0674" to "Bougie de préchauffage cylindre 4, circuit",

        // EGR
        "P0400" to "Recirculation des gaz d'échappement (EGR), débit",
        "P0401" to "Recirculation des gaz d'échappement (EGR), débit insuffisant",
        "P0402" to "Recirculation des gaz d'échappement (EGR), débit excessif",
        "P0403" to "Recirculation des gaz d'échappement (EGR), circuit de commande",

        // Échappement / filtre à particules (diesel)
        "P0470" to "Capteur de pression d'échappement, circuit",
        "P0471" to "Capteur de pression d'échappement, plage/performance",
        "P0472" to "Capteur de pression d'échappement, signal bas",
        "P0544" to "Capteur de température des gaz d'échappement (banc 1, capteur 1), circuit",
        "P2002" to "Efficacité du filtre à particules sous le seuil (banc 1)",
        "P242F" to "Filtre à particules colmaté, accumulation de cendres (banc 1)",
        "P244A" to "Pression différentielle du filtre à particules trop basse (banc 1)",
        "P244B" to "Pression différentielle du filtre à particules trop élevée (banc 1)",
        "P2452" to "Capteur de pression différentielle du filtre à particules A, circuit",
        "P2453" to "Capteur de pression différentielle du filtre à particules A, plage/performance",
        "P2463" to "Filtre à particules colmaté, accumulation de suie (banc 1)",

        // Mélange / allumage (essence)
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

        // Divers
        "P0480" to "Ventilateur de refroidissement 1, circuit de commande",
        "P0500" to "Capteur de vitesse véhicule",
        "P0505" to "Système de régulation de ralenti",
        "P0562" to "Tension système trop basse",
        "P0563" to "Tension système trop élevée",
        "P0606" to "Calculateur, processeur interne",
    )

    fun describe(code: String): String {
        val upper = code.uppercase()
        known[upper]?.let { return it }
        // Classement par la structure du code (SAE J2012), pas par supposition : le
        // deuxième caractère dit qui définit le code, même quand cette liste ignore son sens.
        return when (classify(upper)) {
            DtcOrigin.GENERIC -> "Code générique SAE, non répertorié ici."
            DtcOrigin.MANUFACTURER -> "Code spécifique au constructeur, non répertorié ici."
            null -> "Code non répertorié ici : générique SAE ou spécifique au constructeur."
        }
    }

    internal enum class DtcOrigin { GENERIC, MANUFACTURER }

    /**
     * Origine d'un code d'après SAE J2012 : P0/P2 et P34-P39 génériques, P1 et P30-P33
     * constructeur ; B/C/U : 0 et 3 génériques, 1 et 2 constructeur. null si le code n'a
     * pas la forme attendue (lettre + 4 caractères hexadécimaux).
     */
    internal fun classify(code: String): DtcOrigin? {
        if (code.length != 5 || code[0] !in "PCBU" || code.substring(1).any { it !in "0123456789ABCDEF" }) return null
        val d1 = code[1]
        val d2 = code[2]
        return if (code[0] == 'P') {
            when (d1) {
                '0', '2' -> DtcOrigin.GENERIC
                '1' -> DtcOrigin.MANUFACTURER
                '3' -> if (d2 in "0123") DtcOrigin.MANUFACTURER else DtcOrigin.GENERIC
                else -> null
            }
        } else {
            when (d1) {
                '0', '3' -> DtcOrigin.GENERIC
                '1', '2' -> DtcOrigin.MANUFACTURER
                else -> null
            }
        }
    }
}
