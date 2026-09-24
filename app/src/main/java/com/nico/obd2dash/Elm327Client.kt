package com.nico.obd2dash

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Où joindre l'adaptateur ELM327 : Wi-Fi (socket TCP) ou Bluetooth (RFCOMM/SPP). */
sealed class ConnectionTarget {
    data class Wifi(val host: String, val port: Int = 35000) : ConnectionTarget()
    data class Bluetooth(val device: BluetoothDevice) : ConnectionTarget()
}

/**
 * Client texte pour un adaptateur ELM327, Wi-Fi ou Bluetooth (voir [ConnectionTarget]).
 * Protocole identique dans les deux cas, c'est tout l'intérêt de ce client texte AT/OBD :
 * on envoie des commandes suivies de \r, la sonde répond en ASCII hexadécimal et termine
 * chaque réponse par '>'. Seule l'ouverture du flux ci-dessous diffère par transport ;
 * sendRaw() et tout ce qui suit ne connaissent que out/reader, pas le transport sous-jacent.
 *
 * Vérifié sur un ELM327 Wi-Fi et, depuis le 24 septembre 2026, sur un ELM327 Bluetooth
 * (captures obd_SEAT-000000_20260924_*.csv). Une échéance couvre l'échange entier, même si
 * des octets arrivent au compte-gouttes, et un gardien ferme le transport capturé à
 * l'échéance ou à l'annulation : c'est le seul moyen de débloquer une lecture sur un
 * BluetoothSocket, qui n'expose pas de SO_TIMEOUT (sans lui, un adaptateur Bluetooth
 * appairé mais muet bloquait indéfiniment le polling, constaté le 24/09).
 */
class Elm327Client internal constructor(
    private val target: ConnectionTarget,
    private val timeouts: ElmTimeouts
) {

    constructor(target: ConnectionTarget) : this(target, ElmTimeouts())

    constructor(host: String, port: Int = 35000) : this(ConnectionTarget.Wifi(host, port))

    private class Transport(val wifi: Socket? = null, val bluetooth: BluetoothSocket? = null) {
        var out: OutputStream? = null
        var reader: BufferedReader? = null
        val closed = AtomicBoolean(false)

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            // Socket.close interrompt d'abord les appels bloquants et ferme leurs flux.
            // BufferedReader.close en premier attendrait le verrou de read().
            runCatching { wifi?.close() }
            runCatching { bluetooth?.close() }
        }
    }

    private val transportLock = Any()
    @Volatile private var transport: Transport? = null
    private var initialObdExchange = true

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

    /**
     * true si l'adaptateur accepte le chiffre "nombre de réponses attendues" en fin de
     * requête mode 01 (ex: "010C1", ELM327 v1.3+, voir ELM327DS.pdf "Setting the number of
     * responses") : il rend alors la main dès la première réponse au lieu d'attendre son
     * délai ATST au cas où un autre calculateur répondrait aussi (~100-200 ms par requête,
     * cf. scan FAP du 24/09 : ~265 ms par DID sans réponse). Détecté une fois par connexion
     * (voir [detectResponseCountSupport]), faux par défaut : un clone qui ne le comprend
     * pas répond "?" et garde le comportement historique.
     */
    var supportsResponseCount: Boolean = false
        private set

    /**
     * true si le calculateur répond correctement à une requête mode 01 groupant plusieurs
     * PID (jusqu'à 6 en CAN, ISO 15765-4), vérifié à la connexion par
     * [detectMultiPidSupport]. Faux par défaut et hors CAN.
     */
    var supportsMultiPid: Boolean = false
        private set

    suspend fun connect(network: Network? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            disconnect()
            initialObdExchange = true
            detectedProtocol = null
            isCanProtocol = true
            // Capacités de l'adaptateur précédent : redétectées pour celui-ci.
            supportsResponseCount = false
            supportsMultiPid = false
            val candidate = try {
                when (val t = target) {
                    is ConnectionTarget.Wifi -> {
                        val s = Socket()
                        val connection = Transport(wifi = s)
                        synchronized(transportLock) { transport = connection }
                        // Le réseau de la sonde doit être préféré à la connexion mobile.
                        boundedBlocking(connection, timeouts.connectMs) {
                            network?.bindSocket(s)
                            // Un nom d'hôte déclenche ici une résolution DNS Java que
                            // Socket.close ne peut pas interrompre. L'échéance sera
                            // constatée à son retour ; une IP littérale évite cette limite.
                            s.connect(InetSocketAddress(t.host, t.port), timeouts.connectMs.toInt())
                        }
                        connection.out = s.getOutputStream()
                        connection.reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        connection
                    }
                    is ConnectionTarget.Bluetooth -> {
                        try {
                            val sock = t.device.createRfcommSocketToServiceRecord(SPP_UUID)
                            val connection = Transport(bluetooth = sock)
                            // Publier avant connect permet à disconnect d'interrompre
                            // cet appel Java bloquant (audit B8). Le gardien capture
                            // exactement ce socket, jamais celui d'une reconnexion.
                            synchronized(transportLock) { transport = connection }
                            boundedBlocking(connection, timeouts.connectMs) { sock.connect() }
                            connection.out = sock.outputStream
                            connection.reader = BufferedReader(InputStreamReader(sock.inputStream))
                            connection
                        } catch (e: SecurityException) {
                            throw IOException("Permission Bluetooth manquante", e)
                        }
                    }
                }
            } catch (e: Exception) {
                disconnect()
                throw e
            }

            // Toute la configuration reste sous mutex, avant le premier appelant OBD.
            try {
                exchange(candidate, "ATZ") // reset : la réponse est l'identification ELM.
                for (command in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) {
                    val reply = exchange(candidate, command)
                    val lines = reply.lineSequence().map { it.trim().uppercase(Locale.ROOT) }
                        .filter { it.isNotEmpty() && it != command }.toList()
                    if (lines != listOf("OK")) {
                        throw IOException("$command refusé par l'adaptateur : $reply")
                    }
                }
            } catch (e: Exception) {
                closeTransport(candidate)
                throw e
            }
            // ATDPN est interrogé après la découverte PID : seule la première requête
            // OBD déclenche la recherche de protocole, ATSP0 seul renverrait encore A0.
        }
    }

    /**
     * Demande à l'ELM327 le protocole qu'il a effectivement choisi (ATDPN : numéro, préfixé
     * de "A" si sélectionné automatiquement par ATSP0). Protocoles 6-9/A-C = ISO 15765-4
     * CAN ; 1-5 = SAE J1850/ISO 9141-2/ISO 14230 KWP, non-CAN. Doit être appelée après au
     * moins un échange OBD réel (cf. commentaire dans connect()), jamais juste après l'init.
     * Une réponse non reconnue ("?", vide) conserve l'hypothèse CAN ; une erreur du
     * transport remonte à l'appelant, puisque celui-ci est alors fermé.
     */
    internal suspend fun detectProtocol() {
        try {
            val raw = sendRaw("ATDPN").trim().uppercase()
            detectedProtocol = raw.ifBlank { null }
            val code = raw.removePrefix("A").firstOrNull()
            isCanProtocol = when (code) {
                '1', '2', '3', '4', '5' -> false
                '6', '7', '8', '9', 'A', 'B', 'C' -> true
                else -> true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            isCanProtocol = true
            detectedProtocol = null
            if (!isConnected) throw e
        }
    }

    fun disconnect() {
        // Fermer le socket EN PREMIER interrompt immédiatement une lecture ou une connexion
        // bloquée (reader.close() attend le même verrou interne que r.read(), donc le
        // fermer avant le socket pouvait bloquer l'appelant jusqu'au timeout de lecture).
        // Vrai aussi pour BluetoothSocket : sa documentation garantit qu'un close() depuis
        // un autre thread interrompt une opération bloquante en cours (connect() ou read(),
        // voir audit B8) : un commentaire antérieur affirmait ici à tort le contraire.
        val previous = synchronized(transportLock) {
            transport.also { transport = null }
        }
        previous?.close()
    }

    private fun closeTransport(connection: Transport) {
        synchronized(transportLock) {
            if (transport === connection) transport = null
        }
        connection.close()
    }

    val isConnected: Boolean
        get() = transport?.let { connection -> !connection.closed.get() && when (target) {
            is ConnectionTarget.Wifi -> connection.wifi?.let { it.isConnected && !it.isClosed } == true
            // BluetoothSocket.isConnected() existe depuis l'API 14, bien en dessous du
            // minSdk de ce projet (voir audit B8) : un commentaire antérieur affirmait à
            // tort son absence et se rabattait sur la seule présence de la référence, qui
            // restait vraie même après une déconnexion silencieuse côté adaptateur.
            is ConnectionTarget.Bluetooth -> runCatching { connection.bluetooth?.isConnected }.getOrNull() == true
        } } == true

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val MAX_RESPONSE_CHARS = 65_536
        /** Maximum de PID par requête mode 01 groupée (SAE J1979 / ISO 15765-4). */
        const val MAX_PIDS_PER_REQUEST = 6
    }

    /** L'annulation du gardien ferme aussi l'appel Java bloquant avant que son parent termine. */
    private suspend fun <T> boundedBlocking(
        connection: Transport,
        timeoutMs: Long,
        block: (deadlineNanos: Long) -> T
    ): T = coroutineScope {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val guard = Any()
        var active = true
        val expired = AtomicBoolean(false)
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                delay(timeoutMs)
                expired.set(true)
            } finally {
                synchronized(guard) { if (active) closeTransport(connection) }
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            val result = block(deadline)
            currentCoroutineContext().ensureActive()
            if (expired.get() || System.nanoTime() >= deadline) {
                throw SocketTimeoutException("Échéance de l'échange dépassée (${timeoutMs} ms)")
            }
            result
        } catch (e: Exception) {
            closeTransport(connection)
            currentCoroutineContext().ensureActive()
            if (expired.get() && e !is SocketTimeoutException) {
                throw SocketTimeoutException("Échéance de l'échange dépassée (${timeoutMs} ms)")
                    .apply { initCause(e) }
            }
            throw e
        } finally {
            // Désarmement avant libération du mutex : un ancien gardien ne peut pas
            // fermer le transport pendant l'échange suivant ni une reconnexion.
            synchronized(guard) { active = false }
            watchdog.cancel()
        }
    }

    /** Envoie une commande brute et retourne la réponse (sans le '>' final). */
    suspend fun sendRaw(command: String): String {
        // Une commande déjà en attente appartient à cette connexion. Une reconnexion
        // ne doit pas lui donner silencieusement accès au nouveau transport.
        val expected = transport ?: throw IOException("Non connecté")
        return withContext(Dispatchers.IO) {
            mutex.withLock { exchange(expected, command) }
        }
    }

    private suspend fun exchange(connection: Transport, command: String): String {
        require(command.isNotBlank() && '\r' !in command && '\n' !in command)
        if (transport !== connection || connection.closed.get()) throw IOException("Non connecté")
        val o = connection.out ?: throw IOException("Non connecté")
        val r = connection.reader ?: throw IOException("Non connecté")
        val upper = command.trim().uppercase(Locale.ROOT)
        val isAt = upper.startsWith("AT")
        val timeoutMs = when {
            isAt -> timeouts.atMs
            initialObdExchange -> timeouts.initialObdMs
            else -> timeouts.establishedReadMs
        }
        val sb = StringBuilder()
        val result = try {
            boundedBlocking(connection, timeoutMs) { deadline ->
                o.write("$command\r".toByteArray())
                o.flush()
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw SocketTimeoutException("Échéance de l'échange dépassée")
                    connection.wifi?.soTimeout = ((remaining + 999_999) / 1_000_000)
                        .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                    val c = r.read()
                    // Une fin de flux avant '>' signifie que la connexion a été coupée
                    // (sonde éteinte, WiFi perdu) : ce n'est pas une réponse normale, il
                    // ne faut pas la traiter comme telle sous peine de la confondre plus
                    // tard avec un résultat de lecture valide (ex: "0 défaut").
                    if (c == -1) throw IOException("Connexion perdue (fin de flux avant '>')")
                    val ch = c.toChar()
                    if (ch == '>') break
                    sb.append(ch)
                    if (sb.length > MAX_RESPONSE_CHARS) throw IOException("Réponse ELM trop longue")
                }
                sb.toString().trim()
            }
        } catch (e: IOException) {
            // Les erreurs de découverte gardent leur phase et leurs octets partiels.
            // Le contenu des autres services (VIN notamment) n'est pas ajouté au log.
            val partial = if (isAt || upper.startsWith("01")) {
                sb.toString().take(512).replace("\r", "\\r").replace("\n", "\\n")
            } else "${sb.length} caractères"
            val message = "$upper (${timeoutMs} ms) : ${e.message}; réponse partielle=$partial"
            if (e is SocketTimeoutException) throw SocketTimeoutException(message).apply { initCause(e) }
            throw IOException(message, e)
        }
        if (!isAt) initialObdExchange = false
        if (upper in setOf("ATZ", "ATD", "ATWS", "ATPC") ||
            upper.startsWith("ATSP") || upper.startsWith("ATTP")) initialObdExchange = true
        return result
    }

    /**
     * ATH0 ne permet pas d'attribuer les réponses aux calculateurs. Exige une seule
     * valeur distincte au préfixe attendu ; un conflit reste illisible au lieu de
     * retenir arbitrairement le premier répondant (notamment « zéro défaut »).
     * La découverte traite séparément l'union des bitmaps de capacités.
     */
    internal fun reassembleHex(response: String, expectedPrefix: String): String =
        HeaderlessObdResponse.parse(response).select(expectedPrefix)

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
            // withContext(NonCancellable) est nécessaire ici : sendRaw() suspend via son
            // propre withContext(Dispatchers.IO), qui est un point d'annulation. Sans ça,
            // si cette coroutine est déjà annulée (ex: un autre onglet relance un refresh
            // DTC pendant la capture), ce sendRaw("ATH0") ne s'exécuterait jamais et la
            // sonde resterait avec les headers activés, cassant tout décodage normal
            // jusqu'à la reconnexion.
            withContext(NonCancellable) { sendRaw("ATH0") }
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
        // Chiffre "1 réponse attendue" seulement si l'adaptateur l'a accepté à la connexion
        // (voir supportsResponseCount) : sans lui, la réponse arrive identique mais après le
        // délai d'attente d'éventuels autres calculateurs.
        val suffix = if (supportsResponseCount) "1" else ""
        val response = sendRaw("01$pidHex$suffix")
        val hexstr = reassembleHex(response, expectedPrefix)
        return parseHexPayload(hexstr, expectedPrefix)
    }

    /**
     * Lit plusieurs PID mode 01 en une seule requête (au plus [MAX_PIDS_PER_REQUEST], CAN
     * uniquement, voir [supportsMultiPid]) et renvoie les octets de chacun. Un PID absent
     * de la réponse (non supporté par le calculateur qui a répondu, réponse d'un autre
     * calculateur, trame illisible) est simplement absent de la map : c'est à l'appelant
     * de le relire individuellement s'il y tient (voir ObdViewModel.startPolling).
     */
    suspend fun readPidsBytes(pids: List<Int>): Map<Int, List<Int>> {
        require(pids.size in 1..MAX_PIDS_PER_REQUEST) { "1 à $MAX_PIDS_PER_REQUEST PID par requête" }
        val response = sendRaw("01" + pids.joinToString("") { "%02X".format(it) })
        return parseMultiPidResponse(response, pids.toSet())
    }

    /**
     * Décode la réponse à une requête mode 01 groupée : "41" suivi, pour chaque PID
     * supporté, de son numéro puis de ses octets (longueur fixe par PID, voir
     * [PidCatalog.dataLength]). Plusieurs calculateurs peuvent répondre chacun sur sa
     * propre ligne (headers désactivés) : toutes les lignes sont lues, la première valeur
     * trouvée pour un PID l'emporte. Un PID de longueur inconnue arrête le décodage de
     * CETTE réponse (impossible de savoir où commence le suivant) plutôt que de deviner.
     * Fonction pure, testée indépendamment.
     */
    internal fun parseMultiPidResponse(response: String, requested: Set<Int>): Map<Int, List<Int>> {
        val result = mutableMapOf<Int, List<Int>>()
        for (payload in responsePayloads(response, "41")) {
            var i = 2
            while (i + 2 <= payload.length) {
                val pid = payload.substring(i, i + 2).toIntOrNull(16) ?: break
                if (pid !in requested) break
                val len = PidCatalog.dataLength(pid) ?: break
                val end = i + 2 + len * 2
                if (end > payload.length) break
                val bytes = payload.substring(i + 2, end).chunked(2).map { it.toIntOrNull(16) }
                if (bytes.any { it == null }) break
                result.putIfAbsent(pid, bytes.filterNotNull())
                i = end
            }
        }
        return result
    }

    /**
     * Toutes les réponses exploitables commençant par [expectedPrefix] : chaque ligne
     * hexadécimale d'une seule trame (un calculateur par ligne, headers désactivés), plus
     * la séquence multi-trame recollée s'il y en a une (voir [HeaderlessObdResponse]).
     * Contrairement à [reassembleHex], qui exige une valeur unique, une réponse "rien à
     * signaler" d'un calculateur (ex: boîte de vitesses, "4300") ne masque plus les codes
     * d'un autre (voir audit du 24/09). Une séquence multi-trame incohérente (collision
     * entre deux calculateurs, trame manquante) lève plutôt que d'être ignorée en silence :
     * les lignes restantes ne représenteraient alors qu'une partie des calculateurs.
     */
    internal fun responsePayloads(response: String, expectedPrefix: String): List<String> {
        val parsed = HeaderlessObdResponse.parse(response)
        if (!parsed.isComplete) {
            throw IOException("Réponse multi-trame illisible : ${response.replace('\r', ' ').trim()}")
        }
        val prefix = expectedPrefix.uppercase(Locale.ROOT)
        return parsed.payloads.filter { payload ->
            payload.startsWith(prefix) && payload.all { it in '0'..'9' || it in 'A'..'F' }
        }
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
        if (!dataHex.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return null
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
        return discoverPidSupport().supportedPids
    }

    /** Un bitmap nul reçu est un résultat complet, distinct d'une absence de réponse. */
    suspend fun discoverPidSupport(): PidDiscoveryResult {
        val discoveryTransport = transport ?: throw IOException("Non connecté")
        val supported = mutableSetOf<Int>()
        val responses = linkedMapOf<Int, String>()
        var vehicleResponseObserved = false
        var bitmapReceived = false
        var incompleteResponseObserved = false
        var complete = false
        var base = 0x00
        while (base <= 0xE0) {
            val prefix = "41%02X".format(Locale.ROOT, base)
            val raw = sendRaw("01%02X".format(Locale.ROOT, base))
            responses[base] = raw
            val normalized = raw.replace(" ", "").replace("\t", "")
            val parsed = HeaderlessObdResponse.parse(normalized)
            val candidates = parsed.payloads.filter { it.startsWith(prefix) }
            val bitmaps = candidates.mapNotNull { parseHexPayload(it, prefix)?.takeIf { bytes -> bytes.size == 4 } }
            // Un écho résiduel "0100" peut être la première ligne hexadécimale.
            // Chercher le refus corrélé dans les lignes, pas seulement dans ce repli.
            val negativeCodes = (normalized.split('\r', '\n') + parsed.payloads)
                .map { it.trim() }
                .filter { it.matches(Regex("(?i)7F01[0-9A-F]{2}")) }
                .map { it.takeLast(2).toInt(16) }
            if (negativeCodes.isNotEmpty()) vehicleResponseObserved = true
            val temporaryCode = negativeCodes.firstOrNull {
                it == 0x78 || (it == 0x21 && (bitmaps.isEmpty() || !parsed.isComplete))
            }
            if (temporaryCode != null) {
                // En ATH0, un bitmap positif voisin ne garantit pas que le NRC78
                // provient du même ECU : une autre réponse peut encore arriver.
                // Fermer avant toute autre commande évite de l'attribuer à ATDPN.
                // La fermeture ne doit pas toucher une éventuelle reconnexion.
                closeTransport(discoveryTransport)
                throw IOException(
                    "01%02X : réponse négative temporaire (NRC %02X)"
                        .format(Locale.ROOT, base, temporaryCode)
                )
            }
            if (bitmaps.isEmpty()) break
            vehicleResponseObserved = true
            bitmapReceived = true
            // Les capacités peuvent être unies sans inventer l'identité des répondants.
            // Conserver la réserve si une autre réponse est tronquée ou négative.
            if (!parsed.isComplete || bitmaps.size != candidates.size ||
                negativeCodes.isNotEmpty() || parsed.payloads.any { it.startsWith("7F01") }) {
                incompleteResponseObserved = true
            }
            val value = bitmaps.fold(0) { union, bytes ->
                union or (bytes[0] shl 24) or (bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3]
            }
            var bankContinues = false
            for (i in 0 until 32) {
                val bit = 31 - i
                if ((value shr bit) and 1 == 1) {
                    val pid = base + i + 1
                    if (pid <= 0xFF) supported.add(pid)
                    if (pid == base + 0x20) bankContinues = true
                }
            }
            if (!bankContinues) {
                complete = !incompleteResponseObserved
                break
            }
            base += 0x20
        }
        // Appelée ici, après le(s) échange(s) "01xx" ci-dessus qui déclenchent la recherche
        // de protocole de l'ELM327 : avant ça, ATDPN renverrait une recherche non aboutie.
        // Envoyée même si aucun PID n'a été trouvé (base=0x00 déjà tenté = un échange réel).
        detectProtocol()
        return PidDiscoveryResult(supported.toSet(), vehicleResponseObserved, bitmapReceived,
            complete, responses.toMap())
    }

    /**
     * Statut MIL (voyant moteur) et nombre de DTC stockés, PID 01. PID quasi universel
     * sur tout véhicule OBD2 : une non-réponse est traitée comme une erreur de lecture,
     * pas comme "MIL éteint, 0 défaut" (ça a été une source de faux négatif silencieux).
     */
    suspend fun readMilStatus(): Pair<Boolean, Int> {
        // Jamais de chiffre "1 réponse" ici (voir readPidBytes) : chaque calculateur OBD
        // rapporte SON voyant et SES codes, il faut toutes les réponses pour les combiner.
        return parseMilStatus(sendRaw("0101"))
    }

    /**
     * Combine les réponses PID01 de tous les calculateurs (une ligne chacun, headers
     * désactivés) : voyant allumé si l'un d'eux le demande, nombre de codes = somme. Seule
     * la première ligne était lue jusqu'ici, si bien qu'une boîte de vitesses répondant
     * avant le moteur masquait son voyant. Une réponse d'une autre longueur que 4 octets
     * (SAE J1979) rend l'ensemble illisible au lieu d'être écartée : elle pourrait porter
     * le voyant. Fonction pure, testée indépendamment.
     */
    internal fun parseMilStatus(response: String): Pair<Boolean, Int> {
        val payloads = responsePayloads(response, "4101")
        if (payloads.isEmpty()) throw IOException("PID01 (statut MIL) illisible")
        payloads.firstOrNull { it.length != 12 }?.let {
            throw IOException("PID01 (statut MIL) : longueur invalide (${(it.length - 4) / 2}/4 octets)")
        }
        val statusBytes = payloads.map { it.substring(4, 6).toInt(16) }
        val status = statusBytes.any { it and 0x80 != 0 } to statusBytes.sumOf { it and 0x7F }
        // Un calculateur qui refuse (7F01) garde son voyant inconnu : jamais « éteint,
        // 0 défaut » confirmé sur cette seule base.
        if (status == (false to 0) && hasNegativeResponse(response, 0x01)) {
            throw IOException("PID01 (statut MIL) incomplet : un calculateur a refusé la requête")
        }
        return status
    }

    /**
     * Vrai si un calculateur a refusé le service [service] (réponse négative 7F, ex:
     * "7F0311"). Sans en-tête, on ne sait pas lequel : ses codes restent inconnus.
     */
    private fun hasNegativeResponse(response: String, service: Int): Boolean {
        val negative = "7F%02X".format(Locale.ROOT, service)
        return HeaderlessObdResponse.parse(response).payloads.any { it.startsWith(negative) }
    }

    suspend fun readStoredDtcs(): List<String> = readDtcs(mode = "03", expectedPrefix = "43")

    suspend fun readPendingDtcs(): List<String> = readDtcs(mode = "07", expectedPrefix = "47")

    /** Décode les DTC d'un mode donné (03=stockés, 07=en attente). Voir [parseDtcResponse]. */
    private suspend fun readDtcs(mode: String, expectedPrefix: String): List<String> =
        parseDtcResponses(sendRaw(mode), expectedPrefix, isCanProtocol)

    /**
     * Réunit les DTC de TOUS les calculateurs qui ont répondu (voir [responsePayloads]),
     * sans doublon : sans en-tête, "4300" d'une boîte de vitesses et "43010087" du moteur
     * se complètent, ils ne se contredisent pas. Seul un refus (7F) d'un calculateur
     * empêche de confirmer l'absence de défaut, ses codes restant inconnus. Fonction pure,
     * testée indépendamment.
     */
    internal fun parseDtcResponses(response: String, expectedPrefix: String, isCan: Boolean): List<String> {
        val payloads = responsePayloads(response, expectedPrefix)
        if (payloads.isEmpty()) {
            throw IOException("Réponse DTC inattendue: ${response.ifBlank { "(vide)" }}")
        }
        val codes = payloads.flatMap { parseDtcResponse(it, expectedPrefix, isCan) }.distinct()
        val service = expectedPrefix.take(2).toInt(16) - 0x40
        if (codes.isEmpty() && hasNegativeResponse(response, service)) {
            throw IOException("Diagnostic incomplet : un calculateur a refusé la requête (${response.replace('\r', ' ').trim()})")
        }
        return codes
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
    internal fun parseDtcResponse(hexstr: String, expectedPrefix: String, isCan: Boolean): List<String> =
        DtcPayloadDecoder.parse(hexstr, expectedPrefix, isCan)

    internal fun decodeDtc(b1: Int, b2: Int): String = DtcPayloadDecoder.decode(b1, b2)

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
     *
     * Le numéro de trame fait partie du préfixe attendu, pas seulement service+PID (voir
     * audit B6) : sans lui, une réponse à une AUTRE capture (même service, même PID,
     * numéro de trame différent) passait le filtre de reassembleHex/parseHexPayload et son
     * premier octet était supprimé sans jamais être comparé à celui demandé. Preuve : requête
     * 020C00, réponse 420C011AF8 (trame 01) acceptée comme si elle répondait à la trame 00
     * demandée, décodée 1726 tr/min au lieu des 3000 tr/min réels de la trame 00
     * (420C002EE0). Inclure le numéro de trame dans le préfixe fait à la fois la sélection
     * ET la vérification en un seul endroit, avec le même mécanisme déjà utilisé pour
     * service+PID ailleurs dans ce fichier.
     */
    suspend fun readFreezeFrameBytes(pid: Int, frame: Int = 0): List<Int>? {
        val pidHex = "%02X".format(pid)
        val frameHex = "%02X".format(frame)
        val expectedPrefix = "42$pidHex$frameHex"
        val response = sendRaw("02$pidHex$frameHex")
        val hexstr = reassembleHex(response, expectedPrefix)
        return parseHexPayload(hexstr, expectedPrefix)
    }

    /**
     * Code défaut qui a déclenché la capture du freeze frame (mode 02, PID 02, trame 0) :
     * sans lui, un freeze frame affiché à côté de plusieurs DTC ne dit pas auquel il se
     * rapporte. null si non supporté, illisible, ou 0000 (aucune capture).
     */
    suspend fun readFreezeFrameDtc(frame: Int = 0): String? {
        val bytes = readFreezeFrameBytes(0x02, frame) ?: return null
        if (bytes.size < 2 || (bytes[0] == 0 && bytes[1] == 0)) return null
        return decodeDtc(bytes[0], bytes[1])
    }

    /**
     * Vérifie une fois par connexion si l'adaptateur accepte le chiffre "nombre de
     * réponses" (voir [supportsResponseCount]) : "01001" doit rendre une réponse PID00
     * normale. Un clone qui ne le comprend pas répond "?" (ou autre chose) et l'option
     * reste désactivée. Ne lève jamais pour une simple réponse inattendue : seule une
     * vraie erreur de transport remonte (le socket est alors déjà fermé, voir sendRaw).
     */
    suspend fun detectResponseCountSupport() {
        supportsResponseCount = false
        val bytes = parseHexPayload(reassembleHex(sendRaw("01001"), "4100"), "4100")
        supportsResponseCount = bytes != null && bytes.size >= 4
    }

    /**
     * Vérifie une fois par connexion qu'une requête mode 01 groupée (voir
     * [supportsMultiPid]) rend bien chacun des PID demandés, sur [samplePids] (au moins
     * deux PID supportés par le véhicule). CAN uniquement : ISO 15765-4 prévoit ce
     * groupement, pas les protocoles K-Line/J1850.
     */
    suspend fun detectMultiPidSupport(samplePids: List<Int>) {
        supportsMultiPid = false
        if (!isCanProtocol || samplePids.size < 2) return
        val sample = samplePids.take(MAX_PIDS_PER_REQUEST)
        val result = runCatching { readPidsBytes(sample) }
            .onFailure { if (it is CancellationException || !isConnected) throw it }
            .getOrNull() ?: return
        supportsMultiPid = sample.all { result[it] != null }
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
        // ISO 3779 : un VIN fait toujours exactement 17 caracteres. Une chaine plus courte
        // (trame tronquee non detectee autrement, voir A3) n'est pas un VIN partiel utile :
        // c'est une identite fausse qui peut fusionner l'historique DTC de deux vehicules
        // differents (voir DtcHistoryStore).
        return vin.takeIf { it.length == 17 }
    }

    /**
     * Lit un identifiant UDS (service 0x22 ReadDataByIdentifier, ISO 14229) par curiosité
     * diagnostique : contrairement aux PID mode 01, un DID donné n'a de définition connue
     * que si le constructeur l'a documentée (ou qu'une capture l'a établie empiriquement).
     * Service standard, universel à tout calculateur UDS, quelle que soit la marque : rien
     * ici n'est spécifique à un véhicule particulier, seul le DID interrogé l'est.
     *
     * PUREMENT EN LECTURE : 0x22 ne fait que demander une valeur, il ne peut ni l'écrire
     * ni déclencher d'action (voir WriteDataByIdentifier 0x2E ou RoutineControl 0x31, tous
     * deux hors de portée de cette fonction et absents du reste du code).
     */
    suspend fun readUdsDid(did: Int): UdsProbeResult {
        val didHex = "%04X".format(did)
        val response = sendRaw("22$didHex")
        val hexstr = reassembleHex(response, "62$didHex")
        return UdsProbeResult(response, parseUdsResponse(hexstr, did))
    }

    /**
     * Fixe l'adresse destinataire des prochaines requêtes (ATSH, CAN uniquement) : par
     * défaut l'ELM327 diffuse en broadcast fonctionnel et n'importe quel ECU qui répond
     * peut se mélanger avec un autre puisque les headers restent désactivés (ATH0, voir
     * reassembleHex). Toujours suivi de [resetTargetHeader] par l'appelant, même en cas
     * d'erreur ou d'annulation (voir ObdViewModel.startFapScan).
     *
     * Vérifie la réponse plutôt que de l'ignorer (voir audit B7) : l'ELM327 répond "?"
     * quand la commande est refusée (forme incorrecte pour ce contexte, voir ELM327DS.pdf
     * p. 8-9), auquel cas le sondage continuerait en croyant une adresse ciblée qui n'a
     * jamais été appliquée.
     */
    suspend fun setTargetHeader(header: String) {
        val response = sendRaw("ATSH$header").trim().uppercase()
        if (response != "OK") {
            throw IOException("ATSH$header refusé par l'adaptateur : ${response.ifBlank { "(vide)" }}")
        }
    }

    /** Revient à la diffusion fonctionnelle standard 11 bits (7DF) après [setTargetHeader]. */
    suspend fun resetTargetHeader() {
        sendRaw("ATSH7DF")
    }

    /**
     * Décode l'enveloppe UDS standard : réponse positive (écho du service +0x40, ici 0x62,
     * suivi de l'écho du DID puis des données) ou réponse négative (0x7F, écho du service
     * demandé, code d'erreur NRC). Fonction pure, testée indépendamment.
     */
    internal fun parseUdsResponse(hexstr: String, did: Int): UdsDidResult {
        val didHex = "%04X".format(did)
        val upper = hexstr.uppercase()
        val positivePrefix = "62$didHex"
        if (upper.startsWith(positivePrefix)) {
            val dataHex = upper.substring(positivePrefix.length)
            // Longueur impaire = trame tronquée (rejetée) ; vide est valide (DID reconnu,
            // enregistrement de longueur nulle) et distinct d'une non-réponse.
            if (dataHex.length % 2 != 0) return UdsDidResult.NoResponse
            val bytes = dataHex.chunked(2).map { it.toIntOrNull(16) ?: return UdsDidResult.NoResponse }
            return UdsDidResult.Positive(bytes)
        }
        if (upper.matches(Regex("7F22[0-9A-F]{2}"))) {
            val nrc = upper.substring(4, 6).toIntOrNull(16) ?: return UdsDidResult.NoResponse
            return UdsDidResult.Negative(nrc)
        }
        return UdsDidResult.NoResponse
    }
}

/** Échéances hôte, indépendantes du délai de bus configuré dans l'ELM. */
internal data class ElmTimeouts(
    val connectMs: Long = 10_000,
    val atMs: Long = 10_000,
    val initialObdMs: Long = 45_000,
    val establishedReadMs: Long = 3_000
) {
    init {
        require(listOf(connectMs, atMs, initialObdMs, establishedReadMs)
            .all { it in 1..Int.MAX_VALUE.toLong() })
    }
}

data class PidDiscoveryResult(
    val supportedPids: Set<Int>,
    val vehicleResponseObserved: Boolean,
    val mode01BitmapReceived: Boolean,
    val isComplete: Boolean,
    val rawResponses: Map<Int, String>
)

data class ReadinessMonitor(val name: String, val ready: Boolean)

/** Résultat d'une lecture UDS ReadDataByIdentifier (voir [Elm327Client.readUdsDid]). */
sealed class UdsDidResult {
    /** Réponse positive : le DID existe et l'ECU a renvoyé ces octets (formule inconnue). */
    data class Positive(val data: List<Int>) : UdsDidResult()

    /** Réponse négative UDS (0x7F) : [nrc] est le code d'erreur (voir [nrcDescription]). */
    data class Negative(val nrc: Int) : UdsDidResult()

    /** Ni l'un ni l'autre : réponse absente, tronquée, ou non reconnue comme enveloppe UDS. */
    object NoResponse : UdsDidResult()
}

/**
 * [readUdsDid] avec la réponse brute conservée : le sens d'un DID positif reste à établir
 * après coup (voir ObdViewModel.startFapScan), la réponse brute ne doit donc jamais être
 * jetée au moment du sondage au prétexte qu'elle est déjà "interprétée" par [result].
 */
data class UdsProbeResult(val rawResponse: String, val result: UdsDidResult)

/**
 * Libellé des codes NRC (Negative Response Code) les plus courants en sondage passif,
 * pour qu'une capture s'interprète sans avoir la norme ISO 14229 sous la main. Liste non
 * exhaustive : un code absent d'ici reste affiché en hexadécimal brut, jamais deviné.
 */
fun nrcDescription(nrc: Int): String = when (nrc) {
    0x10 -> "refus général"
    0x11 -> "service non supporté"
    0x12 -> "sous-fonction non supportée"
    0x13 -> "longueur de message incorrecte"
    0x22 -> "conditions actuelles incorrectes (existe, pas lisible maintenant)"
    0x31 -> "hors plage (identifiant probablement inexistant sur cet ECU)"
    0x33 -> "accès sécurisé requis (existe, verrouillé)"
    0x78 -> "réponse en attente (l'ECU prépare une réponse plus lente)"
    else -> "code 0x%02X".format(nrc)
}
