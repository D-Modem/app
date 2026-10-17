// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.neodesktop.dmodem.bridge.BluetoothBridge
import com.neodesktop.dmodem.peer.PeerFinder
import com.neodesktop.dmodem.peer.PeerLine
import com.neodesktop.dmodem.settings.ModemSettings

/** the line's choice: a SIP account, or another phone with the app */
@Composable
fun LineChoice(s: ModemSettings, onChange: (ModemSettings) -> Unit) {
    val context = LocalContext.current
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (BluetoothBridge.hasPermission(context)) onChange(s.copy(line = ModemSettings.LINE_BLUETOOTH))
    }
    Text("Line", style = MaterialTheme.typography.titleMedium)
    LineRadio("A SIP account (calls over the internet)", s.line == ModemSettings.LINE_SIP) {
        onChange(s.copy(line = ModemSettings.LINE_SIP))
    }
    LineRadio("Another phone with D-Modem, on Wi-Fi", s.line == ModemSettings.LINE_WIFI) {
        onChange(s.copy(line = ModemSettings.LINE_WIFI, peerAddress = if (s.line == ModemSettings.LINE_WIFI) s.peerAddress else "", peerName = ""))
    }
    LineRadio("Another phone with D-Modem, on Bluetooth", s.line == ModemSettings.LINE_BLUETOOTH) {
        if (!BluetoothBridge.hasPermission(context)) {
            askBt.launch(BluetoothBridge.permissions())
        } else {
            onChange(s.copy(line = ModemSettings.LINE_BLUETOOTH, peerAddress = if (s.line == ModemSettings.LINE_BLUETOOTH) s.peerAddress else "", peerName = ""))
        }
    }
}

/**
 * A line to another phone: which one to call, whether this one takes
 * calls. The two modems connect as over a perfect phone line (V.90/V.91
 * speeds), each phone's terminal, serial bridges, fax and PPP on its own.
 */
@Composable
fun PeerLineFields(s: ModemSettings, onChange: (ModemSettings) -> Unit) {
    val context = LocalContext.current
    val wifi = s.line == ModemSettings.LINE_WIFI
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "The two phones' modems call each other as if on a phone line, with no SIP account or phone company: " +
                "each side dials (ATD) or answers as usual. The other phone needs D-Modem with its line set the same way, taking calls." +
                if (wifi) "" else " Pair the two phones in Android's Bluetooth settings first.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Take calls from the other phone", Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = s.listen, onCheckedChange = { onChange(s.copy(listen = it)) })
        }
        if (wifi) WifiPeer(s, onChange) else BluetoothPeer(s, onChange, context)
    }
}

@Composable
private fun WifiPeer(s: ModemSettings, onChange: (ModemSettings) -> Unit) {
    val context = LocalContext.current
    var finding by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<Pair<String, String>>() }
    OutlinedTextField(
        value = s.peerAddress,
        onValueChange = { onChange(s.copy(peerAddress = it, peerName = "")) },
        label = { Text("Phone to call (its address, host[:port])") },
        supportingText = { Text("ATD with any number calls it; ATD192*168*1*20 calls that address instead") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { found.clear(); finding = !finding }) {
        Text(if (finding) "Stop looking" else "Find phones on this network")
    }
    if (finding) {
        DisposableEffect(Unit) {
            val f = PeerFinder(context) { name, address ->
                // (from NSD's thread)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (found.none { it.second == address }) found.add(name to address)
                }
            }
            f.start()
            onDispose { f.stop() }
        }
        Column(Modifier.padding(start = 16.dp)) {
            if (found.isEmpty()) {
                Text("Looking… (phones taking calls on Wi-Fi show here)", style = MaterialTheme.typography.bodySmall)
            }
            for ((name, address) in found) {
                Text(
                    "$name  ($address)",
                    Modifier.fillMaxWidth().clickable {
                        finding = false
                        onChange(s.copy(peerAddress = address, peerName = name))
                    }.padding(vertical = 8.dp),
                )
            }
        }
    }
    var port by remember(s.peerPort) { mutableStateOf(s.peerPort.toString()) }
    OutlinedTextField(
        value = port,
        onValueChange = { v ->
            port = v.filter { it.isDigit() }.take(5)
            port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { onChange(s.copy(peerPort = it)) }
        },
        label = { Text("Port calls come in on") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    val here = addresses()
    if (here.isNotEmpty()) {
        Text(
            "This phone: " + here.joinToString(", ") { a ->
                "$a (the other phone dials ATD" + a.replace('.', '*') + (if (s.peerPort != 5070) "#${s.peerPort}" else "") + ")"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BluetoothPeer(s: ModemSettings, onChange: (ModemSettings) -> Unit, context: android.content.Context) {
    var picking by remember { mutableStateOf(false) }
    val paired = remember(picking) { BluetoothBridge.paired(context) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Phone to call: " + (s.peerName.ifBlank { s.peerAddress }.ifBlank { "none chosen" }),
            Modifier.weight(1f),
        )
        TextButton(onClick = { picking = !picking }) { Text("Choose") }
    }
    if (picking) {
        Column(Modifier.padding(start = 16.dp)) {
            if (paired.isEmpty()) Text("No paired devices: pair the other phone in Android's Bluetooth settings", style = MaterialTheme.typography.bodySmall)
            for ((name, address) in paired) {
                Text(
                    "$name  ($address)",
                    Modifier.fillMaxWidth().clickable {
                        picking = false
                        onChange(s.copy(peerAddress = address, peerName = name))
                    }.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LineRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

/** what the line to the other phone is doing, for the user */
fun peerText(st: PeerLine.State, s: ModemSettings): String = when (st) {
    PeerLine.State.Off -> "Starts with the modem"
    is PeerLine.State.Ready -> if (st.listening) "Waiting for the other phone's calls" else "Ready to call"
    is PeerLine.State.Ringing -> "Ringing: ${st.from} calls"
    is PeerLine.State.Calling -> "Calling ${s.peerName.ifBlank { st.to }}…"
    is PeerLine.State.Connected -> "On a call with ${s.peerName.takeIf { st.caller && it.isNotBlank() } ?: st.with}"
}
