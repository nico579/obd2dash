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

// RECONNECTING distingue une nouvelle tentative automatique (silencieuse, voir
// DashboardScreen) d'une ERREUR provoquée par une action explicite (Réglages, message
// affiché) : une tentative automatique qui échoue retombe en RECONNECTING, pas en ERROR,
// même si la cause est identique (voir ObdViewModel.attemptAutoConnect/isAutoRetry).
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

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

/** Un point du graphique (voir GraphScreen) : [value] vient de PidCatalog.extractLeadingNumber sur le texte déjà décodé, pas d'un second décodeur numérique séparé. */
data class GraphPoint(val atMs: Long, val value: Double)

// Fenêtre glissante plutôt qu'un historique complet de session : un PID rapide (300ms)
// sur un trajet d'une heure ferait ~12000 points, illisible sur un écran de téléphone et
// inutilement coûteux en mémoire pour un usage "suivre la tendance récente", pas une
// analyse a posteriori (déjà couverte par l'enregistrement CSV).
internal const val GRAPH_HISTORY_MAX_POINTS = 200

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
    // PID actuellement suivi par l'écran Graphique, et son historique (voir GraphPoint).
    // Repartent à zéro à chaque nouvelle connexion (comme le reste de l'état) : un
    // historique qui continuerait après une coupure/reconnexion afficherait une tendance
    // avec un trou silencieux au milieu.
    val graphPid: Int? = null,
    val graphHistory: List<GraphPoint> = emptyList(),
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
    // Distinct de errorMessage (réservé à la connexion, affiché uniquement hors CONNECTED
    // par DashboardScreen) : une erreur d'enregistrement doit rester visible près de son
    // propre bouton MÊME connecté, sinon elle n'apparaît nulle part sur cet écran (voir
    // audit B4). Conservée jusqu'au prochain essai (voir startRecording).
    val recordingError: String? = null,
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
    // Journal texte des événements du processus en cours (voir EventLog) : un fichier par
    // lancement, listé comme les enregistrements/sondages pour être partageable/supprimable
    // pareil (voir SettingsScreen).
    val logs: List<RecordingFile> = emptyList(),
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
            ConnectionState.RECONNECTING -> "reconnexion en cours (coupure transitoire)"
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

    // true seulement si CE smoke test a lui-même démarré l'enregistrement en cours (voir
    // B1) : sans ce garde-fou, stopAutoTest() arrêtait n'importe quel enregistrement actif
    // au moment de son appel, y compris un enregistrement manuel démarré AVANT le test ou
    // toujours en cours après lui, simplement parce qu'un enregistrement était actif :
    // stopAutoTest() est aussi appelé par précaution depuis refreshDtcs()/
    // probeHeaderFormat()/prepareForNewConnection()/handleConnectionLost(), bien avant tout
    // smoke test réel. Remis à false dès que le test relâche ou n'a jamais pris cette
    // propriété.
    private var autoTestOwnsRecording = false

    private var probeWriter: BufferedWriter? = null

    // true seulement entre un appui sur "Déconnecter" et la prochaine tentative explicite
    // (connect()/connectBluetooth() le remettent à false) : sans ce garde-fou, la boucle de
    // reconnexion automatique ci-dessous relancerait une connexion aussitôt après une
    // déconnexion volontaire, ce qui la rendrait impossible à obtenir pour de vrai.
    private var userRequestedDisconnect = false
    private var autoReconnectJob: Job? = null

    init {
        // Les enregistrements/sondages/journaux des sessions précédentes existent déjà sur
        // le disque au lancement de l'app : visibles sans attendre une nouvelle session.
        _state.update { it.copy(recordings = listRecordings(), probes = listProbes(), logs = listLogs()) }
        // Connexion automatique au lancement (comme Torque), puis retentée en boucle tant
        // qu'elle échoue : sans contact mis (pas de Wi-Fi de la sonde), un seul essai
        // échouait et laissait l'utilisateur devoir rouvrir l'app ou appuyer sur un bouton
        // une fois le contact mis. Aucun panneau IP/port/Bluetooth n'est jamais montré par
        // défaut (voir DashboardScreen) : les réglages de connexion se changent depuis
        // l'écran Réglages, pas depuis un formulaire de connexion manuel.
        attemptAutoConnect()
        startAutoReconnectLoop()
    }

    private fun savedConnectionMode(): ConnectionMode =
        runCatching { ConnectionMode.valueOf(prefs.getString(KEY_CONNECTION_MODE, null) ?: "") }
            .getOrDefault(ConnectionMode.WIFI)

    private fun attemptAutoConnect() {
        when (savedConnectionMode()) {
            ConnectionMode.WIFI -> connect(_state.value.host, _state.value.port, isAutoRetry = true)
            ConnectionMode.BLUETOOTH -> {
                val address = prefs.getString(KEY_BLUETOOTH_ADDRESS, null) ?: return
                val device = runCatching { bluetoothAdapter()?.getRemoteDevice(address) }.getOrNull() ?: return
                connectBluetooth(device, isAutoRetry = true)
            }
        }
    }

    /**
     * Retente attemptAutoConnect() tant que l'app n'est ni connectée ni déjà en train de
     * se connecter, et que l'utilisateur n'a pas explicitement demandé à se déconnecter.
     * Boucle pour la durée de vie du ViewModel (pas de condition d'arrêt autre que la
     * connexion réussie) : ce ViewModel ne vit que pendant qu'une seule Activity l'utilise,
     * pas de risque de la faire tourner sans app visible.
     */
    private fun startAutoReconnectLoop() {
        autoReconnectJob?.cancel()
        autoReconnectJob = viewModelScope.launch {
            while (true) {
                delay(AUTO_RECONNECT_INTERVAL_MS)
                val current = _state.value.connectionState
                if (!userRequestedDisconnect &&
                    (current == ConnectionState.DISCONNECTED ||
                        current == ConnectionState.ERROR ||
                        current == ConnectionState.RECONNECTING)
                ) {
                    attemptAutoConnect()
                }
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

    /** Voir EventLog : un fichier texte par lancement, ".log" plutôt que ".csv" (ce ne sont pas des mesures tabulaires). */
    private fun listLogs(): List<RecordingFile> = listFiles("logs", "log")

    /** Suppression définitive, pas de corbeille : les fichiers vivent en stockage privé de l'app, inaccessibles à un gestionnaire de fichiers classique. */
    fun deleteRecording(path: String) {
        File(path).delete()
        _state.update { it.copy(recordings = listRecordings()) }
    }

    fun deleteProbe(path: String) {
        File(path).delete()
        _state.update { it.copy(probes = listProbes()) }
    }

    fun deleteLog(path: String) {
        // Le fichier actuellement en écriture (session en cours) apparaît aussi dans cette
        // liste : le supprimer sous les pieds d'EventLog laisserait son BufferedWriter
        // écrire dans le vide (silencieusement absorbé par runCatching côté EventLog.log,
        // pas de crash), simplement plus aucune ligne ultérieure de CETTE session ne serait
        // récupérable. Cas volontairement non bloqué : l'utilisateur reste libre de vider
        // le journal en cours, comme pour un enregistrement (pas de fichier protégé).
        File(path).delete()
        _state.update { it.copy(logs = listLogs()) }
    }

    private fun listCsvFiles(subdir: String): List<RecordingFile> = listFiles(subdir, "csv")

    private fun listFiles(subdir: String, extension: String): List<RecordingFile> {
        val dir = File(getApplication<Application>().filesDir, subdir)
        val files = dir.listFiles { f -> f.isFile && f.extension == extension } ?: emptyArray()
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

    /** Change le PID suivi par l'écran Graphique ; repart d'un historique vide (voir GraphPoint). */
    fun selectGraphPid(pid: Int?) {
        if (pid != null) EventLog.log("Graphique : suivi du PID $pid")
        _state.update { it.copy(graphPid = pid, graphHistory = emptyList()) }
    }

    fun connect(host: String, portText: String, isAutoRetry: Boolean = false) {
        if (_state.value.connectionState == ConnectionState.CONNECTING) return
        userRequestedDisconnect = false

        val port = portText.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            _state.update {
                it.copy(
                    connectionState = if (isAutoRetry) ConnectionState.RECONNECTING else ConnectionState.ERROR,
                    errorMessage = "Adresse IP ou port invalide (port entre 1 et 65535)."
                )
            }
            return
        }

        // Auto-retry non journalisé individuellement (voir startAutoReconnectLoop, toutes
        // les 5s tant que le contact n'est pas mis, ce serait juste du bruit) : seul le
        // résultat final (succès ou coupure détectée) laisse une trace, voir
        // finishConnecting/handleConnectionLost.
        if (!isAutoRetry) EventLog.log("Connexion Wi-Fi demandée ($host:$portText)")

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
            finishConnecting(c, isAutoRetry) {
                // Le téléphone a souvent WiFi (sonde, sans Internet) + 4G actifs en même
                // temps. Android route par défaut vers le réseau qui a Internet (donc la 4G),
                // ce qui rend la sonde injoignable. On force explicitement le socket à sortir
                // par le WiFi de la sonde.
                val wifiNetwork = requestWifiNetwork()
                if (wifiNetwork == null) {
                    _state.update {
                        it.copy(
                            connectionState = if (isAutoRetry) ConnectionState.RECONNECTING else ConnectionState.ERROR,
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
    fun connectBluetooth(device: BluetoothDevice, isAutoRetry: Boolean = false) {
        if (_state.value.connectionState == ConnectionState.CONNECTING) return
        userRequestedDisconnect = false

        if (!isAutoRetry) EventLog.log("Connexion Bluetooth demandée (${bluetoothDeviceName(device)})")

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
            finishConnecting(c, isAutoRetry) {
                c.connect()
                true
            }
        }
    }

    /**
     * Repart d'un état neuf pour tout ce qui est propre à UN véhicule (VIN, DTC, valeurs
     * live...) : sans ça, ces données d'une session précédente (potentiellement un AUTRE
     * véhicule) restaient affichées sous l'identité de la nouvelle connexion jusqu'à ce
     * qu'un nouveau scan les remplace, ou pour toujours si la connexion échoue. En
     * revanche, hôte/port/transport, fichiers déjà produits, et surtout un enregistrement
     * ou un graphique EN COURS survivent : une coupure transitoire doit reprendre ce qui
     * avait été commencé, pas l'effacer (voir handleConnectionLost, "le stop doit être
     * manuel"). Le seul vrai arrêt reste stopRecording()/disconnect(), jamais une tentative
     * de connexion qui démarre.
     */
    private fun beginConnecting(mode: ConnectionMode, bluetoothName: String? = null) {
        _state.update {
            ObdUiState(
                host = it.host,
                port = it.port,
                connectionMode = mode,
                bluetoothDeviceName = bluetoothName ?: it.bluetoothDeviceName,
                recordings = it.recordings,
                probes = it.probes,
                logs = it.logs,
                isRecording = it.isRecording,
                recordingSamples = it.recordingSamples,
                graphPid = it.graphPid,
                graphHistory = it.graphHistory,
                connectionState = ConnectionState.CONNECTING
            )
        }
    }

    /** Referme une éventuelle connexion précédente (ex: reconnexion après une erreur)
     * avant d'en ouvrir une nouvelle, pour ne pas laisser un client orphelin tourner en
     * tâche de fond. N'arrête PAS l'enregistrement (voir beginConnecting) : seuls le
     * sondage FAP et l'auto-test, non conçus pour survivre à une reconnexion, le sont. */
    private fun prepareForNewConnection() {
        pollJob?.cancel()
        stopAutoTest()
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
    private suspend fun finishConnecting(c: Elm327Client, isAutoRetry: Boolean, doConnect: suspend () -> Boolean) {
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
                    errorMessage = null,
                    supportedPids = supported,
                    vin = vin,
                    protocol = c.detectedProtocol,
                    dtcHistory = historyStore.load(vehicleId),
                    values = it.values + contextValues
                )
            }
            EventLog.log(
                (if (isAutoRetry) "Reconnecté" else "Connecté") +
                    " : VIN=${vin ?: "inconnu"}, protocole=${c.detectedProtocol ?: "inconnu"}"
            )
            startPolling(c, supported)
            // Un enregistrement mis en pause par une coupure (voir handleConnectionLost/
            // pauseRecordingForReconnect) reprend sur le MÊME fichier dès que la session
            // revit : recordingWriter non nul mais recordingJob nul signale ce cas précis
            // (un enregistrement démarré normalement a déjà les deux non nuls, voir
            // startRecording). Le stop reste manuel : ce n'est jamais ici qu'on en démarre
            // un nouveau, seulement qu'on reprend celui déjà en cours.
            if (recordingWriter != null && recordingJob == null) {
                resumeRecordingLoop()
            }
        } catch (e: CancellationException) {
            // c n'est affecté à `client` qu'après doConnect() plus haut : si l'annulation
            // arrive avant, personne d'autre ne connaît ce client pour le refermer (voir
            // A7). Idempotent et sans risque si c a déjà été publié et fermé ailleurs.
            c.disconnect()
            throw e
        } catch (e: Exception) {
            c.disconnect()
            client = null
            // Comme pour la demande de connexion elle-même : un échec d'auto-retry n'est
            // pas journalisé individuellement (bruit toutes les 5s tant que le contact n'est
            // pas mis), seul un échec suite à une action explicite l'est.
            if (!isAutoRetry) EventLog.log("Échec de connexion : ${e.message ?: "raison inconnue"}")
            _state.update {
                it.copy(
                    connectionState = if (isAutoRetry) ConnectionState.RECONNECTING else ConnectionState.ERROR,
                    errorMessage = e.message ?: "Connexion échouée"
                )
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
            // Filet de sécurité contre une sonde "zombie" : constaté sur capture réelle
            // (obd_20260911_201421.csv) qu'un clone ELM327 peut cesser de répondre à TOUT
            // PID sans jamais lever d'exception ni fermer le socket (readPidBytes renvoie
            // simplement null en boucle) : c.isConnected reste vrai, newValues reste vide
            // à chaque cycle, et sans ce filet handleConnectionLost n'était donc jamais
            // déclenché malgré 30s+ de valeurs figées. Remis à zéro à chaque lecture réussie
            // ET pendant une pause diagnostique légitime (refresh DTC, sondage FAP : ça peut
            // durer plusieurs secondes sans qu'aucun PID ne soit lu, ce n'est pas une panne).
            var lastSuccessAtMs = System.currentTimeMillis()
            // 0 (pas System.currentTimeMillis()) : un premier releve MIL arrive des le
            // premier cycle eligible plutot que d'attendre un plein MIL_CHECK_INTERVAL_MS,
            // pour qu'un enregistrement court (smoke test, trajet bref) ait quand meme une
            // colonne MIL renseignee des le debut (voir capturer les defauts pendant
            // l'enregistrement, ci-dessous).
            var lastMilCheckAtMs = 0L
            while (c.isConnected) {
                if (dtcOperationInProgress) {
                    lastSuccessAtMs = System.currentTimeMillis()
                } else {
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
                            lastSuccessAtMs = System.currentTimeMillis()
                            _state.update { s ->
                                // Lu depuis s (pas une variable capturée plus haut) : le PID
                                // suivi a pu changer entre deux cycles de polling.
                                val graphValue = s.graphPid?.let { newValues[it] }
                                val number = graphValue?.let { extractLeadingNumber(it.text) }
                                val history = if (number != null) {
                                    (s.graphHistory + GraphPoint(graphValue.updatedAtMs, number))
                                        .takeLast(GRAPH_HISTORY_MAX_POINTS)
                                } else {
                                    s.graphHistory
                                }
                                s.copy(values = s.values + newValues, graphHistory = history)
                            }
                        } else if (System.currentTimeMillis() - lastSuccessAtMs > ZOMBIE_CONNECTION_TIMEOUT_MS) {
                            handleConnectionLost(c, "Plus aucune réponse de la sonde depuis ${ZOMBIE_CONNECTION_TIMEOUT_MS / 1000}s")
                            return@launch
                        }

                        // Surveillance MIL en arrière-plan, à un rythme bien plus lent que le
                        // reste du cycle (voir MIL_CHECK_INTERVAL_MS) : sert à capturer un
                        // nouveau défaut PENDANT un enregistrement en cours au lieu de dépendre
                        // de l'utilisateur pour aller manuellement sur l'écran DTC (le CSV
                        // n'avait jusqu'ici aucune colonne reflétant l'état du voyant). Revérifié
                        // comme les PID ci-dessus : dtcOperationInProgress a pu devenir vrai
                        // entre le début du cycle et ce point.
                        if (!dtcOperationInProgress &&
                            System.currentTimeMillis() - lastMilCheckAtMs > MIL_CHECK_INTERVAL_MS
                        ) {
                            lastMilCheckAtMs = System.currentTimeMillis()
                            try {
                                val (mil, count) = c.readMilStatus()
                                if (_state.value.milOn != true && mil) {
                                    // Transition éteint/non lu -> allumé : va chercher les codes
                                    // réels (readStoredDtcs, "03"), pas seulement ce compteur.
                                    // Volontairement PAS aussi complet qu'un refreshDtcs() (pas
                                    // de pending/readiness/freeze frame ici) : le but est
                                    // d'identifier VITE ce qui vient d'apparaître pendant le
                                    // trajet, pas de reproduire l'écran DTC en arrière-plan.
                                    val stored = c.readStoredDtcs()
                                    val history = historyStore.record(vehicleId, stored)
                                    EventLog.log(
                                        "Voyant moteur (MIL) allumé pendant le trajet : " +
                                            stored.joinToString(", ").ifEmpty { "$count code(s) annoncé(s), détail illisible" }
                                    )
                                    _state.update {
                                        it.copy(
                                            milOn = mil,
                                            dtcCount = count,
                                            storedDtcs = stored,
                                            dtcHistory = history,
                                            dtcLastSuccessAtMs = System.currentTimeMillis()
                                        )
                                    }
                                } else {
                                    _state.update { it.copy(milOn = mil, dtcCount = count) }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Ratée ponctuelle traitée comme n'importe quel PID de toPoll
                                // ci-dessus (voir newValues) : un échec de decode/timeout isolé
                                // sur cette seule vérification ne doit pas faire perdre toute la
                                // session. Une vraie coupure sera de toute façon détectée par la
                                // lecture normale des PID ou le filet anti-zombie plus haut.
                            }
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
     * Connexion perdue pendant le polling (coupure WiFi de la sonde, contact coupé, micro
     * coupure réseau, etc.). soTimeout (3s, voir Elm327Client.connect) borne le délai avant
     * qu'une lecture bloquée ne lève ici ; le watchdog de startPolling couvre en plus le cas
     * d'une sonde qui ne lève rien mais ne répond plus jamais (voir capture réelle
     * obd_20260911_201421.csv : 30s+ de valeurs vides sans la moindre exception).
     *
     * Passe en RECONNECTING, pas ERROR : la boucle de reconnexion automatique
     * (startAutoReconnectLoop) reprend seule, SANS action de l'utilisateur, et un
     * enregistrement ou un graphique en cours doivent reprendre avec elle dès que la
     * session revit (voir pauseRecordingForReconnect/resumeRecordingLoop dans
     * finishConnecting) plutôt que de s'arrêter ici. Le seul vrai arrêt reste manuel
     * (bouton "Arrêter", ou disconnect() qui repasse par une reconstruction complète de
     * l'état). D'où un .copy() qui préserve tout par défaut (recording en cours, historique
     * du graphique, VIN/DTC déjà affichés) plutôt qu'une liste blanche à entretenir à la
     * main : une session précédente reconstruisait l'état en clair et avait déjà oublié
     * deux fois d'y ajouter un nouveau champ (connectionMode, bluetoothDeviceName), les
     * réinitialisant silencieusement (voir beginConnecting, qui reste lui volontairement
     * un allowlist explicite car il DOIT repartir propre sur l'identité véhicule).
     *
     * [source] est le client qui a détecté la perte, pas forcément celui actuellement en
     * champ `client` : un job de diagnostic annulé peut encore livrer son exception après
     * qu'une reconnexion a déjà remplacé `client` par une nouvelle session (voir A2). Sans
     * cette vérification, ce job périmé fermerait la session suivante à la place de la sienne.
     */
    private fun handleConnectionLost(source: Elm327Client, message: String) {
        if (client !== source) return
        EventLog.log("Coupure : $message")
        stopAutoTest()
        pauseRecordingForReconnect()
        stopFapScan()
        unregisterNetworkCallback()
        viewModelScope.launch(Dispatchers.IO) { source.disconnect() }
        client = null
        _state.update {
            it.copy(
                connectionState = ConnectionState.RECONNECTING,
                errorMessage = message,
                dtcLoading = false
            )
        }
    }

    /**
     * Met l'échantillonnage en pause sans rien fermer : le fichier (recordingWriter),
     * ses colonnes et le compteur d'échantillons restent en l'état, isRecording reste
     * vrai. Seule la coroutine de la boucle (recordingJob) est annulée, pour ne pas
     * continuer à écrire des lignes vides pendant la coupure. Reprend via
     * resumeRecordingLoop() dès que finishConnecting() réussit à nouveau, sur le MÊME
     * fichier : "le stop doit être manuel" (voir stopRecording, le seul vrai arrêt).
     */
    private fun pauseRecordingForReconnect() {
        recordingJob?.cancel()
        recordingJob = null
    }

    /**
     * Lecture à la demande (pas de polling continu, ce sont des données de diagnostic) :
     * DTC stockés/en attente, moniteurs de préparation, et freeze frame si un DTC est présent.
     */
    fun refreshDtcs() {
        val c = client ?: return
        // Une deuxième entrée sur l'écran DTC pendant qu'un refresh tourne encore, un
        // sondage FAP en cours, ou un smoke test automatique, annule le précédent plutôt
        // que de les laisser cogner en parallèle sur le fil (voir beginExclusiveDiagnostic).
        val previousJobs = beginExclusiveDiagnostic()
        dtcJob = viewModelScope.launch {
            dtcOperationCount++
            _state.update { it.copy(dtcLoading = true, dtcError = null) }
            try {
                // cancel() ne fait que DEMANDER l'arrêt des jobs précédents : sans ce join,
                // leur propre restauration (ex: ATH0 après une capture headers, cf. A8) peut
                // encore être en vol et leur commande se mélanger avec la nôtre sur le même
                // mutex. Les DEUX jobs capturés par beginExclusiveDiagnostic (dtcJob ET
                // autoTestJob) doivent être attendus, pas seulement l'un des deux (voir B2 :
                // un appel à stopAutoTest() séparé de stopFapScanAndGetPrevious() perdait la
                // référence au job de ce dernier avant que l'appelant ait pu le récupérer).
                previousJobs.forEach { it.join() }
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
        val previousJobs = beginExclusiveDiagnostic()
        dtcJob = viewModelScope.launch {
            dtcOperationCount++
            // dtcLoading=false : ce job vient d'annuler un éventuel refreshDtcs() en cours
            // (dtcJob partagé). Son "finally" ne s'exécute jamais (coroutine annulée avant),
            // donc dtcLoading resterait bloqué à true (bouton Rafraîchir désactivé pour de
            // bon) si on ne le remettait pas ici.
            _state.update { it.copy(headerProbeResult = "Lecture...", dtcLoading = false) }
            try {
                // Voir le commentaire équivalent dans refreshDtcs (A8/B2) : attendre que les
                // jobs précédents (dtcJob ET autoTestJob) aient fini leur propre restauration
                // avant d'envoyer nos commandes.
                previousJobs.forEach { it.join() }
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
        EventLog.log("Démarrage du sondage DID %04X-%04X".format(startDid, endDid))
        // Adresse destinataire optionnelle (ATSH, voir Elm327Client.setTargetHeader) : les
        // essais PC de référence ciblaient explicitement 7E0, alors que ce sondage envoie
        // par défaut en diffusion (voir audit, "Sondage UDS expérimental") — sans cette
        // option il n'y a aucun moyen de reproduire le même essai depuis l'app.
        val targetHeader = targetHeaderText.trim().removePrefix("0x").removePrefix("0X").uppercase()
        if (targetHeader.isNotEmpty()) {
            // Seule forme réellement prise en charge : CAN 11 bits, exactement 3 chiffres
            // hexadécimaux (ex: 7E0). "1" passait toIntOrNull(16) sans être une forme valide
            // d'ATSH (voir ELM327DS.pdf p. 25) ; le CAN 29 bits (8 chiffres) et le ciblage
            // non-CAN existent sur l'ELM327 mais ne sont pas pris en charge ici (voir audit
            // B7) : autant le refuser explicitement plutôt que laisser croire à un ciblage
            // qui ne s'applique pas comme demandé.
            if (targetHeader.length != 3 || targetHeader.toIntOrNull(16) == null) {
                _state.update {
                    it.copy(fapScanError = "Adresse cible invalide : exactement 3 chiffres hexadécimaux (ex: 7E0), ou la laisser vide.")
                }
                return
            }
            if (!c.isCanProtocol) {
                _state.update {
                    it.copy(fapScanError = "Ciblage d'adresse non pris en charge hors CAN (protocole détecté : ${c.detectedProtocol ?: "non-CAN"}).")
                }
                return
            }
        }

        // Ferme aussi proprement un refresh DTC ou une capture de headers en cours
        // (dtcJob partagé) : voir le commentaire de refreshDtcs. Pas de stopAutoTest() ici
        // contrairement à refreshDtcs/probeHeaderFormat : runAutoTest() appelle lui-même
        // startFapScan() comme dernière étape, ce qui s'annulerait sa propre coroutine
        // (autoTestJob) juste avant d'attendre le résultat de ce même appel.
        val previousJob = stopFapScanAndGetPrevious()

        val dir = File(getApplication<Application>().filesDir, "probes").apply { mkdirs() }
        val baseName = "fap_scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val probeFile = try {
            uniqueFile(dir, baseName)
        } catch (e: Exception) {
            _state.update { it.copy(fapScanError = "Impossible de créer le fichier de sondage : ${e.message}") }
            return
        }
        val writer = try {
            probeFile.bufferedWriter()
        } catch (e: Exception) {
            _state.update { it.copy(fapScanError = "Impossible d'ouvrir le fichier de sondage : ${e.message}") }
            return
        }
        try {
            writeSessionMetadata(writer, listOf("Adresse cible" to targetHeader.ifEmpty { "diffusion (défaut)" }))
            writer.write(csvRow(listOf("Horodatage", "DID", "Résultat", "NRC", "Détail", "Réponse brute")))
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            // En-tête non écrit : le writer reste fermé explicitement ici (voir A5, résiduel
            // relevé par l'audit B4) plutôt que de laisser un descripteur de fichier ouvert
            // sans jamais plus être référencé nulle part.
            runCatching { writer.close() }
            _state.update { it.copy(fapScanError = "Impossible d'initialiser le fichier de sondage : ${e.message}") }
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
                // ATSH n'a de sens qu'en CAN : la validation dans startFapScan() refuse déjà
                // un protocole non-CAN ou une forme invalide avant d'arriver ici. Il reste
                // possible que l'adaptateur refuse quand même la commande (réponse "?", voir
                // ELM327DS.pdf p. 8-9) : vérifier la réponse plutôt que de continuer comme si
                // la cible avait été appliquée (voir audit B7).
                val targetApplied = if (targetHeader.isEmpty()) {
                    true
                } else {
                    try {
                        c.setTargetHeader(targetHeader)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _state.update {
                            it.copy(
                                fapScanError = e.message ?: "Adresse cible refusée par l'adaptateur",
                                fapScanOutcome = FapScanOutcome.ERREUR
                            )
                        }
                        false
                    }
                }
                if (targetApplied) {
                    try {
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
                        if (targetHeader.isNotEmpty()) {
                            // Best-effort : si la restauration échoue aussi, le signaler
                            // plutôt que de l'avaler en silence (voir audit B7), sans pour
                            // autant masquer une éventuelle exception réelle du bloc ci-dessus.
                            withContext(NonCancellable) {
                                runCatching { c.resetTargetHeader() }
                                    .onFailure { EventLog.log("Restauration de l'adresse cible échouée : ${it.message}") }
                            }
                        }
                    }
                    // Verdict "terminé" seulement si rien n'a déjà tranché autrement ci-dessus
                    // (échec d'écriture) : une annulation externe ne redescend jamais jusqu'ici
                    // (voir stopFapScanAndGetPrevious pour son propre verdict "interrompu").
                    if (_state.value.fapScanOutcome == null) {
                        _state.update { it.copy(fapScanOutcome = FapScanOutcome.TERMINE) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Distinct de l'INTERROMPU par défaut que poserait stopFapScanAndGetPrevious
                // ci-dessous (via handleConnectionLost) : une vraie exception de transport
                // n'est pas une interruption volontaire (voir audit, "Résultats d'outils").
                _state.update { it.copy(fapScanError = e.message ?: "Lecture échouée", fapScanOutcome = FapScanOutcome.ERREUR) }
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
        val file = try {
            uniqueFile(dir, baseName)
        } catch (e: Exception) {
            _state.update { it.copy(recordingError = "Impossible de créer l'enregistrement : ${e.message}") }
            return
        }
        val writer = try {
            file.bufferedWriter()
        } catch (e: Exception) {
            _state.update { it.copy(recordingError = "Impossible d'ouvrir l'enregistrement : ${e.message}") }
            return
        }
        try {
            writeSessionMetadata(writer)
            writer.write(csvRow(listOf("Horodatage", "État", "MIL", "Codes stockés") + columns.map { it.label }))
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            // En-tête non écrit : le writer reste fermé explicitement ici (voir A5, résiduel
            // relevé par l'audit B4) plutôt que de laisser un descripteur de fichier ouvert
            // sans jamais plus être référencé nulle part.
            runCatching { writer.close() }
            _state.update { it.copy(recordingError = "Impossible d'initialiser l'enregistrement : ${e.message}") }
            return
        }

        EventLog.log("Démarrage de l'enregistrement ($baseName.csv)")
        recordingColumns = columns
        recordingWriter = writer
        _state.update { it.copy(isRecording = true, recordingSamples = 0, recordingError = null) }
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

        resumeRecordingLoop()
    }

    /**
     * Boucle d'échantillonnage, extraite de [startRecording] pour être réutilisable après
     * une coupure : [finishConnecting] l'appelle à nouveau dès qu'une reconnexion réussit
     * sur un enregistrement resté en pause (voir pauseRecordingForReconnect), sur le même
     * fichier/writer, sans repasser par startRecording (qui refuserait, isRecording étant
     * resté vrai pendant la coupure).
     */
    private fun resumeRecordingLoop() {
        // Colonnes réellement mesurées par le polling (voir startPolling) : les PID
        // "contexte" (CONTEXT_ONLY_PIDS) sont lus une fois à la connexion et jamais
        // périmés par design, ils ne comptent donc pas dans "combien de mesures
        // dynamiques cette ligne a obtenu" (voir B3 ci-dessous).
        val dynamicColumns = recordingColumns.filter { it.pid !in PidCatalog.CONTEXT_ONLY_PIDS }
        recordingJob = viewModelScope.launch {
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
            while (true) {
                delay(RECORDING_INTERVAL_MS)
                val snapshot = _state.value
                val now = System.currentTimeMillis()
                val cells = recordingColumns.map { def ->
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
                // Colonne "État" : une pause diagnostique (refresh DTC, capture headers,
                // sondage FAP) suspend le polling sans arrêter l'enregistrement, sinon on
                // rejoue silencieusement les dernières valeurs comme si elles étaient
                // fraîches (voir A1). Une ligne qui n'a par ailleurs obtenu AUCUNE mesure
                // dynamique fraîche (sonde muette, voir capture réelle
                // obd_20260911_201421.csv) ne doit pas non plus se lire "ok" comme une
                // ligne normale, ni une ligne partiellement vide se confondre avec une
                // ligne complète (voir B3) : une coupure réseau (RECONNECTING) produit le
                // même trou de mesures, pour la même raison.
                val freshDynamicCount = recordingColumns.indices.count { i ->
                    recordingColumns[i].pid !in PidCatalog.CONTEXT_ONLY_PIDS && cells[i].isNotEmpty()
                }
                val etat = when {
                    dtcOperationInProgress -> "pause diagnostic"
                    dynamicColumns.isEmpty() || freshDynamicCount == 0 -> "aucune mesure"
                    freshDynamicCount < dynamicColumns.size -> "partiel"
                    else -> "ok"
                }
                // MIL/codes stockés : alimentés en arrière-plan par la "Surveillance MIL" de
                // startPolling, pas par une commande envoyée ici (voir MIL_CHECK_INTERVAL_MS).
                // "non lu" tant qu'aucune vérification n'a encore eu lieu (ex: tout début d'un
                // enregistrement très court) : distinct d'un MIL éteint confirmé, pour ne pas
                // laisser croire à une lecture qui n'a pas eu lieu.
                val milText = when (snapshot.milOn) {
                    true -> "allumé"
                    false -> "éteint"
                    null -> "non lu"
                }
                // Les codes réels (storedDtcs) ne sont récupérés qu'à la transition MIL éteint
                // -> allumé ou via un refresh manuel de l'écran DTC (voir startPolling) : entre
                // les deux, seul le compteur brut de PID01 est disponible, affiché comme repli
                // plutôt que de laisser la colonne vide alors qu'un chiffre est bien connu.
                val dtcText = when {
                    snapshot.storedDtcs != null -> snapshot.storedDtcs.joinToString(" ").ifEmpty { "aucun" }
                    snapshot.dtcCount != null -> "${snapshot.dtcCount} annoncé(s), détail non lu"
                    else -> ""
                }
                val row = listOf(timestampFormat.format(Date()), etat, milText, dtcText) + cells
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
                    // Erreur disque réelle (plein, permission révoquée), pas une coupure
                    // réseau : celle-ci ne touche jamais recordingWriter (voir
                    // pauseRecordingForReconnect), donc un vrai arrêt ici reste justifié.
                    _state.update { it.copy(recordingError = "Écriture de l'enregistrement échouée, arrêté.") }
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
            .onFailure { EventLog.log("Fermeture de l'enregistrement échouée : ${it.message}") }
        recordingWriter = null
        if (wasRecording) {
            EventLog.log("Arrêt de l'enregistrement (${_state.value.recordingSamples} échantillons)")
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
        EventLog.log("Démarrage du smoke test automatique")

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
                // reprenne, sinon elle ne capturerait que des valeurs figées. Le join() du
                // job précédent est couvert par CE MÊME try/finally, pas avant lui (voir
                // B10) : une annulation pendant cette attente doit décrémenter le compteur
                // tout autant qu'une annulation pendant les vérifications qui suivent, sinon
                // il reste positif indéfiniment (polling en pause permanente, watchdog
                // neutralisé puisqu'il se croit en pause diagnostique légitime).
                dtcOperationCount++
                try {
                    previousJob?.join()
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
                    // Ne prend la propriété de l'enregistrement que s'il en démarre un
                    // lui-même (voir B1) : un enregistrement manuel déjà en cours doit
                    // continuer après ce test, pas s'arrêter avec lui. Les échantillons sont
                    // comptés en delta sur la fenêtre du test, pas en valeur absolue, pour la
                    // même raison (un enregistrement manuel déjà ancien aurait sinon un total
                    // qui ne reflète pas ce que CE test vient d'écrire).
                    val alreadyRecording = _state.value.isRecording
                    if (!alreadyRecording) {
                        startRecording()
                        autoTestOwnsRecording = _state.value.isRecording
                    }
                    if (!_state.value.isRecording) {
                        setCheck(5, AutoTestStatus.ECHEC, _state.value.recordingError ?: "L'enregistrement n'a pas démarré")
                    } else {
                        val samplesBefore = _state.value.recordingSamples
                        delay(10_000)
                        val samplesDuring = _state.value.recordingSamples - samplesBefore
                        if (autoTestOwnsRecording) {
                            stopRecording()
                            autoTestOwnsRecording = false
                        }
                        if (samplesDuring >= 1) {
                            setCheck(5, AutoTestStatus.OK, "$samplesDuring échantillon(s) écrit(s) pendant le test")
                        } else {
                            setCheck(5, AutoTestStatus.ECHEC, "Aucun échantillon écrit pendant le test (voir l'erreur d'enregistrement)")
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
        // Seule la ressource que LUI a démarrée doit s'arrêter avec lui (voir B1) : cette
        // fonction est aussi appelée par précaution depuis refreshDtcs()/probeHeaderFormat()/
        // prepareForNewConnection()/handleConnectionLost(), bien avant tout smoke test réel,
        // et ne doit alors jamais toucher un enregistrement manuel en cours.
        if (autoTestOwnsRecording) {
            stopRecording()
            autoTestOwnsRecording = false
        }
        stopFapScan()
        _state.update { it.copy(isAutoTesting = false) }
    }

    /**
     * Point d'entrée commun à refreshDtcs()/probeHeaderFormat() : annule le diagnostic
     * exclusif en cours (dtcJob ET autoTestJob, un sondage FAP manuel ou l'un des deux
     * appartenant à un smoke test automatique) et renvoie les jobs à attendre (Job.join())
     * avant d'envoyer de nouvelles commandes sur le même mutex.
     *
     * Capture la référence à autoTestJob AVANT de l'annuler, plutôt que de déléguer à
     * stopAutoTest() puis récupérer dtcJob séparément via stopFapScanAndGetPrevious() (voir
     * B2/A8) : stopAutoTest() appelle lui-même stopFapScan() en interne et remet dtcJob à
     * null avant que l'appelant ait pu le récupérer, si bien que le join() ajouté pour A8
     * n'attendait jamais rien en pratique (reproduit : ATH1 → 0101 → ATH0 au lieu d'attendre
     * la restauration en cours).
     */
    private fun beginExclusiveDiagnostic(): List<Job> {
        val previousAutoTestJob = autoTestJob
        autoTestJob?.cancel()
        autoTestJob = null
        if (autoTestOwnsRecording) {
            stopRecording()
            autoTestOwnsRecording = false
        }
        _state.update { it.copy(isAutoTesting = false) }
        val previousDtcJob = stopFapScanAndGetPrevious()
        return listOfNotNull(previousDtcJob, previousAutoTestJob)
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
        EventLog.log("Déconnexion demandée par l'utilisateur")
        // Empêche la boucle de reconnexion automatique de relancer aussitôt une connexion
        // qu'on vient de couper volontairement (voir startAutoReconnectLoop) ; levé par la
        // prochaine tentative explicite (connect()/connectBluetooth()).
        userRequestedDisconnect = true
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
                probes = it.probes,
                logs = it.logs
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
        // Bien plus lent que RECORDING_INTERVAL_MS : le MIL/nombre de codes stockés ne
        // change pas d'une seconde à l'autre comme le régime ou la vitesse, une commande
        // PID01 de plus à chaque cycle rapide serait un coût réseau inutile pour une
        // surveillance qui reste utile même vérifiée toutes les 30s (voir la colonne MIL
        // du CSV, ObdViewModel.startPolling/resumeRecordingLoop).
        private const val MIL_CHECK_INTERVAL_MS = 30_000L
        // 5 cycles : le délai entre deux cycles n'est QUE le delay(300) ci-dessus, pas le
        // temps du cycle complet (voir audit) : chaque PID lu dans ce cycle ajoute son
        // propre aller-retour réseau, largement variable selon la sonde/le transport. Reste
        // néanmoins bien sous VALUE_UNAVAILABLE_AFTER_MS (10s) et STALE_AFTER_MS côté
        // Dashboard (3s) en pratique, donc jamais visible comme périmée, pour un cinquième
        // des requêtes qu'au rythme normal (voir PidCatalog.SLOW_PIDS).
        private const val SLOW_PID_EVERY_N_CYCLES = 5
        // Une tentative échouée peut déjà prendre ~9s (4s de recherche Wi-Fi + 5s de
        // connexion socket, voir requestWifiNetwork/Elm327Client.connect) : ce délai
        // s'ajoute entre deux tentatives, pour ne pas marteler en continu tant que le
        // contact n'est pas mis.
        private const val AUTO_RECONNECT_INTERVAL_MS = 5_000L
        // Cas réel (obd_20260911_201421.csv) : 30s+ sans la moindre valeur, ni la moindre
        // exception. Assez long pour ne jamais confondre ce filet avec une lenteur normale
        // de la sonde (un cycle complet dépasse rarement 1-2s), assez court pour reprendre
        // une connexion dans le même ordre de grandeur que AUTO_RECONNECT_INTERVAL_MS.
        private const val ZOMBIE_CONNECTION_TIMEOUT_MS = 15_000L
    }
}
