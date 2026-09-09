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

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

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
    val dtcError: String? = null
)

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
    private var pollJob: Job? = null
    private var dtcJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vehicleId: String = DtcHistoryStore.UNKNOWN_VEHICLE
    private val historyStore = DtcHistoryStore(application)

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
        _state.update { it.copy(connectionState = ConnectionState.CONNECTING, errorMessage = null) }

        viewModelScope.launch {
            // Referme une éventuelle connexion précédente (ex: reconnexion après une
            // erreur) avant d'en ouvrir une nouvelle, pour ne pas laisser un socket
            // orphelin tourner en tâche de fond.
            pollJob?.cancel()
            dtcJob?.cancel()
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
                // Best-effort : sert à isoler l'historique DTC par véhicule, mais son
                // absence ne doit pas empêcher le reste de l'app de fonctionner.
                val vin = runCatching { c.readVin() }.getOrNull()
                vehicleId = vin ?: DtcHistoryStore.UNKNOWN_VEHICLE
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

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            val cm = getApplication<Application>()
                .getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
    }

    fun disconnect() {
        pollJob?.cancel()
        dtcJob?.cancel()
        unregisterNetworkCallback()
        val c = client
        client = null
        // Repart d'un état par défaut, mais en gardant l'hôte/port actuellement affichés
        // (sinon une déconnexion effacerait ce que l'utilisateur vient de configurer).
        _state.update { ObdUiState(host = it.host, port = it.port) }
        // Fermeture hors du thread principal : socket.close() est désormais rapide
        // (voir Elm327Client.disconnect()), mais autant ne pas en dépendre.
        if (c != null) {
            viewModelScope.launch(Dispatchers.IO) { c.disconnect() }
        }
    }

    override fun onCleared() {
        disconnect()
    }

    companion object {
        private const val KEY_HOST = "conn_host"
        private const val KEY_PORT = "conn_port"
    }
}
