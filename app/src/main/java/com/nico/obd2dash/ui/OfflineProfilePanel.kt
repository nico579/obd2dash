package com.nico.obd2dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.R
import com.nico.obd2dash.profiles.OfflineProfileAnalysis
import com.nico.obd2dash.profiles.OfflineProfileSummary
import com.nico.obd2dash.profiles.ProfileProtocol
import com.nico.obd2dash.profiles.ProfileMatchStatus

private const val MAX_CAPTURE_CHARACTERS = 2048

private enum class CaptureInputError { INVALID_FRAME, TOO_LONG }

/** Analyses only pasted text. No connection state, transport or read action is used here. */
@Composable
fun OfflineProfilePanel(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var frameHex by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<OfflineProfileSummary?>(null) }
    var inputError by remember { mutableStateOf<CaptureInputError?>(null) }
    var protocol by remember { mutableStateOf<ProfileProtocol?>(null) }
    var protocolMenuOpen by remember { mutableStateOf(false) }
    val expansionDescription = stringResource(
        if (expanded) R.string.offline_profile_expanded else R.string.offline_profile_collapsed
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = expansionDescription }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.offline_profile_title),
                    modifier = Modifier.weight(1f)
                )
                Text(if (expanded) "−" else "+")
            }
        }

        if (expanded) {
            Text(
                stringResource(R.string.offline_profile_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.offline_profile_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                OutlinedButton(onClick = { protocolMenuOpen = true }) {
                    Text(stringResource(when (protocol) {
                        ProfileProtocol.KWP_FAST -> R.string.offline_profile_protocol_fast
                        ProfileProtocol.KWP_SLOW -> R.string.offline_profile_protocol_slow
                        else -> R.string.offline_profile_protocol_unknown
                    }))
                }
                DropdownMenu(expanded = protocolMenuOpen, onDismissRequest = { protocolMenuOpen = false }) {
                    for ((choice, label) in listOf(
                        null to R.string.offline_profile_protocol_unknown,
                        ProfileProtocol.KWP_FAST to R.string.offline_profile_protocol_fast,
                        ProfileProtocol.KWP_SLOW to R.string.offline_profile_protocol_slow
                    )) {
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                            protocol = choice
                            result = null
                            protocolMenuOpen = false
                        })
                    }
                }
            }
            OutlinedTextField(
                value = frameHex,
                onValueChange = { newValue ->
                    result = null
                    // Reject an oversized paste instead of silently analysing a truncated frame.
                    if (newValue.length > MAX_CAPTURE_CHARACTERS) {
                        inputError = CaptureInputError.TOO_LONG
                    } else {
                        frameHex = newValue
                        inputError = null
                    }
                },
                label = { Text(stringResource(R.string.offline_profile_frame_label)) },
                supportingText = {
                    Text(
                        when (inputError) {
                            CaptureInputError.INVALID_FRAME -> stringResource(R.string.offline_profile_invalid_frame)
                            CaptureInputError.TOO_LONG -> stringResource(
                                R.string.offline_profile_too_long, MAX_CAPTURE_CHARACTERS
                            )
                            null -> stringResource(
                                R.string.offline_profile_input_limit, frameHex.length, MAX_CAPTURE_CHARACTERS
                            )
                        }
                    )
                },
                isError = inputError != null,
                minLines = 3,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = fieldContainerColor(),
                    focusedContainerColor = fieldContainerColor(),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    try {
                        result = OfflineProfileAnalysis.analyze(frameHex, protocol)
                        inputError = null
                    } catch (_: IllegalArgumentException) {
                        result = null
                        inputError = CaptureInputError.INVALID_FRAME
                    }
                },
                enabled = frameHex.isNotBlank() && inputError != CaptureInputError.TOO_LONG
            ) {
                Text(stringResource(R.string.offline_profile_analyze))
            }
            result?.let { summary -> OfflineProfileResult(summary) }
        }
    }
}

@Composable
private fun OfflineProfileResult(summary: OfflineProfileSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.offline_profile_result_title),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            stringResource(R.string.offline_profile_addresses, summary.ecuAddress, summary.testerAddress),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(
                R.string.offline_profile_diagnostic_version,
                summary.diagnosticVersion,
                summary.diagnosticVersion
            ),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(R.string.offline_profile_supplier, summary.supplier),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(R.string.offline_profile_software_version, summary.software, summary.version),
            style = MaterialTheme.typography.bodySmall
        )
        if (summary.profileStatus == ProfileMatchStatus.INCOMPLETE_IDENTITY) {
            Text(stringResource(R.string.offline_profile_protocol_required), style = MaterialTheme.typography.bodyMedium)
        } else if (summary.matchedProfileId == null) {
            Text(
                stringResource(R.string.offline_profile_no_validated_profile),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                pluralStringResource(
                    R.plurals.offline_profile_matched_profile,
                    summary.plannedReadCount,
                    summary.matchedProfileId,
                    summary.plannedReadCount
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
