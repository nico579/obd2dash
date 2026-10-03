package com.nico.obd2dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nico.obd2dash.ConnectionIndicator
import com.nico.obd2dash.ConnectionMode
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: ObdUiState,
    onDisconnect: () -> Unit,
    onModeChange: (ConnectionMode) -> Unit,
    onToggleRecording: () -> Unit,
    onShareRecording: (String) -> Unit,
    onDeleteRecording: (String) -> Unit,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenGraphs: () -> Unit = {},
    onReorderGauges: (List<Int>) -> Unit = {}
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            nowMs = System.currentTimeMillis()
        }
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showRecordings by remember { mutableStateOf(false) }
    var showReorderHelp by remember { mutableStateOf(false) }
    val connected = state.connectionState == ConnectionState.CONNECTED
    val primaryDefs = state.bigGaugePids.filter { it in state.supportedPids }
        .mapNotNull { pid -> PidCatalog.defs.find { it.pid == pid } }
    val secondaryDefs = PidCatalog.defs.filter { it.pid !in state.bigGaugePids && it.pid in state.supportedPids }
    val canStartRecording = connected && state.dataAvailability == ObdDataAvailability.STANDARD_MEASUREMENTS_AVAILABLE

    // Hauteur bornée : les cadrans remplissent la surface restante, sans défilement ni
    // liste de boutons/fichiers qui leur prendrait une partie de l'écran de conduite.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sideRail = maxWidth > maxHeight
        val showRecordingCount = !sideRail && maxWidth >= 440.dp
        DashboardChrome(sideRail = sideRail, controls = { leadingModifier ->
            Row(
                modifier = leadingModifier, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (sideRail) Arrangement.Center else Arrangement.Start
            ) {
                if (fullScreen) ConnectionIndicator(state.connectionState, state.dataAvailability)
                if (state.isRecording && showRecordingCount) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    Text(
                        stringResource(R.string.dashboard_recording_indicator, state.recordingSamples),
                        modifier = Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
            DashboardActionButton(
                painter = painterResource(R.drawable.ic_chart),
                label = stringResource(R.string.dashboard_graphs),
                description = stringResource(R.string.dashboard_graphs),
                onClick = onOpenGraphs,
                enabled = connected,
                prominent = true,
                modifier = Modifier.width(88.dp)
            )
            if (canStartRecording || state.isRecording) {
                DashboardActionButton(
                    painter = painterResource(if (state.isRecording) R.drawable.ic_stop_recording else R.drawable.ic_record),
                    label = stringResource(if (state.isRecording) R.string.dashboard_stop_short else R.string.dashboard_record_short),
                    description = if (state.isRecording) {
                        pluralStringResource(R.plurals.dashboard_stop_recording, state.recordingSamples, state.recordingSamples)
                    } else stringResource(R.string.dashboard_start_recording),
                    onClick = onToggleRecording,
                    recording = state.isRecording
                )
            }
            DashboardActionButton(
                painter = painterResource(if (fullScreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen),
                label = stringResource(R.string.dashboard_screen_short),
                description = stringResource(if (fullScreen) R.string.dashboard_exit_fullscreen else R.string.dashboard_enter_fullscreen),
                onClick = onToggleFullScreen
            )
            Box {
                DashboardActionButton(
                    painter = rememberVectorPainter(Icons.Filled.MoreVert),
                    label = stringResource(R.string.dashboard_menu_short),
                    description = stringResource(R.string.dashboard_actions),
                    onClick = { menuExpanded = true }
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_choose_gauges)) }, onClick = {
                        menuExpanded = false
                        onOpenSettings()
                    })
                    if (primaryDefs.size > 1) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_reorder_gauges)) }, onClick = {
                            menuExpanded = false
                            showReorderHelp = true
                        })
                    }
                    if (secondaryDefs.isNotEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_other_measurements, secondaryDefs.size)) }, onClick = {
                            menuExpanded = false
                            showDetails = true
                        })
                    }
                    if (state.recordings.isNotEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_recordings_title, state.recordings.size)) }, onClick = {
                            menuExpanded = false
                            showRecordings = true
                        })
                    }
                    if (connected) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_disconnect_button)) }, onClick = {
                            menuExpanded = false
                            onDisconnect()
                        })
                    }
                }
            }
        }, content = { contentModifier ->
            Column(modifier = contentModifier) {
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
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModeButton("Wi-Fi", state.connectionMode == ConnectionMode.WIFI, { onModeChange(ConnectionMode.WIFI) }, Modifier.weight(1f))
                            ModeButton("Bluetooth", state.connectionMode == ConnectionMode.BLUETOOTH, { onModeChange(ConnectionMode.BLUETOOTH) }, Modifier.weight(1f))
                        }
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text(stringResource(R.string.dashboard_waiting_connection), style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (state.dataAvailability == ObdDataAvailability.NO_VEHICLE_RESPONSE ||
                    state.dataAvailability == ObdDataAvailability.NO_STANDARD_MEASUREMENTS) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                    ) {
                        val adapterOnly = state.dataAvailability == ObdDataAvailability.NO_VEHICLE_RESPONSE
                        Text(stringResource(if (adapterOnly) R.string.dashboard_adapter_only else R.string.dashboard_vehicle_responding), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (adapterOnly) R.string.dashboard_no_vehicle_response else R.string.dashboard_no_standard_measurements), style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (primaryDefs.isEmpty()) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(stringResource(R.string.dashboard_no_selected_gauges), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.dashboard_choose_gauges)) }
                    }
                } else {
                    if (state.vehicleResponseObserved && !state.pidDiscoveryComplete) {
                        Text(stringResource(R.string.dashboard_partial_discovery), style = MaterialTheme.typography.bodySmall)
                    }
                    InstrumentPanel(
                        primaryDefs, state.values, nowMs,
                        Modifier.weight(1f).fillMaxWidth().padding(bottom = 4.dp), onReorderGauges
                    )
                }
            }
        })
    }
    if (showReorderHelp) {
        AlertDialog(
            onDismissRequest = { showReorderHelp = false },
            title = { Text(stringResource(R.string.dashboard_reorder_gauges)) },
            text = { Text(stringResource(R.string.dashboard_reorder_help)) },
            confirmButton = {
                TextButton(onClick = { showReorderHelp = false }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.dashboard_understood))
                }
            }
        )
    }
    if (showDetails || showRecordings) {
        ModalBottomSheet(onDismissRequest = { showDetails = false; showRecordings = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showDetails) {
                    Text(stringResource(R.string.dashboard_other_measurements, secondaryDefs.size), style = MaterialTheme.typography.titleMedium)
                    for (def in secondaryDefs) {
                        val (text, stale) = staleness(def.pid, if (connected) state.values[def.pid] else null, nowMs)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(def.label, modifier = Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
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

/** En paysage, les commandes occupent une bande latérale plutôt qu'une rangée de hauteur. */
@Composable
private fun DashboardChrome(
    sideRail: Boolean,
    controls: @Composable (Modifier) -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    if (sideRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            content(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 4.dp))
            Column(
                modifier = Modifier.width(96.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                controls(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                controls(Modifier.weight(1f))
            }
            content(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp))
        }
    }
}

/** Commandes lisibles à distance : pictogrammes de 32 dp et cibles de 56 × 64 dp minimum. */
@Composable
private fun DashboardActionButton(
    painter: Painter,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
    recording: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp)
    val labelWidth = remember(label, labelStyle, density) {
        textMeasurer.measure(label, labelStyle, softWrap = false, maxLines = 1).size.width
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.width(56.dp).height(64.dp).semantics { contentDescription = description; role = Role.Button },
        shape = RoundedCornerShape(12.dp),
        color = when {
            recording -> colors.errorContainer
            prominent -> colors.secondaryContainer
            else -> colors.surfaceVariant
        },
        contentColor = when {
            !enabled -> colors.onSurface.copy(alpha = .38f)
            else -> colors.onSurface
        }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
            val availableWidth = with(density) { maxWidth.toPx() }
            val labelScale = if (labelWidth > availableWidth) availableWidth / labelWidth * .98f else 1f
            Column(
                Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(painter, contentDescription = null, modifier = Modifier.size(32.dp))
                Text(
                    label, modifier = Modifier.clearAndSetSemantics {},
                    style = labelStyle.copy(fontSize = labelStyle.fontSize * labelScale),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
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
    Obd2DashTheme {
        DashboardScreen(
            state = ObdUiState(
                connectionState = ConnectionState.CONNECTED,
                dataAvailability = ObdDataAvailability.STANDARD_MEASUREMENTS_AVAILABLE,
                supportedPids = values.keys, bigGaugePids = values.keys.toList(),
                values = values.mapValues { GaugeValue(it.value, now) }, pidDiscoveryComplete = true
            ),
            onDisconnect = {}, onModeChange = {}, onToggleRecording = {}, onShareRecording = {}, onDeleteRecording = {},
            fullScreen = true
        )
    }
}
