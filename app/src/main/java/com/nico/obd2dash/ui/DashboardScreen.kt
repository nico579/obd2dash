package com.nico.obd2dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.ConnectionMode
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
    onDisconnect: () -> Unit,
    onToggleRecording: () -> Unit,
    onShareRecording: (String) -> Unit,
    onDeleteRecording: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
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
        if (state.connectionState != ConnectionState.CONNECTED) {
            // Pas de formulaire IP/port ni de bouton Connecter ici : la connexion est
            // entièrement automatique (voir ObdViewModel.startAutoReconnectLoop), retentée
            // toute seule tant qu'elle échoue (sonde injoignable avant que le contact soit
            // mis, par exemple). Changer d'adresse, de transport ou d'appareil Bluetooth se
            // fait depuis Réglages (icône engrenage), pas depuis cet écran.
            when (state.connectionState) {
                ConnectionState.CONNECTING -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text("Connexion...", style = MaterialTheme.typography.bodyMedium)
                }
                ConnectionState.RECONNECTING -> {
                    // Coupure transitoire (voir ObdViewModel.handleConnectionLost) : la
                    // boucle reprend seule, rien d'alarmant à montrer ("ne pas afficher
                    // d'erreur si rien n'est disponible, il faut juste boucler et attendre").
                    // Un enregistrement/graphique en cours n'est PAS arrêté ici : il reprendra
                    // automatiquement (voir finishConnecting/resumeRecordingLoop), donc le
                    // signaler plutôt que de faire croire à un arrêt.
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text("Reconnexion en cours...", style = MaterialTheme.typography.bodyMedium)
                    if (state.isRecording) {
                        Text(
                            "Enregistrement en pause, reprendra automatiquement " +
                                "(${state.recordingSamples} échantillons déjà enregistrés).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    Text(
                        "En attente de la sonde" +
                            (if (state.connectionMode == ConnectionMode.WIFI) " Wi-Fi" else " Bluetooth") + "...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Nouvel essai automatique toutes les 5 secondes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Erreur affichée seulement ici (état ERROR, provoqué uniquement par une
                    // action explicite dans Réglages) : une tentative automatique qui échoue
                    // passe par RECONNECTING ci-dessus, jamais par ERROR, précisément pour ne
                    // rien afficher de ce genre pendant une simple attente.
                    if (state.connectionState == ConnectionState.ERROR) {
                        state.errorMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            // bigGaugePids : choix de l'utilisateur depuis Réglages (voir ObdUiState),
            // PRIMARY_PIDS par défaut tant que rien n'est personnalisé.
            val primaryDefs = PidCatalog.defs.filter { it.pid in state.bigGaugePids && it.pid in state.supportedPids }
            val secondaryDefs = PidCatalog.defs.filter { it.pid !in state.bigGaugePids && it.pid in state.supportedPids }

            for (def in primaryDefs) {
                GaugeRow(def.label, state.values[def.pid], nowMs, def.pid in PidCatalog.CONTEXT_ONLY_PIDS)
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
                                SmallGauge(def.label, state.values[def.pid], nowMs, def.pid in PidCatalog.CONTEXT_ONLY_PIDS)
                            }
                        }
                        if (row.size == 1) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            HorizontalDivider()
            Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Déconnecter")
            }
        }

        // En dehors du bloc connecté, à dessein : un enregistrement en cours doit rester
        // arrêtable manuellement même pendant une reconnexion automatique (RECONNECTING),
        // pas seulement quand CONNECTED ("le stop doit être manuel", jamais automatique, voir
        // ObdViewModel.pauseRecordingForReconnect). "Démarrer" n'a de sens que CONNECTED
        // (startRecording() exige un client actif), d'où la condition en union plutôt qu'un
        // simple state.isRecording.
        if (state.connectionState == ConnectionState.CONNECTED || state.isRecording) {
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
            // Visible près de son propre bouton, y compris CONNECTED (voir audit B4) :
            // errorMessage ne s'affiche sur cet écran que hors CONNECTED, une erreur
            // d'enregistrement (démarrage refusé, écriture échouée) restait donc invisible
            // ici tant que la connexion elle-même allait bien.
            state.recordingError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        // En dehors du bloc connecté : ce sont des fichiers déjà sur le disque, consultables
        // et partageables même sans être branché à une sonde.
        if (state.recordings.isNotEmpty()) {
            HorizontalDivider()
            Text("Enregistrements (${state.recordings.size})", style = MaterialTheme.typography.titleMedium)
            for (recording in state.recordings) {
                RecordingRow(
                    recording,
                    onShare = { onShareRecording(recording.path) },
                    onDelete = { onDeleteRecording(recording.path) }
                )
            }
        }
    }
}

/** Réutilisée par ProbeScreen : un fichier CSV terminé (enregistrement ou sondage) est présenté pareil. */
@Composable
internal fun RecordingRow(recording: RecordingFile, onShare: () -> Unit, onDelete: () -> Unit) {
    var confirmingDelete by remember { mutableStateOf(false) }

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onShare) {
                Text("Partager")
            }
            IconButton(onClick = { confirmingDelete = true }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Supprimer ${recording.name}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Supprimer ce fichier ?") },
            text = { Text("${recording.name} sera définitivement supprimé, sans confirmation possible après coup.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Annuler") }
            }
        )
    }
}

/**
 * Texte à afficher, et s'il faut le présenter atténué (probablement périmé).
 *
 * [neverStale] : PidCatalog.CONTEXT_ONLY_PIDS (PID4F/PID50) sont lus une seule fois à la
 * connexion, jamais réinterrogés (voir ObdViewModel.startPolling) : leur âge dépasse
 * mécaniquement VALUE_UNAVAILABLE_AFTER_MS après les 10 premières secondes de CHAQUE
 * session, sans que la valeur soit fausse pour autant. Leur appliquer la même règle
 * d'âge que les PID vraiment repollés les aurait fait disparaître ("--") en permanence
 * après ce délai (constaté sur capture réelle du 11 septembre).
 *
 * internal (pas private) : réutilisée telle quelle par GraphScreen pour son "Actuel" (voir
 * audit B5), plutôt que d'y dupliquer un calcul de péremption qui pourrait diverger avec
 * le temps.
 */
internal fun staleness(value: GaugeValue?, nowMs: Long, neverStale: Boolean = false): Pair<String, Boolean> {
    if (value == null) return "--" to false
    if (neverStale) return value.text to false
    val age = nowMs - value.updatedAtMs
    return when {
        age > VALUE_UNAVAILABLE_AFTER_MS -> "--" to false
        age > STALE_AFTER_MS -> value.text to true
        else -> value.text to false
    }
}

@Composable
private fun GaugeRow(label: String, value: GaugeValue?, nowMs: Long, neverStale: Boolean = false) {
    val (text, stale) = staleness(value, nowMs, neverStale)
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
private fun SmallGauge(label: String, value: GaugeValue?, nowMs: Long, neverStale: Boolean = false) {
    val (text, stale) = staleness(value, nowMs, neverStale)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
        )
    }
}
