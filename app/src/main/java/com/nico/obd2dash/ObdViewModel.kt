package com.nico.obd2dash

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Un enregistrement CSV terminé, tel que retrouvé sur le disque (pas forcément celui de la session en cours). */
data class RecordingFile(val path: String, val name: String, val sizeBytes: Long, val date: String)

/** Valeur d'une jauge avec l'instant de sa dernière lecture réussie, pour en afficher la fraîcheur. */
data class GaugeValue(val text: String, val updatedAtMs: Long)

data class ObdUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val errorMessage: String? = null,
    // Survivent à la navigation Dashboard -> DTC -> Dashboard (contrairement à un
    // `remember` local à DashboardScreen, détruit quand l'écran sort de la composition)
    // et sont persistés au moment de la connexion.
    val host: String = "192.168.0.10",
    val port: String = "35000",
    val supportedPids: Set<Int> = emptySet(),
    val values: Map<Int, GaugeValue> = emptyMap(),
    val vin: String? = null,
    val protocol: String? = null,
    // null = jamais lu avec succès (pas encore connecté, ou dernière lecture en échec) :
    // distinct de "lu et confirmé sans défaut", pour ne pas afficher un faux résultat propre.
    val milOn: Boolean? = null,
    val dtcCount: Int? = null,
    val storedDtcs: List<String>? = null,
    val pendingDtcs: List<String>? = null,
    val readiness: List<ReadinessMonitor> = emptyList(),
    val freezeFrame: Map<Int, String> = emptyMap(),
    val dtcHistory: List<DtcHistoryEntry> = emptyList(),
    val dtcLoading: Boolean = false,
    val dtcError: String? = null,
    // Diagnostic ponctuel pour préparer le fix multi-ECU (finding 3), pas une donnée
    // du véhicule. À retirer une fois ce format confirmé. Voir Elm327Client.probeHeaderFormat.
    val headerProbeResult: String? = null,
    // Enregistrement CSV à intervalle régulier (départ/arrêt manuel), pour analyse externe
    // dans la durée. N'interroge pas la sonde : échantillonne les valeurs déjà lues par le
    // polling, aucune commande supplémentaire sur le fil.
    val isRecording: Boolean = false,
    val recordingSamples: Int = 0,
    // Tous les enregistrements terminés (le disque garde tout, même après une nouvelle
    // session) : sans cette liste, seul le tout dernier fichier resterait accessible pour
    // le partage, les précédents existeraient sur le téléphone sans moyen de les retrouver.
    val recordings: List<RecordingFile> = emptyList()
)

/**
 * Texte récapitulatif exportable (partage Android standard, pas de format maison) :
 * de quoi analyser une session ailleurs que sur le téléphone. Fonction pure de l'état,
 * testable sans ViewModel ni contexte Android.
 */
internal fun buildDiagnosticReport(state: ObdUiState): String {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE).format(Date())
    val sb = StringBuilder()
    sb.appendLine("=== OBD2 Dash — export diagnostic ===")
    sb.appendLine("Date export : $date")
    sb.appendLine("VIN : ${state.vin ?: "inconnu"}")
    sb.appendLine("Protocole : ${state.protocol ?: "inconnu"}")
    sb.appendLine(
        "MIL : " + when (state.milOn) {
            true -> "allumé"
            false -> "éteint"
            null -> "non lu"
        }
    )

    sb.appendLine()
    sb.appendLine("--- DTC stockés (${state.storedDtcs?.size ?: "non lu"}) ---")
    when {
        state.storedDtcs == null -> sb.appendLine("Non lu")
        state.storedDtcs.isEmpty() -> sb.appendLine("Aucun")
        else -> state.storedDtcs.forEach { sb.appendLine("$it : ${DtcDictionary.describe(it)}") }
    }

    sb.appendLine()
    sb.appendLine("--- DTC en attente (${state.pendingDtcs?.size ?: "non lu"}) ---")
    when {
        state.pendingDtcs == null -> sb.appendLine("Non lu")
        state.pendingDtcs.isEmpty() -> sb.appendLine("Aucun")
        else -> state.pendingDtcs.forEach { sb.appendLine("$it : ${DtcDictionary.describe(it)}") }
    }

    if (state.freezeFrame.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("--- Freeze frame (au moment du défaut) ---")
        for (def in PidCatalog.defs) {
            state.freezeFrame[def.pid]?.let { sb.appendLine("${def.label} : $it") }
        }
    }

    if (state.readiness.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("--- Moniteurs de préparation ---")
        for (monitor in state.readiness) {
            sb.appendLine("${monitor.name} : ${if (monitor.ready) "Complet" else "Incomplet"}")
        }
    }

    if (state.dtcHistory.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("--- Historique sur ce véhicule ---")
        for (entry in state.dtcHistory) {
            val status = if (entry.active) "actif" else "résolu"
            sb.appendLine("${entry.code} : vu du ${entry.firstSeen} au ${entry.lastSeen}, $status")
        }
    }

    if (state.values.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("--- Valeurs live (dernière lecture) ---")
        for (def in PidCatalog.defs) {
            state.values[def.pid]?.let { sb.appendLine("${def.label} : ${it.text}") }
        }
    }

    return sb.toString()
}

// Séparateur point-virgule plutôt que virgule : convention Excel en locale française
// (déjà celle du téléphone, cf. les valeurs affichées "94,20 V") où la virgule est le
// séparateur décimal des nombres eux-mêmes. Évite d'avoir à gérer la collision entre
// virgule-séparateur-de-colonnes et virgule-décimale dans les valeurs déjà formatées.
private const val CSV_DELIMITER = ";"

internal fun csvEscape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

internal fun csvRow(fields: List<String>): String = fields.joinToString(CSV_DELIMITER) { csvEscape(it) }

class ObdViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("obd2dash", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        ObdUiState(
            host = prefs.getString(KEY_HOST, null) ?: "192.168.0.10",
            port = prefs.getString(KEY_PORT, null) ?: "35000"
        )
    )
    val state: StateFlow<ObdUiState> = _state

    private var client: Elm327Client? = null
    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var dtcJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vehicleId: String = DtcHistoryStore.UNKNOWN_VEHICLE
    private val historyStore = DtcHistoryStore(application)

    private var recordingJob: Job? = null
    private var recordingWriter: BufferedWriter? = null
    private var recordingColumns: List<PidCatalog.Def> = emptyList()

    init {
        // Les enregistrements des sessions précédentes existent déjà sur le disque au
        // lancement de l'app : visibles sans attendre un nouvel enregistrement.
        _state.update { it.copy(recordings = listRecordings()) }
    }

    private fun listRecordings(): List<RecordingFile> {
        val dir = File(getApplication<Application>().filesDir, "recordings")
        val files = dir.listFiles { f -> f.isFile && f.extension == "csv" } ?: emptyArray()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE)
        return files.sortedByDescending { it.lastModified() }.map {
            RecordingFile(
                path = it.absolutePath,
                name = it.name,
                sizeBytes = it.length(),
                date = dateFormat.format(Date(it.lastModified()))
            )
        }
    }

    fun updateHost(value: String) {
        _state.update { it.copy(host = value) }
    }

    fun updatePort(value: String) {
        _state.update { it.copy(port = value) }
    }

    fun connect(host: String, portText: String) {
        if (_state.value.connectionState == ConnectionState.CONNECTING) return

        val port = portText.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            _state.update {
                it.copy(
                    connectionState = ConnectionState.ERROR,
                    errorMessage = "Adresse IP ou port invalide (port entre 1 et 65535)."
                )
            }
            return
        }

        prefs.edit().putString(KEY_HOST, host).putString(KEY_PORT, portText).apply()
        // Repart d'un état neuf (hôte/port et enregistrements passés gardés) : sans ça, les
        // valeurs, DTC, historique etc. d'une session précédente (potentiellement un AUTRE
        // véhicule) restaient affichés sous l'identité de la nouvelle connexion jusqu'à ce
        // qu'un nouveau scan les remplace, ou pour toujours s'il échoue. Les enregistrements
        // CSV ne sont pas liés à une session : ce sont des fichiers sur le disque, pas de
        // raison de les faire disparaître de la liste au moment de se reconnecter.
        _state.update {
            ObdUiState(
                host = it.host,
                port = it.port,
                recordings = it.recordings,
                connectionState = ConnectionState.CONNECTING
            )
        }

        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            // Referme une éventuelle connexion précédente (ex: reconnexion après une
            // erreur) avant d'en ouvrir une nouvelle, pour ne pas laisser un socket
            // orphelin tourner en tâche de fond.
            pollJob?.cancel()
            dtcJob?.cancel()
            stopRecording()
            unregisterNetworkCallback()
            client?.disconnect()
            client = null

            val c = Elm327Client(host, port)
            try {
                // Le téléphone a souvent WiFi (sonde, sans Internet) + 4G actifs en même
                // temps. Android route par défaut vers le réseau qui a Internet (donc la 4G),
                // ce qui rend la sonde injoignable. On force explicitement le socket à sortir
                // par le WiFi de la sonde.
                val wifiNetwork = requestWifiNetwork()
                if (wifiNetwork == null) {
                    _state.update {
                        it.copy(
                            connectionState = ConnectionState.ERROR,
                            errorMessage = "Aucun réseau WiFi détecté après 4s (timeout). " +
                                "Vérifie que le téléphone est bien connecté au WiFi de la sonde."
                        )
                    }
                    return@launch
                }
                c.connect(wifiNetwork)
                client = c

                val supported = c.discoverSupportedPids()
                // Ne PAS envelopper dans runCatching : readVin() ne lève que si sendRaw a
                // échoué au niveau transport (timeout, coupure), auquel cas Elm327Client a
                // déjà fermé le socket en interne. Avaler cette exception ici publierait
                // "connecté" sur un client mort (le polling ne démarrerait même pas, puisque
                // c.isConnected serait déjà faux, sans qu'aucune erreur ne soit montrée).
                // Un ECU qui ne supporte simplement pas le mode 09 répond par un préfixe
                // inattendu et readVin() renvoie null normalement, sans lever.
                val vin = c.readVin()
                vehicleId = vin ?: DtcHistoryStore.UNKNOWN_VEHICLE

                // Échelles réelles du PID24 (voir PidCatalog.o2MaxRatio/o2MaxVoltage) :
                // caractéristique fixe de ce véhicule, lue une fois ici plutôt qu'à chaque
                // cycle de polling. PidCatalog est un singleton partagé entre connexions :
                // remis à l'hypothèse de repli si CE véhicule ne supporte pas PID4F, pour
                // qu'une valeur laissée par un véhicule précédent ne s'applique pas ici.
                val scaleBytes = if (0x4F in supported) c.readPidBytes(0x4F) else null
                if (scaleBytes != null && scaleBytes.size >= 2) {
                    PidCatalog.o2MaxRatio = scaleBytes[0].toDouble()
                    PidCatalog.o2MaxVoltage = scaleBytes[1].toDouble()
                } else {
                    PidCatalog.o2MaxRatio = 2.0
                    PidCatalog.o2MaxVoltage = 8.0
                }

                if (!c.isConnected) {
                    error("Connexion perdue pendant l'établissement de la session")
                }
                _state.update {
                    it.copy(
                        connectionState = ConnectionState.CONNECTED,
                        supportedPids = supported,
                        vin = vin,
                        protocol = c.detectedProtocol,
                        dtcHistory = historyStore.load(vehicleId)
                    )
                }
                startPolling(c, supported)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                c.disconnect()
                client = null
                _state.update {
                    it.copy(connectionState = ConnectionState.ERROR, errorMessage = e.message ?: "Connexion échouée")
                }
            }
        }
    }

    /** Attend jusqu'à 4s le réseau WiFi actuel (même sans accès Internet), ou null si absent. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun requestWifiNetwork(): Network? = withTimeoutOrNull(4000) {
        suspendCancellableCoroutine { cont ->
            val cm = getApplication<Application>()
                .getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Ne se désinscrit plus ici : la requête doit rester active tant que
                    // la session dure, sinon Android peut ne plus se sentir tenu de garder
                    // ce réseau WiFi disponible pour l'app. Libérée dans disconnect().
                    if (cont.isActive) cont.resume(network, onCancellation = null)
                }
            }

            networkCallback = callback
            cm.requestNetwork(request, callback)
            cont.invokeOnCancellation {
                // Là, aucun réseau n'a été obtenu (timeout) : rien à garder.
                runCatching { cm.unregisterNetworkCallback(callback) }
                networkCallback = null
            }
        }
    }

    // Le dashboard (poll live) et l'écran DTC (refresh à la demande) partagent la même
    // sonde. Le mutex dans Elm327Client empêche les réponses de se mélanger, mais un
    // clone ELM327 bon marché peut décrocher (répondre n'importe quoi) si on l'arrose de
    // deux flux de commandes en même temps sans respirer. On met donc le poll en pause
    // pendant un refresh DTC plutôt que de les laisser cogner en parallèle.
    //
    // Compteur plutôt que booléen : refreshDtcs() annule un refresh précédent avant de
    // relancer le sien (double entrée sur l'écran DTC), mais l'ancienne coroutine peut
    // rester bloquée dans une lecture socket jusqu'à 3s (timeout) avant de vraiment
    // s'arrêter. Avec un simple booléen, son "finally" pourrait remettre le drapeau à
    // faux APRÈS que la nouvelle coroutine l'ait déjà mis à vrai, réactivant le polling
    // pendant que le nouveau refresh tourne encore. Un compteur incrémenté/décrémenté
    // par chacune reste correct quel que soit l'ordre de terminaison.
    @Volatile private var dtcOperationCount = 0
    private val dtcOperationInProgress: Boolean get() = dtcOperationCount > 0

    private fun startPolling(c: Elm327Client, supported: Set<Int>) {
        val toPoll = PidCatalog.defs.filter { it.pid in supported }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (c.isConnected) {
                if (!dtcOperationInProgress) {
                    try {
                        val now = System.currentTimeMillis()
                        val newValues = mutableMapOf<Int, GaugeValue>()
                        for (def in toPoll) {
                            // Revérifié à chaque PID, pas seulement au début du cycle : un
                            // cycle déjà engagé (plusieurs PID à lire d'affilée) pourrait
                            // sinon continuer d'interroger la sonde avec les hypothèses
                            // normales pendant qu'une opération de diagnostic vient de
                            // changer la configuration de la sonde (ex: ATH1 en cours).
                            if (dtcOperationInProgress) break
                            val bytes = c.readPidBytes(def.pid)
                            if (bytes != null && bytes.size >= def.expectedBytes) {
                                runCatching { def.decode(bytes) }.getOrNull()?.let {
                                    newValues[def.pid] = GaugeValue(it, now)
                                }
                            }
                        }
                        // Fusionne plutôt que remplace : une lecture ratée ponctuelle garde
                        // la dernière valeur connue au lieu d'afficher "--" en régression.
                        if (newValues.isNotEmpty()) {
                            _state.update { it.copy(values = it.values + newValues) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _state.update {
                            it.copy(connectionState = ConnectionState.ERROR, errorMessage = e.message ?: "Lecture échouée")
                        }
                        break
                    }
                }
                delay(300)
            }
        }
    }

    /**
     * Lecture à la demande (pas de polling continu, ce sont des données de diagnostic) :
     * DTC stockés/en attente, moniteurs de préparation, et freeze frame si un DTC est présent.
     */
    fun refreshDtcs() {
        val c = client ?: return
        // Une deuxième entrée sur l'écran DTC pendant qu'un refresh tourne encore
        // annule le précédent plutôt que de les laisser cogner en parallèle sur le fil.
        dtcJob?.cancel()
        dtcJob = viewModelScope.launch {
            dtcOperationCount++
            _state.update { it.copy(dtcLoading = true, dtcError = null) }
            try {
                val (mil, count) = c.readMilStatus()
                val stored = c.readStoredDtcs()
                val pending = c.readPendingDtcs()
                val readiness = c.readReadiness() ?: emptyList()

                val freezeFrame = if (stored.isNotEmpty()) {
                    val supported = _state.value.supportedPids
                    val values = mutableMapOf<Int, String>()
                    for (def in PidCatalog.defs.filter { it.pid in supported }) {
                        val bytes = c.readFreezeFrameBytes(def.pid)
                        if (bytes != null && bytes.size >= def.expectedBytes) {
                            runCatching { def.decode(bytes) }.getOrNull()?.let { values[def.pid] = it }
                        }
                    }
                    values
                } else {
                    emptyMap()
                }

                val history = historyStore.record(vehicleId, stored)

                _state.update {
                    it.copy(
                        milOn = mil,
                        dtcCount = count,
                        storedDtcs = stored,
                        pendingDtcs = pending,
                        readiness = readiness,
                        freezeFrame = freezeFrame,
                        dtcHistory = history,
                        dtcLoading = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(dtcLoading = false, dtcError = e.message ?: "Lecture DTC échouée") }
            } finally {
                dtcOperationCount--
            }
        }
    }

    /**
     * Diagnostic ponctuel pour préparer le vrai correctif du finding 3 (multi-ECU) :
     * capture le format de réponse avec headers CAN activés sur le véhicule réellement
     * connecté, au lieu de deviner. Voir Elm327Client.probeHeaderFormat. Partage la même
     * pause du polling que refreshDtcs, pour la même raison (ne pas cogner le fil en même
     * temps que le poll live).
     */
    fun probeHeaderFormat() {
        val c = client ?: return
        dtcJob?.cancel()
        dtcJob = viewModelScope.launch {
            dtcOperationCount++
            // dtcLoading=false : ce job vient d'annuler un éventuel refreshDtcs() en cours
            // (dtcJob partagé). Son "finally" ne s'exécute jamais (coroutine annulée avant),
            // donc dtcLoading resterait bloqué à true (bouton Rafraîchir désactivé pour de
            // bon) si on ne le remettait pas ici.
            _state.update { it.copy(headerProbeResult = "Lecture...", dtcLoading = false) }
            try {
                // Calculé AVANT l'appel à update() : c.probeHeaderFormat() a des effets de
                // bord réels sur la sonde (ATH1/ATH0). Le placer à l'intérieur du bloc
                // update{} l'exposerait à être réexécuté plusieurs fois si sa comparaison
                // atomique échoue à cause d'une publication concurrente (le polling live
                // notamment), envoyant la séquence de commandes une deuxième fois.
                val result = c.probeHeaderFormat()
                _state.update { it.copy(headerProbeResult = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(headerProbeResult = "Échec: ${e.message}") }
            } finally {
                dtcOperationCount--
            }
        }
    }

    /**
     * Enregistrement CSV à intervalle régulier, démarré/arrêté manuellement (pas
     * automatique à la connexion : un test de 30s ne doit pas laisser un fichier).
     * N'envoie AUCUNE commande à la sonde : échantillonne périodiquement les valeurs déjà
     * mises à jour par [startPolling], donc aucun risque de contention avec le polling ou
     * un refresh DTC en cours. Colonnes figées au démarrage (PID supportés à cet instant).
     */
    fun startRecording() {
        if (_state.value.isRecording) return
        if (client == null) return
        val columns = PidCatalog.defs.filter { it.pid in _state.value.supportedPids }
        if (columns.isEmpty()) return

        val dir = File(getApplication<Application>().filesDir, "recordings").apply { mkdirs() }
        val fileName = "obd_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date()) + ".csv"
        val file = File(dir, fileName)
        val writer = file.bufferedWriter()
        writer.write(csvRow(listOf("Horodatage") + columns.map { it.label }))
        writer.newLine()
        writer.flush()

        recordingColumns = columns
        recordingWriter = writer
        _state.update { it.copy(isRecording = true, recordingSamples = 0) }

        recordingJob = viewModelScope.launch {
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
            while (true) {
                delay(RECORDING_INTERVAL_MS)
                val snapshot = _state.value
                val row = listOf(timestampFormat.format(Date())) +
                    recordingColumns.map { def -> snapshot.values[def.pid]?.text ?: "" }
                runCatching {
                    recordingWriter?.write(csvRow(row))
                    recordingWriter?.newLine()
                    recordingWriter?.flush()
                }
                _state.update { it.copy(recordingSamples = it.recordingSamples + 1) }
            }
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        val wasRecording = recordingWriter != null
        runCatching { recordingWriter?.close() }
        recordingWriter = null
        if (wasRecording) {
            // Le fichier qui vient de se fermer doit apparaître dans la liste tout de
            // suite, sans attendre un redémarrage de l'app.
            _state.update { it.copy(isRecording = false, recordings = listRecordings()) }
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            val cm = getApplication<Application>()
                .getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
    }

    /** Annule les tâches et libère la requête réseau ; retourne le client à fermer, s'il y en a un. */
    private fun cancelJobsAndReleaseNetwork(): Elm327Client? {
        connectJob?.cancel()
        pollJob?.cancel()
        dtcJob?.cancel()
        stopRecording()
        unregisterNetworkCallback()
        val c = client
        client = null
        return c
    }

    fun disconnect() {
        val c = cancelJobsAndReleaseNetwork()
        // Repart d'un état par défaut, mais en gardant l'hôte/port actuellement affichés
        // (sinon une déconnexion effacerait ce que l'utilisateur vient de configurer), et
        // le dernier enregistrement CSV (fichier + nombre d'échantillons) pour pouvoir
        // encore le partager après coup.
        _state.update {
            // recordings porté tel quel : cancelJobsAndReleaseNetwork() ci-dessus vient de
            // le rafraîchir via stopRecording() si un enregistrement était en cours.
            ObdUiState(host = it.host, port = it.port, recordings = it.recordings)
        }
        // Fermeture hors du thread principal : socket.close() est désormais rapide
        // (voir Elm327Client.disconnect()), mais autant ne pas en dépendre.
        if (c != null) {
            viewModelScope.launch(Dispatchers.IO) { c.disconnect() }
        }
    }

    override fun onCleared() {
        // Ne PAS passer par disconnect() : viewModelScope est déjà annulé quand onCleared()
        // est appelé (AndroidX ferme le CloseableCoroutineScope avant d'invoquer onCleared),
        // donc un viewModelScope.launch{} ici ne s'exécuterait jamais et le socket ne serait
        // jamais fermé. Fermeture directe et synchrone à la place (rapide depuis le fix de
        // l'ordre socket/reader dans Elm327Client.disconnect()).
        cancelJobsAndReleaseNetwork()?.disconnect()
    }

    companion object {
        private const val KEY_HOST = "conn_host"
        private const val KEY_PORT = "conn_port"
        // ~600 o/échantillon (~40 colonnes max) : à 5s, une session de 2h fait autour de
        // 850 Ko. Assez fin pour une analyse de tendance, sans accumuler des Mo inutiles.
        private const val RECORDING_INTERVAL_MS = 5_000L
    }
}
