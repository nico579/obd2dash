package com.nico.obd2dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.R
import com.nico.obd2dash.updates.AppUpdateState
import com.nico.obd2dash.updates.UpdateStage

@Composable
internal fun AppUpdatesScreen(
    state: AppUpdateState,
    installationBlocked: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onSaveAccessToken: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showAccess by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val twoColumns = maxWidth >= 600.dp
        Column(
            Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.updates_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.updates_installed, state.installedVersion))
            val access: @Composable () -> Unit = {
                OutlinedButton(onClick = { showAccess = true }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(if (state.hasAccessToken) R.string.updates_access_configured else R.string.updates_access))
                }
            }
            if (!state.hasAccessToken) access()
            val summary: @Composable (Modifier) -> Unit = { cardModifier ->
                Card(cardModifier) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(when (state.stage) {
                            UpdateStage.IDLE -> R.string.updates_idle
                            UpdateStage.CHECKING -> R.string.updates_checking
                            UpdateStage.CURRENT -> R.string.updates_current
                            UpdateStage.AVAILABLE -> R.string.updates_available
                            UpdateStage.DOWNLOADING -> R.string.updates_downloading
                            UpdateStage.VERIFYING -> R.string.updates_verifying
                            UpdateStage.READY -> R.string.updates_ready
                        }), style = MaterialTheme.typography.titleLarge)
                        state.release?.let { release ->
                            Text(stringResource(R.string.updates_latest, release.versionName))
                            if (state.stage != UpdateStage.CURRENT) {
                                Text(stringResource(R.string.updates_size, release.size / (1024f * 1024f)))
                            }
                        }
                        if (state.stage == UpdateStage.DOWNLOADING && state.release != null) {
                            val progress = (state.downloadedBytes.toFloat() / state.release.size).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.updates_progress, (progress * 100).toInt()))
                        } else if (state.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        state.message?.let { Text(it, modifier = Modifier.testTag("update_message")) }
                    }
                }
            }
            val actions: @Composable (Modifier) -> Unit = { actionsModifier ->
                Column(actionsModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.stage == UpdateStage.AVAILABLE || state.stage == UpdateStage.READY) {
                        Button(
                            onClick = if (state.stage == UpdateStage.READY) onInstall else onDownload,
                            enabled = state.stage != UpdateStage.READY || !installationBlocked,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("update_primary")
                        ) {
                            Text(stringResource(if (state.stage == UpdateStage.READY) R.string.updates_install else R.string.updates_download))
                        }
                        if (installationBlocked && state.stage == UpdateStage.READY) {
                            Text(stringResource(R.string.updates_capture_active), color = MaterialTheme.colorScheme.error)
                        }
                        if (state.stage == UpdateStage.READY && state.message != null) {
                            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text(stringResource(R.string.updates_download_again))
                            }
                        }
                    }
                    if (state.busy) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.updates_cancel))
                        }
                    } else {
                        OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("update_check")) {
                            Text(stringResource(R.string.updates_check))
                        }
                    }
                }
            }
            // Les commandes précèdent l'aide ; en paysage, elles restent à côté
            // du résumé plutôt que sous un bloc de texte à faire défiler.
            if (twoColumns) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    summary(Modifier.weight(1f))
                    actions(Modifier.weight(1f))
                }
            } else {
                summary(Modifier.fillMaxWidth())
                actions(Modifier.fillMaxWidth())
            }
            if (state.stage == UpdateStage.AVAILABLE || state.stage == UpdateStage.READY) {
                Text(stringResource(R.string.updates_install_help))
            }
            Text(stringResource(R.string.updates_source), style = MaterialTheme.typography.bodySmall)
            if (state.hasAccessToken) access()
        }
    }
    if (showAccess) {
        UpdateAccessDialog(state.hasAccessToken, state.busy, onSaveAccessToken, onDismiss = { showAccess = false })
    }
}
