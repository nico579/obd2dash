package com.nico.obd2dash

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/**
 * Décodage local du VIN (ISO 3780/SAE J853), entièrement hors ligne et indépendant de la
 * marque connectée (voir demande "solution tout véhicule") : les trois premiers
 * caractères (WMI, World Manufacturer Identifier) identifient le constructeur de façon
 * standardisée internationalement. Le 10e caractère fournit des années candidates
 * lorsque le constructeur utilise ce codage, pas une année certaine pour tout VIN.
 * Ne décode PAS le
 * modèle précis (Ibiza, Golf, Clio...) : ça reste propre à chaque constructeur (section
 * VDS, positions 4-9), sans registre public unifié fiable pour toutes les marques - voir
 * ObdViewModel/Elm327Client pour le PID mode09/04 (identifiant de calibration), plus
 * précis mais spécifique à chaque constructeur, pas une solution générique.
 */
object VinDecoder {

    data class VinInfo(
        val manufacturer: String?,
        val region: String?,
        /** Candidate la plus récente plausible, à afficher comme estimation. */
        val modelYear: Int?,
        /** Cycles depuis 1980 compatibles avec le caractère et l'année de référence. */
        val modelYearCandidates: List<Int> = emptyList(),
        val modelYearIsEstimate: Boolean = modelYear != null
    )

    // Table non exhaustive : les constructeurs les plus courants en Europe (là où ce
    // projet est utilisé, cf. les deux véhicules déjà testés) plutôt qu'un registre
    // mondial complet, que seul un service tiers à jour peut réellement maintenir. Un WMI
    // absent de cette table retombe sur la seule région (voir regionFor), pas sur "inconnu"
    // sans rien dire.
    private val KNOWN_WMI = mapOf(
        "VSS" to "SEAT",
        "TMB" to "Skoda",
        "WVW" to "Volkswagen",
        "WV1" to "Volkswagen (utilitaires)",
        "WV2" to "Volkswagen (utilitaires)",
        "WAU" to "Audi",
        "WA1" to "Audi",
        "WBA" to "BMW",
        "WBX" to "BMW",
        "WDB" to "Mercedes-Benz",
        "WDD" to "Mercedes-Benz",
        "WDC" to "Mercedes-Benz",
        "W0L" to "Opel/Vauxhall",
        "VSX" to "Opel/Vauxhall",
        "VXK" to "Opel/Vauxhall",
        "VF1" to "Renault",
        "VF3" to "Peugeot",
        "VF7" to "Citroën",
        "ZFA" to "Fiat",
        "ZAR" to "Alfa Romeo",
        "ZFF" to "Ferrari",
        "ZHW" to "Lamborghini",
        "YV1" to "Volvo",
        "JF1" to "Subaru",
        "JHM" to "Honda",
        "JM1" to "Mazda",
        "JN1" to "Nissan",
        "JT2" to "Toyota",
        "KMH" to "Hyundai",
        "KNA" to "Kia",
        "LFV" to "FAW-Volkswagen",
        "LSV" to "SAIC-Volkswagen",
        "1FA" to "Ford",
        "1G1" to "Chevrolet",
        "1G2" to "Pontiac"
    )

    // Codage cyclique de l'année-modèle : aucune conformité OBD ne permet de choisir
    // à elle seule le cycle 2010–2039. Le 7e caractère n'est pas utilisé pour lever
    // l'ambiguïté : les règles du marché nord-américain ne sont pas universelles.
    private val MODEL_YEAR_CODES: Map<Char, Int> =
        "ABCDEFGHJKLMNPRSTVWXY123456789".withIndex().associate { (index, c) -> c to (1980 + index) }

    /** Région du monde à partir du seul premier caractère (voir ISO 3780), quand le WMI complet n'est pas dans [KNOWN_WMI]. */
    private fun regionFor(first: Char): String? = when {
        first in '1'..'5' -> "Amérique du Nord"
        first in '6'..'7' -> "Océanie"
        first in '8'..'9' || first == '0' -> "Amérique du Sud"
        first in 'A'..'C' -> "Afrique"
        first == 'E' || first in 'H'..'R' -> "Asie"
        first in 'S'..'Z' -> "Europe"
        else -> null
    }

    /** VIN de 17 caractères déjà validé par [Elm327Client.readVin] (longueur/plage ASCII) attendu ; sinon renvoie une info vide plutôt que de deviner. */
    fun decode(vin: String, referenceYear: Int = GregorianCalendar().get(Calendar.YEAR)): VinInfo {
        if (vin.length != 17) return VinInfo(null, null, null)
        val upper = vin.uppercase(Locale.ROOT)
        val wmi = upper.take(3)
        val manufacturer = KNOWN_WMI[wmi]
        val region = if (manufacturer == null) regionFor(upper[0]) else null
        // Une année-modèle de l'année suivante peut déjà être commercialisée.
        // Les cycles plus lointains restent exclus, sans deviner le marché du VIN.
        val maxYear = referenceYear.toLong() + 1
        val candidates = MODEL_YEAR_CODES[upper[9]]?.let { firstYear ->
            generateSequence(firstYear.toLong()) { it + 30 }
                .takeWhile { it <= maxYear }
                .map { it.toInt() }
                .toList()
        }.orEmpty()
        return VinInfo(manufacturer, region, candidates.lastOrNull(), candidates)
    }

    /**
     * Identifiant compact pour préfixer un nom de fichier (enregistrement, sondage) : voir
     * ObdViewModel.startRecording/startFapScan - sans lui, plusieurs véhicules utilisant la
     * même appli produisent des fichiers indiscernables par leur seul nom. Constructeur si
     * connu (sinon "vehicule" générique), suivi des 6 derniers caractères du VIN (numéro de
     * série, convention déjà courante chez les garages/casses pour désigner un véhicule
     * sans réciter tout le VIN). "vehicule_inconnu" si aucun VIN n'a pu être lu (protocole
     * non-CAN encore incomplet pour ce PID, ou pas encore connecté) : ne pas confondre avec
     * un vrai identifiant, juste pour éviter un préfixe vide.
     */
    fun fileTag(vin: String?): String {
        if (vin == null || vin.length != 17) return "vehicule_inconnu"
        val manufacturer = decode(vin).manufacturer?.let(::sanitizeForFilename) ?: "vehicule"
        return "$manufacturer-${vin.takeLast(6)}"
    }

    // Un nom de constructeur peut contenir des caractères invalides ou gênants dans un nom
    // de fichier (ex: "Opel/Vauxhall" casserait un chemin, "Alfa Romeo" contient un
    // espace) : remplacés par un tiret bas plutôt que rejetés, pour rester un préfixe
    // lisible sur tous les systèmes de fichiers (y compris un partage vers un autre OS).
    private fun sanitizeForFilename(name: String): String =
        name.replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_')
}
