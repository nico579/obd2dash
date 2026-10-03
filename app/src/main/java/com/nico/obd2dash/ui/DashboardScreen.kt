package com.nico.obd2dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.ConnectionState
import com.nico.obd2dash.GaugeValue
import com.nico.obd2dash.ObdDataAvailability
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.PidCatalog
import com.nico.obd2dash.R
import com.nico.obd2dash.RecordingFile
import com.nico.obd2dash.isUnavailable
import kotlinx.coroutines.delay

private const val STALE_AFTER_MS = 3_000L
private const val SLOW_STALE_AFTER_MS = 12_000L

/** Les commandes sont portées par ObdAppChrome : cette page ne contient que les mesures. */
@Composable
fun DashboardScreen(
    state: ObdUiState,
    modifier: Modifier = Modifier,
    onReorderGauges: (List<Int>) -> Unit = {}
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            nowMs = System.currentTimeMillis()
        }
    }
    val connected = state.connectionState == ConnectionState.CONNECTED
    val primaryDefs = state.bigGaugePids.filter { it in state.supportedPids }
        .mapNotNull { pid -> PidCatalog.defs.find { it.pid == pid } }
    Column(modifier.fillMaxSize().padding(8.dp)) {
        state.recordingError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.isRecording && (!connected || state.dataAvailability != ObdDataAvailability.STANDARD_MEASUREMENTS_AVAILABLE)) {
            Text(
                if (!connected) pluralStringResource(R.plurals.dashboard_recording_paused, state.recordingSamples, state.recordingSamples)
                else stringResource(R.string.dashboard_recording_no_data), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!connected) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text(stringResource(R.string.dashboard_waiting_connection), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.dashboard_settings_hint), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (state.dataAvailability == ObdDataAvailability.NO_VEHICLE_RESPONSE ||
            state.dataAvailability == ObdDataAvailability.NO_STANDARD_MEASUREMENTS) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                val adapterOnly = state.dataAvailability == ObdDataAvailability.NO_VEHICLE_RESPONSE
                Text(stringResource(if (adapterOnly) R.string.dashboard_adapter_only else R.string.dashboard_vehicle_responding), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (adapterOnly) R.string.dashboard_no_vehicle_response else R.string.dashboard_no_standard_measurements), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (primaryDefs.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Text(stringResource(R.string.dashboard_no_selected_gauges), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.dashboard_settings_hint), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            if (state.vehicleResponseObserved && !state.pidDiscoveryComplete) {
                Text(stringResource(R.string.dashboard_partial_discovery), style = MaterialTheme.typography.bodySmall)
            }
            InstrumentPanel(primaryDefs, state.values, nowMs, Modifier.weight(1f).fillMaxWidth(), onReorderGauges)
        }
    }
}

/** Les feuilles et l'aide sont accessibles depuis le menu commun, quelle que soit la page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardMenuDialogs(
    state: ObdUiState,
    dialog: AppMenuAction?,
    onDismiss: () -> Unit,
    onShareRecording: (String) -> Unit,
    onDeleteRecording: (String) -> Unit
) {
    if (dialog == AppMenuAction.REORDER_HELP) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.dashboard_reorder_gauges)) },
            text = { Text(stringResource(R.string.dashboard_reorder_help)) },
            confirmButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.dashboard_understood))
                }
            }
        )
    } else if (dialog != null) {
        var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) { delay(500); nowMs = System.currentTimeMillis() }
        }
        val connected = state.connectionState == ConnectionState.CONNECTED
        val secondaryDefs = PidCatalog.defs.filter { it.pid !in state.bigGaugePids && it.pid in state.supportedPids }
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (dialog == AppMenuAction.MEASUREMENTS) {
                    Text(stringResource(R.string.dashboard_other_measurements, secondaryDefs.size), style = MaterialTheme.typography.titleMedium)
                    for (def in secondaryDefs) {
                        val (text, stale) = staleness(def.pid, if (connected) state.values[def.pid] else null, nowMs)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(def.label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
                            Text(text, style = MaterialTheme.typography.titleMedium, color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                } else {
                    Text(stringResource(R.string.dashboard_recordings_title, state.recordings.size), style = MaterialTheme.typography.titleMedium)
                    for (recording in state.recordings) {
                        RecordingRow(recording, { onShareRecording(recording.path) }, { onDeleteRecording(recording.path) })
                    }
                }
            }
        }
    }
}

/** Réutilisée par ProbeScreen et SettingsScreen. */
@Composable
internal fun RecordingRow(recording: RecordingFile, onShare: () -> Unit, onDelete: () -> Unit) {
    var confirmingDelete by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(recording.date, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.common_file_size_kb, recording.sizeBytes / 1024.0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onShare) { Text(stringResource(R.string.common_share)) }
        IconButton(onClick = { confirmingDelete = true }) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete_content_description, recording.name), tint = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.common_delete_confirm_title)) },
            text = { Text(stringResource(R.string.common_delete_confirm_message, recording.name)) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

/** Même péremption pour les cadrans, les mesures secondaires et GraphScreen. */
internal fun staleness(pid: Int, value: GaugeValue?, nowMs: Long): Pair<String, Boolean> {
    if (value == null) return "--" to false
    if (pid in PidCatalog.CONTEXT_ONLY_PIDS) return value.text to false
    val age = nowMs - value.updatedAtMs
    val staleAfter = if (pid in PidCatalog.SLOW_PIDS) SLOW_STALE_AFTER_MS else STALE_AFTER_MS
    return when {
        isUnavailable(pid, value, nowMs) -> "--" to false
        age > staleAfter -> value.text to true
        else -> value.text to false
    }
}
@Preview(name = "Six cadrans • paysage", widthDp = 800, heightDp = 360)
@Preview(name = "Six cadrans • portrait", widthDp = 360, heightDp = 720)
@Composable
private fun DashboardPreview() {
    val now = System.currentTimeMillis()
    val values = mapOf(0x0C to "1728 rpm", 0x0D to "120 km/h", 0x05 to "75 °C", 0x04 to "38,0 %", 0x42 to "14,20 V", 0x0B to "133,5 kPa")
    val state = ObdUiState(
        connectionState = ConnectionState.CONNECTED,
        dataAvailability = ObdDataAvailability.STANDARD_MEASUREMENTS_AVAILABLE,
        supportedPids = values.keys, bigGaugePids = values.keys.toList(),
        values = values.mapValues { GaugeValue(it.value, now) }, pidDiscoveryComplete = true
    )
    Obd2DashTheme {
        ObdAppChrome(
            state, AppScreen.DASHBOARD, true, onNavigate = {}, onToggleRecording = {},
            onToggleFullScreen = {}, onDisconnect = {}, onMenuAction = {}
        ) { DashboardScreen(state, it) }
    }
}
