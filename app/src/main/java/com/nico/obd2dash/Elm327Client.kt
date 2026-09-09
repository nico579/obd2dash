package com.nico.obd2dash

import android.net.Network
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
    }

    fun disconnect() {
        runCatching { reader?.close() }
        runCatching { out?.close() }
        runCatching { socket?.close() }
        socket = null
    }

    val isConnected: Boolean get() = socket?.isConnected == true

    /** Envoie une commande brute et retourne la réponse (sans le '>' final). */
    suspend fun sendRaw(command: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val o = out ?: error("Non connecté")
            val r = reader ?: error("Non connecté")

            o.write("$command\r".toByteArray())
            o.flush()

            val sb = StringBuilder()
            while (true) {
                val c = r.read()
                // Une fin de flux avant '>' signifie que la connexion a été coupée
                // (sonde éteinte, WiFi perdu) : ce n'est pas une réponse normale, il ne
                // faut pas la traiter comme telle sous peine de la confondre plus tard
                // avec un résultat de lecture valide (ex: "0 défaut").
                if (c == -1) throw IOException("Connexion perdue (fin de flux avant '>')")
                val ch = c.toChar()
                if (ch == '>') break
                sb.append(ch)
            }
            sb.toString().trim()
        }
    }

    /**
     * Recolle les réponses multi-trames ISO-TP affichées en lignes "N: <hex>" (headers
     * off mais réponse trop longue pour une seule trame CAN, ex: VIN, DTC nombreux).
     * Sinon retombe sur la première ligne purement hexadécimale de la réponse.
     */
    private fun reassembleHex(response: String): String {
        val lines = response.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val frameRegex = Regex("^([0-9A-Fa-f]):(.+)$")
        val frames = sortedMapOf<Int, String>()
        for (line in lines) {
            val m = frameRegex.find(line) ?: continue
            frames[m.groupValues[1].toInt(16)] = m.groupValues[2].trim()
        }
        if (frames.isNotEmpty()) return frames.values.joinToString("")
        return lines.firstOrNull { line -> line.all { c -> c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f' } } ?: ""
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
        val hexstr = reassembleHex(response)
        if (!hexstr.uppercase().startsWith(expectedPrefix)) return null
        val dataHex = hexstr.substring(expectedPrefix.length)
        if (dataHex.isEmpty()) return null
        return dataHex.chunked(2).mapNotNull { it.toIntOrNull(16) }
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

    /**
     * Décode les DTC d'un mode donné (03=stockés, 07=en attente).
     *
     * En CAN (ISO 15765-4), l'octet qui suit immédiatement l'écho de mode (43/47) est le
     * NOMBRE de DTC annoncés, pas le début du premier code (contrairement à K-Line/KWP2000
     * où les paires s'enchaînent directement). L'ignorer décale tout le décodage d'un octet :
     * "43 01 00 87" (1 défaut) se lisait "01 00" -> P0100 en jetant le "87", alors que c'est
     * "01"(compteur=1) puis "00 87" -> P0087. Se borner exactement à ce compteur élimine
     * aussi le padding de fin de trame (0x00 ou 0xAA selon calculateur) sans avoir besoin
     * de le deviner. Toute réponse qui ne correspond pas au préfixe attendu, ou trop
     * courte pour contenir ne serait-ce que le compteur, est une erreur de lecture et doit
     * être signalée comme telle, pas confondue avec "0 défaut confirmé".
     */
    private suspend fun readDtcs(mode: String, expectedPrefix: String): List<String> {
        val response = sendRaw(mode)
        val hexstr = reassembleHex(response)
        if (!hexstr.uppercase().startsWith(expectedPrefix)) {
            throw IOException("Réponse DTC inattendue: ${response.ifBlank { "(vide)" }}")
        }
        val payload = hexstr.substring(expectedPrefix.length)
        if (payload.length < 2) {
            throw IOException("Réponse DTC tronquée (pas de compteur): $hexstr")
        }
        val declaredCount = payload.substring(0, 2).toIntOrNull(16)
            ?: throw IOException("Compteur DTC illisible: $hexstr")
        val dtcData = payload.substring(2)

        val codes = mutableListOf<String>()
        var i = 0
        while (i + 4 <= dtcData.length && codes.size < declaredCount) {
            val b1 = dtcData.substring(i, i + 2).toIntOrNull(16)
            val b2 = dtcData.substring(i + 2, i + 4).toIntOrNull(16)
            i += 4
            if (b1 == null || b2 == null) throw IOException("Octet DTC illisible: $hexstr")
            codes.add(decodeDtc(b1, b2))
        }
        return codes
    }

    private fun decodeDtc(b1: Int, b2: Int): String {
        val letter = when ((b1 shr 6) and 0b11) {
            0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U"
        }
        val digit1 = (b1 shr 4) and 0b11
        val digit2 = b1 and 0b1111
        return "%s%d%X%02X".format(letter, digit1, digit2, b2)
    }

    private val continuousMonitors = listOf("Misfire", "Système carburant", "Composants")
    private val nonContinuousMonitors = listOf(
        "Catalyseur", "Catalyseur chauffé", "Système EVAP", "Air secondaire",
        "Climatisation (obsolète)", "Sonde O2", "Chauffage sonde O2", "EGR/VVT"
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
        val hexstr = reassembleHex(response)
        if (!hexstr.uppercase().startsWith(expectedPrefix)) return null
        var dataHex = hexstr.substring(expectedPrefix.length)
        if (dataHex.length < 2) return null
        dataHex = dataHex.substring(2) // écho du numéro de trame, pas de la donnée
        if (dataHex.isEmpty()) return null
        return dataHex.chunked(2).mapNotNull { it.toIntOrNull(16) }
    }
}

data class ReadinessMonitor(val name: String, val ready: Boolean)
