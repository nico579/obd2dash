package com.nico.obd2dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.DtcDictionary
import com.nico.obd2dash.DtcHistoryEntry
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.PidCatalog
import com.nico.obd2dash.ReadinessMonitor

@Composable
fun DtcScreen(
    state: ObdUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) { onRefresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Codes défaut", style = MaterialTheme.typography.headlineSmall)
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        when (state.milOn) {
                            true -> "MIL allumé"
                            false -> "MIL éteint"
                            null -> "MIL non lu"
                        }
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = when (state.milOn) {
                        true -> MaterialTheme.colorScheme.errorContainer
                        false -> MaterialTheme.colorScheme.secondaryContainer
                        null -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            )
        }

        if (state.vin != null || state.protocol != null) {
            Text(
                listOfNotNull(
                    state.vin?.let { "VIN $it" },
                    state.protocol?.let { "Protocole $it" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(onClick = onRefresh, enabled = !state.dtcLoading, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.dtcLoading) "Lecture..." else "Rafraîchir")
        }

        state.dtcError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        DtcSection(title = "Stockés (${state.storedDtcs?.size ?: "?"})", codes = state.storedDtcs)
        DtcSection(title = "En attente (${state.pendingDtcs?.size ?: "?"})", codes = state.pendingDtcs)

        Text(
            "Descriptions limitées aux codes génériques les plus courants, non exhaustif.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.freezeFrame.isNotEmpty()) {
            HorizontalDivider()
            Text("Freeze frame (au moment du défaut)", style = MaterialTheme.typography.titleMedium)
            for (def in PidCatalog.defs) {
                val value = state.freezeFrame[def.pid] ?: continue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(def.label, style = MaterialTheme.typography.bodyMedium)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (state.readiness.isNotEmpty()) {
            HorizontalDivider()
            Text("Moniteurs de préparation", style = MaterialTheme.typography.titleMedium)
            for (monitor in state.readiness) {
                ReadinessRow(monitor)
            }
        }

        if (state.dtcHistory.isNotEmpty()) {
            HorizontalDivider()
            Text("Historique sur ce véhicule", style = MaterialTheme.typography.titleMedium)
            for (entry in state.dtcHistory) {
                HistoryRow(entry)
            }
        }
    }
}

@Composable
private fun DtcSection(title: String, codes: List<String>?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (codes == null) {
            Text(
                "Non lu",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (codes.isEmpty()) {
            Text("Aucun", style = MaterialTheme.typography.bodyMedium)
        } else {
            for (code in codes) {
                Column {
                    Text(code, style = MaterialTheme.typography.titleLarge)
                    Text(DtcDictionary.describe(code), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(monitor: ReadinessMonitor) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(monitor.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (monitor.ready) "Complet" else "Incomplet",
            style = MaterialTheme.typography.bodyMedium,
            color = if (monitor.ready) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
}

@Composable
private fun HistoryRow(entry: DtcHistoryEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(entry.code, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Vu du ${entry.firstSeen} au ${entry.lastSeen}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (entry.active) "Actif" else "Résolu",
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.active) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
    }
}
