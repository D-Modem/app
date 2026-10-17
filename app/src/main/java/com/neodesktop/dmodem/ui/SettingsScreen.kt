// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.ModemSettings
import com.neodesktop.dmodem.settings.SettingsStore
import kotlinx.coroutines.launch

/** The SIP account and the modem's options; they apply when the modem next starts. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onOpenTerminal: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val run by ModemState.run.collectAsStateWithLifecycle()

    var loaded by remember { mutableStateOf<ModemSettings?>(null) }
    LaunchedEffect(Unit) { loaded = store.current() }
    val initial = loaded ?: return

    var s by remember(initial) { mutableStateOf(initial) }
    var login by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SIP account (saved with the button below)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = login,
            onValueChange = { v ->
                login = v
                ModemSettings.parseLogin(v.trim())?.let { (u, p, d) ->
                    s = s.copy(user = u, password = p, domain = d)
                }
            },
            label = { Text("Paste user:password@host (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Field("User", s.user) { s = s.copy(user = it) }
        OutlinedTextField(
            value = s.password,
            onValueChange = { s = s.copy(password = it) },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Field("Server (host[:port])", s.domain) { s = s.copy(domain = it) }
        NumberField("Local SIP port", s.sipPort) { s = s.copy(sipPort = it) }
        Toggle("Take incoming calls (register)", s.listen) { s = s.copy(listen = it) }
        Toggle("Fax over T.38", s.t38) { s = s.copy(t38 = it) }

        Text("Modem", style = MaterialTheme.typography.titleMedium)
        Field("Init commands (sent at start)", s.initCommands) { s = s.copy(initCommands = it) }
        Field("Country (empty: USA)", s.country) { s = s.copy(country = it) }
        NumberField("Modem log level (0-5)", s.logLevel) { s = s.copy(logLevel = it.coerceIn(0, 5)) }
        NumberField("SIP log level (0-4)", s.sipLogLevel) { s = s.copy(sipLogLevel = it.coerceIn(0, 4)) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                scope.launch {
                    store.save(s)
                    saved = true
                }
            }) { Text("Save") }
            if (saved) {
                Text(
                    if (run == ModemState.Run.STOPPED) "Saved" else "Saved: restart the modem to apply",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
        BridgeSection()
        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
        FaxSettingsSection()
        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
        AppearanceSection()
        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
        TerminalSettingsSection()
        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SshKeysSection()
        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LinuxSection(onOpened = onOpenTerminal)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
