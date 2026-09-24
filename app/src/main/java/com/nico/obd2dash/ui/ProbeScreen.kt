package com.nico.obd2dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.ConnectionState
import com.nico.obd2dash.FapScanOutcome
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.R
import com.nico.obd2dash.UNVALIDATED_MANUFACTURER_PROBES_ENABLED

/**
 * Sondage en lecture seule d'identifiants UDS (voir ObdViewModel.startFapScan) : aucun DID
 * n'a de définition publique connue pour le calculateur connecté, l'écran sert donc à
 * explorer une plage empiriquement et à consigner chaque réponse pour analyse après coup,
 * pas à afficher un résultat déjà interprété.
 */
@Composable
fun ProbeScreen(
    state: ObdUiState,
    onStartScan: (startDid: String, endDid: String, targetHeader: String) -> Unit,
    onStopScan: () -> Unit,
    onShareProbe: (String) -> Unit,
    onDeleteProbe: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var startDid by remember { mutableStateOf("1140") }
    var endDid by remember { mutableStateOf("11FF") }
    // 7E0 (calculateur moteur) par défaut plutôt que la diffusion : en diffusion, les
    // calculateurs UDS ne renvoient généralement pas leurs refus (NRC 0x11/0x12/0x31,
    // ISO 14229), si bien qu'un sondage entier revenait "aucune réponse" sans rien
    // apprendre (fap_scan_SEAT-000000_20260924_172101.csv, 192 DID).
    var targetHeader by remember { mutableStateOf("7E0") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.probe_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.probe_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.connectionState != ConnectionState.CONNECTED) {
            Text(
                stringResource(R.string.probe_not_connected),
                style = MaterialTheme.typography.bodyMedium
            )
        } else if (!UNVALIDATED_MANUFACTURER_PROBES_ENABLED) {
            Text(stringResource(R.string.probe_requires_validated_profile), style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = startDid,
                    onValueChange = { startDid = it },
                    label = { Text(stringResource(R.string.probe_start_did_label)) },
                    enabled = !state.isFapScanning,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = endDid,
                    onValueChange = { endDid = it },
                    label = { Text(stringResource(R.string.probe_end_did_label)) },
                    enabled = !state.isFapScanning,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = targetHeader,
                onValueChange = { targetHeader = it },
                label = { Text(stringResource(R.string.probe_target_header_label)) },
                enabled = !state.isFapScanning,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.probe_broadcast_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.fapScanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (state.isFapScanning) {
                val fraction = if (state.fapScanTotal > 0) {
                    state.fapScanDone / state.fapScanTotal.toFloat()
                } else {
                    0f
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(
                        R.string.probe_current_did,
                        state.fapScanCurrentDid?.let { "%04X".format(it) } ?: "--",
                        state.fapScanDone,
                        state.fapScanTotal
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onStopScan, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.probe_stop_button))
                }
            } else {
                Button(
                    onClick = { onStartScan(startDid, endDid, targetHeader) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.probe_start_button))
                }
            }
        }

        // Bilan du dernier sondage et réponses positives : déplacés hors du bloc CONNECTED
        // (voir audit, "Résultats d'outils") : une reconnexion qui suit immédiatement la
        // fin d'un sondage ne doit pas faire disparaître ce qu'il vient de trouver, seuls
        // les contrôles pour en lancer un NOUVEAU exigent une connexion active.
        if (!state.isFapScanning) {
            state.fapScanOutcome?.let {
                Text(
                    stringResource(
                        when (it) {
                            FapScanOutcome.TERMINE -> R.string.probe_outcome_done
                            FapScanOutcome.INTERROMPU -> R.string.probe_outcome_interrupted
                            FapScanOutcome.ERREUR -> R.string.probe_outcome_error
                        },
                        state.fapScanDone,
                        state.fapScanTotal
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.fapScanPositives.isNotEmpty()) {
            HorizontalDivider()
            Text(
                stringResource(R.string.probe_positives_title, state.fapScanPositives.size),
                style = MaterialTheme.typography.titleMedium
            )
            for (positive in state.fapScanPositives) {
                Text(positive, style = MaterialTheme.typography.bodySmall)
            }
        }

        // En dehors du bloc connecté : fichiers déjà sur le disque, consultables et
        // partageables même sans être branché à une sonde (même logique que les
        // enregistrements du Dashboard).
        if (state.probes.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.probe_saved_title, state.probes.size), style = MaterialTheme.typography.titleMedium)
            for (probe in state.probes) {
                RecordingRow(
                    probe,
                    onShare = { onShareProbe(probe.path) },
                    onDelete = { onDeleteProbe(probe.path) }
                )
            }
        }
    }
}
