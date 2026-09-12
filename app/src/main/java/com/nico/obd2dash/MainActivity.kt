package com.nico.obd2dash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nico.obd2dash.ui.AutoTestScreen
import com.nico.obd2dash.ui.DashboardScreen
import com.nico.obd2dash.ui.DtcScreen
import com.nico.obd2dash.ui.GraphScreen
import com.nico.obd2dash.ui.ProbeScreen
import com.nico.obd2dash.ui.SettingsScreen
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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier) {
                    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
                    var showSettings by remember { mutableStateOf(false) }
                    val state by viewModel.state.collectAsState()

                    // Sans ça, le retour système depuis Réglages ferme l'Activity racine
                    // (Android <=11) ou la met en arrière-plan (Android 12+) au lieu de
                    // revenir au Dashboard comme la flèche de la barre du haut (voir audit,
                    // "Navigation") : le premier cas arrête aussi l'acquisition liée à ce
                    // ViewModel, contrairement à ce qu'un simple retour d'écran laisse
                    // attendre.
                    BackHandler(enabled = showSettings) { showSettings = false }

                    Scaffold(
                        topBar = {
                            TopAppBar(
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
                                    ConnectionIndicator(state.connectionState)
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
                            NavigationBar {
                                NavigationBarItem(
                                    selected = screen == Screen.DASHBOARD,
                                    onClick = { screen = Screen.DASHBOARD; showSettings = false },
                                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_dashboard)) }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.DTC,
                                    onClick = { screen = Screen.DTC; showSettings = false },
                                    icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_dtc)) }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.PROBE,
                                    onClick = { screen = Screen.PROBE; showSettings = false },
                                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_probe)) }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.GRAPH,
                                    onClick = { screen = Screen.GRAPH; showSettings = false },
                                    icon = { Icon(painterResource(R.drawable.ic_chart), contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_graph)) }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.AUTO_TEST,
                                    onClick = { screen = Screen.AUTO_TEST; showSettings = false },
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
                                onModeChange = { viewModel.setConnectionMode(it) },
                                onRefreshBluetoothDevices = { ensureBluetoothPermissionThenRefresh() },
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
                                modifier = Modifier.padding(padding)
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
private fun ConnectionIndicator(state: ConnectionState) {
    val (color, label) = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF2E7D32) to stringResource(R.string.connection_status_connected)
        ConnectionState.CONNECTING -> Color(0xFFF9A825) to stringResource(R.string.connection_status_connecting)
        ConnectionState.RECONNECTING -> Color(0xFFF9A825) to stringResource(R.string.connection_status_reconnecting)
        ConnectionState.ERROR -> Color(0xFFC62828) to stringResource(R.string.connection_status_error)
        ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E) to stringResource(R.string.connection_status_disconnected)
    }
    val blinking = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING
    val infiniteTransition = rememberInfiniteTransition(label = "connectionIndicatorBlink")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "connectionIndicatorAlpha"
    )
    Box(
        modifier = Modifier
            .padding(end = 16.dp)
            .size(10.dp)
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
