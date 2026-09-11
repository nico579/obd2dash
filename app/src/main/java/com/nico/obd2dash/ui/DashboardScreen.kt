package com.nico.obd2dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import android.bluetooth.BluetoothDevice
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.ConnectionMode
import com.nico.obd2dash.ConnectionState
import com.nico.obd2dash.GaugeValue
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.PidCatalog
import com.nico.obd2dash.RecordingFile
import com.nico.obd2dash.VALUE_UNAVAILABLE_AFTER_MS
import kotlinx.coroutines.delay

// Au-delà de ce délai sans nouvelle lecture, une valeur est affichée atténuée (une pause
// de polling pendant un refresh DTC dure normalement moins longtemps que ça). Au-delà de
// VALUE_UNAVAILABLE_AFTER_MS (partagé avec l'enregistrement CSV, voir ObdViewModel), plus
// large pour ne pas clignoter pendant une pause normale, elle est masquée : trop vieille
// pour être présentée comme l'état actuel du véhicule.
private const val STALE_AFTER_MS = 3_000L

@Composable
fun DashboardScreen(
    state: ObdUiState,
    onConnect: (host: String, port: String) -> Unit,
    onConnectBluetooth: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onModeChange: (ConnectionMode) -> Unit,
    onRefreshBluetoothDevices: () -> Unit,
    onToggleRecording: () -> Unit,
    onShareRecording: (String) -> Unit,
    onDeleteRecording: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            nowMs = System.currentTimeMillis()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state.connectionState != ConnectionState.CONNECTED) {
            val connecting = state.connectionState == ConnectionState.CONNECTING
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
                    label = { Text("IP de la sonde") },
                    enabled = !connecting,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    enabled = !connecting,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onConnect(state.host, state.port) },
                    enabled = !connecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (connecting) "Connexion..." else "Connecter")
                }
            } else {
                LaunchedEffect(state.connectionMode) { onRefreshBluetoothDevices() }
                Text(
                    "Sélectionne un appareil déjà appairé dans les réglages Bluetooth du " +
                        "téléphone. Non vérifié sur un vrai adaptateur ELM327 Bluetooth (seul " +
                        "du Wi-Fi a été testé à ce jour) : le protocole est identique, mais " +
                        "cette voie de connexion elle-même ne l'est pas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onRefreshBluetoothDevices,
                    enabled = !connecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Actualiser les appareils appairés")
                }
                if (state.bondedBluetoothDevices.isEmpty()) {
                    Text(
                        "Aucun appareil appairé (ou Bluetooth désactivé, ou permission refusée).",
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
                if (connecting) {
                    Text("Connexion...", style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        } else {
            val primaryDefs = PidCatalog.defs.filter { it.pid in PidCatalog.PRIMARY_PIDS && it.pid in state.supportedPids }
            val secondaryDefs = PidCatalog.defs.filter { it.pid !in PidCatalog.PRIMARY_PIDS && it.pid in state.supportedPids }

            for (def in primaryDefs) {
                GaugeRow(def.label, state.values[def.pid], nowMs, def.pid in PidCatalog.CONTEXT_ONLY_PIDS)
            }

            if (secondaryDefs.isNotEmpty()) {
                HorizontalDivider()
                // Grille simple (pas LazyVerticalGrid) : le nombre de PID varie avec ce que
                // le véhicule annonce supporter, potentiellement plusieurs dizaines. Une
                // grille paresseuse à hauteur bornée aurait demandé un défilement interne
                // en plus de celui de la page (deux zones de scroll imbriquées, mauvaise
                // expérience). Ici toute la page défile d'un seul tenant.
                for (row in secondaryDefs.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        for (def in row) {
                            Box(modifier = Modifier.weight(1f)) {
                                SmallGauge(def.label, state.values[def.pid], nowMs, def.pid in PidCatalog.CONTEXT_ONLY_PIDS)
                            }
                        }
                        if (row.size == 1) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            HorizontalDivider()
            Button(onClick = onToggleRecording, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state.isRecording) {
                        "Arrêter l'enregistrement (${state.recordingSamples} échantillons)"
                    } else {
                        "Démarrer l'enregistrement"
                    }
                )
            }

            Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Déconnecter")
            }
        }

        // En dehors du bloc connecté : ce sont des fichiers déjà sur le disque, consultables
        // et partageables même sans être branché à une sonde.
        if (state.recordings.isNotEmpty()) {
            HorizontalDivider()
            Text("Enregistrements (${state.recordings.size})", style = MaterialTheme.typography.titleMedium)
            for (recording in state.recordings) {
                RecordingRow(
                    recording,
                    onShare = { onShareRecording(recording.path) },
                    onDelete = { onDeleteRecording(recording.path) }
                )
            }
        }
    }
}

/** Réutilisée par ProbeScreen : un fichier CSV terminé (enregistrement ou sondage) est présenté pareil. */
@Composable
internal fun RecordingRow(recording: RecordingFile, onShare: () -> Unit, onDelete: () -> Unit) {
    var confirmingDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(recording.date, style = MaterialTheme.typography.bodyMedium)
            Text(
                "%.1f Ko".format(recording.sizeBytes / 1024.0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onShare) {
                Text("Partager")
            }
            IconButton(onClick = { confirmingDelete = true }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Supprimer ${recording.name}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Supprimer ce fichier ?") },
            text = { Text("${recording.name} sera définitivement supprimé, sans confirmation possible après coup.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Annuler") }
            }
        )
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
        Button(onClick = onClick, enabled = enabled) { Text("Connecter") }
    }
}

/**
 * Texte à afficher, et s'il faut le présenter atténué (probablement périmé).
 *
 * [neverStale] : PidCatalog.CONTEXT_ONLY_PIDS (PID4F/PID50) sont lus une seule fois à la
 * connexion, jamais réinterrogés (voir ObdViewModel.startPolling) : leur âge dépasse
 * mécaniquement VALUE_UNAVAILABLE_AFTER_MS après les 10 premières secondes de CHAQUE
 * session, sans que la valeur soit fausse pour autant. Leur appliquer la même règle
 * d'âge que les PID vraiment repollés les aurait fait disparaître ("--") en permanence
 * après ce délai (constaté sur capture réelle du 11 septembre).
 */
private fun staleness(value: GaugeValue?, nowMs: Long, neverStale: Boolean = false): Pair<String, Boolean> {
    if (value == null) return "--" to false
    if (neverStale) return value.text to false
    val age = nowMs - value.updatedAtMs
    return when {
        age > VALUE_UNAVAILABLE_AFTER_MS -> "--" to false
        age > STALE_AFTER_MS -> value.text to true
        else -> value.text to false
    }
}

@Composable
private fun GaugeRow(label: String, value: GaugeValue?, nowMs: Long, neverStale: Boolean = false) {
    val (text, stale) = staleness(value, nowMs, neverStale)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            text,
            style = MaterialTheme.typography.displayMedium,
            color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
        )
    }
}

@Composable
private fun SmallGauge(label: String, value: GaugeValue?, nowMs: Long, neverStale: Boolean = false) {
    val (text, stale) = staleness(value, nowMs, neverStale)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
        )
    }
}
