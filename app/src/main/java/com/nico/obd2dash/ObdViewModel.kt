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
import androidx.annotation.StringRes
import androidx.annotation.PluralsRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

// PidCatalog.SLOW_PIDS ne sont relus que toutes les SLOW_PID_INTERVAL_MS (voir
// startPolling) : leur appliquer le seuil des mesures rapides les faisait passer pour
// périmées une fois sur deux avec un adaptateur Bluetooth (cycle de 2-3s, capture
// obd_SEAT-000000_20260924_172323.csv : lignes "partiel", températures vides).
internal const val SLOW_VALUE_UNAVAILABLE_AFTER_MS = 20_000L

/** Âge au-delà duquel la valeur de [pid] n'est plus présentée comme actuelle (voir ci-dessus). */
internal fun unavailableAfterMs(pid: Int): Long =
    if (pid in PidCatalog.SLOW_PIDS) SLOW_VALUE_UNAVAILABLE_AFTER_MS else VALUE_UNAVAILABLE_AFTER_MS

/**
 * Valeur de [pid] trop vieille pour être présentée comme l'état actuel du véhicule, à
 * [nowMs]. CONTEXT_ONLY_PIDS jamais : lus une seule fois à la connexion par design (voir
 * startPolling), leur âge dépasse ce seuil dès les premières secondes de CHAQUE session
 * sans que la valeur soit fausse.
 */
internal fun isUnavailable(pid: Int, value: GaugeValue, nowMs: Long): Boolean =
    pid !in PidCatalog.CONTEXT_ONLY_PIDS && nowMs - value.updatedAtMs > unavailableAfterMs(pid)

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
    val dataAvailability: ObdDataAvailability = ObdDataAvailability.NOT_CHECKED,
    val vehicleResponseObserved: Boolean = false,
    val mode01BitmapReceived: Boolean = false,
    val pidDiscoveryComplete: Boolean = false,
    val values: Map<Int, GaugeValue> = emptyMap(),
    // Paramètres affichés en gros sur le Dashboard (voir GaugeRow), choisis par
    // l'utilisateur depuis Réglages (jusqu'à MAX_BIG_GAUGE_PIDS) plutôt que le triplet
    // RPM/vitesse/température fixe d'avant : persisté comme host/port, pour retrouver le
    // même panneau d'un lancement à l'autre sans redemander à chaque fois. Défaut =
    // PidCatalog.PRIMARY_PIDS (comportement inchangé tant que rien n'a été personnalisé).
    val bigGaugePids: Set<Int> = PidCatalog.PRIMARY_PIDS,
    // PID actuellement suivi par l'écran Graphique, et son historique (voir GraphPoint).
    // Repartent à zéro à chaque nouvelle connexion (comme le reste de l'état) : un
    // historique qui continuerait après une coupure/reconnexion afficherait une tendance
    // avec un trou silencieux au milieu.
    val graphPid: Int? = null,
    val graphHistory: List<GraphPoint> = emptyList(),
    val vin: String? = null,
    val protocol: String? = null,
    // Identité de l'adaptateur et optimisations de lecture acceptées (voir
    // Elm327Client.readAdapterInfo/supportsResponseCount/supportsMultiPid), relues à chaque
    // connexion : distinguent un clone d'une puce STN dans l'export, indépendamment du
    // transport Wi-Fi/Bluetooth.
    val adapterInfo: AdapterInfo? = null,
    val supportsResponseCount: Boolean? = null,
    val supportsMultiPid: Boolean? = null,
    // null = jamais lu avec succès (pas encore connecté, ou dernière lecture en échec) :
    // distinct de "lu et confirmé sans défaut", pour ne pas afficher un faux résultat propre.
    val milOn: Boolean? = null,
    val dtcCount: Int? = null,
    val milLastSuccessAtMs: Long? = null,
    val storedDtcs: List<String>? = null,
    val storedDtcsLastSuccessAtMs: Long? = null,
    val pendingDtcs: List<String>? = null,
    val readiness: List<ReadinessMonitor> = emptyList(),
    val freezeFrame: Map<Int, String> = emptyMap(),
    // Code qui a déclenché le freeze frame (mode 02 PID 02) : null si non lu ou non fourni.
    val freezeFrameDtc: String? = null,
    val dtcHistory: List<DtcHistoryEntry> = emptyList(),
    val dtcLoading: Boolean = false,
    val dtcError: String? = null,
    // Dernier scan complet (pending/readiness/freeze frame inclus). Une relecture
    // automatique des seuls codes stockés ne doit jamais rajeunir ce résultat.
    // Sans cette date, un
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
    // Décodage local, pas propre à une marque (voir VinDecoder) : utile pour qui relit ce
    // rapport plus tard (partage, forum) sans avoir le véhicule sous les yeux.
    state.vin?.let { vin ->
        val info = VinDecoder.decode(vin)
        val details = listOfNotNull(info.manufacturer ?: info.region, info.modelYear?.let { "année-modèle $it" })
        if (details.isNotEmpty()) sb.appendLine("Décodage VIN : " + details.joinToString(", "))
    }
    sb.appendLine("Protocole : ${state.protocol ?: "inconnu"}")
    sb.appendLine("Adaptateur : ${state.adapterInfo?.summary() ?: "non identifié"}")
    if (state.supportsResponseCount != null || state.supportsMultiPid != null) {
        fun yesNo(v: Boolean?) = when (v) { true -> "oui"; false -> "non"; null -> "non testé" }
        sb.appendLine(
            "Optimisations de lecture : réponse unique ${yesNo(state.supportsResponseCount)}, " +
                "groupage ${yesNo(state.supportsMultiPid)}"
        )
    }
    sb.appendLine("Données OBD standard : " + when (state.dataAvailability) {
        ObdDataAvailability.NOT_CHECKED -> "découverte non terminée"
        ObdDataAvailability.NO_VEHICLE_RESPONSE -> "sonde joignable, aucune réponse véhicule confirmée"
        ObdDataAvailability.NO_STANDARD_MEASUREMENTS -> "véhicule répond, aucune mesure exploitable par le catalogue actuel"
        ObdDataAvailability.STANDARD_MEASUREMENTS_AVAILABLE -> "paramètres disponibles dans le catalogue actuel"
    })
    sb.appendLine("Bitmap mode 01 reçu : ${state.mode01BitmapReceived}")
    sb.appendLine("Découverte PID complète : ${state.pidDiscoveryComplete}")
    sb.appendLine(
        "MIL : " + when (state.milOn) {
            true -> "allumé"
            false -> "éteint"
            null -> "non lu"
        }
    )
    sb.appendLine("Dernière lecture MIL réussie : " +
        (state.milLastSuccessAtMs?.let { timestampFormat.format(Date(it)) } ?: "jamais"))
    sb.appendLine("Dernière lecture des codes stockés réussie : " +
        (state.storedDtcsLastSuccessAtMs?.let { timestampFormat.format(Date(it)) } ?: "jamais"))

    // Les sections pending/readiness/freeze frame datent de cette dernière lecture
    // réussie, pas de la date d'export ci-dessus : sans cette ligne, un ancien résultat
    // encore affiché après un refresh en échec se lisait comme une lecture actuelle
    // (voir A6). dtcError est celle de la tentative la PLUS RÉCENTE, potentiellement
    // postérieure à ce succès : les deux peuvent cohabiter (échec après un succès passé).
    sb.appendLine(
        "Dernière lecture DTC réussie : " +
            (state.dtcLastSuccessAtMs?.let { timestampFormat.format(Date(it)) } ?: "jamais")
    )
    sb.appendLine("Portée de cette lecture : scan complet (codes en attente, moniteurs, freeze frame si disponible).")
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
        state.freezeFrameDtc?.let { sb.appendLine("Code déclencheur : $it") }
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
                val suffix = if (isUnavailable(def.pid, it, nowMs)) {
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

/** Les états diagnostiques échantillonnés gardent la date de leur vraie lecture. */
internal fun recordingDiagnosticFields(state: ObdUiState, formatTime: (Long) -> String): List<String> {
    val mil = when (state.milOn) { true -> "allumé"; false -> "éteint"; null -> "non lu" }
    val codes = when {
        state.storedDtcs != null -> state.storedDtcs.joinToString(" ").ifEmpty { "aucun" }
        state.dtcCount != null -> "${state.dtcCount} annoncé(s), détail non lu"
        else -> ""
    }
    return listOf(mil, codes, state.milLastSuccessAtMs?.let(formatTime).orEmpty(),
        state.storedDtcsLastSuccessAtMs?.let(formatTime).orEmpty())
}

class ObdViewModel(application: Application) : AndroidViewModel(application) {

    // Pas de stringResource() ici : ce ViewModel n'est pas @Composable. getApplication()
    // reste un vrai Context Android, donc getString() classique fonctionne (même résolution
    // par ressources que côté UI, juste l'API pré-Compose).
    private fun getString(@StringRes res: Int): String = getApplication<Application>().getString(res)
    private fun getString(@StringRes res: Int, vararg args: Any): String = getApplication<Application>().getString(res, *args)
    private fun getQuantityString(@PluralsRes res: Int, quantity: Int): String =
        getApplication<Application>().resources.getQuantityString(res, quantity, quantity)

    private val prefs = application.getSharedPreferences("obd2dash", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        ObdUiState(
            host = prefs.getString(KEY_HOST, null) ?: "192.168.0.10",
            port = prefs.getString(KEY_PORT, null) ?: "35000",
            connectionMode = savedConnectionMode(),
            bluetoothDeviceName = prefs.getString(KEY_BLUETOOTH_NAME, null),
            bigGaugePids = loadBigGaugePids(DtcHistoryStore.UNKNOWN_VEHICLE)
        )
    )
    val state: StateFlow<ObdUiState> = _state

    private var client: Elm327Client? = null
    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var dtcJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vehicleId: String = DtcHistoryStore.UNKNOWN_VEHICLE
    // Voir VinDecoder.fileTag : préfixe des fichiers d'enregistrement/sondage (voir
    // startRecording/startFapScan), pour s'y retrouver entre plusieurs véhicules utilisant
    // la même appli. Recalculé au même moment que vehicleId ci-dessus, jamais laissé
    // périmer d'une connexion précédente.
    private var vehicleFileTag: String = VinDecoder.fileTag(null)
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

    // Une clé par véhicule (VIN, ou UNKNOWN_VEHICLE si non lu) plutôt qu'un seul réglage
    // global : demande explicite (le choix "gros paramètres" a du sens PAR véhicule, un
    // utilitaire diesel et une citadine essence n'ont pas les mêmes PID pertinents à
    // surveiller en gros). Même principe que DtcHistoryStore, déjà scindé par vehicleId.
    private fun bigGaugePidsKey(vehicleId: String) = "$KEY_BIG_GAUGE_PIDS:$vehicleId"

    // Repli sur l'ancienne clé globale (jamais scindée par véhicule avant ce correctif) si
    // rien n'est encore enregistré pour CE véhicule précis : évite de perdre un réglage
    // déjà fait par un utilisateur existant simplement parce que la clé a changé de forme.
    private fun loadBigGaugePids(vehicleId: String): Set<Int> =
        (prefs.getString(bigGaugePidsKey(vehicleId), null) ?: prefs.getString(KEY_BIG_GAUGE_PIDS, null))
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            ?: PidCatalog.PRIMARY_PIDS

    /**
     * Coche/décoche un paramètre pour l'affichage en gros (voir ObdUiState.bigGaugePids),
     * persisté immédiatement comme host/port, sous la clé du véhicule CONNECTÉ (voir
     * vehicleId, mis à jour par finishConnecting()). Refuse silencieusement d'ajouter un
     * septième paramètre plutôt que de dépasser MAX_BIG_GAUGE_PIDS : l'UI (Réglages)
     * désactive déjà les cases non cochées une fois le maximum atteint, ce garde-fou
     * n'est là qu'en repli.
     */
    fun setBigGaugePidSelected(pid: Int, selected: Boolean) {
        val current = _state.value.bigGaugePids
        val updated = when {
            !selected -> current - pid
            pid in current || current.size < MAX_BIG_GAUGE_PIDS -> current + pid
            else -> current
        }
        if (updated == current) return
        prefs.edit().putString(bigGaugePidsKey(vehicleId), updated.joinToString(",")).apply()
        _state.update { it.copy(bigGaugePids = updated) }
    }

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
            "Adaptateur" to (state.adapterInfo?.summary() ?: "non identifié"),
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

    /**
     * Change le transport ET tente réellement de s'y connecter, contrairement à
     * setConnectionMode() ci-dessus : utilisée par le bouton Wi-Fi/Bluetooth du Dashboard
     * (voir DashboardScreen.ModeButton), qui n'a plus de bouton "Se connecter" séparé juste
     * après comme dans l'ancien formulaire de Réglages. Sans ceci, taper Bluetooth ne
     * changeait que l'affichage : la boucle d'auto-reconnexion visait toujours le transport
     * persisté (Wi-Fi), et son prochain essai (5s) rappelait beginConnecting(WIFI), qui
     * réécrit connectionMode dans son état reconstruit — l'affichage revenait donc tout
     * seul sur Wi-Fi (constaté : "je clique sur Bluetooth, ça repasse sur Wi-Fi").
     *
     * Bluetooth sans appareil déjà choisi (KEY_BLUETOOTH_ADDRESS absent, ex. tout premier
     * essai de ce transport) : rien à reconnecter automatiquement, un appareil précis reste
     * à choisir dans Réglages. Le simple appel à setConnectionMode() ne suffit PAS ici : ça
     * ne fait que changer l'affichage SANS persister le mode (voir sa propre doc), donc la
     * boucle d'auto-reconnexion restait calée sur l'ancien transport persisté et l'écrasait
     * au cycle suivant (5s), reproduisant exactement le même bug que ce correctif visait à
     * régler (constaté deux fois : sans device connu, ça "repassait sur Wi-Fi" pareil).
     * Persiste donc explicitement KEY_CONNECTION_MODE ici, et repasse l'état à DISCONNECTED
     * (pas de tentative en cours pour ce transport tant qu'aucun appareil n'est choisi) :
     * attemptAutoConnect() lira alors le bon mode, verra qu'aucune adresse n'est encore
     * connue, et n'attentera simplement rien, sans jamais revenir sur Wi-Fi tout seul.
     */
    fun switchConnectionMode(mode: ConnectionMode) {
        when (mode) {
            ConnectionMode.WIFI -> connect(_state.value.host, _state.value.port)
            ConnectionMode.BLUETOOTH -> {
                val address = prefs.getString(KEY_BLUETOOTH_ADDRESS, null)
                val device = address?.let { runCatching { bluetoothAdapter()?.getRemoteDevice(it) }.getOrNull() }
                if (device != null) {
                    connectBluetooth(device)
                } else {
                    userRequestedDisconnect = false
                    connectJob?.cancel()
                    // Ferme aussi une session Wi-Fi en cours : passer l'état à DISCONNECTED
                    // sans ça laissait le polling, le client et l'enregistrement tourner en
                    // arrière-plan sur l'ancien transport, derrière un écran "déconnecté".
                    // L'enregistrement est mis en pause (pas arrêté, voir
                    // pauseRecordingForReconnect) : il reprendra à la prochaine connexion.
                    pauseRecordingForReconnect()
                    prepareForNewConnection()
                    prefs.edit().putString(KEY_CONNECTION_MODE, ConnectionMode.BLUETOOTH.name).apply()
                    _state.update { it.copy(connectionMode = mode, connectionState = ConnectionState.DISCONNECTED, errorMessage = null) }
                }
            }
        }
    }

    /** Change le PID suivi par l'écran Graphique ; repart d'un historique vide (voir GraphPoint). */
    fun selectGraphPid(pid: Int?) {
        if (pid != null) EventLog.log("Graphique : suivi du PID $pid")
        _state.update { it.copy(graphPid = pid, graphHistory = emptyList()) }
    }

    fun connect(host: String, portText: String, isAutoRetry: Boolean = false) {
        // Seule la boucle automatique s'efface devant une tentative en cours : une demande
        // explicite (bouton Wi-Fi/Bluetooth, Réglages) la remplace (connectJob?.cancel()
        // plus bas). Refuser ici perdait l'appui en silence, sans même persister le choix,
        // environ une fois sur deux sans adaptateur Wi-Fi présent (chaque essai Wi-Fi
        // automatique reste ~4s en CONNECTING sur ~9s de cycle).
        if (isAutoRetry && _state.value.connectionState == ConnectionState.CONNECTING) return
        userRequestedDisconnect = false

        val port = portText.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            // Une adresse refusée ne remplace pas la session existante. Ne pas annoncer
            // une coupure/pause alors que ce client et son acquisition fonctionnent encore.
            val keepConnected = _state.value.connectionState == ConnectionState.CONNECTED &&
                client?.isConnected == true
            _state.update {
                it.copy(
                    connectionState = when {
                        keepConnected -> ConnectionState.CONNECTED
                        isAutoRetry -> ConnectionState.RECONNECTING
                        else -> ConnectionState.ERROR
                    },
                    errorMessage = getString(R.string.error_wifi_invalid_address)
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
                            errorMessage = getString(R.string.error_wifi_no_network)
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
     * fait aucune découverte, seulement la connexion socket. Vérifié sur un vrai
     * adaptateur Bluetooth le 24/09, voir le commentaire de classe d'Elm327Client.
     */
    fun connectBluetooth(device: BluetoothDevice, isAutoRetry: Boolean = false) {
        // Voir connect() : une demande explicite remplace une tentative en cours.
        if (isAutoRetry && _state.value.connectionState == ConnectionState.CONNECTING) return
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
     *
     * supportedPids fait partie de ce deuxième groupe (pas une remise à zéro comme VIN/DTC) :
     * un oubli laissait GraphScreen sans aucun paramètre à proposer pendant CHAQUE nouvelle
     * tentative de reconnexion (cette fonction tournant à chaque essai, pas seulement le
     * premier), y compris juste après une coupure réelle en cours de route où la liste
     * précédente restait pourtant valable pour le même véhicule.
     */
    private fun beginConnecting(mode: ConnectionMode, bluetoothName: String? = null) {
        // Commun au Wi-Fi et au Bluetooth, y compris au retour des Réglages : mettre
        // la capture en pause AVANT d'effacer les valeurs ou de lancer le nouveau job.
        // Le fichier reste ouvert et finishConnecting reprendra seulement ses mesures.
        pauseRecordingForReconnect()
        _state.update {
            ObdUiState(
                host = it.host,
                port = it.port,
                connectionMode = mode,
                bluetoothDeviceName = bluetoothName ?: it.bluetoothDeviceName,
                bigGaugePids = it.bigGaugePids,
                recordings = it.recordings,
                probes = it.probes,
                logs = it.logs,
                isRecording = it.isRecording,
                recordingSamples = it.recordingSamples,
                graphPid = it.graphPid,
                graphHistory = it.graphHistory,
                supportedPids = it.supportedPids,
                connectionState = ConnectionState.CONNECTING
            )
        }
    }

    /** Referme une éventuelle connexion précédente (ex: reconnexion après une erreur)
     * avant d'en ouvrir une nouvelle, pour ne pas laisser un client orphelin tourner en
     * tâche de fond. La capture manuelle est déjà en pause (voir beginConnecting) : seuls le
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
            // Une demande explicite peut remplacer cette tentative pendant doConnect() (voir
            // connect()) : un appel Java bloquant (Socket.connect) ne voit pas l'annulation,
            // il faut la vérifier avant de publier ce client à la place de celui du nouveau job.
            currentCoroutineContext().ensureActive()
            client = c

            val discovery = c.discoverPidSupport()
            val supported = discovery.supportedPids
            val availability = standardObdAvailability(discovery.vehicleResponseObserved, supported)
            _state.update {
                it.copy(
                    supportedPids = if (discovery.vehicleResponseObserved) supported else it.supportedPids,
                    dataAvailability = availability,
                    vehicleResponseObserved = discovery.vehicleResponseObserved,
                    mode01BitmapReceived = discovery.mode01BitmapReceived,
                    pidDiscoveryComplete = discovery.isComplete,
                    protocol = c.detectedProtocol
                )
            }
            EventLog.log(
                "Découverte OBD : réponse véhicule=${discovery.vehicleResponseObserved}, " +
                    "bitmap=${discovery.mode01BitmapReceived}, complète=${discovery.isComplete}, " +
                    "PID annoncés=${supported.size}, état=$availability"
            )
            discovery.rawResponses.forEach { (bank, raw) ->
                EventLog.log("Réponse 01%02X : %s".format(bank, raw.replace('\r', ' ').replace('\n', ' ').take(4096)))
            }
            if (!discovery.vehicleResponseObserved) {
                // A silent vehicle may simply have its ignition off. Retry normally;
                // unlike a confirmed zero bitmap, this is not completed discovery.
                error(getString(R.string.dashboard_no_vehicle_response))
            }
            // Ne PAS envelopper dans runCatching : readVin() ne lève que si sendRaw a
            // échoué au niveau transport (timeout, coupure), auquel cas Elm327Client a
            // déjà fermé le socket en interne. Avaler cette exception ici publierait
            // "connecté" sur un client mort (le polling ne démarrerait même pas, puisque
            // c.isConnected serait déjà faux, sans qu'aucune erreur ne soit montrée).
            // Un ECU qui ne supporte simplement pas le mode 09 répond par un préfixe
            // inattendu et readVin() renvoie null normalement, sans lever.
            // No additional automatic vehicle query after an empty/refused discovery.
            val vin = if (supported.isNotEmpty()) c.readVin() else null
            vehicleId = vin ?: DtcHistoryStore.UNKNOWN_VEHICLE
            vehicleFileTag = VinDecoder.fileTag(vin)
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
            val scaleBytes = if (0x4F in supported) c.readPidBytes(0x4F)?.takeIf { it.size == 4 } else null

            // Même principe pour le débit d'air (PID10), annoncé par PID50 (un seul
            // octet, max en dizaines de g/s).
            val mafScaleBytes = if (0x50 in supported) c.readPidBytes(0x50)?.takeIf { it.size == 4 } else null
            PidCatalog.applyAnnouncedScales(scaleBytes, mafScaleBytes)

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

            // Optimisations de lecture propres à CET adaptateur/véhicule (voir
            // Elm327Client.supportsResponseCount/supportsMultiPid), vérifiées une fois ici
            // plutôt que supposées : un clone qui ne les comprend pas garde simplement le
            // comportement historique (une requête par PID, attente complète).
            c.detectResponseCountSupport()
            c.detectMultiPidSupport(
                PidCatalog.defs
                    .filter { it.pid in supported && it.pid !in PidCatalog.CONTEXT_ONLY_PIDS }
                    .take(3)
                    .map { it.pid }
            )
            EventLog.log(
                "Optimisations de lecture : réponse unique ${if (c.supportsResponseCount) "oui" else "non"}, " +
                    "groupage ${if (c.supportsMultiPid) "oui" else "non"}"
            )
            // Après la recherche de protocole (discoverPidSupport) : l'échéance des échanges
            // est alors celle d'une lecture établie (ElmTimeouts), une commande
            // d'identification sans réponse ne retient donc pas la connexion 45 s.
            val adapterInfo = c.readAdapterInfo()
            EventLog.log("Adaptateur : ${adapterInfo.summary()}")

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
                    adapterInfo = adapterInfo,
                    supportsResponseCount = c.supportsResponseCount,
                    supportsMultiPid = c.supportsMultiPid,
                    dtcHistory = historyStore.load(vehicleId),
                    bigGaugePids = loadBigGaugePids(vehicleId),
                    values = it.values + contextValues
                )
            }
            EventLog.log(
                (if (isAutoRetry) "Reconnecté" else "Connecté") +
                    " : VIN=${vin ?: "inconnu"}, protocole=${c.detectedProtocol ?: "inconnu"}"
            )
            if (hasAutomaticObdReads(supported)) {
                startPolling(c, supported)
            } else {
                pollJob?.cancel()
                pollJob = null
            }
            // Un enregistrement mis en pause par une coupure (voir handleConnectionLost/
            // pauseRecordingForReconnect) reprend sur le MÊME fichier dès que la session
            // revit : recordingWriter non nul mais recordingJob nul signale ce cas précis
            // (un enregistrement démarré normalement a déjà les deux non nuls, voir
            // startRecording). Le stop reste manuel : ce n'est jamais ici qu'on en démarre
            // un nouveau, seulement qu'on reprend celui déjà en cours.
            if (recordingWriter != null && recordingJob == null &&
                availability == ObdDataAvailability.STANDARD_MEASUREMENTS_AVAILABLE) {
                resumeRecordingLoop()
            }
        } catch (e: CancellationException) {
            // c n'est affecté à `client` qu'après doConnect() plus haut : si l'annulation
            // arrive avant, personne d'autre ne connaît ce client pour le refermer (voir
            // A7). Idempotent et sans risque si c a déjà été publié et fermé ailleurs.
            c.disconnect()
            if (client === c) client = null
            throw e
        } catch (e: Exception) {
            c.disconnect()
            client = null
            // Preserve the failing phase even for automatic attempts; the Dashboard
            // remains quiet, while exported logs can explain the failure.
            EventLog.log("Échec de connexion${if (isAutoRetry) " automatique" else ""} : ${e.message ?: "raison inconnue"}")
            _state.update {
                it.copy(
                    connectionState = if (isAutoRetry) ConnectionState.RECONNECTING else ConnectionState.ERROR,
                    errorMessage = e.message ?: getString(R.string.error_connection_failed_fallback)
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
            // Filet de sécurité contre une sonde "zombie" : constaté sur capture réelle
            // (obd_20260911_201421.csv) qu'un clone ELM327 peut cesser de répondre à TOUT
            // PID sans jamais lever d'exception ni fermer le socket (readPidBytes renvoie
            // simplement null en boucle) : c.isConnected reste vrai, newValues reste vide
            // à chaque cycle, et sans ce filet handleConnectionLost n'était donc jamais
            // déclenché malgré 30s+ de valeurs figées. Remis à zéro à chaque lecture réussie
            // ET pendant une pause diagnostique légitime (refresh DTC, sondage FAP : ça peut
            // durer plusieurs secondes sans qu'aucun PID ne soit lu, ce n'est pas une panne).
            // Un adaptateur complètement muet (lecture bloquée) est, lui, coupé par
            // l'échéance d'Elm327Client (le gardien ferme le transport), ce filet n'étant
            // évalué qu'en fin de cycle.
            val health = PollingHealth(
                measurementsExpected = fastPids.isNotEmpty() || slowPids.isNotEmpty(),
                failureTimeoutMs = ZOMBIE_CONNECTION_TIMEOUT_MS
            )
            // null : un premier relevé MIL arrive dès le
            // premier cycle eligible plutot que d'attendre un plein MIL_CHECK_INTERVAL_MS,
            // pour qu'un enregistrement court (smoke test, trajet bref) ait quand meme une
            // colonne MIL renseignee des le debut (voir capturer les defauts pendant
            // l'enregistrement, ci-dessous).
            var lastMilCheckAtMs: Long? = null
            // Une lecture de codes ratée doit être retentée, même si le voyant et
            // le compteur n'ont pas changé depuis la réussite du PID01.
            var storedRefreshPending = false
            // Même logique pour les PID lents : lus dès le premier cycle.
            var lastSlowPollAtMs = 0L
            // Durée des cycles complets, journalisée périodiquement (voir EventLog) : seul
            // moyen de comparer objectivement deux adaptateurs (Wi-Fi/Bluetooth) ou l'effet
            // des optimisations de lecture sur un vrai véhicule.
            var cycleSamples = 0
            var cycleTotalMs = 0L
            var lastCycleLogAtMs = 0L
            while (c.isConnected) {
                if (dtcOperationInProgress) {
                    health.diagnosticPause()
                } else {
                    try {
                        val cycleStartMs = System.currentTimeMillis()
                        // Les PID lents (SLOW_PIDS) sont relus à intervalle de TEMPS, pas tous
                        // les N cycles : la durée d'un cycle dépend de l'adaptateur (2-3s en
                        // Bluetooth le 24/09), si bien que "tous les 5 cycles" dépassait le
                        // seuil de péremption et vidait les températures une ligne sur deux.
                        val includeSlow = cycleStartMs - lastSlowPollAtMs >= SLOW_PID_INTERVAL_MS
                        if (includeSlow) lastSlowPollAtMs = cycleStartMs
                        val toPoll = if (includeSlow) fastPids + slowPids else fastPids
                        val newValues = mutableMapOf<Int, GaugeValue>()
                        // Requêtes groupées (jusqu'à 6 PID) quand le véhicule l'accepte (voir
                        // Elm327Client.supportsMultiPid, vérifié à la connexion) : un aller-
                        // retour au lieu de six. Un PID absent de la réponse groupée est relu
                        // seul juste après, pour ne jamais perdre une valeur à cause du
                        // groupement lui-même.
                        val batches = if (c.supportsMultiPid) {
                            toPoll.chunked(Elm327Client.MAX_PIDS_PER_REQUEST)
                        } else {
                            toPoll.map { listOf(it) }
                        }
                        for (batch in batches) {
                            // Revérifié avant chaque requête, pas seulement au début du cycle :
                            // un cycle déjà engagé pourrait sinon continuer d'interroger la
                            // sonde avec les hypothèses normales pendant qu'une opération de
                            // diagnostic vient de changer sa configuration (ex: ATH1 en cours).
                            if (dtcOperationInProgress) break
                            val grouped = if (batch.size > 1) readGroupedOrEmpty(c, batch.map { it.pid }) else emptyMap()
                            for (def in batch) {
                                if (dtcOperationInProgress) break
                                val bytes = grouped[def.pid] ?: c.readPidBytes(def.pid)
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
                        }
                        if (!dtcOperationInProgress) {
                            val nowMs = System.currentTimeMillis()
                            cycleSamples++
                            cycleTotalMs += nowMs - cycleStartMs
                            if (cycleSamples >= CYCLE_LOG_MIN_SAMPLES && nowMs - lastCycleLogAtMs >= CYCLE_LOG_INTERVAL_MS) {
                                EventLog.log(
                                    "Cycle de lecture moyen : ${cycleTotalMs / cycleSamples} ms sur $cycleSamples cycles " +
                                        "(${fastPids.size} PID rapides + ${slowPids.size} lents, " +
                                        "groupage ${if (c.supportsMultiPid) "oui" else "non"}, " +
                                        "réponse unique ${if (c.supportsResponseCount) "oui" else "non"})"
                                )
                                lastCycleLogAtMs = nowMs
                                cycleSamples = 0
                                cycleTotalMs = 0L
                            }
                        }
                        // Fusionne plutôt que remplace : une lecture ratée ponctuelle garde
                        // la dernière valeur connue au lieu d'afficher "--" en régression.
                        if (newValues.isNotEmpty()) {
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
                        }
                        if (dtcOperationInProgress) health.diagnosticPause()
                        if (health.measurementsRead(newValues.isNotEmpty())) {
                            handleConnectionLost(c, getString(R.string.error_zombie_connection, ZOMBIE_CONNECTION_TIMEOUT_MS / 1000))
                            return@launch
                        }

                        // Surveillance MIL en arrière-plan, à un rythme bien plus lent que le
                        // reste du cycle (voir MIL_CHECK_INTERVAL_MS) : sert à capturer un
                        // nouveau défaut PENDANT un enregistrement en cours au lieu de dépendre
                        // de l'utilisateur pour aller manuellement sur l'écran DTC (le CSV
                        // n'avait jusqu'ici aucune colonne reflétant l'état du voyant). Revérifié
                        // comme les PID ci-dessus : dtcOperationInProgress a pu devenir vrai
                        // entre le début du cycle et ce point.
                        if (0x01 in supported && !dtcOperationInProgress &&
                            (lastMilCheckAtMs == null ||
                                System.nanoTime() / 1_000_000L - lastMilCheckAtMs > MIL_CHECK_INTERVAL_MS)
                        ) {
                            lastMilCheckAtMs = System.nanoTime() / 1_000_000L
                            var milReceived = false
                            try {
                                val (mil, count) = c.readMilStatus()
                                milReceived = true
                                health.milRead(success = true)
                                val previous = _state.value
                                val milTurnedOn = previous.milOn != true && mil
                                // Nombre de codes changé (ou premier relevé de la session sans
                                // liste déjà lue) : un code stocké SANS voyant (cas réel de
                                // P0087 le 24/09, voyant éteint) n'apparaissait jusqu'ici qu'en
                                // rafraîchissant l'écran DTC à la main.
                                val countChanged = count != previous.dtcCount &&
                                    (previous.dtcCount != null || previous.storedDtcs == null)
                                _state.update {
                                    it.copy(milOn = mil, dtcCount = count, milLastSuccessAtMs = System.currentTimeMillis())
                                }
                                if (milTurnedOn || countChanged) storedRefreshPending = true
                                if (storedRefreshPending) {
                                    // Va chercher les codes réels (readStoredDtcs, "03"), pas
                                    // seulement ce compteur. Volontairement PAS aussi complet
                                    // qu'un refreshDtcs() (pas de pending/readiness/freeze frame
                                    // ici) : le but est d'identifier VITE ce qui vient d'apparaître
                                    // pendant le trajet, pas de reproduire l'écran DTC en
                                    // arrière-plan.
                                    val stored = c.readStoredDtcs()
                                    val history = historyStore.record(vehicleId, stored)
                                    val detail = stored.joinToString(", ").ifEmpty { "$count code(s) annoncé(s), détail illisible" }
                                    when {
                                        milTurnedOn -> EventLog.log("Voyant moteur (MIL) allumé pendant le trajet : $detail")
                                        previous.dtcCount != null -> EventLog.log("Nombre de codes stockés passé de ${previous.dtcCount} à $count : ${stored.joinToString(", ").ifEmpty { "aucun" }}")
                                    }
                                    _state.update {
                                        it.copy(
                                            milOn = mil,
                                            dtcCount = count,
                                            storedDtcs = stored,
                                            dtcHistory = history,
                                            storedDtcsLastSuccessAtMs = System.currentTimeMillis()
                                        )
                                    }
                                    storedRefreshPending = false
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (!c.isConnected) throw e
                                // Ratée ponctuelle traitée comme n'importe quel PID de toPoll
                                // ci-dessus (voir newValues) : un échec de décodage isolé
                                // sur cette seule vérification ne doit pas faire perdre toute la
                                // session. Pour PID01 seul, cette lecture MIL est le seul
                                // contrôle périodique : une panne persistante doit déconnecter.
                                if (!milReceived && health.milRead(success = false)) {
                                    handleConnectionLost(c, getString(R.string.error_zombie_connection, ZOMBIE_CONNECTION_TIMEOUT_MS / 1000))
                                    return@launch
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        handleConnectionLost(c, e.message ?: getString(R.string.error_read_failed_fallback))
                        return@launch
                    }
                }
                delay(300)
            }
            // Atteint uniquement si c.isConnected est devenu faux SANS exception (voir A2) :
            // le transport a été fermé par une autre opération sur ce même client (ex: un
            // sendRaw en échec dans un refresh DTC). Une annulation volontaire (déconnexion,
            // nouvelle connexion) lève CancellationException au prochain delay() ci-dessus et
            // ne redescend jamais jusqu'ici.
            handleConnectionLost(c, getString(R.string.error_connection_lost))
        }
    }

    /**
     * Requête groupée du polling (voir Elm327Client.readPidsBytes) : une réponse illisible
     * (collision entre calculateurs, trame manquante) n'est PAS une perte de connexion, les
     * PID concernés sont simplement relus un par un par l'appelant. Seule une vraie erreur
     * de transport (socket déjà fermé par sendRaw) remonte.
     */
    private suspend fun readGroupedOrEmpty(c: Elm327Client, pids: List<Int>): Map<Int, List<Int>> =
        try {
            c.readPidsBytes(pids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!c.isConnected) throw e
            emptyMap()
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
                _state.update {
                    it.copy(milOn = mil, dtcCount = count, milLastSuccessAtMs = System.currentTimeMillis())
                }
                val stored = c.readStoredDtcs()
                val history = historyStore.record(vehicleId, stored)
                _state.update {
                    it.copy(storedDtcs = stored, dtcHistory = history, storedDtcsLastSuccessAtMs = System.currentTimeMillis())
                }
                val pending = c.readPendingDtcs()
                val readiness = c.readReadiness() ?: emptyList()

                // Pas de runCatching : readFreezeFrameDtc ne lève que sur erreur de
                // transport (réponse inattendue = null), traitée comme les autres lectures.
                val freezeFrameDtc = if (stored.isNotEmpty()) c.readFreezeFrameDtc() else null
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

                _state.update {
                    it.copy(
                        milOn = mil,
                        dtcCount = count,
                        storedDtcs = stored,
                        pendingDtcs = pending,
                        readiness = readiness,
                        freezeFrame = freezeFrame,
                        freezeFrameDtc = freezeFrameDtc,
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
                    handleConnectionLost(c, e.message ?: getString(R.string.error_dtc_read_failed_fallback))
                    return@launch
                }
                _state.update { it.copy(dtcLoading = false, dtcError = e.message ?: getString(R.string.error_dtc_read_failed_fallback)) }
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
            _state.update { it.copy(headerProbeResult = getString(R.string.error_header_probe_reading), dtcLoading = false) }
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
                    handleConnectionLost(c, e.message ?: getString(R.string.error_read_failed_fallback))
                    return@launch
                }
                _state.update { it.copy(headerProbeResult = getString(R.string.error_header_probe_failed, e.message.toString())) }
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
        // An unknown DID range has no validated meaning for the connected ECU.
        // Keep old captures available, but do not emit exploratory requests.
        if (!UNVALIDATED_MANUFACTURER_PROBES_ENABLED) {
            _state.update { it.copy(fapScanError = getString(R.string.probe_requires_validated_profile)) }
            return
        }
        if (_state.value.isFapScanning) return
        val c = client ?: return

        val startDid = startDidText.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
        val endDid = endDidText.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
        if (startDid == null || endDid == null || startDid !in 0..0xFFFF || endDid !in 0..0xFFFF || startDid > endDid) {
            _state.update { it.copy(fapScanError = getString(R.string.error_probe_range_invalid)) }
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
                    it.copy(fapScanError = getString(R.string.error_probe_target_invalid))
                }
                return
            }
            if (!c.isCanProtocol) {
                _state.update {
                    it.copy(fapScanError = getString(R.string.error_probe_target_not_can, c.detectedProtocol ?: "non-CAN"))
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
        val baseName = "fap_scan_${vehicleFileTag}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val probeFile = try {
            uniqueFile(dir, baseName)
        } catch (e: Exception) {
            _state.update { it.copy(fapScanError = getString(R.string.error_probe_file_create, e.message.toString())) }
            return
        }
        val writer = try {
            probeFile.bufferedWriter()
        } catch (e: Exception) {
            _state.update { it.copy(fapScanError = getString(R.string.error_probe_file_open, e.message.toString())) }
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
            _state.update { it.copy(fapScanError = getString(R.string.error_probe_file_init, e.message.toString())) }
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
                                        fapScanError = getString(R.string.error_probe_write_failed),
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
                _state.update { it.copy(fapScanError = e.message ?: getString(R.string.error_read_failed_fallback), fapScanOutcome = FapScanOutcome.ERREUR) }
                handleConnectionLost(c, e.message ?: getString(R.string.error_read_failed_fallback))
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
        if (client?.isConnected != true || _state.value.connectionState != ConnectionState.CONNECTED ||
            _state.value.dataAvailability != ObdDataAvailability.STANDARD_MEASUREMENTS_AVAILABLE) return
        val columns = PidCatalog.defs.filter { it.pid in _state.value.supportedPids }
        if (columns.isEmpty()) return

        val dir = File(getApplication<Application>().filesDir, "recordings").apply { mkdirs() }
        val baseName = "obd_${vehicleFileTag}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val file = try {
            uniqueFile(dir, baseName)
        } catch (e: Exception) {
            _state.update { it.copy(recordingError = getString(R.string.error_recording_file_create, e.message.toString())) }
            return
        }
        val writer = try {
            file.bufferedWriter()
        } catch (e: Exception) {
            _state.update { it.copy(recordingError = getString(R.string.error_recording_file_open, e.message.toString())) }
            return
        }
        try {
            writeSessionMetadata(writer)
            writer.write(csvRow(listOf("Horodatage", "État", "MIL", "Codes stockés", "Lecture MIL", "Lecture codes stockés") + columns.map { it.label }))
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            // En-tête non écrit : le writer reste fermé explicitement ici (voir A5, résiduel
            // relevé par l'audit B4) plutôt que de laisser un descripteur de fichier ouvert
            // sans jamais plus être référencé nulle part.
            runCatching { writer.close() }
            _state.update { it.copy(recordingError = getString(R.string.error_recording_file_init, e.message.toString())) }
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
        // Une écriture IO peut finir après l'annulation de son job. Garder le writer
        // de CETTE boucle : un arrêt puis un nouveau départ ne doit jamais rediriger
        // sa fin de ligne/son flush vers le nouveau fichier.
        val writer = recordingWriter ?: return
        // Colonnes réellement mesurées par le polling (voir startPolling) : les PID
        // "contexte" (CONTEXT_ONLY_PIDS) sont lus une fois à la connexion et jamais
        // périmés par design, ils ne comptent donc pas dans "combien de mesures
        // dynamiques cette ligne a obtenu" (voir B3 ci-dessous).
        val dynamicColumns = recordingColumns.filter { it.pid !in PidCatalog.CONTEXT_ONLY_PIDS }
        recordingJob = viewModelScope.launch {
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
            val diagnosticTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ssXXX", Locale.FRANCE)
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
                    if (value != null && !isUnavailable(def.pid, value, now)) {
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
                // Aucune requête ici : chaque état cache sa propre date de lecture.
                // Celle-ci peut être antérieure à la date d'échantillonnage de la ligne.
                val row = listOf(timestampFormat.format(Date(now)), etat) +
                    recordingDiagnosticFields(snapshot) { diagnosticTimeFormat.format(Date(it)) } + cells
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        writer.write(csvRow(row))
                        writer.newLine()
                        writer.flush()
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
                    _state.update { it.copy(recordingError = getString(R.string.error_recording_write_failed)) }
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
            getString(R.string.smoketest_step_mil),
            getString(R.string.smoketest_step_dynamic_value),
            getString(R.string.smoketest_step_vin),
            getString(R.string.smoketest_step_dtc),
            getString(R.string.smoketest_step_readiness),
            getString(R.string.smoketest_step_recording),
            getString(R.string.smoketest_step_probe)
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
                        setCheck(0, AutoTestStatus.OK, getQuantityString(if (mil) R.plurals.smoketest_detail_mil_on else R.plurals.smoketest_detail_mil_off, count))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setCheck(0, AutoTestStatus.ECHEC, e.message ?: getString(R.string.smoketest_generic_failure))
                    }

                    setCheck(1, AutoTestStatus.EN_COURS)
                    val dynamicDef = PidCatalog.defs.firstOrNull {
                        it.pid in _state.value.supportedPids && it.pid !in PidCatalog.CONTEXT_ONLY_PIDS
                    }
                    if (dynamicDef == null) {
                        setCheck(1, AutoTestStatus.ATTENTION, getString(R.string.smoketest_no_dynamic_pid))
                    } else {
                        try {
                            val bytes = c.readPidBytes(dynamicDef.pid)
                            if (bytes != null && bytes.size >= dynamicDef.expectedBytes) {
                                setCheck(1, AutoTestStatus.OK, getString(R.string.smoketest_dynamic_value_result, dynamicDef.label, dynamicDef.decode(bytes)))
                            } else {
                                setCheck(1, AutoTestStatus.ECHEC, getString(R.string.smoketest_dynamic_value_unusable, dynamicDef.label))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            setCheck(1, AutoTestStatus.ECHEC, e.message ?: getString(R.string.smoketest_generic_failure))
                        }
                    }

                    setCheck(2, AutoTestStatus.EN_COURS)
                    try {
                        val vin = c.readVin()
                        if (vin != null) {
                            setCheck(2, AutoTestStatus.OK, vin)
                        } else {
                            setCheck(2, AutoTestStatus.ATTENTION, getString(R.string.smoketest_vin_not_read))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setCheck(2, AutoTestStatus.ECHEC, e.message ?: getString(R.string.smoketest_generic_failure))
                    }

                    setCheck(3, AutoTestStatus.EN_COURS)
                    try {
                        val stored = c.readStoredDtcs()
                        val pending = c.readPendingDtcs()
                        setCheck(3, AutoTestStatus.OK, getString(R.string.smoketest_dtc_summary, stored.size, pending.size))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setCheck(3, AutoTestStatus.ECHEC, e.message ?: getString(R.string.smoketest_generic_failure))
                    }

                    setCheck(4, AutoTestStatus.EN_COURS)
                    try {
                        val readiness = c.readReadiness()
                        if (readiness != null) {
                            setCheck(4, AutoTestStatus.OK, getQuantityString(R.plurals.smoketest_readiness_count, readiness.size))
                        } else {
                            setCheck(4, AutoTestStatus.ATTENTION, getString(R.string.smoketest_readiness_unreadable))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setCheck(4, AutoTestStatus.ECHEC, e.message ?: getString(R.string.smoketest_generic_failure))
                    }
                } finally {
                    dtcOperationCount--
                }

                setCheck(5, AutoTestStatus.EN_COURS)
                if (client !== c) {
                    setCheck(5, AutoTestStatus.ECHEC, getString(R.string.smoketest_connection_changed))
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
                        setCheck(5, AutoTestStatus.ECHEC, _state.value.recordingError ?: getString(R.string.smoketest_recording_not_started))
                    } else {
                        val samplesBefore = _state.value.recordingSamples
                        delay(10_000)
                        val samplesDuring = _state.value.recordingSamples - samplesBefore
                        if (autoTestOwnsRecording) {
                            stopRecording()
                            autoTestOwnsRecording = false
                        }
                        if (samplesDuring >= 1) {
                            setCheck(5, AutoTestStatus.OK, getQuantityString(R.plurals.smoketest_recording_samples, samplesDuring))
                        } else {
                            setCheck(5, AutoTestStatus.ECHEC, getString(R.string.smoketest_recording_zero_samples))
                        }
                    }
                }

                setCheck(6, AutoTestStatus.EN_COURS)
                if (!UNVALIDATED_MANUFACTURER_PROBES_ENABLED) {
                    setCheck(6, AutoTestStatus.ATTENTION, getString(R.string.probe_requires_validated_profile))
                } else if (client !== c) {
                    setCheck(6, AutoTestStatus.ECHEC, getString(R.string.smoketest_connection_changed))
                } else {
                    startFapScan("1140", "1142")
                    if (!_state.value.isFapScanning) {
                        setCheck(6, AutoTestStatus.ECHEC, _state.value.fapScanError ?: getString(R.string.smoketest_probe_not_started))
                    } else {
                        _state.first { !it.isFapScanning }
                        when (_state.value.fapScanOutcome) {
                            FapScanOutcome.TERMINE -> setCheck(6, AutoTestStatus.OK, getString(R.string.smoketest_probe_ok))
                            FapScanOutcome.ERREUR -> setCheck(6, AutoTestStatus.ECHEC, _state.value.fapScanError ?: getString(R.string.smoketest_generic_failure))
                            FapScanOutcome.INTERROMPU, null -> setCheck(6, AutoTestStatus.ATTENTION, getString(R.string.smoketest_probe_interrupted))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleConnectionLost(c, e.message ?: getString(R.string.error_smoke_test_failed_fallback))
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
                bigGaugePids = it.bigGaugePids,
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
        private const val KEY_BIG_GAUGE_PIDS = "big_gauge_pids"
        // Assez pour un coup d'œil rapide en conduisant (voir GaugeRow, ObdUiState.
        // bigGaugePids) sans réduire chaque valeur à une taille illisible sur le
        // téléphone si l'utilisateur en choisissait beaucoup plus.
        private const val MAX_BIG_GAUGE_PIDS = 6
        // ~600 o/échantillon (~40 colonnes max) : à 5s, une session de 2h fait autour de
        // 850 Ko. Assez fin pour une analyse de tendance, sans accumuler des Mo inutiles.
        private const val RECORDING_INTERVAL_MS = 5_000L
        // Bien plus lent que RECORDING_INTERVAL_MS : le MIL/nombre de codes stockés ne
        // change pas d'une seconde à l'autre comme le régime ou la vitesse, une commande
        // PID01 de plus à chaque cycle rapide serait un coût réseau inutile pour une
        // surveillance qui reste utile même vérifiée toutes les 30s (voir la colonne MIL
        // du CSV, ObdViewModel.startPolling/resumeRecordingLoop).
        private const val MIL_CHECK_INTERVAL_MS = 30_000L
        // Intervalle de relecture des PidCatalog.SLOW_PIDS, en temps et non en nombre de
        // cycles (voir startPolling) : un cycle dure de quelques centaines de ms à 2-3s selon
        // l'adaptateur. 5s + un cycle reste sous SLOW_VALUE_UNAVAILABLE_AFTER_MS (20s) et
        // SLOW_STALE_AFTER_MS côté Dashboard (12s) même avec un adaptateur lent.
        private const val SLOW_PID_INTERVAL_MS = 5_000L
        // Journal de la durée moyenne des cycles de lecture (voir startPolling) : une
        // première mesure dès 20 cycles, puis une toutes les 5 minutes au plus.
        private const val CYCLE_LOG_MIN_SAMPLES = 20
        private const val CYCLE_LOG_INTERVAL_MS = 5 * 60_000L
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
