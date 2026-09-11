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
    onDisconnect: () -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onToggleRecording: () -> Unit,
    onShareRecording: (String) -> Unit,
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
                                SmallGauge(def.label, state.values[def.pid], nowMs)
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
                RecordingRow(recording, onShare = { onShareRecording(recording.path) })
            }
        }
    }
}

/** Réutilisée par ProbeScreen : un fichier CSV terminé (enregistrement ou sondage) est présenté pareil. */
@Composable
internal fun RecordingRow(recording: RecordingFile, onShare: () -> Unit) {
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
        Button(onClick = onShare) {
            Text("Partager")
        }
    }
}

/** Texte à afficher, et s'il faut le présenter atténué (probablement périmé). */
private fun staleness(value: GaugeValue?, nowMs: Long): Pair<String, Boolean> {
    if (value == null) return "--" to false
    val age = nowMs - value.updatedAtMs
    return when {
        age > VALUE_UNAVAILABLE_AFTER_MS -> "--" to false
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
