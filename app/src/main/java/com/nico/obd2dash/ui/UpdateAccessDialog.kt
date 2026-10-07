package com.nico.obd2dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.R

@Composable
internal fun UpdateAccessDialog(hasAccess: Boolean, busy: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    // Jamais rememberSaveable : le texte secret ne doit pas entrer dans le Bundle Android.
    var token by remember { mutableStateOf("") }
    var browserFailed by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    fun close() { token = ""; onDismiss() }
    AlertDialog(
        onDismissRequest = { close() },
        title = { Text(stringResource(R.string.updates_access)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.updates_access_help))
                TextButton(onClick = {
                    try { uriHandler.openUri("https://github.com/settings/personal-access-tokens/new") }
                    catch (_: Exception) { browserFailed = true }
                }) { Text(stringResource(R.string.updates_create_token)) }
                if (browserFailed) Text(stringResource(R.string.updates_browser_failed))
                OutlinedTextField(
                    value = token, onValueChange = { token = it.take(512) }, singleLine = true,
                    label = { Text(stringResource(R.string.updates_token)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                if (hasAccess) {
                    TextButton(onClick = { onSave(""); close() }, enabled = !busy) {
                        Text(stringResource(R.string.updates_remove_access))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(token); close() }, enabled = !busy && token.isNotBlank()) {
                Text(stringResource(R.string.updates_save_access))
            }
        },
        dismissButton = { TextButton(onClick = { close() }) { Text(stringResource(R.string.updates_cancel)) } }
    )
}
