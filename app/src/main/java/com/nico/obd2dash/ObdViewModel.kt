package com.nico.obd2dash

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

data class ObdUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val errorMessage: String? = null,
    val supportedPids: Set<Int> = emptySet(),
    val values: Map<Int, String> = emptyMap(),
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

    private val _state = MutableStateFlow(ObdUiState())
    val state: StateFlow<ObdUiState> = _state

    private var client: Elm327Client? = null
    private var pollJob: Job? = null
    private val historyStore = DtcHistoryStore(application)

    fun connect(host: String, port: Int) {
        if (_state.value.connectionState == ConnectionState.CONNECTING) return
        _state.update { it.copy(connectionState = ConnectionState.CONNECTING, errorMessage = null) }

        viewModelScope.launch {
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
                _state.update {
                    it.copy(
                        connectionState = ConnectionState.CONNECTED,
                        supportedPids = supported,
                        dtcHistory = historyStore.load()
                    )
                }
                startPolling(c, supported)
            } catch (e: Exception) {
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
                    cm.unregisterNetworkCallback(this)
                    if (cont.isActive) cont.resume(network, onCancellation = null)
                }
            }

            cm.requestNetwork(request, callback)
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
    }

    // Le dashboard (poll live) et l'écran DTC (refresh à la demande) partagent la même
    // sonde. Le mutex dans Elm327Client empêche les réponses de se mélanger, mais un
    // clone ELM327 bon marché peut décrocher (répondre n'importe quoi) si on l'arrose de
    // deux flux de commandes en même temps sans respirer. On met donc le poll en pause
    // pendant un refresh DTC plutôt que de les laisser cogner en parallèle.
    @Volatile private var dtcOperationInProgress = false

    private fun startPolling(c: Elm327Client, supported: Set<Int>) {
        val toPoll = PidCatalog.defs.filter { it.pid in supported }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (c.isConnected) {
                if (!dtcOperationInProgress) {
                    try {
                        val newValues = mutableMapOf<Int, String>()
                        for (def in toPoll) {
                            val bytes = c.readPidBytes(def.pid)
                            if (bytes != null) newValues[def.pid] = def.decode(bytes)
                        }
                        // Fusionne plutôt que remplace : une lecture ratée ponctuelle garde
                        // la dernière valeur connue au lieu d'afficher "--" en régression.
                        if (newValues.isNotEmpty()) {
                            _state.update { it.copy(values = it.values + newValues) }
                        }
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
        viewModelScope.launch {
            dtcOperationInProgress = true
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
                        if (bytes != null) {
                            runCatching { def.decode(bytes) }.getOrNull()?.let { values[def.pid] = it }
                        }
                    }
                    values
                } else {
                    emptyMap()
                }

                val history = historyStore.record(stored)

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
            } catch (e: Exception) {
                _state.update { it.copy(dtcLoading = false, dtcError = e.message ?: "Lecture DTC échouée") }
            } finally {
                dtcOperationInProgress = false
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        client?.disconnect()
        client = null
        _state.update { ObdUiState() }
    }

    override fun onCleared() {
        disconnect()
    }
}
