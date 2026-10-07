package com.nico.obd2dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nico.obd2dash.ConnectionIndicator
import com.nico.obd2dash.ConnectionState
import com.nico.obd2dash.ObdDataAvailability
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.PidCatalog
import com.nico.obd2dash.R

internal enum class AppScreen { DASHBOARD, GRAPH, DTC, PROBE, AUTO_TEST, SETTINGS }
internal enum class AppMenuAction { MEASUREMENTS, RECORDINGS, REORDER_HELP }

/** Une seule zone de commandes, partagée par toutes les pages et le plein écran. */
@Composable
internal fun ObdAppChrome(
    state: ObdUiState,
    screen: AppScreen,
    fullScreen: Boolean,
    onNavigate: (AppScreen) -> Unit,
    onToggleRecording: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onDisconnect: () -> Unit,
    onMenuAction: (AppMenuAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val connected = state.connectionState == ConnectionState.CONNECTED
    val waitingScreen = !connected && (screen == AppScreen.DASHBOARD || screen == AppScreen.GRAPH)
    val canRecord = state.isRecording || (connected &&
        state.dataAvailability == ObdDataAvailability.STANDARD_MEASUREMENTS_AVAILABLE)
    val secondaryCount = PidCatalog.defs.count { it.pid in state.supportedPids && it.pid !in state.bigGaugePids }
    val visibleGaugeCount = state.bigGaugePids.count { it in state.supportedPids }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Sur une fenêtre basse, la rangée horizontale garde les cinq commandes
        // visibles sans diminuer leurs cibles tactiles ni faire défiler le menu.
        val sideRail = maxWidth > maxHeight && maxHeight >= 344.dp
        val controls: @Composable (Modifier) -> Unit = { buttonModifier ->
            AppCommandButton(
                rememberVectorPainter(Icons.Filled.Home), stringResource(R.string.nav_dashboard),
                stringResource(R.string.nav_dashboard), { onNavigate(AppScreen.DASHBOARD) },
                buttonModifier.testTag("command_dashboard"), selected = screen == AppScreen.DASHBOARD
            ) {
                if (!waitingScreen) {
                    ConnectionIndicator(
                        state.connectionState, state.dataAvailability,
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp)
                    )
                }
            }
            AppCommandButton(
                painterResource(R.drawable.ic_chart), stringResource(R.string.nav_graph),
                stringResource(R.string.nav_graph), { onNavigate(AppScreen.GRAPH) },
                buttonModifier.testTag("command_graph"), enabled = connected, selected = screen == AppScreen.GRAPH
            )
            AppCommandButton(
                painterResource(if (state.isRecording) R.drawable.ic_stop_recording else R.drawable.ic_record),
                stringResource(if (state.isRecording) R.string.dashboard_stop_short else R.string.dashboard_record_short),
                if (state.isRecording) {
                    val stop = pluralStringResource(R.plurals.dashboard_stop_recording, state.recordingSamples, state.recordingSamples)
                    if (!connected) {
                        pluralStringResource(R.plurals.dashboard_recording_paused, state.recordingSamples, state.recordingSamples) + ". " + stop
                    } else stop
                } else stringResource(R.string.dashboard_start_recording),
                onToggleRecording, buttonModifier.testTag("command_record"), enabled = canRecord, recording = state.isRecording
            )
            AppCommandButton(
                painterResource(if (fullScreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen),
                stringResource(R.string.dashboard_screen_short),
                stringResource(if (fullScreen) R.string.dashboard_exit_fullscreen else R.string.dashboard_enter_fullscreen),
                onToggleFullScreen, buttonModifier.testTag("command_fullscreen"), selected = fullScreen
            )
            Box(buttonModifier) {
                AppCommandButton(
                    rememberVectorPainter(Icons.Filled.MoreVert), stringResource(R.string.dashboard_menu_short),
                    stringResource(R.string.dashboard_actions), { menuExpanded = true },
                    Modifier.fillMaxWidth().testTag("command_menu")
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    for ((destination, label) in listOf(
                        AppScreen.DTC to R.string.nav_dtc,
                        AppScreen.PROBE to R.string.nav_probe,
                        AppScreen.AUTO_TEST to R.string.nav_smoke_test,
                        AppScreen.SETTINGS to R.string.topbar_title_settings
                    )) {
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            enabled = connected || destination == AppScreen.SETTINGS,
                            onClick = { menuExpanded = false; onNavigate(destination) },
                            modifier = Modifier.testTag("menu_${destination.name.lowercase()}")
                        )
                    }
                    if (visibleGaugeCount > 1 || secondaryCount > 0 || state.recordings.isNotEmpty() || connected) {
                        HorizontalDivider()
                    }
                    if (visibleGaugeCount > 1) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_reorder_gauges)) }, onClick = {
                            menuExpanded = false
                            onNavigate(AppScreen.DASHBOARD)
                            onMenuAction(AppMenuAction.REORDER_HELP)
                        })
                    }
                    if (secondaryCount > 0) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_other_measurements, secondaryCount)) }, onClick = {
                            menuExpanded = false; onMenuAction(AppMenuAction.MEASUREMENTS)
                        })
                    }
                    if (state.recordings.isNotEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_recordings_title, state.recordings.size)) }, onClick = {
                            menuExpanded = false; onMenuAction(AppMenuAction.RECORDINGS)
                        })
                    }
                    if (connected) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_disconnect_button)) }, onClick = {
                            menuExpanded = false; onDisconnect()
                        })
                    }
                }
            }
        }
        if (sideRail) {
            Row(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f).fillMaxHeight())
                Surface(color = navBarContainerColor()) {
                    Column(
                        Modifier.width(84.dp).fillMaxHeight().padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
                    ) { controls(Modifier.fillMaxWidth()) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f).fillMaxWidth())
                Surface(color = navBarContainerColor()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) { controls(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AppCommandButton(
    painter: Painter,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    recording: Boolean = false,
    badge: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp)
    val labelWidth = remember(label, labelStyle, density) {
        textMeasurer.measure(label, labelStyle, softWrap = false, maxLines = 1).size.width
    }
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.height(64.dp).semantics { contentDescription = description; role = Role.Button },
        shape = RoundedCornerShape(12.dp),
        color = when {
            recording -> colors.errorContainer
            selected -> colors.secondaryContainer
            else -> colors.surfaceVariant
        },
        contentColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f)
    ) {
        Box(Modifier.fillMaxSize()) {
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
            badge()
        }
    }
}
