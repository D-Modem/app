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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.NativeModem
import com.neodesktop.dmodem.modem.ModemService
import com.neodesktop.dmodem.modem.ModemState

/** The modem: on or off, the call, and a command line to it with what it answered. */
@Composable
fun StatusScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val run by ModemState.run.collectAsStateWithLifecycle()
    val status by ModemState.status.collectAsStateWithLifecycle()
    val error by ModemState.error.collectAsStateWithLifecycle()
    val console by ModemState.console.flow.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(runText(run), style = MaterialTheme.typography.titleLarge)
                if (run == ModemState.Run.RUNNING) {
                    Text(linkText(status), style = MaterialTheme.typography.bodyLarge)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (run) {
                ModemState.Run.STOPPED ->
                    Button(onClick = { ModemService.start(context) }) { Text("Start modem") }
                else ->
                    Button(
                        onClick = { ModemService.stop(context) },
                        enabled = run != ModemState.Run.STOPPING,
                    ) { Text("Stop modem") }
            }
            OutlinedButton(onClick = { ModemState.console.clear() }) { Text("Clear") }
        }
        CommandLine(enabled = run == ModemState.Run.RUNNING)
        ConsoleText(console, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun CommandLine(enabled: Boolean) {
    var line by remember { mutableStateOf("") }
    val send = {
        if (line.isNotEmpty()) {
            ModemState.dte.write(line + "\r")
            line = ""
        }
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = line,
            onValueChange = { line = it },
            label = { Text("AT command") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = send, enabled = enabled, modifier = Modifier.padding(start = 8.dp)) {
            Text("Send")
        }
    }
}

@Composable
private fun ConsoleText(text: String, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    Card(modifier) {
        Text(
            text.replace("\r\n", "\n").replace('\r', '\n'),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxSize().padding(8.dp).verticalScroll(scroll),
        )
    }
}

private fun runText(r: ModemState.Run) = when (r) {
    ModemState.Run.STOPPED -> "Modem off"
    ModemState.Run.STARTING -> "Starting…"
    ModemState.Run.RUNNING -> "Modem on"
    ModemState.Run.STOPPING -> "Stopping…"
}

private fun linkText(s: NativeModem.Status) = when (s.link) {
    NativeModem.Link.ONLINE -> "Online: ${s.rxRate} bit/s down, ${s.txRate} bit/s up"
    NativeModem.Link.CONNECTING -> "Connecting…"
    NativeModem.Link.HANGING_UP -> "Hanging up…"
    NativeModem.Link.IDLE -> if (s.offhook) "Off hook" else "On hook"
}
