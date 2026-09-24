package com.nico.obd2dash.ui

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.DtcDictionary
import com.nico.obd2dash.DtcHistoryEntry
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.PidCatalog
import com.nico.obd2dash.R
import com.nico.obd2dash.ReadinessMonitor
import com.nico.obd2dash.VinDecoder
import com.nico.obd2dash.buildDiagnosticReport

@Composable
fun DtcScreen(
    state: ObdUiState,
    onRefresh: () -> Unit,
    onProbeHeaders: () -> Unit,
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
            Text(stringResource(R.string.dtc_title), style = MaterialTheme.typography.headlineSmall)
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        stringResource(
                            when (state.milOn) {
                                true -> R.string.dtc_mil_on
                                false -> R.string.dtc_mil_off
                                null -> R.string.dtc_mil_unknown
                            }
                        )
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
            // Décodage local du VIN (constructeur + année-modèle), pas propre à une
            // marque (voir VinDecoder) : constructeur en priorité, région seule si ce WMI
            // précis n'est pas dans la table.
            val vinInfo = state.vin?.let { VinDecoder.decode(it) }
            Text(
                listOfNotNull(
                    state.vin?.let { stringResource(R.string.dtc_vin_label, it) },
                    state.protocol?.let { stringResource(R.string.dtc_protocol_label, it) },
                    vinInfo?.manufacturer ?: vinInfo?.region,
                    vinInfo?.modelYear?.let { stringResource(R.string.dtc_model_year_label, it) }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(onClick = onRefresh, enabled = !state.dtcLoading, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (state.dtcLoading) R.string.dtc_loading else R.string.dtc_refresh_button))
        }

        val context = LocalContext.current
        val exportSubject = stringResource(R.string.dtc_export_subject)
        val shareChooserTitle = stringResource(R.string.dtc_share_chooser_title)
        Button(
            onClick = {
                // Sharesheet Android standard : l'utilisateur choisit où envoyer le texte
                // (mail, fichiers, messagerie...), rien de spécifique à gérer côté app.
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, exportSubject)
                    putExtra(Intent.EXTRA_TEXT, buildDiagnosticReport(state))
                }
                context.startActivity(Intent.createChooser(shareIntent, shareChooserTitle))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.dtc_share_button))
        }

        state.dtcError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        DtcSection(title = stringResource(R.string.dtc_stored_title, state.storedDtcs?.size?.toString() ?: "?"), codes = state.storedDtcs)
        DtcSection(title = stringResource(R.string.dtc_pending_title, state.pendingDtcs?.size?.toString() ?: "?"), codes = state.pendingDtcs)

        Text(
            stringResource(R.string.dtc_description_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.freezeFrame.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.dtc_freeze_frame_title), style = MaterialTheme.typography.titleMedium)
            state.freezeFrameDtc?.let {
                Text(stringResource(R.string.dtc_freeze_frame_trigger, it), style = MaterialTheme.typography.bodyMedium)
            }
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
            Text(stringResource(R.string.dtc_readiness_title), style = MaterialTheme.typography.titleMedium)
            for (monitor in state.readiness) {
                ReadinessRow(monitor)
            }
        }

        if (state.dtcHistory.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.dtc_history_title), style = MaterialTheme.typography.titleMedium)
            for (entry in state.dtcHistory) {
                HistoryRow(entry)
            }
        }

        HorizontalDivider()
        Text(
            stringResource(R.string.dtc_probe_headers_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onProbeHeaders, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dtc_probe_headers_button))
        }
        state.headerProbeResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DtcSection(title: String, codes: List<String>?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (codes == null) {
            Text(
                stringResource(R.string.dtc_section_not_read),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (codes.isEmpty()) {
            Text(stringResource(R.string.dtc_section_none), style = MaterialTheme.typography.bodyMedium)
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
            stringResource(if (monitor.ready) R.string.dtc_readiness_complete else R.string.dtc_readiness_incomplete),
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
                stringResource(R.string.dtc_history_seen_range, entry.firstSeen, entry.lastSeen),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            // "Résolu" affirmait une panne réparée ; la seule preuve disponible est son
            // absence de la dernière liste de DTC stockés lue avec succès (même correction
            // que buildDiagnosticReport, voir audit, terminologie historique).
            stringResource(if (entry.active) R.string.dtc_history_active else R.string.dtc_history_not_found),
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.active) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
    }
}
