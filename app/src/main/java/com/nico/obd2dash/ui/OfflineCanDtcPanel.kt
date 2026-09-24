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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.CanCaptureGlobalFailure
import com.nico.obd2dash.CanCaptureStatus
import com.nico.obd2dash.CanDtcCaptureAnalysis
import com.nico.obd2dash.CanDtcCaptureSummary
import com.nico.obd2dash.CanDtcFailure
import com.nico.obd2dash.CanDtcFailureReason
import com.nico.obd2dash.CanDtcMode
import com.nico.obd2dash.CanIdFormat
import com.nico.obd2dash.R
import java.util.Locale

private const val MAX_CAN_CAPTURE_CHARACTERS = 65_536

/** Pasted historical data only: this panel has no transport or connection action. */
@Composable
fun OfflineCanDtcPanel(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf("") }
    var oversizedPaste by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf<CanIdFormat?>(null) }
    var mode by remember { mutableStateOf<CanDtcMode?>(null) }
    var formatMenuOpen by remember { mutableStateOf(false) }
    var modeMenuOpen by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CanDtcCaptureSummary?>(null) }
    val expansionDescription = stringResource(
        if (expanded) R.string.offline_can_expanded else R.string.offline_can_collapsed
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
                Text(stringResource(R.string.offline_can_title), modifier = Modifier.weight(1f))
                Text(if (expanded) "−" else "+")
            }
        }

        if (expanded) {
            Text(
                stringResource(R.string.offline_can_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.offline_can_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                OutlinedButton(onClick = { formatMenuOpen = true }) {
                    Text(stringResource(when (format) {
                        CanIdFormat.CAN_11 -> R.string.offline_can_format_11
                        CanIdFormat.CAN_29 -> R.string.offline_can_format_29
                        null -> R.string.offline_can_format_required
                    }))
                }
                DropdownMenu(expanded = formatMenuOpen, onDismissRequest = { formatMenuOpen = false }) {
                    for ((choice, label) in listOf(
                        CanIdFormat.CAN_11 to R.string.offline_can_format_11,
                        CanIdFormat.CAN_29 to R.string.offline_can_format_29
                    )) {
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                            format = choice
                            result = null
                            formatMenuOpen = false
                        })
                    }
                }
            }
            Box {
                OutlinedButton(onClick = { modeMenuOpen = true }) {
                    Text(stringResource(when (mode) {
                        CanDtcMode.STORED -> R.string.offline_can_mode_stored
                        CanDtcMode.PENDING -> R.string.offline_can_mode_pending
                        null -> R.string.offline_can_mode_required
                    }))
                }
                DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
                    for ((choice, label) in listOf(
                        CanDtcMode.STORED to R.string.offline_can_mode_stored,
                        CanDtcMode.PENDING to R.string.offline_can_mode_pending
                    )) {
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                            mode = choice
                            result = null
                            modeMenuOpen = false
                        })
                    }
                }
            }
            OutlinedTextField(
                value = capture,
                onValueChange = { newValue ->
                    result = null
                    // Keep the previous input intact if a paste exceeds the parser limit.
                    oversizedPaste = newValue.length > MAX_CAN_CAPTURE_CHARACTERS
                    if (!oversizedPaste) capture = newValue
                },
                label = { Text(stringResource(R.string.offline_can_capture_label)) },
                supportingText = {
                    Text(if (oversizedPaste) {
                        stringResource(R.string.offline_can_too_long, MAX_CAN_CAPTURE_CHARACTERS)
                    } else {
                        stringResource(R.string.offline_can_input_limit, capture.length, MAX_CAN_CAPTURE_CHARACTERS)
                    })
                },
                isError = oversizedPaste,
                minLines = 3,
                maxLines = 8,
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
                    val selectedFormat = format
                    val selectedMode = mode
                    if (selectedFormat != null && selectedMode != null) {
                        result = CanDtcCaptureAnalysis.analyze(capture, selectedFormat, selectedMode)
                    }
                },
                enabled = capture.isNotBlank() && !oversizedPaste && format != null && mode != null
            ) {
                Text(stringResource(R.string.offline_can_analyze))
            }
            val resultFormat = format
            if (resultFormat != null) result?.let { summary ->
                OfflineCanDtcResult(summary, resultFormat)
            }
        }
    }
}

@Composable
private fun OfflineCanDtcResult(summary: CanDtcCaptureSummary, format: CanIdFormat) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.offline_can_result_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(when (summary.status) {
                CanCaptureStatus.NO_RESPONSE -> R.string.offline_can_no_response
                CanCaptureStatus.COMPLETE -> if (summary.noCodesInCompleteCapture) {
                    R.string.offline_can_complete_no_codes
                } else R.string.offline_can_complete_codes
                CanCaptureStatus.PARTIAL -> R.string.offline_can_partial
                CanCaptureStatus.INVALID -> R.string.offline_can_invalid
            }),
            style = MaterialTheme.typography.bodyMedium
        )
        summary.globalFailure?.let { failure ->
            Text(
                stringResource(when (failure) {
                    CanCaptureGlobalFailure.INPUT_TOO_LARGE -> R.string.offline_can_global_too_large
                    CanCaptureGlobalFailure.UNRECOGNIZED_LINE -> R.string.offline_can_unrecognized_line
                }),
                style = MaterialTheme.typography.bodySmall
            )
        }
        for (ecuId in (summary.codesByEcu.keys + summary.failuresByEcu.keys).sorted()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.offline_can_ecu,
                        String.format(Locale.ROOT, "%0${format.hexDigits}X", ecuId)
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                val failure = summary.failuresByEcu[ecuId]
                if (failure != null) {
                    OfflineCanDtcFailure(failure)
                } else {
                    val codes = summary.codesByEcu.getValue(ecuId)
                    Text(
                        if (codes.isEmpty()) stringResource(R.string.offline_can_ecu_no_codes)
                        else codes.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineCanDtcFailure(failure: CanDtcFailure) {
    Text(
        stringResource(when (failure.reason) {
            CanDtcFailureReason.MALFORMED_FRAME -> R.string.offline_can_malformed_frame
            CanDtcFailureReason.INVALID_SEQUENCE -> R.string.offline_can_invalid_sequence
            CanDtcFailureReason.UNEXPECTED_SERVICE -> R.string.offline_can_unexpected_service
            CanDtcFailureReason.NEGATIVE_RESPONSE -> R.string.offline_can_negative_response
            CanDtcFailureReason.MALFORMED_PAYLOAD -> R.string.offline_can_malformed_payload
        }),
        style = MaterialTheme.typography.bodySmall
    )
    failure.nrc?.let { nrc ->
        Text(
            stringResource(R.string.offline_can_nrc, String.format(Locale.ROOT, "%02X", nrc)),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
