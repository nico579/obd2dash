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
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.ConnectionState
import com.nico.obd2dash.FapScanOutcome
import com.nico.obd2dash.ObdUiState

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
    var targetHeader by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Sondage FAP (expérimental, lecture seule)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Interroge un par un les identifiants UDS (ReadDataByIdentifier, 0x22) d'une " +
                "plage donnée et note ce que RÉPOND CE SONDAGE : aucune écriture, aucun " +
                "effacement, aucune commande d'actionneur ou de régénération forcée. Headers " +
                "CAN désactivés (ATH0) : sans adresse cible ci-dessous, impossible de savoir " +
                "quel calculateur a répondu si plusieurs répondent à la diffusion. Reste en " +
                "session diagnostique par défaut : certains identifiants existants peuvent " +
                "malgré tout revenir en erreur s'ils nécessitent une session étendue que cet " +
                "outil ne demande volontairement pas. Le sens d'une réponse positive reste à " +
                "établir après coup, rien n'est décodé automatiquement ici.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.connectionState != ConnectionState.CONNECTED) {
            Text(
                "Connecte-toi à la sonde depuis le Dashboard pour lancer un sondage.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = startDid,
                    onValueChange = { startDid = it },
                    label = { Text("DID début (hex)") },
                    enabled = !state.isFapScanning,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = endDid,
                    onValueChange = { endDid = it },
                    label = { Text("DID fin (hex)") },
                    enabled = !state.isFapScanning,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = targetHeader,
                onValueChange = { targetHeader = it },
                label = { Text("Adresse cible (hex, optionnel — ex: 7E0)") },
                enabled = !state.isFapScanning,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Vide = diffusion (comportement par défaut, peut mélanger plusieurs " +
                    "calculateurs). Une adresse cible n'a de sens qu'en CAN.",
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
                    "DID courant : ${state.fapScanCurrentDid?.let { "%04X".format(it) } ?: "--"} " +
                        "(${state.fapScanDone}/${state.fapScanTotal})",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onStopScan, modifier = Modifier.fillMaxWidth()) {
                    Text("Arrêter le sondage")
                }
            } else {
                Button(
                    onClick = { onStartScan(startDid, endDid, targetHeader) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Démarrer le sondage")
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
                    when (it) {
                        FapScanOutcome.TERMINE -> "Dernier sondage : terminé (${state.fapScanDone}/${state.fapScanTotal})."
                        FapScanOutcome.INTERROMPU -> "Dernier sondage : interrompu (${state.fapScanDone}/${state.fapScanTotal})."
                        FapScanOutcome.ERREUR -> "Dernier sondage : arrêté en erreur (${state.fapScanDone}/${state.fapScanTotal})."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.fapScanPositives.isNotEmpty()) {
            HorizontalDivider()
            Text(
                "Réponses positives (${state.fapScanPositives.size})",
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
            Text("Sondages enregistrés (${state.probes.size})", style = MaterialTheme.typography.titleMedium)
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
