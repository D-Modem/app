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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.neodesktop.dmodem.bridge.BluetoothBridge
import com.neodesktop.dmodem.peer.PeerFinder
import com.neodesktop.dmodem.peer.PeerLine
import com.neodesktop.dmodem.settings.ModemSettings

/** the line's choice (a menu): a SIP account, another phone with the app, the phone's mobile calls */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineChoice(s: ModemSettings, onChange: (ModemSettings) -> Unit) {
    val context = LocalContext.current
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (BluetoothBridge.hasPermission(context)) onChange(s.copy(line = ModemSettings.LINE_BLUETOOTH))
    }
    Text("Line", style = MaterialTheme.typography.titleMedium)
    val lines = listOf(
        ModemSettings.LINE_SIP to "A SIP account (calls over the internet)",
        ModemSettings.LINE_WIFI to "Another phone with D-Modem, on Wi-Fi",
        ModemSettings.LINE_BLUETOOTH to "Another phone with D-Modem, on Bluetooth",
        ModemSettings.LINE_CELL to "This phone's mobile calls (slow; root)",
        ModemSettings.LINE_USB to "A phone line, through a USB modem",
        ModemSettings.LINE_NONE to "No line (the modem on its own)",
    )
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = lines.firstOrNull { it.first == s.line }?.second ?: lines[0].second,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("The modem's line") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((line, label) in lines) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        when (line) {
                            ModemSettings.LINE_SIP, ModemSettings.LINE_CELL, ModemSettings.LINE_NONE, ModemSettings.LINE_USB ->
                                onChange(s.copy(line = line))
                            ModemSettings.LINE_WIFI -> onChange(
                                s.copy(line = line, peerAddress = if (s.line == line) s.peerAddress else "", peerName = ""),
                            )
                            ModemSettings.LINE_BLUETOOTH -> if (!BluetoothBridge.hasPermission(context)) {
                                askBt.launch(BluetoothBridge.permissions())
                            } else {
                                onChange(s.copy(line = line, peerAddress = if (s.line == line) s.peerAddress else "", peerName = ""))
                            }
                        }
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** A phone line through a USB modem: which ones there are, what's plugged in */
@Composable
fun UsbLineFields() {
    val context = LocalContext.current
    // (looked at again when the screen is: plugging one in brings the app up)
    val plugged = remember { com.neodesktop.dmodem.modem.UsbModems.describe(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "The modem dials and answers a real phone line through a USB modem: a Smart Link SmartUSB56, a " +
                "pico-serial Line stick (with the country's line settings) or an Agere USB 2.0 soft modem (Agere's ROM patches " +
                "come with the app), all with caller ID. The phone runs the modem; " +
                "the USB one is its line. No SIP account or network needed. Android asks once whether D-Modem may use it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Plugged in: $plugged", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Incoming calls ring in the terminal (RING); ATS0=1 answers them. The modem's country (below) sets " +
                "the tones it listens for and, on the pico-serial Line, the line's electrical settings. The phone's speaker stays quiet on this line.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * A line to another phone: which one to call, whether this one takes
 * calls. The two modems connect as over a perfect phone line (V.90/V.91
 * speeds), each phone's terminal, serial bridges, fax and PPP on its own.
 */
@Composable
fun PeerLineFields(s: ModemSettings, onChange: (ModemSettings) -> Unit) {
    if (s.noLine) {
        Text(
            "The modem starts with nothing on its line: no SIP account, no network needed. It answers AT commands " +
                "in the terminal and on the serial bridges (ATI, AT&V, its registers, its settings); a dial gets NO DIALTONE, " +
                "as a modem with no phone cable plugged in.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    if (s.line == ModemSettings.LINE_CELL) {
        CellLineFields(s, onChange)
        return
    }
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
        OutlinedTextField(
            value = s.peerPin,
            onValueChange = { v -> onChange(s.copy(peerPin = v.filter { it in '!'..'~' }.take(32))) },
            label = { Text("PIN (optional)") },
            supportingText = {
                Text(
                    "Set the same PIN on both phones: calls without it are turned down. " +
                        "Without one, anyone " + (if (wifi) "on this network" else "paired with this phone") + " can call while calls are taken.",
                )
            },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
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
    is PeerLine.State.Calling -> "Calling ${s.peerName.takeIf { s.line != ModemSettings.LINE_CELL }?.ifBlank { null } ?: st.to}…"
    is PeerLine.State.Connected -> "On a call with ${s.peerName.takeIf { st.caller && it.isNotBlank() } ?: st.with}"
}

/**
 * The phone's mobile calls as the line: what the phone allows (the
 * call's sound only for a privileged app: root makes one), the calls'
 * permissions, whether calls ringing here go to the modem.
 */
@Composable
fun CellLineFields(s: ModemSettings, onChange: (ModemSettings) -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refresh by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val audioWhy = remember(refresh) { com.neodesktop.dmodem.cell.AndroidCallAudio(context).unavailable() }
    val missing = remember(refresh) {
        com.neodesktop.dmodem.cell.AndroidCellPhone.PERMISSIONS.filter {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    var root by remember { mutableStateOf<Boolean?>(null) }
    var module by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(refresh) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            root = com.neodesktop.dmodem.cell.Privileged.canInstall()
            module = root == true && com.neodesktop.dmodem.cell.Privileged.installed()
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "The modem calls (ATD with a phone number) and answers on the phone's own mobile line. Mobile calls carry " +
                "speech through codecs (AMR) that wreck modem signals: 300 bit/s gets through, 1200 and 2400 connect " +
                "but lose data, faster modems don't connect at all, and 2G calls carry nothing. Android gives a " +
                "call's sound only to a privileged app.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (audioWhy == null) "This phone lets D-Modem have the calls' sound" else "Not available: $audioWhy",
            style = MaterialTheme.typography.bodyMedium,
            color = if (audioWhy == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (missing.isNotEmpty()) {
            OutlinedButton(onClick = { ask.launch(missing.toTypedArray()) }) { Text("Allow phone calls and their sound") }
        }
        if (audioWhy != null && root == true) {
            Text(
                if (module) "D-Modem's Magisk module is installed: restart the phone to make it a privileged app."
                else "This phone is rooted: D-Modem can install itself as a privileged app (a Magisk module, from the next restart).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        result = com.neodesktop.dmodem.cell.Privileged.install(context)?.let { "Couldn't install: $it" }
                            ?: "Installed: restart the phone"
                        refresh++
                    }
                }) { Text(if (module) "Install again" else "Make D-Modem privileged") }
                if (module) {
                    TextButton(onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            result = com.neodesktop.dmodem.cell.Privileged.remove()?.let { "Couldn't remove: $it" } ?: "Removed: restart the phone"
                            refresh++
                        }
                    }) { Text("Remove") }
                }
            }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Speed", style = MaterialTheme.typography.bodyMedium)
        for ((ms, label) in listOf(
            ModemSettings.CELL_VCVM to "About 1200 bit/s (V.cvm, made for mobile calls), to another phone with D-Modem: " +
                "15 to 20 s to connect. Other modems get V.22, which a mobile call garbles: V.21 for those",
            "V21" to "300 bit/s (V.21): what any modem's call carries",
            "V22" to "1200 bit/s (V.22): a good signal, may lose data",
            "V22B" to "2400 bit/s (V.22bis): a good signal, may lose data",
        )) {
            LineRadio(label, s.cellSpeed == ms) { onChange(s.copy(cellSpeed = ms)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Calls ringing here go to the modem")
                Text(
                    "RING in the terminal; the modem answers with ATA or S0. Off: the phone rings as usual.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            androidx.compose.material3.Switch(checked = s.listen, onCheckedChange = { onChange(s.copy(listen = it)) })
        }
    }
}
