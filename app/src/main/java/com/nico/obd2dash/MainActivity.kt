package com.nico.obd2dash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nico.obd2dash.ui.AutoTestScreen
import com.nico.obd2dash.ui.DashboardScreen
import com.nico.obd2dash.ui.DtcScreen
import com.nico.obd2dash.ui.GraphScreen
import com.nico.obd2dash.ui.Obd2DashTheme
import com.nico.obd2dash.ui.ProbeScreen
import com.nico.obd2dash.ui.SettingsScreen
import com.nico.obd2dash.ui.navBarContainerColor
import com.nico.obd2dash.ui.topBarContainerColor
import java.io.File

private enum class Screen { DASHBOARD, DTC, PROBE, GRAPH, AUTO_TEST }

class MainActivity : ComponentActivity() {

    private val viewModel: ObdViewModel by viewModels()

    // Sur Android 13+, RecordingService a besoin de cette permission pour que sa
    // notification (obligatoire pour tout service de premier plan) s'affiche réellement ;
    // son refus ne bloque ni l'enregistrement ni le service, juste sa visibilité (voir
    // RecordingService). Doit être enregistré ici, avant que l'Activity soit démarrée.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignoré, voir ci-dessus */ }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // BLUETOOTH_CONNECT (permission d'exécution) n'existe qu'à partir d'Android 12 ; avant,
    // BLUETOOTH/BLUETOOTH_ADMIN (déclarées dans le manifeste) suffisent sans prompt. Sans
    // cette permission, adapter.bondedDevices lève une SecurityException (voir
    // ObdViewModel.refreshBondedBluetoothDevices, qui l'attrape et rend une liste vide).
    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.refreshBondedBluetoothDevices()
        }

    private fun ensureBluetoothPermissionThenRefresh() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.refreshBondedBluetoothDevices()
        } else {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private var bleScanRequested = false
    private val bleScanPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (bleScanRequested) {
                if (grants.values.all { it }) viewModel.startBleScan()
                else viewModel.bleScanPermissionDenied()
            }
        }

    private fun ensureBleScanPermissionThenStart() {
        bleScanRequested = true
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) viewModel.startBleScan()
        else bleScanPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun stopBleSearch() {
        bleScanRequested = false
        viewModel.stopBleScan()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Obd2DashTheme {
                // contentColor explicite : Surface déduit normalement la couleur de texte par
                // défaut à partir de sa propre couleur de fond (contentColorFor), mais
                // Color.Transparent (nécessaire ici pour laisser voir le dégradé du thème,
                // voir Obd2DashTheme) ne correspond à aucun rôle connu de cette déduction,
                // d'où un texte presque invisible constaté sur un vrai téléphone (tout Text()
                // sans couleur explicite héritait d'une valeur par défaut proche du noir).
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
                    var showSettings by remember { mutableStateOf(false) }
                    val state by viewModel.state.collectAsState()
                    // Le téléphone posé sur le tableau de bord bénéficie d'emblée de
                    // toute la surface en paysage. Le bouton conserve le choix manuel
                    // lors des rotations, et reste disponible aussi en portrait.
                    var expandedDashboard by rememberSaveable { mutableStateOf<Boolean?>(null) }
                    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val fullScreen = screen == Screen.DASHBOARD && !showSettings && (expandedDashboard ?: landscape)

                    // Écran maintenu allumé seulement pendant l'usage réel "au volant" : connecté
                    // ET sur Dashboard ou Graphique (jauges/courbe en train d'être regardées),
                    // pas sur Réglages/DTC/Sondage/Smoke test, et pas tant que la connexion
                    // n'est pas établie (inutile de garder l'écran allumé en attente au garage).
                    // FLAG_KEEP_SCREEN_ON standard Android (voir View.keepScreenOn), pas de
                    // wake lock ici : contrairement à RecordingService (CPU actif écran éteint),
                    // le seul besoin est que l'écran ne s'éteigne pas tout seul pendant que
                    // l'app reste au premier plan.
                    //
                    // FLAG_TURN_SCREEN_ON + FLAG_SHOW_WHEN_LOCKED en plus (voir demande
                    // explicite) : la connexion automatique retente pendant plusieurs
                    // dizaines de secondes (toutes les 5s) après le contact mis, largement
                    // de quoi laisser le délai de verrouillage système éteindre l'écran avant
                    // que la connexion aboutisse. Sans ces deux flags, keepScreenOn seul
                    // n'aurait rien changé : il empêche l'extinction FUTURE, il ne rallume pas
                    // un écran déjà éteint. FLAG_SHOW_WHEN_LOCKED affiche le Dashboard par-
                    // dessus un verrouillage existant SANS le lever (pas FLAG_DISMISS_KEYGUARD) :
                    // si un code est configuré, il reste actif et se represente dès qu'on
                    // quitte cet écran, comme un réveil ou un lecteur vidéo au-dessus du
                    // verrouillage, pas un contournement permanent.
                    val view = LocalView.current
                    DisposableEffect(fullScreen) {
                        val controller = WindowInsetsControllerCompat(window, view)
                        if (fullScreen) {
                            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            controller.hide(WindowInsetsCompat.Type.systemBars())
                        }
                        onDispose { if (fullScreen) controller.show(WindowInsetsCompat.Type.systemBars()) }
                    }
                    val keepAwake = !showSettings &&
                        state.connectionState == ConnectionState.CONNECTED &&
                        (screen == Screen.DASHBOARD || screen == Screen.GRAPH)
                    SideEffect {
                        view.keepScreenOn = keepAwake
                        applyWakeOverLockScreenFlags(window, keepAwake)
                    }

                    // Sans ça, le retour système depuis Réglages ferme l'Activity racine
                    // (Android <=11) ou la met en arrière-plan (Android 12+) au lieu de
                    // revenir au Dashboard comme la flèche de la barre du haut (voir audit,
                    // "Navigation") : le premier cas arrête aussi l'acquisition liée à ce
                    // ViewModel, contrairement à ce qu'un simple retour d'écran laisse
                    // attendre.
                    BackHandler(enabled = showSettings) { showSettings = false }
                    BackHandler(enabled = fullScreen) { expandedDashboard = false }

                    Scaffold(
                        containerColor = Color.Transparent,
                        topBar = {
                            if (!fullScreen) TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = topBarContainerColor()),
                                title = {
                                    Text(stringResource(if (showSettings) R.string.topbar_title_settings else R.string.app_name))
                                },
                                navigationIcon = {
                                    if (showSettings) {
                                        IconButton(onClick = { showSettings = false }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.topbar_back))
                                        }
                                    }
                                },
                                actions = {
                                    ConnectionIndicator(state.connectionState, state.dataAvailability)
                                    if (!showSettings) {
                                        IconButton(onClick = { showSettings = true }) {
                                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.topbar_settings_icon))
                                        }
                                    }
                                }
                            )
                        },
                        bottomBar = {
                            // Chaque onglet ferme aussi Réglages (voir audit, "Navigation") :
                            // sans ça, screen changeait bien en interne mais l'écran affiché
                            // restait Réglages (priorité du "if (showSettings)" ci-dessous),
                            // jusqu'à ce que la flèche de retour soit pressée séparément.
                            if (!fullScreen) NavigationBar(containerColor = navBarContainerColor()) {
                                NavigationBarItem(
                                    selected = screen == Screen.DASHBOARD,
                                    onClick = { screen = Screen.DASHBOARD; showSettings = false },
                                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_dashboard)) }
                                )
                                // DTC/Sondage/Graphique/Smoke test verrouillés tant que CONNECTED
                                // n'est pas atteint (demande explicite) : seul Dashboard reste
                                // accessible pendant l'attente initiale ou une reconnexion, ces
                                // écrans n'ont rien d'exploitable à montrer avant une connexion
                                // réussie (supportedPids vide, aucune donnée véhicule). Ne force
                                // pas la navigation si on y est déjà et que la connexion tombe en
                                // cours de route (RECONNECTING) : seul le résultat d'un NOUVEAU
                                // tap est bloqué, l'écran déjà affiché gère lui-même ce cas.
                                val connected = state.connectionState == ConnectionState.CONNECTED
                                NavigationBarItem(
                                    selected = screen == Screen.DTC,
                                    onClick = { screen = Screen.DTC; showSettings = false },
                                    enabled = connected,
                                    icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_dtc)) }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.PROBE,
                                    onClick = { screen = Screen.PROBE; showSettings = false },
                                    enabled = connected,
                                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_probe)) }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.GRAPH,
                                    onClick = { screen = Screen.GRAPH; showSettings = false },
                                    enabled = connected,
                                    icon = { Icon(painterResource(R.drawable.ic_chart), contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_graph)) }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.AUTO_TEST,
                                    onClick = { screen = Screen.AUTO_TEST; showSettings = false },
                                    enabled = connected,
                                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_smoke_test)) }
                                )
                            }
                        }
                    ) { padding ->
                        if (showSettings) {
                            SettingsScreen(
                                state = state,
                                onConnect = { host, port -> viewModel.connect(host, port) },
                                onConnectBluetooth = { device -> viewModel.connectBluetooth(device) },
                                onHostChange = { viewModel.updateHost(it) },
                                onPortChange = { viewModel.updatePort(it) },
                                onRefreshBluetoothDevices = { ensureBluetoothPermissionThenRefresh() },
                                onBluetoothTransportChange = { viewModel.setBluetoothTransport(it) },
                                onStartBleSearch = { ensureBleScanPermissionThenStart() },
                                onStopBleSearch = { stopBleSearch() },
                                onConnectBleDevice = { device ->
                                    stopBleSearch()
                                    viewModel.setBluetoothTransport(BluetoothTransport.BLE)
                                    viewModel.connectBluetooth(device)
                                },
                                onSetBigGaugePid = { pid, selected -> viewModel.setBigGaugePidSelected(pid, selected) },
                                onShareLog = { path -> shareCsvFile(path, R.string.share_log_title, mimeType = "text/plain") },
                                onDeleteLog = { path -> viewModel.deleteLog(path) },
                                modifier = Modifier.padding(padding)
                            )
                            return@Scaffold
                        }
                        when (screen) {
                            Screen.DASHBOARD -> DashboardScreen(
                                state = state,
                                onDisconnect = { viewModel.disconnect() },
                                onModeChange = { viewModel.switchConnectionMode(it) },
                                onToggleRecording = {
                                    if (state.isRecording) {
                                        viewModel.stopRecording()
                                    } else {
                                        ensureNotificationPermission()
                                        viewModel.startRecording()
                                    }
                                },
                                onShareRecording = { path -> shareCsvFile(path, R.string.share_recording_title) },
                                onDeleteRecording = { path -> viewModel.deleteRecording(path) },
                                modifier = Modifier.padding(padding),
                                fullScreen = fullScreen,
                                onToggleFullScreen = { expandedDashboard = !fullScreen },
                                onOpenSettings = { showSettings = true },
                                onOpenGraphs = { screen = Screen.GRAPH },
                                onReorderGauges = { viewModel.reorderBigGaugePids(it) }
                            )
                            Screen.DTC -> DtcScreen(
                                state = state,
                                onRefresh = { viewModel.refreshDtcs() },
                                onProbeHeaders = { viewModel.probeHeaderFormat() },
                                modifier = Modifier.padding(padding)
                            )
                            Screen.PROBE -> ProbeScreen(
                                state = state,
                                onStartScan = { startDid, endDid, targetHeader ->
                                    viewModel.startFapScan(startDid, endDid, targetHeader)
                                },
                                onStopScan = { viewModel.stopFapScan() },
                                onShareProbe = { path -> shareCsvFile(path, R.string.share_probe_title) },
                                onDeleteProbe = { path -> viewModel.deleteProbe(path) },
                                modifier = Modifier.padding(padding)
                            )
                            Screen.GRAPH -> GraphScreen(
                                state = state,
                                onSelectPid = { pid -> viewModel.selectGraphPid(pid) },
                                modifier = Modifier.padding(padding)
                            )
                            Screen.AUTO_TEST -> AutoTestScreen(
                                state = state,
                                onRun = { viewModel.runAutoTest() },
                                onStop = { viewModel.stopAutoTest() },
                                modifier = Modifier.padding(padding)
                            )
                        }
                    }
                }
            }
        }
    }

    /** Partage Android standard (sharesheet) : identique pour un enregistrement, un sondage ou un journal (mimeType "text/plain" pour ce dernier, ce n'est pas un tableau). */
    private fun shareCsvFile(path: String, @StringRes chooserTitleRes: Int, mimeType: String = "text/csv") {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(chooserTitleRes)))
    }
}

/**
 * FLAG_TURN_SCREEN_ON/FLAG_SHOW_WHEN_LOCKED plutôt que Activity.setTurnScreenOn()/
 * setShowWhenLocked() (API 27+, plus récentes) : minSdk de ce projet est 26, ces deux
 * flags restent la seule API qui couvre tout l'intervalle supporté. Dépréciées, pas
 * supprimées : toujours documentées et fonctionnelles, la dépréciation ne fait
 * qu'indiquer l'existence de l'alternative plus récente.
 */
@Suppress("DEPRECATION")
private fun applyWakeOverLockScreenFlags(window: Window, active: Boolean) {
    val flags = WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
    if (active) window.addFlags(flags) else window.clearFlags(flags)
}

/**
 * Indicateur persistant (comme Torque) : visible sur tous les écrans, pas seulement le
 * Dashboard, pour repérer une perte de connexion sans devoir y retourner. Couleurs
 * littérales plutôt que les jetons du thème Material (primary/error...) : vert/rouge est
 * une convention universelle de feu tricolore demandée telle quelle, indépendante de la
 * palette générée par le thème (qui n'est pas forcément vert/rouge par défaut).
 *
 * CONNECTING/RECONNECTING clignotent (convention voyant de bord : clignotant = en cours,
 * fixe = état stabilisé) ; c'est désormais le seul signal de connexion en cours, le
 * Dashboard n'affiche plus son propre bloc "occupé" séparé (voir DashboardScreen, qui
 * masquait la liste des enregistrements pendant une reconnexion).
 *
 * Juste le voyant, sans libellé à côté (le texte dupliquait ce que dit déjà la couleur et
 * encombrait la barre du haut) : le libellé survit comme contentDescription pour
 * l'accessibilité (lecteur d'écran), simplement plus affiché visuellement.
 */
@Composable
internal fun ConnectionIndicator(state: ConnectionState, availability: ObdDataAvailability) {
    // Connecté réutilise directement le turquoise fonctionnel de l'appli (colorScheme.primary,
    // déjà utilisé par Wi-Fi/Bluetooth/Partager) plutôt qu'un vert isolé : un seul "cette
    // couleur = actif/bon" dans toute l'appli, qui s'assombrit aussi cohéremment la nuit avec
    // le reste. Attente/erreur restent des teintes dédiées (ambre/rouge, sans rôle ColorScheme
    // équivalent), choisies plus nettes après retour direct ("sa teinte paraît un peu sale" -
    // l'ancien amber n'était pas en cause, plutôt son mélange avec le fond via le clignotement
    // en alpha juste en dessous, mais la teinte plus propre aide dans les deux cas).
    val (color, label) = when (state) {
        ConnectionState.CONNECTED -> when (availability) {
            ObdDataAvailability.NO_VEHICLE_RESPONSE -> Color(0xFFE0A12D) to stringResource(R.string.dashboard_adapter_only)
            ObdDataAvailability.NO_STANDARD_MEASUREMENTS -> Color(0xFFE0A12D) to stringResource(R.string.dashboard_no_standard_measurements)
            else -> MaterialTheme.colorScheme.primary to stringResource(R.string.connection_status_connected)
        }
        ConnectionState.CONNECTING -> Color(0xFFE0A12D) to stringResource(R.string.connection_status_connecting)
        ConnectionState.RECONNECTING -> Color(0xFFE0A12D) to stringResource(R.string.connection_status_reconnecting)
        ConnectionState.ERROR -> Color(0xFFE25555) to stringResource(R.string.connection_status_error)
        ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E) to stringResource(R.string.connection_status_disconnected)
    }
    val blinking = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING
    val infiniteTransition = rememberInfiniteTransition(label = "connectionIndicatorBlink")
    // Creux remonté de 0.2 à 0.5 : en dessous, la couleur se mélange trop au fond sombre de la
    // barre du haut et prend une teinte "sale" à chaque bas de cycle (constaté sur le
    // téléphone) - le clignotement reste net avec un creux moins prononcé.
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "connectionIndicatorAlpha"
    )
    Box(
        modifier = Modifier
            .padding(end = 16.dp)
            .size(16.dp)
            .clip(CircleShape)
            // alpha AVANT background : un modifier n'affecte que ce qui vient après lui
            // dans la chaîne (plus "interne"), donc alpha().background() rend le fond
            // semi-transparent, alors que background().alpha() n'a aucun effet visible
            // (alpha n'enveloppe plus rien, c'est le dernier modifier de la chaîne) —
            // exactement le bug constaté sur le téléphone : la couleur restait fixe.
            .alpha(if (blinking) blinkAlpha else 1f)
            .background(color)
            .semantics { contentDescription = label }
    )
}
