package com.nico.obd2dash

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Transport vers la sonde ELM327 : Wi-Fi (IP/port) ou Bluetooth (appareil appairé). Persisté pour la reconnexion automatique au lancement (voir ObdViewModel.init). */
enum class ConnectionMode { WIFI, BLUETOOTH }

/** Comment un sondage FAP s'est terminé, pour l'afficher clairement (voir audit, "Ergonomie du sondage") : null = jamais lancé cette session, ou encore en cours. */
enum class FapScanOutcome { TERMINE, INTERROMPU, ERREUR }

enum class AutoTestStatus { EN_ATTENTE, EN_COURS, OK, ATTENTION, ECHEC }

/** Une étape du smoke test automatique (voir ObdViewModel.runAutoTest) : [detail] explique le verdict, jamais vide sur OK/ATTENTION/ECHEC. */
data class AutoTestCheck(val name: String, val status: AutoTestStatus, val detail: String? = null)

/** Un enregistrement CSV terminé, tel que retrouvé sur le disque (pas forcément celui de la session en cours). */
data class RecordingFile(val path: String, val name: String, val sizeBytes: Long, val date: String)

/** Valeur d'une jauge avec l'instant de sa dernière lecture réussie, pour en afficher la fraîcheur. */
data class GaugeValue(val text: String, val updatedAtMs: Long)

// Au-delà de cet âge, une valeur n'est plus assez fraîche pour être présentée comme
// l'état actuel du véhicule : le Dashboard la masque ("--"), et l'enregistrement CSV
// laisse la cellule vide plutôt que de répéter une lecture périmée comme si elle était
// nouvelle (voir A1 : 954 lignes identiques observées faute de ce contrôle).
internal const val VALUE_UNAVAILABLE_AFTER_MS = 10_000L

data class ObdUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val errorMessage: String? = null,
    // Survivent à la navigation Dashboard -> DTC -> Dashboard (contrairement à un
    // `remember` local à DashboardScreen, détruit quand l'écran sort de la composition)
    // et sont persistés au moment de la connexion.
    val host: String = "192.168.0.10",
    val port: String = "35000",
    val connectionMode: ConnectionMode = ConnectionMode.WIFI,
    // Nom d'affichage du dernier appareil Bluetooth utilisé/sélectionné (voir
    // ObdViewModel.connectBluetooth) ; bondedDevices n'est rafraîchie qu'à la demande
    // (ouverture du sélecteur), pas un flux continu de l'état du Bluetooth système.
    val bluetoothDeviceName: String? = null,
    val bondedBluetoothDevices: List<BluetoothDevice> = emptyList(),
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
    // Age de storedDtcs/pendingDtcs/readiness/freezeFrame ci-dessus : sans cette date, un
    // ancien résultat encore affiché après un refresh en échec (dtcError non-null) se
    // confond avec une lecture actuelle dans l'export (voir A6). null = jamais lu avec
    // succès depuis le lancement de l'app.
    val dtcLastSuccessAtMs: Long? = null,
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
    val recordings: List<RecordingFile> = emptyList(),
    // Sondage UDS (service 0x22 ReadDataByIdentifier) en lecture seule sur une plage
    // d'identifiants : aucun DID n'ayant de définition publique connue pour ce calculateur,
    // il faut interroger le véhicule empiriquement et consigner chaque réponse pour analyse
    // ultérieure. Générique à tout ECU compatible UDS (rien de spécifique à une marque).
    val isFapScanning: Boolean = false,
    val fapScanDone: Int = 0,
    val fapScanTotal: Int = 0,
    val fapScanCurrentDid: Int? = null,
    val fapScanPositives: List<String> = emptyList(),
    val fapScanError: String? = null,
    val fapScanOutcome: FapScanOutcome? = null,
    val probes: List<RecordingFile> = emptyList(),
    // Smoke test automatique : exécute une petite séquence de vraies lectures/écritures
    // contre le véhicule réellement connecté et rapporte OK/ATTENTION/ECHEC par étape, pour
    // couvrir mécaniquement ce qu'un humain vérifierait autrement à la main (voir la
    // checklist "en voiture"). Ne remplace pas les tests qui demandent une action physique
    // (couper le contact, éteindre l'écran) : ceux-là restent sur la checklist.
    val isAutoTesting: Boolean = false,
    val autoTestChecks: List<AutoTestCheck> = emptyList()
)

/**
 * Texte récapitulatif exportable (partage Android standard, pas de format maison) :
 * de quoi analyser une session ailleurs que sur le téléphone. Fonction pure de l'état,
 * testable sans ViewModel ni contexte Android.
 */
internal fun buildDiagnosticReport(state: ObdUiState): String {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE).format(Date())
    val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
    val sb = StringBuilder()
    sb.appendLine("=== OBD2 Dash — export diagnostic ===")
    sb.appendLine("Date export : $date")
    sb.appendLine(
        "Connexion : " + when (state.connectionState) {
            ConnectionState.CONNECTED -> "connectée"
            ConnectionState.CONNECTING -> "connexion en cours"
            ConnectionState.DISCONNECTED -> "déconnectée"
            ConnectionState.ERROR -> "en erreur" + (state.errorMessage?.let { " : $it" } ?: "")
        }
    )
    sb.appendLine("VIN : ${state.vin ?: "inconnu"}")
    sb.appendLine("Protocole : ${state.protocol ?: "inconnu"}")
    sb.appendLine(
        "MIL : " + when (state.milOn) {
            true -> "allumé"
            false -> "éteint"
            null -> "non lu"
        }
    )

    // Les sections DTC/readiness/freeze frame ci-dessous datent de cette dernière lecture
    // réussie, pas de la date d'export ci-dessus : sans cette ligne, un ancien résultat
    // encore affiché après un refresh en échec se lisait comme une lecture actuelle
    // (voir A6). dtcError est celle de la tentative la PLUS RÉCENTE, potentiellement
    // postérieure à ce succès : les deux peuvent cohabiter (échec après un succès passé).
    sb.appendLine(
        "Dernière lecture DTC réussie : " +
            (state.dtcLastSuccessAtMs?.let { timestampFormat.format(Date(it)) } ?: "jamais")
    )
    if (state.dtcLoading) sb.appendLine("Lecture DTC en cours au moment de cet export.")
    state.dtcError?.let { sb.appendLine("Dernière tentative de lecture DTC en échec : $it") }

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
            // "Résolu" affirmait une panne réparée ; la seule preuve disponible est son
            // absence de la dernière liste de DTC stockés lue avec succès (voir audit,
            // terminologie historique).
            val status = if (entry.active) "actif" else "non retrouvé à la dernière lecture"
            sb.appendLine("${entry.code} : vu du ${entry.firstSeen} au ${entry.lastSeen}, $status")
        }
    }

    if (state.values.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("--- Valeurs live (dernière lecture) ---")
        val nowMs = System.currentTimeMillis()
        for (def in PidCatalog.defs) {
            state.values[def.pid]?.let {
                // Marqueur de péremption (voir A6) : sans lui, une valeur figée depuis la
                // dernière lecture réussie du polling (connexion perdue, ou pause pendant
                // un sondage/diagnostic) se lit comme la mesure actuelle du véhicule.
                // CONTEXT_ONLY_PIDS exclus : lus une seule fois à la connexion par design
                // (voir startPolling), leur âge dépasse ce seuil dès les 10 premières
                // secondes de CHAQUE session sans que la valeur soit fausse (constaté sur
                // capture réelle : "Ratio/tension O2 max annoncés" marqué périmé alors que
                // PID4F n'a jamais changé depuis la connexion).
                val suffix = if (def.pid !in PidCatalog.CONTEXT_ONLY_PIDS && nowMs - it.updatedAtMs > VALUE_UNAVAILABLE_AFTER_MS) {
                    " (périmé)"
                } else {
                    ""
                }
                sb.appendLine("${def.label} : ${it.text}$suffix")
            }
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
            port = prefs.getString(KEY_PORT, null) ?: "35000",
            connectionMode = savedConnectionMode(),
            bluetoothDeviceName = prefs.getString(KEY_BLUETOOTH_NAME, null)
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

    // Job dédié, pas dtcJob : runAutoTest() déclenche lui-même startRecording()/
    // startFapScan() en cours de séquence, qui gèrent déjà dtcJob pour leur propre
    // compte. Le partager aurait fait de l'auto-test la victime de son propre appel à
    // startFapScan() (stopFapScanAndGetPrevious annule le job actuellement dans dtcJob,
    // soit l'auto-test lui-même si on le lui avait assigné).
    private var autoTestJob: Job? = null

    private var probeWriter: BufferedWriter? = null

    init {
        // Les enregistrements/sondages des sessions précédentes existent déjà sur le disque
        // au lancement de l'app : visibles sans attendre une nouvelle session.
        _state.update { it.copy(recordings = listRecordings(), probes = listProbes()) }
        // Connexion automatique au lancement (comme Torque) : retente la dernière sonde
        // utilisée sans action de l'utilisateur. Échoue silencieusement vers l'écran de
        // connexion habituel si elle n'est pas joignable, exactement comme un échec
        // manuel (voir connect()/connectBluetooth(), aucun chemin d'erreur spécifique ici).
        autoConnectOnLaunch()
    }

    private fun savedConnectionMode(): ConnectionMode =
        runCatching { ConnectionMode.valueOf(prefs.getString(KEY_CONNECTION_MODE, null) ?: "") }
            .getOrDefault(ConnectionMode.WIFI)

    private fun autoConnectOnLaunch() {
        when (savedConnectionMode()) {
            ConnectionMode.WIFI -> connect(_state.value.host, _state.value.port)
            ConnectionMode.BLUETOOTH -> {
                val address = prefs.getString(KEY_BLUETOOTH_ADDRESS, null) ?: return
                val device = runCatching { bluetoothAdapter()?.getRemoteDevice(address) }.getOrNull() ?: return
                connectBluetooth(device)
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter

    private fun bluetoothDeviceName(device: BluetoothDevice): String =
        // .name lève une SecurityException sans BLUETOOTH_CONNECT sur API 31+ (try/catch
        // explicite plutôt que runCatching : le lint MissingPermission d'Android ne
        // reconnaît que cette forme comme une protection valable) ; l'adresse MAC ne
        // demande elle aucune permission et reste un identifiant valable à défaut.
        try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }

    /**
     * Appareils déjà appairés (voir réglages Bluetooth du téléphone) : cette app ne fait
     * aucune découverte/appairage elle-même, ce qui évite ACCESS_FINE_LOCATION (nécessaire
     * pour scanner activement, pas pour lister des appairages déjà faits). Rafraîchie à la
     * demande (ouverture du sélecteur), pas un flux continu.
     */
    fun refreshBondedBluetoothDevices() {
        val adapter = bluetoothAdapter()
        val devices = if (adapter != null && adapter.isEnabled) {
            try {
                adapter.bondedDevices.toList()
            } catch (e: SecurityException) {
                emptyList()
            }
        } else {
            emptyList()
        }
        _state.update { it.copy(bondedBluetoothDevices = devices) }
    }

    private fun listRecordings(): List<RecordingFile> = listCsvFiles("recordings")

    private fun listProbes(): List<RecordingFile> = listCsvFiles("probes")

    /** Suppression définitive, pas de corbeille : les fichiers vivent en stockage privé de l'app, inaccessibles à un gestionnaire de fichiers classique. */
    fun deleteRecording(path: String) {
        File(path).delete()
        _state.update { it.copy(recordings = listRecordings()) }
    }

    fun deleteProbe(path: String) {
        File(path).delete()
        _state.update { it.copy(probes = listProbes()) }
    }

    private fun listCsvFiles(subdir: String): List<RecordingFile> {
        val dir = File(getApplication<Application>().filesDir, subdir)
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

    /**
     * Fichier de nom unique dans [dir] : deux enregistrements démarrés dans la même
     * seconde produiraient sinon le même nom horodaté, et `bufferedWriter()` écrase
     * silencieusement un fichier existant (voir audit, "Noms de fichiers"). createNewFile()
     * est atomique (échoue si le fichier existe déjà) contrairement à un simple exists()
     * suivi d'une écriture, qui laisserait une fenêtre de course.
     */
    private fun uniqueFile(dir: File, baseName: String): File {
        var candidate = File(dir, "$baseName.csv")
        var suffix = 2
        while (!candidate.createNewFile()) {
            candidate = File(dir, "${baseName}_$suffix.csv")
            suffix++
        }
        return candidate
    }

    /** Nom/version affichés par l'OS pour ce build, "?" si indisponible (ne doit jamais faire échouer un export). */
    private fun appVersionName(): String = runCatching {
        val app = getApplication<Application>()
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /**
     * Identité du véhicule/session en tête d'un CSV (enregistrement ou sondage) : sans ça,
     * plusieurs fichiers ouverts sur PC (deux véhicules, voir audit "Métadonnées des
     * fichiers") ne se distinguent que par leur nom de fichier. Le fuseau explicite (XXX)
     * comble aussi le manque relevé en A1 sur les horodatages CSV.
     */
    private fun writeSessionMetadata(writer: BufferedWriter, extra: List<Pair<String, String>> = emptyList()) {
        val state = _state.value
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ssXXX", Locale.FRANCE)
        val entries = listOf(
            "VIN" to (state.vin ?: "inconnu"),
            "Protocole" to (state.protocol ?: "inconnu"),
            "Version app" to appVersionName(),
            "Début session" to timestampFormat.format(Date())
        ) + extra
        for ((key, value) in entries) {
            writer.write(csvRow(listOf(key, value)))
            writer.newLine()
        }
        writer.newLine()
    }

    fun updateHost(value: String) {
        _state.update { it.copy(host = value) }
    }

    fun updatePort(value: String) {
        _state.update { it.copy(port = value) }
    }

    /** Sélection d'écran seulement (voir onModeChange) : ne persiste et n'affecte le transport réel qu'au moment de connect()/connectBluetooth(). */
    fun setConnectionMode(mode: ConnectionMode) {
        _state.update { it.copy(connectionMode = mode) }
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

        prefs.edit()
            .putString(KEY_HOST, host)
            .putString(KEY_PORT, portText)
            .putString(KEY_CONNECTION_MODE, ConnectionMode.WIFI.name)
            .apply()
        beginConnecting(ConnectionMode.WIFI)

        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            prepareForNewConnection()
            val c = Elm327Client(host, port)
            finishConnecting(c) {
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
                    return@finishConnecting false
                }
                c.connect(wifiNetwork)
                true
            }
        }
    }

    /**
     * Connexion Bluetooth (RFCOMM/SPP) : voir Elm327Client et ConnectionTarget.Bluetooth.
     * [device] doit être déjà appairé (voir bondedBluetoothDevices) ; cette fonction ne
     * fait aucune découverte, seulement la connexion socket. Non vérifié sur un vrai
     * adaptateur Bluetooth, voir le commentaire de classe d'Elm327Client.
     */
    fun connectBluetooth(device: BluetoothDevice) {
        if (_state.value.connectionState == ConnectionState.CONNECTING) return

        prefs.edit()
            .putString(KEY_CONNECTION_MODE, ConnectionMode.BLUETOOTH.name)
            .putString(KEY_BLUETOOTH_ADDRESS, device.address)
            .putString(KEY_BLUETOOTH_NAME, bluetoothDeviceName(device))
            .apply()
        beginConnecting(ConnectionMode.BLUETOOTH, bluetoothDeviceName(device))

        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            prepareForNewConnection()
            val c = Elm327Client(ConnectionTarget.Bluetooth(device))
            finishConnecting(c) {
                c.connect()
                true
            }
        }
    }

    /** Repart d'un état neuf (hôte/port et enregistrements passés gardés) : sans ça, les
     * valeurs, DTC, historique etc. d'une session précédente (potentiellement un AUTRE
     * véhicule) restaient affichés sous l'identité de la nouvelle connexion jusqu'à ce
     * qu'un nouveau scan les remplace, ou pour toujours s'il échoue. Les enregistrements
     * CSV ne sont pas liés à une session : ce sont des fichiers sur le disque, pas de
     * raison de les faire disparaître de la liste au moment de se reconnecter. */
    private fun beginConnecting(mode: ConnectionMode, bluetoothName: String? = null) {
        _state.update {
            ObdUiState(
                host = it.host,
                port = it.port,
                connectionMode = mode,
                bluetoothDeviceName = bluetoothName ?: it.bluetoothDeviceName,
                recordings = it.recordings,
                probes = it.probes,
                connectionState = ConnectionState.CONNECTING
            )
        }
    }

    /** Referme une éventuelle connexion précédente (ex: reconnexion après une erreur)
     * avant d'en ouvrir une nouvelle, pour ne pas laisser un client orphelin tourner en
     * tâche de fond. */
    private fun prepareForNewConnection() {
        pollJob?.cancel()
        stopAutoTest()
        stopRecording()
        stopFapScan()
        unregisterNetworkCallback()
        client?.disconnect()
        client = null
    }

    /**
     * Établissement de session commun aux deux transports, une fois [doConnect] chargé
     * d'ouvrir le flux propre à chacun (Wi-Fi avec liaison réseau, Bluetooth sans). Renvoyer
     * false depuis [doConnect] annule l'établissement sans le traiter comme une erreur
     * (ex: pas de réseau Wi-Fi détecté, déjà signalé par l'appelant).
     */
    private suspend fun finishConnecting(c: Elm327Client, doConnect: suspend () -> Boolean) {
        try {
            if (!doConnect()) return
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
            // Sans VIN, l'historique de CETTE connexion ne doit rien hériter d'une
            // précédente session sans VIN, potentiellement un autre véhicule (voir A6bis
            // / audit "Deux véhicules et historique") : jamais persisté pour ce cas, voir
            // DtcHistoryStore.resetSessionHistory.
            historyStore.resetSessionHistory()

            // Échelles réelles des PID24/0B (voir PidCatalog.o2MaxRatio/o2MaxVoltage/
            // mapMaxKpa) : caractéristique fixe de ce véhicule, lue une fois ici plutôt
            // qu'à chaque cycle de polling. PidCatalog est un singleton partagé entre
            // connexions : chaque octet est remis à son repli si CE véhicule ne
            // supporte pas PID4F OU annonce zéro sur cet octet précis (voir A4 : un
            // octet nul ne veut pas dire "plafonner à zéro", mais "garder le repli"),
            // pour qu'une valeur laissée par un véhicule précédent ne s'applique pas ici.
            val scaleBytes = if (0x4F in supported) c.readPidBytes(0x4F) else null
            PidCatalog.o2MaxRatio = scaleBytes?.getOrNull(0)?.takeIf { it != 0 }?.toDouble() ?: 2.0
            PidCatalog.o2MaxVoltage = scaleBytes?.getOrNull(1)?.takeIf { it != 0 }?.toDouble() ?: 8.0
            PidCatalog.mapMaxKpa = scaleBytes?.getOrNull(3)?.takeIf { it != 0 }?.let { it * 10.0 }

            // Même principe pour le débit d'air (PID10), annoncé par PID50 (un seul
            // octet, max en dizaines de g/s).
            val mafScaleBytes = if (0x50 in supported) c.readPidBytes(0x50) else null
            PidCatalog.mafMaxGramsPerSec = mafScaleBytes?.getOrNull(0)?.takeIf { it != 0 }?.let { it * 10.0 }

            // PID4F/PID50 sont exclus du polling répété (voir startPolling) puisqu'ils
            // ne varient pas ; affichés une fois ici à partir des octets déjà reçus
            // ci-dessus, pour ne pas rester vides sur le Dashboard faute d'y être jamais
            // "mesurés" par le polling normal.
            val now = System.currentTimeMillis()
            val contextValues = mutableMapOf<Int, GaugeValue>()
            PidCatalog.defs.firstOrNull { it.pid == 0x4F }?.let { def ->
                if (scaleBytes != null && scaleBytes.size >= def.expectedBytes) {
                    runCatching { def.decode(scaleBytes) }.getOrNull()?.let {
                        contextValues[def.pid] = GaugeValue(it, now)
                    }
                }
            }
            PidCatalog.defs.firstOrNull { it.pid == 0x50 }?.let { def ->
                if (mafScaleBytes != null && mafScaleBytes.size >= def.expectedBytes) {
                    runCatching { def.decode(mafScaleBytes) }.getOrNull()?.let {
                        contextValues[def.pid] = GaugeValue(it, now)
                    }
                }
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
                    dtcHistory = historyStore.load(vehicleId),
                    values = it.values + contextValues
                )
            }
            startPolling(c, supported)
        } catch (e: CancellationException) {
            // c n'est affecté à `client` qu'après doConnect() plus haut : si l'annulation
            // arrive avant, personne d'autre ne connaît ce client pour le refermer (voir
            // A7). Idempotent et sans risque si c a déjà été publié et fermé ailleurs.
            c.disconnect()
            throw e
        } catch (e: Exception) {
            c.disconnect()
            client = null
            _state.update {
                it.copy(connectionState = ConnectionState.ERROR, errorMessage = e.message ?: "Connexion échouée")
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
        // CONTEXT_ONLY_PIDS (PID4F/PID50) sont déjà lus une fois dans connect() et
        // n'évoluent pas pendant la session : les réinterroger ici ne changerait jamais
        // leur valeur, au prix d'une commande de moins pour celles qui varient vraiment
        // (voir audit, "Contexte standard et fréquence").
        val fastPids = PidCatalog.defs.filter {
            it.pid in supported && it.pid !in PidCatalog.CONTEXT_ONLY_PIDS && it.pid !in PidCatalog.SLOW_PIDS
        }
        val slowPids = PidCatalog.defs.filter { it.pid in supported && it.pid in PidCatalog.SLOW_PIDS }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var cycle = 0
            while (c.isConnected) {
                if (!dtcOperationInProgress) {
                    try {
                        // Les températures (SLOW_PIDS) ne sont ajoutées qu'une fraction des
                        // cycles : assez souvent pour ne jamais paraître périmées à l'affichage
                        // (bien en dessous de VALUE_UNAVAILABLE_AFTER_MS), trop lentes pour
                        // justifier le même rythme que le RPM/la vitesse (voir audit, "Contexte
                        // standard et fréquence").
                        val toPoll = if (cycle % SLOW_PID_EVERY_N_CYCLES == 0) fastPids + slowPids else fastPids
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
                                // Horodaté au retour de CETTE lecture, pas au début du cycle
                                // (voir A1) : un cycle de plusieurs dizaines de PID peut durer
                                // plus d'une seconde, la première valeur lue ne doit pas hériter
                                // de l'âge de la dernière.
                                runCatching { def.decode(bytes) }.getOrNull()?.let {
                                    newValues[def.pid] = GaugeValue(it, System.currentTimeMillis())
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
                        handleConnectionLost(c, e.message ?: "Lecture échouée")
                        return@launch
                    }
                }
                cycle++
                delay(300)
            }
            // Atteint uniquement si c.isConnected est devenu faux SANS exception (voir A2) :
            // le transport a été fermé par une autre opération sur ce même client (ex: un
            // sendRaw en échec dans un refresh DTC). Une annulation volontaire (déconnexion,
            // nouvelle connexion) lève CancellationException au prochain delay() ci-dessus et
            // ne redescend jamais jusqu'ici.
            handleConnectionLost(c, "Connexion perdue avec la sonde")
        }
    }

    /**
     * Connexion perdue pendant le polling (coupure WiFi de la sonde, contact coupé, etc.),
     * traitée comme une vraie déconnexion plutôt qu'un simple passage en erreur. soTimeout
     * (3s, voir Elm327Client.connect) borne le délai avant qu'une lecture bloquée ne lève
     * ici. Un enregistrement CSV en cours DOIT s'arrêter à cet instant : sinon il continue
     * à échantillonner _state.value.values (jamais remis à jour, le polling vient de
     * s'arrêter) toutes les 5s pour le reste de la session, sans qu'aucun bouton "Arrêter"
     * ne reste accessible puisque l'écran retombe sur le formulaire de connexion. Cas réel
     * constaté sur capture : 82 min de valeurs identiques après une coupure de contact.
     *
     * [source] est le client qui a détecté la perte, pas forcément celui actuellement en
     * champ `client` : un job de diagnostic annulé peut encore livrer son exception après
     * qu'une reconnexion a déjà remplacé `client` par une nouvelle session (voir A2). Sans
     * cette vérification, ce job périmé fermerait la session suivante à la place de la sienne.
     */
    private fun handleConnectionLost(source: Elm327Client, message: String) {
        if (client !== source) return
        stopAutoTest()
        stopRecording()
        stopFapScan()
        unregisterNetworkCallback()
        viewModelScope.launch(Dispatchers.IO) { source.disconnect() }
        client = null
        _state.update {
            ObdUiState(
                host = it.host,
                port = it.port,
                connectionMode = it.connectionMode,
                bluetoothDeviceName = it.bluetoothDeviceName,
                recordings = it.recordings,
                probes = it.probes,
                connectionState = ConnectionState.ERROR,
                errorMessage = message
            )
        }
    }

    /**
     * Lecture à la demande (pas de polling continu, ce sont des données de diagnostic) :
     * DTC stockés/en attente, moniteurs de préparation, et freeze frame si un DTC est présent.
     */
    fun refreshDtcs() {
        val c = client ?: return
        // Une deuxième entrée sur l'écran DTC pendant qu'un refresh tourne encore, ou un
        // sondage FAP en cours, annule le précédent plutôt que de les laisser cogner en
        // parallèle sur le fil (stopFapScanAndGetPrevious ferme aussi proprement un sondage
        // éventuel : un simple dtcJob?.cancel() laisserait son fichier ouvert indéfiniment).
        // stopAutoTest() en plus : le smoke test automatique n'utilise pas dtcJob (voir son
        // champ dédié autoTestJob), donc stopFapScanAndGetPrevious seul ne le verrait pas.
        stopAutoTest()
        val previousJob = stopFapScanAndGetPrevious()
        dtcJob = viewModelScope.launch {
            dtcOperationCount++
            _state.update { it.copy(dtcLoading = true, dtcError = null) }
            try {
                // cancel() ne fait que DEMANDER l'arrêt du job précédent : sans ce join, sa
                // propre restauration (ex: ATH0 après une capture headers, cf. A8) peut encore
                // être en vol et sa commande se mélanger avec la nôtre sur le même mutex.
                previousJob?.join()
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
                        dtcLoading = false,
                        dtcLastSuccessAtMs = System.currentTimeMillis()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Elm327Client.sendRaw ferme systématiquement le transport avant de relancer
                // (voir son commentaire) : si c'est bien fait, traiter comme une vraie perte
                // de connexion (arrête l'enregistrement etc., voir A2) plutôt que de laisser
                // l'état CONNECTED avec un enregistrement fantôme derrière une simple erreur
                // locale.
                if (!c.isConnected) {
                    handleConnectionLost(c, e.message ?: "Lecture DTC échouée")
                    return@launch
                }
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
        stopAutoTest()
        val previousJob = stopFapScanAndGetPrevious()
        dtcJob = viewModelScope.launch {
            dtcOperationCount++
            // dtcLoading=false : ce job vient d'annuler un éventuel refreshDtcs() en cours
            // (dtcJob partagé). Son "finally" ne s'exécute jamais (coroutine annulée avant),
            // donc dtcLoading resterait bloqué à true (bouton Rafraîchir désactivé pour de
            // bon) si on ne le remettait pas ici.
            _state.update { it.copy(headerProbeResult = "Lecture...", dtcLoading = false) }
            try {
                // Voir le commentaire équivalent dans refreshDtcs (A8) : attendre que le job
                // précédent ait fini sa propre restauration avant d'envoyer nos commandes.
                previousJob?.join()
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
                if (!c.isConnected) {
                    handleConnectionLost(c, e.message ?: "Lecture échouée")
                    return@launch
                }
                _state.update { it.copy(headerProbeResult = "Échec: ${e.message}") }
            } finally {
                dtcOperationCount--
            }
        }
    }

    /**
     * Sondage en lecture seule d'une plage d'identifiants UDS (service 0x22
     * ReadDataByIdentifier, ISO 14229, générique à tout ECU compatible) : aucune définition
     * publique n'existe pour les DID de CE calculateur, donc la seule façon de savoir
     * lesquels répondent est de les interroger un par un sur le véhicule réel et de
     * consigner chaque réponse pour analyse ultérieure (le sens exact d'un DID positif
     * reste à établir après coup, cette fonction ne décode rien).
     *
     * N'envoie que des lectures (0x22) : jamais WriteDataByIdentifier (0x2E), RoutineControl
     * (0x31, actionneurs/régénération forcée) ni effacement. Reste en session diagnostique
     * par défaut (pas de passage en session étendue 0x1003) : si tout revient en NRC
     * "hors plage" ou "service non supporté", certains DID peuvent nécessiter une session
     * que cet outil ne demande volontairement pas encore.
     */
    fun startFapScan(startDidText: String, endDidText: String, targetHeaderText: String = "") {
        if (_state.value.isFapScanning) return
        val c = client ?: return

        val startDid = startDidText.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
        val endDid = endDidText.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
        if (startDid == null || endDid == null || startDid !in 0..0xFFFF || endDid !in 0..0xFFFF || startDid > endDid) {
            _state.update { it.copy(fapScanError = "Plage invalide (hexadécimal, 0000-FFFF, début ≤ fin).") }
            return
        }
        // Adresse destinataire optionnelle (ATSH, voir Elm327Client.setTargetHeader) : les
        // essais PC de référence ciblaient explicitement 7E0, alors que ce sondage envoie
        // par défaut en diffusion (voir audit, "Sondage UDS expérimental") — sans cette
        // option il n'y a aucun moyen de reproduire le même essai depuis l'app.
        val targetHeader = targetHeaderText.trim().removePrefix("0x").removePrefix("0X").uppercase()
        if (targetHeader.isNotEmpty() && targetHeader.toIntOrNull(16) == null) {
            _state.update { it.copy(fapScanError = "Adresse cible invalide (hexadécimal, ex: 7E0), ou la laisser vide.") }
            return
        }

        // Ferme aussi proprement un refresh DTC ou une capture de headers en cours
        // (dtcJob partagé) : voir le commentaire de refreshDtcs. Pas de stopAutoTest() ici
        // contrairement à refreshDtcs/probeHeaderFormat : runAutoTest() appelle lui-même
        // startFapScan() comme dernière étape, ce qui s'annulerait sa propre coroutine
        // (autoTestJob) juste avant d'attendre le résultat de ce même appel.
        val previousJob = stopFapScanAndGetPrevious()

        val dir = File(getApplication<Application>().filesDir, "probes").apply { mkdirs() }
        val baseName = "fap_scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val writer = try {
            uniqueFile(dir, baseName).bufferedWriter().apply {
                writeSessionMetadata(this, listOf("Adresse cible" to targetHeader.ifEmpty { "diffusion (défaut)" }))
                write(csvRow(listOf("Horodatage", "DID", "Résultat", "NRC", "Détail", "Réponse brute")))
                newLine()
                flush()
            }
        } catch (e: Exception) {
            // Création/écriture d'en-tête non protégée avant ce correctif (voir A5) :
            // un stockage plein ou une permission révoquée levait une IOException non
            // rattrapée jusqu'ici, hors coroutine (cet appel est synchrone), donc un crash
            // direct de l'UI au clic sur "Démarrer".
            _state.update { it.copy(fapScanError = "Impossible de créer le fichier de sondage : ${e.message}") }
            return
        }
        probeWriter = writer

        _state.update {
            it.copy(
                fapScanError = null,
                isFapScanning = true,
                fapScanDone = 0,
                fapScanTotal = endDid - startDid + 1,
                fapScanPositives = emptyList(),
                fapScanCurrentDid = null,
                fapScanOutcome = null
            )
        }

        val rowTimestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.FRANCE)

        dtcJob = viewModelScope.launch {
            dtcOperationCount++
            try {
                previousJob?.join()
                try {
                    // ATSH n'a de sens qu'en CAN : sur un véhicule non-CAN, la commande
                    // échouera probablement sans effet plutôt que de casser quoi que ce
                    // soit, mais elle n'est envoyée que si l'utilisateur l'a explicitement
                    // demandée (targetHeader vide = comportement inchangé, diffusion).
                    if (targetHeader.isNotEmpty()) c.setTargetHeader(targetHeader)
                    for (did in startDid..endDid) {
                        _state.update { it.copy(fapScanCurrentDid = did) }
                        val probe = c.readUdsDid(did)
                        val result = probe.result
                        val didHex = "%04X".format(did)
                        val nrcHex = (result as? UdsDidResult.Negative)?.let { "%02X".format(it.nrc) } ?: ""
                        val (label, detail) = when (result) {
                            is UdsDidResult.Positive ->
                                "positif" to result.data.joinToString(" ") { "%02X".format(it) }.ifBlank { "(vide)" }
                            is UdsDidResult.Negative -> "négatif" to nrcDescription(result.nrc)
                            UdsDidResult.NoResponse -> "aucune réponse" to ""
                        }
                        // \r/\n remplacés par des espaces : une réponse brute multi-trame
                        // reste sur une seule ligne de CSV, plus lisible dans un tableur
                        // simple qu'un champ entre guillemets sur plusieurs lignes.
                        val rawForCsv = probe.rawResponse.replace('\r', ' ').replace('\n', ' ').trim()
                        val row = listOf(rowTimestampFormat.format(Date()), didHex, label, nrcHex, detail, rawForCsv)
                        val written = withContext(Dispatchers.IO) {
                            runCatching {
                                probeWriter?.write(csvRow(row))
                                probeWriter?.newLine()
                                probeWriter?.flush()
                            }.isSuccess
                        }
                        if (!written) {
                            // Ne pas continuer à compter des DID "faits" qui ne sont plus
                            // écrits nulle part (voir A5) : arrêter et le dire, en gardant
                            // le fichier partiel déjà sur le disque.
                            _state.update {
                                it.copy(
                                    fapScanError = "Écriture du sondage échouée, arrêté.",
                                    fapScanOutcome = FapScanOutcome.ERREUR
                                )
                            }
                            break
                        }
                        _state.update {
                            it.copy(
                                fapScanDone = it.fapScanDone + 1,
                                fapScanPositives = if (result is UdsDidResult.Positive) {
                                    it.fapScanPositives + "$didHex : $detail"
                                } else {
                                    it.fapScanPositives
                                }
                            )
                        }
                    }
                } finally {
                    if (targetHeader.isNotEmpty()) withContext(NonCancellable) { c.resetTargetHeader() }
                }
                // Verdict "terminé" seulement si rien n'a déjà tranché autrement ci-dessus
                // (échec d'écriture) : une annulation externe ne redescend jamais jusqu'ici
                // (voir stopFapScanAndGetPrevious pour son propre verdict "interrompu").
                if (_state.value.fapScanOutcome == null) {
                    _state.update { it.copy(fapScanOutcome = FapScanOutcome.TERMINE) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleConnectionLost(c, e.message ?: "Lecture échouée")
                return@launch
            } finally {
                dtcOperationCount--
            }
            stopFapScan()
        }
    }

    /**
     * Arrête un sondage en cours (bouton, nouvelle connexion, perte de connexion, ou prise
     * de main par un refresh DTC / une capture de headers) : ferme le fichier pour qu'il
     * soit exploitable même incomplet, et remet dtcLoading à disposition (même logique que
     * le commentaire de probeHeaderFormat : un job annulé ne termine jamais son "try" en
     * cours, seul le "finally" s'exécute). Sans effet si rien n'était en cours.
     */
    fun stopFapScan() {
        stopFapScanAndGetPrevious()
    }

    /**
     * Variante de [stopFapScan] qui retourne le job annulé, pour les trois entrées qui
     * démarrent immédiatement leur propre job sur le même client (refreshDtcs,
     * probeHeaderFormat, startFapScan). `cancel()` ne fait que DEMANDER l'arrêt : la
     * coroutine visée continue jusqu'à son prochain point de suspension, qui peut être au
     * milieu d'une séquence à plusieurs commandes (ex: ATH1 ... ATH0 dans
     * Elm327Client.probeHeaderFormat). Sans attendre ([Job.join]) cette fin réelle avant
     * d'envoyer la première commande du nouveau job, les deux peuvent s'entrelacer sur le
     * même mutex et laisser la sonde dans un état intermédiaire (voir A8, reproduit :
     * ATH1 → 0101 → ATH0 au lieu de ATH1 → 0100 → ATH0).
     */
    private fun stopFapScanAndGetPrevious(): Job? {
        val previous = dtcJob
        previous?.cancel()
        dtcJob = null
        val wasScanning = probeWriter != null
        runCatching { probeWriter?.close() }
        probeWriter = null
        _state.update {
            it.copy(
                dtcLoading = false,
                isFapScanning = false,
                fapScanCurrentDid = null,
                // Un scan qui vient de se terminer normalement ou en erreur a déjà écrit
                // son verdict avant d'appeler stopFapScan lui-même (voir startFapScan) :
                // ne pas l'écraser par INTERROMPU. null ici veut dire qu'aucun verdict
                // n'a encore été rendu, donc que l'arrêt vient bien de l'extérieur.
                fapScanOutcome = if (wasScanning && it.fapScanOutcome == null) FapScanOutcome.INTERROMPU else it.fapScanOutcome,
                probes = if (wasScanning) listProbes() else it.probes
            )
        }
        return previous
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
        val baseName = "obd_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val writer = try {
            uniqueFile(dir, baseName).bufferedWriter().apply {
                writeSessionMetadata(this)
                write(csvRow(listOf("Horodatage", "État") + columns.map { it.label }))
                newLine()
                flush()
            }
        } catch (e: Exception) {
            // Création/écriture d'en-tête non protégée avant ce correctif (voir A5) :
            // un stockage plein ou une permission révoquée levait une IOException non
            // rattrapée jusqu'ici, hors coroutine (cet appel est synchrone), donc un crash
            // direct de l'UI au clic sur "Démarrer l'enregistrement".
            _state.update { it.copy(errorMessage = "Impossible de créer l'enregistrement : ${e.message}") }
            return
        }

        recordingColumns = columns
        recordingWriter = writer
        _state.update { it.copy(isRecording = true, recordingSamples = 0) }
        // Effort raisonnable, pas une condition bloquante : démarré depuis un bouton
        // visible à l'écran, c'est un cas autorisé à lancer un service de premier plan
        // (voir RecordingService). Un échec ici (rare) laisse l'enregistrement fonctionner
        // tant que l'app reste au premier plan ; seule sa résistance à l'écran éteint en
        // pâtirait (voir audit, "Écran éteint et arrière-plan").
        runCatching {
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), RecordingService::class.java)
            )
        }

        recordingJob = viewModelScope.launch {
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
            while (true) {
                delay(RECORDING_INTERVAL_MS)
                val snapshot = _state.value
                val now = System.currentTimeMillis()
                // Colonne "État" : une pause diagnostique (refresh DTC, capture headers,
                // sondage FAP) suspend le polling sans arrêter l'enregistrement, sinon on
                // rejoue silencieusement les dernières valeurs comme si elles étaient
                // fraîches (voir A1). Une valeur elle-même plus vieille que
                // VALUE_UNAVAILABLE_AFTER_MS est laissée vide plutôt que répétée : un trou
                // visible dans le CSV plutôt qu'une donnée fantôme.
                val etat = if (dtcOperationInProgress) "pause diagnostic" else "ok"
                val row = listOf(timestampFormat.format(Date()), etat) +
                    recordingColumns.map { def ->
                        val value = snapshot.values[def.pid]
                        // CONTEXT_ONLY_PIDS exclus de la limite d'âge : lus une seule fois à
                        // la connexion par design (voir startPolling), ils dépasseraient ce
                        // seuil dès les 10 premières secondes de CHAQUE enregistrement sans
                        // que la valeur soit fausse (constaté sur capture réelle : colonne
                        // "Ratio/tension O2 max annoncés" vide dans tout l'enregistrement).
                        if (value != null &&
                            (def.pid in PidCatalog.CONTEXT_ONLY_PIDS || now - value.updatedAtMs <= VALUE_UNAVAILABLE_AFTER_MS)
                        ) {
                            value.text
                        } else {
                            ""
                        }
                    }
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        recordingWriter?.write(csvRow(row))
                        recordingWriter?.newLine()
                        recordingWriter?.flush()
                    }.isSuccess
                }
                if (written) {
                    _state.update { it.copy(recordingSamples = it.recordingSamples + 1) }
                } else {
                    // Ne pas continuer à annoncer des échantillons qui ne sont plus
                    // réellement écrits (voir A5) : arrêter, en gardant le fichier partiel.
                    _state.update { it.copy(errorMessage = "Écriture de l'enregistrement échouée, arrêté.") }
                    stopRecording()
                    break
                }
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
            runCatching {
                getApplication<Application>().stopService(Intent(getApplication(), RecordingService::class.java))
            }
            // Le fichier qui vient de se fermer doit apparaître dans la liste tout de
            // suite, sans attendre un redémarrage de l'app.
            _state.update { it.copy(isRecording = false, recordings = listRecordings()) }
        }
    }

    /**
     * Smoke test automatique : exécute contre le véhicule RÉELLEMENT connecté une petite
     * séquence de lectures, puis un enregistrement et un sondage courts, et rapporte
     * OK/ATTENTION/ECHEC par étape. Complète la checklist manuelle "en voiture", ne la
     * remplace pas : rien ici ne peut couper le contact ni éteindre l'écran à la place
     * d'un humain, ces cas-là restent sur la checklist.
     *
     * Lecture seule côté ECU (readMilStatus/readStoredDtcs/readPendingDtcs/readReadiness/
     * readUdsDid). Les deux dernières étapes appellent startRecording()/startFapScan()
     * tels quels, pas une copie de leur logique : un bug qu'elles auraient serait donc
     * visible ici aussi, pas masqué par un chemin de test séparé. Elles laissent un petit
     * fichier réel dans les listes d'enregistrements/sondages, comme n'importe quelle
     * utilisation manuelle.
     */
    fun runAutoTest() {
        if (_state.value.isAutoTesting) return
        val c = client ?: return

        // Attend la fin réelle d'un refresh DTC / capture headers / sondage manuel déjà en
        // cours (voir A8) avant d'envoyer ses propres commandes, même motif que les autres
        // entrées sur dtcJob : sans ça, les deux séquences se mélangeraient sur le fil.
        val previousJob = stopFapScanAndGetPrevious()

        val names = listOf(
            "Statut MIL (PID01)",
            "Une valeur dynamique",
            "VIN",
            "Codes défaut",
            "Moniteurs de préparation",
            "Enregistrement court (10s)",
            "Sondage UDS court (3 DID)"
        )
        _state.update {
            it.copy(
                isAutoTesting = true,
                autoTestChecks = names.map { name -> AutoTestCheck(name, AutoTestStatus.EN_ATTENTE) }
            )
        }

        fun setCheck(index: Int, status: AutoTestStatus, detail: String? = null) {
            _state.update { s ->
                val updated = s.autoTestChecks.toMutableList()
                updated[index] = AutoTestCheck(names[index], status, detail)
                s.copy(autoTestChecks = updated)
            }
        }

        autoTestJob = viewModelScope.launch {
            try {
                // dtcOperationCount géré directement ici, pas via dtcJob (voir son champ) :
                // relâché avant l'étape d'enregistrement pour que le polling normal
                // reprenne, sinon elle ne capturerait que des valeurs figées.
                dtcOperationCount++
                previousJob?.join()
                try {
                    setCheck(0, AutoTestStatus.EN_COURS)
                    try {
                        val (mil, count) = c.readMilStatus()
                        setCheck(0, AutoTestStatus.OK, "MIL ${if (mil) "allumé" else "éteint"}, $count code(s) annoncé(s)")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setCheck(0, AutoTestStatus.ECHEC, e.message ?: "échec")
                    }

                    setCheck(1, AutoTestStatus.EN_COURS)
                    val dynamicDef = PidCatalog.defs.firstOrNull {
                        it.pid in _state.value.supportedPids && it.pid !in PidCatalog.CONTEXT_ONLY_PIDS
                    }
                    if (dynamicDef == null) {
                        setCheck(1, AutoTestStatus.ATTENTION, "Aucun PID dynamique annoncé supporté")
                    } else {
                        try {
                            val bytes = c.readPidBytes(dynamicDef.pid)
                            if (bytes != null && bytes.size >= dynamicDef.expectedBytes) {
                                setCheck(1, AutoTestStatus.OK, "${dynamicDef.label} = ${dynamicDef.decode(bytes)}")
                            } else {
                                setCheck(1, AutoTestStatus.ECHEC, "${dynamicDef.label} : pas de réponse exploitable")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            setCheck(1, AutoTestStatus.ECHEC, e.message ?: "échec")
                        }
                    }

                    setCheck(2, AutoTestStatus.EN_COURS)
                    try {
                        val vin = c.readVin()
                        if (vin != null) {
                            setCheck(2, AutoTestStatus.OK, vin)
                        } else {
                            setCheck(2, AutoTestStatus.ATTENTION, "Non lu (normal en non-CAN, ou VIN non supporté)")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setCheck(2, AutoTestStatus.ECHEC, e.message ?: "échec")
                    }

                    setCheck(3, AutoTestStatus.EN_COURS)
                    try {
                        val stored = c.readStoredDtcs()
                        val pending = c.readPendingDtcs()
                        setCheck(3, AutoTestStatus.OK, "${stored.size} stocké(s), ${pending.size} en attente")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setCheck(3, AutoTestStatus.ECHEC, e.message ?: "échec")
                    }

                    setCheck(4, AutoTestStatus.EN_COURS)
                    try {
                        val readiness = c.readReadiness()
                        if (readiness != null) {
                            setCheck(4, AutoTestStatus.OK, "${readiness.size} moniteur(s) annoncé(s)")
                        } else {
                            setCheck(4, AutoTestStatus.ATTENTION, "PID01 illisible pour les moniteurs")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setCheck(4, AutoTestStatus.ECHEC, e.message ?: "échec")
                    }
                } finally {
                    dtcOperationCount--
                }

                setCheck(5, AutoTestStatus.EN_COURS)
                if (client !== c) {
                    setCheck(5, AutoTestStatus.ECHEC, "Connexion changée en cours de test")
                } else {
                    startRecording()
                    if (!_state.value.isRecording) {
                        setCheck(5, AutoTestStatus.ECHEC, _state.value.errorMessage ?: "L'enregistrement n'a pas démarré")
                    } else {
                        delay(10_000)
                        val samples = _state.value.recordingSamples
                        stopRecording()
                        if (samples >= 1) {
                            setCheck(5, AutoTestStatus.OK, "$samples échantillon(s) écrit(s)")
                        } else {
                            setCheck(5, AutoTestStatus.ECHEC, "Démarré, mais aucun échantillon écrit (voir errorMessage)")
                        }
                    }
                }

                setCheck(6, AutoTestStatus.EN_COURS)
                if (client !== c) {
                    setCheck(6, AutoTestStatus.ECHEC, "Connexion changée en cours de test")
                } else {
                    startFapScan("1140", "1142")
                    if (!_state.value.isFapScanning) {
                        setCheck(6, AutoTestStatus.ECHEC, _state.value.fapScanError ?: "Le sondage n'a pas démarré")
                    } else {
                        _state.first { !it.isFapScanning }
                        when (_state.value.fapScanOutcome) {
                            FapScanOutcome.TERMINE -> setCheck(6, AutoTestStatus.OK, "Mécanisme de sondage fonctionnel (3 DID lus)")
                            FapScanOutcome.ERREUR -> setCheck(6, AutoTestStatus.ECHEC, _state.value.fapScanError ?: "échec")
                            FapScanOutcome.INTERROMPU, null -> setCheck(6, AutoTestStatus.ATTENTION, "Interrompu avant la fin")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleConnectionLost(c, e.message ?: "Échec du smoke test automatique")
            } finally {
                _state.update { it.copy(isAutoTesting = false) }
            }
        }
    }

    /** Arrête le smoke test automatique en cours (bouton, déconnexion, ou perte de connexion). */
    fun stopAutoTest() {
        autoTestJob?.cancel()
        autoTestJob = null
        if (_state.value.isRecording) stopRecording()
        stopFapScan()
        _state.update { it.copy(isAutoTesting = false) }
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
        stopAutoTest()
        stopRecording()
        stopFapScan()
        unregisterNetworkCallback()
        val c = client
        client = null
        return c
    }

    fun disconnect() {
        val c = cancelJobsAndReleaseNetwork()
        // Repart d'un état par défaut, mais en gardant l'hôte/port et le transport (Wi-Fi
        // ou Bluetooth) actuellement affichés (sinon une déconnexion effacerait ce que
        // l'utilisateur vient de configurer), et les fichiers déjà produits (enregistrements,
        // sondages) pour pouvoir encore les partager après coup.
        _state.update {
            // recordings/probes portés tels quels : cancelJobsAndReleaseNetwork() ci-dessus
            // vient de les rafraîchir via stopRecording()/stopFapScan() si actifs.
            ObdUiState(
                host = it.host,
                port = it.port,
                connectionMode = it.connectionMode,
                bluetoothDeviceName = it.bluetoothDeviceName,
                recordings = it.recordings,
                probes = it.probes
            )
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
        private const val KEY_CONNECTION_MODE = "conn_mode"
        private const val KEY_BLUETOOTH_ADDRESS = "conn_bt_address"
        private const val KEY_BLUETOOTH_NAME = "conn_bt_name"
        // ~600 o/échantillon (~40 colonnes max) : à 5s, une session de 2h fait autour de
        // 850 Ko. Assez fin pour une analyse de tendance, sans accumuler des Mo inutiles.
        private const val RECORDING_INTERVAL_MS = 5_000L
        // 5 cycles à 300ms = 1,5s : largement sous VALUE_UNAVAILABLE_AFTER_MS (10s) et même
        // STALE_AFTER_MS côté Dashboard (3s), donc jamais visible comme périmée, pour un
        // cinquième des requêtes qu'au rythme normal (voir PidCatalog.SLOW_PIDS).
        private const val SLOW_PID_EVERY_N_CYCLES = 5
    }
}
