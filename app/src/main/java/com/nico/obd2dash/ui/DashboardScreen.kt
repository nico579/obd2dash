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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.ConnectionState
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.PidCatalog

@Composable
fun DashboardScreen(
    state: ObdUiState,
    onConnect: (host: String, port: Int) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var host by remember { mutableStateOf("192.168.0.10") }
    var port by remember { mutableStateOf("35000") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("OBD2 Dash", style = MaterialTheme.typography.headlineMedium)

        if (state.connectionState != ConnectionState.CONNECTED) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("IP de la sonde") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onConnect(host, port.toIntOrNull() ?: 35000) },
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
                GaugeRow(def.label, state.values[def.pid] ?: "--")
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
                        SmallGauge(def.label, state.values[def.pid] ?: "--")
                    }
                }
            }

            Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Déconnecter")
            }
        }
    }
}

@Composable
private fun GaugeRow(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.displayMedium)
    }
}

@Composable
private fun SmallGauge(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
