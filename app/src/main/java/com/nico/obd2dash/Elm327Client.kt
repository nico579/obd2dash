package com.nico.obd2dash

import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client texte pour un adaptateur ELM327 WiFi.
 * Protocole : socket TCP brut, on envoie des commandes AT/PID suivies de \r,
 * la sonde répond en ASCII hexadécimal et termine chaque réponse par '>'.
 */
class Elm327Client(private val host: String, private val port: Int = 35000) {

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var reader: BufferedReader? = null

    // L'ELM327 est un canal requête/réponse strictement séquentiel : deux appelants
    // (le polling live et l'écran DTC) ne peuvent jamais dialoguer en même temps
    // sous peine de mélanger les réponses. Ce verrou serialise tout accès au fil.
    private val mutex = Mutex()

    // Protocole effectivement sélectionné par ATSP0 (auto), détecté à la connexion.
    // Conditionne le décodage DTC (voir parseDtcResponse) : en CAN, un octet compteur
    // suit l'écho de mode ; en K-Line/KWP2000, les paires de DTC s'enchaînent directement.
    // Vrai par défaut/en cas d'échec de détection : seul cas validé sur un véhicule réel
    // à ce jour (SEAT diesel, CAN 11 bits).
    var isCanProtocol: Boolean = true
        private set
    var detectedProtocol: String? = null
        private set

    suspend fun connect(network: Network? = null) = withContext(Dispatchers.IO) {
        val s = Socket()
        // Force le socket à sortir par le WiFi de la sonde plutôt que par la 4G,
        // voir le commentaire dans ObdViewModel.requestWifiNetwork().
        network?.bindSocket(s)
        s.connect(InetSocketAddress(host, port), 5000)
        s.soTimeout = 3000
        socket = s
        out = s.getOutputStream()
        reader = BufferedReader(InputStreamReader(s.getInputStream()))

        // Séquence d'init standard ELM327
        sendRaw("ATZ")   // reset
        sendRaw("ATE0")  // echo off
        sendRaw("ATL0")  // linefeeds off
        sendRaw("ATS0")  // espaces off
        sendRaw("ATH0")  // headers off
        sendRaw("ATSP0") // protocole auto
        detectProtocol()
    }

    /**
     * Demande à l'ELM327 le protocole qu'il a effectivement choisi (ATDPN : numéro, préfixé
     * de "A" si sélectionné automatiquement par ATSP0). Protocoles 6-9/A-C = ISO 15765-4
     * CAN ; 1-5 = SAE J1850/ISO 9141-2/ISO 14230 KWP, non-CAN. Une réponse inattendue (ex:
     * ATDPN non supporté par un clone bon marché) retombe sur l'hypothèse CAN plutôt que
     * de faire échouer toute la connexion pour une commande secondaire.
     */
    private suspend fun detectProtocol() {
        try {
            val raw = sendRaw("ATDPN").trim().uppercase()
            detectedProtocol = raw.ifBlank { null }
            val code = raw.removePrefix("A").firstOrNull()
            isCanProtocol = code == null || code in "6789ABC"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            isCanProtocol = true
        }
    }

    fun disconnect() {
        // Fermer le socket EN PREMIER interrompt immédiatement une lecture bloquée
        // (reader.close() attend le même verrou interne que r.read(), donc le fermer
        // avant le socket pouvait bloquer l'appelant jusqu'au timeout de lecture).
        runCatching { socket?.close() }
        runCatching { reader?.close() }
        runCatching { out?.close() }
        socket = null
    }

    val isConnected: Boolean get() = socket?.isConnected == true

    /** Envoie une commande brute et retourne la réponse (sans le '>' final). */
    suspend fun sendRaw(command: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val o = out ?: error("Non connecté")
            val r = reader ?: error("Non connecté")

            try {
                o.write("$command\r".toByteArray())
                o.flush()

                val sb = StringBuilder()
                while (true) {
                    val c = r.read()
                    // Une fin de flux avant '>' signifie que la connexion a été coupée
                    // (sonde éteinte, WiFi perdu) : ce n'est pas une réponse normale, il
                    // ne faut pas la traiter comme telle sous peine de la confondre plus
                    // tard avec un résultat de lecture valide (ex: "0 défaut").
                    if (c == -1) throw IOException("Connexion perdue (fin de flux avant '>')")
                    val ch = c.toChar()
                    if (ch == '>') break
                    sb.append(ch)
                }
                sb.toString().trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Un timeout (soTimeout=3000) ou une coupure en cours d'échange laisse le
                // flux dans un état qu'on ne peut pas resynchroniser de façon fiable : une
                // réponse tardive à cette commande serait sinon lue comme réponse à la
                // suivante. On ferme la session plutôt que de risquer de mélanger deux
                // échanges ; toute réutilisation échouera franchement ("Non connecté").
                disconnect()
                throw e
            }
        }
    }

    /**
     * Recolle les réponses multi-trames ISO-TP affichées en lignes "N: <hex>" (headers
     * off mais réponse trop longue pour une seule trame CAN, ex: VIN, DTC nombreux).
     * Sinon retombe sur une ligne purement hexadécimale de la réponse.
     *
     * Limite connue (non résolue ici) : les headers sont désactivés (ATH0), donc rien
     * n'identifie quel calculateur a répondu quoi. Si plusieurs ECU répondent à une même
     * requête broadcast, leurs lignes/trames peuvent se mélanger. On limite les dégâts de
     * deux façons sans deviner le format headers-on (jamais vérifié sur un véhicule réel,
     * cf. [probeHeaderFormat]) :
     * - Repli une ligne : on préfère, parmi plusieurs lignes candidates, celle qui
     *   correspond au préfixe attendu plutôt que la première venue.
     * - Multi-trame : deux calculateurs qui répondraient tous deux en multi-trame
     *   produiraient des numéros de séquence qui se chevauchent avec un contenu
     *   différent. Plutôt que d'écraser silencieusement l'un par l'autre (résultat
     *   arbitraire et faux), on détecte le conflit et on renvoie une chaîne vide :
     *   l'appelant la traite comme une lecture ratée, pas comme une donnée corrompue
     *   présentée comme valide.
     */
    internal fun reassembleHex(response: String, expectedPrefix: String): String {
        val lines = response.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val frameRegex = Regex("^([0-9A-Fa-f]):(.+)$")
        val frames = sortedMapOf<Int, String>()
        for (line in lines) {
            val m = frameRegex.find(line) ?: continue
            val index = m.groupValues[1].toInt(16)
            val data = m.groupValues[2].trim()
            val existing = frames[index]
            if (existing != null && existing != data) return ""
            frames[index] = data
        }
        if (frames.isNotEmpty()) return frames.values.joinToString("")
        val hexLines = lines.filter { line -> line.all { c -> c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f' } }
        return hexLines.firstOrNull { it.uppercase().startsWith(expectedPrefix.uppercase()) }
            ?: hexLines.firstOrNull()
            ?: ""
    }

    /**
     * Diagnostic ponctuel, pas utilisé en fonctionnement normal : capture la réponse brute
     * de `0100` (toujours supporté) sans puis avec les headers CAN activés, pour connaître
     * le format exact que produit CETTE sonde sur CE véhicule avant d'adopter ATH1 pour de
     * bon dans [connect]. Remet les headers dans leur état normal (désactivés) avant de
     * retourner, quoi qu'il arrive. À supprimer une fois le format headers-on confirmé et
     * le vrai correctif multi-ECU écrit.
     */
    suspend fun probeHeaderFormat(): String {
        val withoutHeaders = sendRaw("0100")
        try {
            sendRaw("ATH1")
            val withHeaders = sendRaw("0100")
            return "Sans headers (ATH0): $withoutHeaders\nAvec headers (ATH1): $withHeaders"
        } finally {
            sendRaw("ATH0")
        }
    }

    /**
     * Interroge un PID mode 01 et retourne ses octets de données, ou null si absent.
     *
     * On ne peut pas se contenter de retirer les caractères non-hexadécimaux de la
     * réponse : un statut comme "SEARCHING..." contient déjà des lettres hexadécimales
     * valides (A, C, E) qui pollueraient l'extraction. On exige donc une ligne (ou trame
     * recollée) entièrement hexadécimale qui commence par l'écho attendu (41+PID).
     */
    suspend fun readPidBytes(pid: Int): List<Int>? {
        val pidHex = "%02X".format(pid)
        val expectedPrefix = "41$pidHex"
        val response = sendRaw("01$pidHex")
        val hexstr = reassembleHex(response, expectedPrefix)
        return parseHexPayload(hexstr, expectedPrefix)
    }

    /**
     * Vérifie le préfixe attendu et découpe le reste en octets. Fonction pure (pas
     * d'accès réseau), testée indépendamment.
     *
     * Un nombre impair de caractères signale une trame tronquée (un demi-octet ne peut
     * pas être une donnée valide) : mieux vaut rejeter que deviner un octet à partir d'un
     * seul caractère. Idem si un des groupes n'est pas de l'hexadécimal.
     */
    internal fun parseHexPayload(hexstr: String, expectedPrefix: String): List<Int>? {
        if (!hexstr.uppercase().startsWith(expectedPrefix.uppercase())) return null
        val dataHex = hexstr.substring(expectedPrefix.length)
        if (dataHex.isEmpty() || dataHex.length % 2 != 0) return null
        val bytes = dataHex.chunked(2).map { it.toIntOrNull(16) }
        if (bytes.any { it == null }) return null
        return bytes.filterNotNull()
    }

    /**
     * Découvre les PID mode 01 réellement supportés par le véhicule connecté, en
     * chaînant les bitmasks 0x00/0x20/0x40/0x60... (mécanisme standard SAE J1979).
     * Deux véhicules différents peuvent renvoyer des ensembles très différents.
     */
    suspend fun discoverSupportedPids(): Set<Int> {
        val supported = mutableSetOf<Int>()
        var base = 0x00
        while (true) {
            val bytes = readPidBytes(base) ?: break
            if (bytes.size < 4) break
            val value = (bytes[0] shl 24) or (bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3]
            var bankContinues = false
            for (i in 0 until 32) {
                val bit = 31 - i
                if ((value shr bit) and 1 == 1) {
                    val pid = base + i + 1
                    supported.add(pid)
                    if (pid == base + 0x20) bankContinues = true
                }
            }
            if (!bankContinues) break
            base += 0x20
        }
        return supported
    }

    /**
     * Statut MIL (voyant moteur) et nombre de DTC stockés, PID 01. PID quasi universel
     * sur tout véhicule OBD2 : une non-réponse est traitée comme une erreur de lecture,
     * pas comme "MIL éteint, 0 défaut" (ça a été une source de faux négatif silencieux).
     */
    suspend fun readMilStatus(): Pair<Boolean, Int> {
        val bytes = readPidBytes(0x01) ?: throw IOException("PID01 (statut MIL) illisible")
        val a = bytes.getOrNull(0) ?: throw IOException("PID01 (statut MIL) tronqué")
        return (a and 0x80 != 0) to (a and 0x7F)
    }

    suspend fun readStoredDtcs(): List<String> = readDtcs(mode = "03", expectedPrefix = "43")

    suspend fun readPendingDtcs(): List<String> = readDtcs(mode = "07", expectedPrefix = "47")

    /** Décode les DTC d'un mode donné (03=stockés, 07=en attente). Voir [parseDtcResponse]. */
    private suspend fun readDtcs(mode: String, expectedPrefix: String): List<String> {
        val response = sendRaw(mode)
        val hexstr = reassembleHex(response, expectedPrefix)
        if (!hexstr.uppercase().startsWith(expectedPrefix)) {
            throw IOException("Réponse DTC inattendue: ${response.ifBlank { "(vide)" }}")
        }
        return parseDtcResponse(hexstr, expectedPrefix, isCanProtocol)
    }

    /**
     * Extrait les DTC d'une réponse déjà reformée en une seule chaîne hexadécimale
     * commençant par le préfixe attendu (vérifié par l'appelant). Fonction pure (pas
     * d'accès réseau), testée indépendamment avec les captures de l'audit.
     *
     * Le format dépend du protocole, d'où le paramètre `isCan` (voir [detectProtocol]) :
     * - **CAN** (ISO 15765-4) : l'octet qui suit l'écho de mode (43/47) est le NOMBRE de
     *   DTC annoncés, pas le début du premier code. L'ignorer décale tout le décodage d'un
     *   octet : "43 01 00 87" (1 défaut) se lisait "01 00" -> P0100 en jetant le "87",
     *   alors que c'est "01"(compteur=1) puis "00 87" -> P0087. Se borner exactement à ce
     *   compteur élimine aussi le padding de fin de trame CAN (0x00/0xAA) sans le deviner.
     * - **K-Line/KWP2000** (ISO 9141-2, ISO 14230) : pas de compteur, les paires de DTC
     *   s'enchaînent directement après l'écho de mode ; la trame se termine naturellement
     *   avec le dernier DTC (contrairement au CAN, ces protocoles n'imposent pas un cadre
     *   fixe de 8 octets à remplir de padding).
     *
     * Dans les deux cas, une réponse trop courte pour contenir un DTC complet, ou (en CAN)
     * pour contenir ne serait-ce que le compteur, est une erreur de lecture et doit être
     * signalée comme telle, pas confondue avec "0 défaut confirmé".
     */
    internal fun parseDtcResponse(hexstr: String, expectedPrefix: String, isCan: Boolean): List<String> {
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
        } else {
            declaredCount = null
            dtcData = payload
        }

        val codes = mutableListOf<String>()
        var i = 0
        while (i + 4 <= dtcData.length && (declaredCount == null || codes.size < declaredCount)) {
            val b1 = dtcData.substring(i, i + 2).toIntOrNull(16)
            val b2 = dtcData.substring(i + 2, i + 4).toIntOrNull(16)
            i += 4
            if (b1 == null || b2 == null) throw IOException("Octet DTC illisible: $hexstr")
            codes.add(decodeDtc(b1, b2))
        }
        if (declaredCount != null) {
            // En CAN, le compteur promettait plus de codes que la trame n'en contenait
            // réellement : trame tronquée, pas "moins de défauts que prévu".
            if (codes.size != declaredCount) {
                throw IOException("Nombre de DTC incohérent (annoncé $declaredCount, lu ${codes.size}): $hexstr")
            }
        } else if (i < dtcData.length) {
            // Non-CAN : pas de compteur pour se caler dessus, donc un reste plus court
            // qu'une paire complète ne peut être qu'une trame tronquée en transmission.
            throw IOException("Trame DTC tronquée (reste incomplet): $hexstr")
        }
        return codes
    }

    internal fun decodeDtc(b1: Int, b2: Int): String {
        val letter = when ((b1 shr 6) and 0b11) {
            0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U"
        }
        val digit1 = (b1 shr 4) and 0b11
        val digit2 = b1 and 0b1111
        return "%s%d%X%02X".format(letter, digit1, digit2, b2)
    }

    private val continuousMonitors = listOf("Misfire", "Système carburant", "Composants")

    // SAE J1979 définit deux tables différentes pour les 8 moniteurs non-continus selon le
    // type de moteur (bit 3 de l'octet B, cf. readReadiness) : les positions de bits ne
    // désignent pas les mêmes moniteurs en essence et en diesel.
    private val nonContinuousMonitorsSpark = listOf(
        "Catalyseur", "Catalyseur chauffé", "Système EVAP", "Air secondaire",
        "Climatisation (obsolète)", "Sonde O2", "Chauffage sonde O2", "EGR/VVT"
    )
    private val nonContinuousMonitorsCompression = listOf(
        "Catalyseur NMHC", "NOx/SCR", "(réservé)", "Suralimentation",
        "(réservé)", "Capteur gaz d'échappement", "Filtre à particules", "EGR/VVT"
    )

    /**
     * Décode les moniteurs de préparation (readiness) à partir de PID 01. Ne renvoie
     * que les moniteurs marqués supportés par ce véhicule (deux véhicules diffèrent).
     */
    suspend fun readReadiness(): List<ReadinessMonitor>? {
        val bytes = readPidBytes(0x01) ?: return null
        if (bytes.size < 4) return null
        val byteB = bytes[1]
        val byteC = bytes[2]
        val byteD = bytes[3]

        // Bit 3 de l'octet B : 0 = allumage commandé (essence), 1 = allumage par
        // compression (diesel). Détermine quelle table de moniteurs non-continus
        // s'applique aux octets C/D (mêmes positions de bits, moniteurs différents).
        val isCompressionIgnition = (byteB shr 3) and 1 == 1
        val nonContinuousMonitors = if (isCompressionIgnition) {
            nonContinuousMonitorsCompression
        } else {
            nonContinuousMonitorsSpark
        }

        val result = mutableListOf<ReadinessMonitor>()
        for (i in continuousMonitors.indices) {
            if ((byteB shr i) and 1 == 1) {
                val notReady = (byteB shr (i + 4)) and 1 == 1
                result.add(ReadinessMonitor(continuousMonitors[i], ready = !notReady))
            }
        }
        for (i in nonContinuousMonitors.indices) {
            if ((byteC shr i) and 1 == 1) {
                val notReady = (byteD shr i) and 1 == 1
                result.add(ReadinessMonitor(nonContinuousMonitors[i], ready = !notReady))
            }
        }
        return result
    }

    /**
     * Freeze frame (mode 02) : conditions capturées par le calculateur au moment où le
     * DTC s'est déclenché. Contrairement au mode 01, la réponse commence par un octet
     * d'écho du numéro de trame avant la donnée réelle (trouvaille de la session de scan).
     */
    suspend fun readFreezeFrameBytes(pid: Int, frame: Int = 0): List<Int>? {
        val pidHex = "%02X".format(pid)
        val frameHex = "%02X".format(frame)
        val expectedPrefix = "42$pidHex"
        val response = sendRaw("02$pidHex$frameHex")
        val hexstr = reassembleHex(response, expectedPrefix)
        val bytes = parseHexPayload(hexstr, expectedPrefix) ?: return null
        return bytes.drop(1).ifEmpty { null } // 1er octet = écho du numéro de trame, pas la donnée
    }

    /**
     * VIN du véhicule (mode 09, PID 02) : sert à distinguer l'historique DTC d'un
     * véhicule à l'autre. Réponse multi-trames (17 caractères ASCII ne tiennent pas dans
     * une seule trame CAN), réassemblée par reassembleHex. Un octet "nombre d'items"
     * (toujours 1 en pratique) précède les 17 octets ASCII du VIN.
     */
    suspend fun readVin(): String? {
        val expectedPrefix = "4902"
        val response = sendRaw("0902")
        val hexstr = reassembleHex(response, expectedPrefix)
        val bytes = parseHexPayload(hexstr, expectedPrefix) ?: return null
        val vinBytes = bytes.drop(1) // octet "nombre d'items"
        val vin = vinBytes.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
        return vin.ifBlank { null }
    }
}

data class ReadinessMonitor(val name: String, val ready: Boolean)
