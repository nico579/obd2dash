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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    onModeChange: (ConnectionMode) -> Unit,
    onRefreshBluetoothDevices: () -> Unit,
    onSetBigGaugePid: (pid: Int, selected: Boolean) -> Unit,
    onShareLog: (String) -> Unit,
    onDeleteLog: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val connecting = state.connectionState == ConnectionState.CONNECTING

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

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton(
                "Wi-Fi",
                selected = state.connectionMode == ConnectionMode.WIFI,
                enabled = !connecting,
                onClick = { onModeChange(ConnectionMode.WIFI) },
                modifier = Modifier.weight(1f)
            )
            ModeButton(
                "Bluetooth",
                selected = state.connectionMode == ConnectionMode.BLUETOOTH,
                enabled = !connecting,
                onClick = { onModeChange(ConnectionMode.BLUETOOTH) },
                modifier = Modifier.weight(1f)
            )
        }

        if (state.connectionMode == ConnectionMode.WIFI) {
            OutlinedTextField(
                value = state.host,
                onValueChange = onHostChange,
                label = { Text(stringResource(R.string.settings_wifi_host_label)) },
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.settings_wifi_port_label)) },
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onConnect(state.host, state.port) },
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (connecting) R.string.settings_wifi_connecting else R.string.settings_wifi_connect_now))
            }
        } else {
            LaunchedEffect(state.connectionMode) { onRefreshBluetoothDevices() }
            Text(
                stringResource(R.string.settings_bluetooth_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onRefreshBluetoothDevices,
                enabled = !connecting,
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
                        enabled = !connecting,
                        onClick = { onConnectBluetooth(device) }
                    )
                }
            }
        }

        if (state.connectionState == ConnectionState.CONNECTED) {
            Text(stringResource(R.string.settings_connected_label), color = MaterialTheme.colorScheme.primary)
        }
        state.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        // Distinct de errorMessage (voir ObdUiState.recordingError, audit B4) : affiché
        // aussi ici pour qui ne serait pas déjà sur le Dashboard au moment de l'échec.
        state.recordingError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // Menu de configuration, pas un réglage de connexion : le panneau qui en résulte
        // (voir DashboardScreen.primaryDefs) est lui affiché sur le Dashboard, l'écran
        // qu'on garde ouvert en conduisant, pas ici (voir ObdUiState.bigGaugePids).
        HorizontalDivider()
        Text(stringResource(R.string.settings_big_gauges_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_big_gauges_description, state.bigGaugePids.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        for (def in PidCatalog.defs) {
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
    }
}

/** Bouton plein si sélectionné, contour sinon : matérialise le choix Wi-Fi/Bluetooth sans dépendre d'un composant à sélection segmentée expérimental pour deux options seulement. */
@Composable
private fun ModeButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
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
