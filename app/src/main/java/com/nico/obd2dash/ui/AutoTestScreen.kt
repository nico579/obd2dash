package com.nico.obd2dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.AutoTestCheck
import com.nico.obd2dash.AutoTestStatus
import com.nico.obd2dash.ConnectionState
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.R

/**
 * Smoke test automatique (voir ObdViewModel.runAutoTest) : exécute contre le véhicule
 * réellement connecté une petite séquence de lectures et deux écritures courtes, sans
 * intervention manuelle entre les étapes. Complète la checklist "en voiture", ne la
 * remplace pas : rien ici ne peut couper le contact ni éteindre l'écran à la place d'un
 * humain.
 */
@Composable
fun AutoTestScreen(
    state: ObdUiState,
    onRun: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.smoketest_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.smoketest_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.connectionState != ConnectionState.CONNECTED) {
            Text(
                stringResource(R.string.smoketest_not_connected),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            if (state.isAutoTesting) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.smoketest_stop_button))
                }
            } else {
                Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.autoTestChecks.isEmpty()) R.string.smoketest_run_button else R.string.smoketest_rerun_button))
                }
            }
        }

        // Résultat du dernier smoke test : déplacé hors du bloc CONNECTED (voir audit,
        // "Résultats d'outils") : une reconnexion qui suit immédiatement l'étape
        // d'enregistrement ne doit pas faire disparaître les statuts déjà obtenus, seul le
        // contrôle pour en lancer un NOUVEAU exige une connexion active.
        if (state.autoTestChecks.isNotEmpty()) {
            HorizontalDivider()
            for (check in state.autoTestChecks) {
                AutoTestRow(check)
            }
        }
    }
}

@Composable
private fun AutoTestRow(check: AutoTestCheck) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        StatusDot(check.status)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(check.name, style = MaterialTheme.typography.bodyMedium)
            check.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: AutoTestStatus) {
    if (status == AutoTestStatus.EN_COURS) {
        CircularProgressIndicator(modifier = Modifier.padding(top = 2.dp).size(14.dp), strokeWidth = 2.dp)
        return
    }
    val color = when (status) {
        AutoTestStatus.EN_ATTENTE -> MaterialTheme.colorScheme.outlineVariant
        AutoTestStatus.OK -> MaterialTheme.colorScheme.primary
        AutoTestStatus.ATTENTION -> Color(0xFFB8860B) // ambre : ni faux ni un vrai echec, a lire
        AutoTestStatus.ECHEC -> MaterialTheme.colorScheme.error
        AutoTestStatus.EN_COURS -> MaterialTheme.colorScheme.outline // inatteignable, deja rendu ci-dessus
    }
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
}
