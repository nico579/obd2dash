package com.nico.obd2dash.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.ConnectionMode
import com.nico.obd2dash.ConnectionState
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.PidCatalog
import com.nico.obd2dash.R

/**
 * Réglages de connexion : adresse Wi-Fi, transport, appareil Bluetooth. Séparé du
 * Dashboard (qui ne montre plus aucun formulaire, voir DashboardScreen) parce que la
 * connexion se fait automatiquement au lancement puis se retente toute seule tant
 * qu'elle échoue (voir ObdViewModel.startAutoReconnectLoop) : ces réglages ne servent
 * qu'à changer ce que l'auto-connexion doit viser, pas à déclencher chaque connexion.
 */
@Composable
fun SettingsScreen(
    state: ObdUiState,
    onConnect: (host: String, port: String) -> Unit,
    onConnectBluetooth: (BluetoothDevice) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onRefreshBluetoothDevices: () -> Unit,
    onSetBigGaugePid: (pid: Int, selected: Boolean) -> Unit,
    onShareLog: (String) -> Unit,
    onDeleteLog: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Pas de bouton "Se connecter" ici (demande explicite) : le polling/l'auto-connexion
    // tournent déjà en continu (voir ObdViewModel.startAutoReconnectLoop), donc pas besoin
    // d'un déclencheur manuel. Les champs IP/port éditent l'état au fil de la frappe (voir
    // onHostChange/onPortChange) ; c'est en QUITTANT cet écran, pas à chaque caractère, que
    // la nouvelle adresse est réellement tentée (voir DisposableEffect plus bas) - un connect()
    // par lettre tapée serait à la fois inutile et perturbant pendant la saisie.
    //
    // rememberUpdatedState, pas une simple capture directe de state/onConnect dans onDispose :
    // DisposableEffect(Unit) n'exécute son bloc qu'une fois (clé Unit stable), donc un
    // onDispose qui capturerait state/onConnect directement figerait leur toute première
    // valeur composée, ignorant tout ce qui a été tapé depuis (l'IP éditée juste avant de
    // sortir serait perdue). rememberUpdatedState garde ces deux références à jour à chaque
    // recomposition, pour que le onDispose, lu seulement au moment où il se déclenche,
    // voie bien la dernière valeur.
    val currentState by rememberUpdatedState(state)
    val currentOnConnect by rememberUpdatedState(onConnect)
    DisposableEffect(Unit) {
        onDispose {
            if (currentState.connectionMode == ConnectionMode.WIFI) {
                currentOnConnect(currentState.host, currentState.port)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.settings_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Le choix Wi-Fi/Bluetooth lui-même vit sur Dashboard (voir DashboardScreen.ModeButton),
        // visible dès le premier lancement plutôt que caché derrière l'icône Réglages : ici,
        // seuls les détails du transport DÉJÀ choisi (adresse/port, ou appareil Bluetooth).
        if (state.connectionMode == ConnectionMode.WIFI) {
            // Bordure/fond explicites (retour direct : les champs paraissaient "mous" avec le
            // contour outline très discret d'origine) ; le focus reste sur colorScheme.primary,
            // déjà le défaut Material3, pas besoin de le répéter ici.
            val fieldColors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = fieldContainerColor(),
                focusedContainerColor = fieldContainerColor(),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
            OutlinedTextField(
                value = state.host,
                onValueChange = onHostChange,
                label = { Text(stringResource(R.string.settings_wifi_host_label)) },
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.settings_wifi_port_label)) },
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LaunchedEffect(state.connectionMode) { onRefreshBluetoothDevices() }
            Text(
                stringResource(R.string.settings_bluetooth_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onRefreshBluetoothDevices,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_bluetooth_refresh_button))
            }
            if (state.bondedBluetoothDevices.isEmpty()) {
                Text(
                    stringResource(R.string.settings_bluetooth_no_devices),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                for (device in state.bondedBluetoothDevices) {
                    BluetoothDeviceRow(
                        device = device,
                        enabled = true,
                        onClick = { onConnectBluetooth(device) }
                    )
                }
            }
        }

        if (state.connectionState == ConnectionState.CONNECTED) {
            Text(stringResource(R.string.settings_connected_label), color = MaterialTheme.colorScheme.primary)
        }
        // errorMessage jamais affiché ici, même derrière ERROR (voir DashboardScreen pour
        // l'historique complet) : switchConnectionMode() traverse ERROR à chaque tap Wi-Fi/
        // Bluetooth qui échoue immédiatement, avant que la boucle d'auto-reconnexion ne
        // reprenne la main 5s plus tard, rendant ce texte fréquent et jamais actionnable
        // depuis l'écran vu que tout est automatique ("y a toujours le message de connexion
        // en rouge" constaté malgré la garde ERROR déjà en place). Reste consultable dans le
        // rapport diagnostic exporté (buildDiagnosticReport) et le journal (EventLog).
        // Distinct de errorMessage (voir ObdUiState.recordingError, audit B4) : affiché
        // aussi ici pour qui ne serait pas déjà sur le Dashboard au moment de l'échec.
        state.recordingError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // Menu de configuration, pas un réglage de connexion : le panneau qui en résulte
        // (voir DashboardScreen.primaryDefs) est lui affiché sur le Dashboard, l'écran
        // qu'on garde ouvert en conduisant, pas ici (voir ObdUiState.bigGaugePids).
        //
        // Liste limitée aux PID que LE VÉHICULE CONNECTÉ annonce réellement supporter
        // (state.supportedPids), pas tout PidCatalog.defs (~50 PID) : proposer de choisir
        // en gros un paramètre que ce véhicule précis ne renverra jamais n'a pas de sens.
        // Vide tant que non connecté (rien à annoncer) : section entière masquée plutôt que
        // montrée avec une liste vide (demande explicite). bigGaugePids restauré par
        // véhicule à la connexion (voir ObdViewModel.finishConnecting/loadBigGaugePids) :
        // rebranche automatiquement les coches déjà faites pour CE VIN précis.
        val availableDefs = PidCatalog.defs.filter { it.pid in state.supportedPids }
        if (availableDefs.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.settings_big_gauges_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_big_gauges_description, state.bigGaugePids.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (def in availableDefs) {
                val checked = def.pid in state.bigGaugePids
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onSetBigGaugePid(def.pid, it) },
                        // Cases non cochées désactivées une fois le maximum atteint (voir
                        // ObdViewModel.MAX_BIG_GAUGE_PIDS), plutôt qu'un message d'erreur après
                        // coup : la limite reste visible avant même d'essayer de la dépasser.
                        enabled = checked || state.bigGaugePids.size < 6
                    )
                    Text(def.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Ici plutôt que sur le Dashboard : un journal d'événements n'est pas une donnée du
        // véhicule (contrairement aux enregistrements), c'est un diagnostic de l'app elle-même
        // (voir EventLog), au même titre que les réglages de connexion sur cet écran.
        if (state.logs.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.settings_log_title, state.logs.size), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_log_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (logFile in state.logs) {
                RecordingRow(
                    logFile,
                    onShare = { onShareLog(logFile.path) },
                    onDelete = { onDeleteLog(logFile.path) }
                )
            }
        }

        HorizontalDivider()
        OfflineProfilePanel(modifier = Modifier.fillMaxWidth())
        OfflineCanDtcPanel(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun BluetoothDeviceRow(device: BluetoothDevice, enabled: Boolean, onClick: () -> Unit) {
    // .name peut lever une SecurityException sans BLUETOOTH_CONNECT (ne devrait pas arriver
    // ici : la liste elle-même vient d'un appel qui l'exige déjà ; try/catch explicite
    // plutôt que runCatching, seule forme reconnue par le lint MissingPermission d'Android).
    // L'adresse MAC est le repli sûr dans tous les cas.
    val name = try { device.name ?: device.address } catch (e: SecurityException) { device.address }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onClick, enabled = enabled) { Text(stringResource(R.string.settings_bluetooth_connect_button)) }
    }
}
