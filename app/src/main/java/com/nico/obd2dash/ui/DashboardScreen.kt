package com.nico.obd2dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.nico.obd2dash.ConnectionState
import com.nico.obd2dash.GaugeValue
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.PidCatalog
import kotlinx.coroutines.delay

// Au-delà de ce délai sans nouvelle lecture, une valeur est affichée atténuée (une pause
// de polling pendant un refresh DTC dure normalement moins longtemps que ça). Au-delà du
// second délai, plus large pour ne pas clignoter pendant une pause normale, elle est
// masquée : trop vieille pour être présentée comme l'état actuel du véhicule.
private const val STALE_AFTER_MS = 3_000L
private const val UNAVAILABLE_AFTER_MS = 10_000L

@Composable
fun DashboardScreen(
    state: ObdUiState,
    onConnect: (host: String, port: String) -> Unit,
    onDisconnect: () -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
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
        Text("OBD2 Dash", style = MaterialTheme.typography.headlineMedium)

        if (state.connectionState != ConnectionState.CONNECTED) {
            OutlinedTextField(
                value = state.host,
                onValueChange = onHostChange,
                label = { Text("IP de la sonde") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onConnect(state.host, state.port) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when (state.connectionState) {
                        ConnectionState.CONNECTING -> "Connexion..."
                        else -> "Connecter"
                    }
                )
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        } else {
            val primaryDefs = PidCatalog.defs.filter { it.pid in PidCatalog.PRIMARY_PIDS && it.pid in state.supportedPids }
            val secondaryDefs = PidCatalog.defs.filter { it.pid !in PidCatalog.PRIMARY_PIDS && it.pid in state.supportedPids }

            for (def in primaryDefs) {
                GaugeRow(def.label, state.values[def.pid], nowMs)
            }

            if (secondaryDefs.isNotEmpty()) {
                HorizontalDivider()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(secondaryDefs) { def ->
                        SmallGauge(def.label, state.values[def.pid], nowMs)
                    }
                }
            }

            Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Déconnecter")
            }
        }
    }
}

/** Texte à afficher, et s'il faut le présenter atténué (probablement périmé). */
private fun staleness(value: GaugeValue?, nowMs: Long): Pair<String, Boolean> {
    if (value == null) return "--" to false
    val age = nowMs - value.updatedAtMs
    return when {
        age > UNAVAILABLE_AFTER_MS -> "--" to false
        age > STALE_AFTER_MS -> value.text to true
        else -> value.text to false
    }
}

@Composable
private fun GaugeRow(label: String, value: GaugeValue?, nowMs: Long) {
    val (text, stale) = staleness(value, nowMs)
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
private fun SmallGauge(label: String, value: GaugeValue?, nowMs: Long) {
    val (text, stale) = staleness(value, nowMs)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
        )
    }
}
