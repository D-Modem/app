// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.neodesktop.dmodem.bridge.BluetoothBridge
import com.neodesktop.dmodem.bridge.Bridges
import com.neodesktop.dmodem.bridge.TcpBridge
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.BridgeSettings
import com.neodesktop.dmodem.settings.BridgeSettingsStore
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/** The modem's serial port for other programs and devices: on TCP and on Bluetooth. */
@Composable
fun BridgeSection() {
    val context = LocalContext.current
    val store = remember { BridgeSettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val current by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = current ?: return
    fun change(f: (BridgeSettings) -> BridgeSettings) {
        scope.launch { store.update(f) }
    }
    val run by ModemState.run.collectAsStateWithLifecycle()
    val tcpState by Bridges.tcpState.collectAsStateWithLifecycle()
    val btState by Bridges.btState.collectAsStateWithLifecycle()
    val usbState by Bridges.usbState.collectAsStateWithLifecycle()
    val gadgetState by Bridges.gadgetState.collectAsStateWithLifecycle()
    val client by Bridges.serial.client.collectAsStateWithLifecycle()
    var hasBt by remember { mutableStateOf(BluetoothBridge.hasPermission(context)) }
    var paired by remember { mutableStateOf(BluetoothBridge.paired(context)) }
    var pickDevice by remember { mutableStateOf(false) }
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasBt = BluetoothBridge.hasPermission(context)
        paired = BluetoothBridge.paired(context)
        if (hasBt) change { it.copy(bluetooth = true) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Serial port for other devices", style = MaterialTheme.typography.titleMedium)
        Text(
            "Programs and computers use the modem as if on a serial cable, one at a time, while the modem is on." +
                if (run == ModemState.Run.STOPPED) " (The modem is off now.)" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        client?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("In use: ${c.name} (${c.via})", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { Thread { Bridges.serial.kick() }.start() }) { Text("Disconnect") }
            }
        }

        // TCP
        Toggle("On TCP (ser2net, emulators, socat)", s.tcp) { v -> change { it.copy(tcp = v) } }
        if (s.tcp) {
            Text(tcpText(tcpState, s), style = MaterialTheme.typography.bodySmall)
            Applied("Port", s.tcpPort.toString(), number = true) { v ->
                v.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> change { it.copy(tcpPort = p) } }
            }
            Toggle("Telnet, with RFC 2217 port control (else raw)", s.tcpTelnet) { v -> change { it.copy(tcpTelnet = v) } }
            Toggle("Other devices on the network can connect", s.tcpAnyAddress) { v -> change { it.copy(tcpAnyAddress = v) } }
            if (s.tcpAnyAddress) {
                Text(
                    "Anyone on the same network can then dial out on your SIP account: set a password." +
                        addresses().takeIf { it.isNotEmpty() }?.let { "\nThis phone: " + it.joinToString(", ") { a -> "$a:${s.tcpPort}" } }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.tcpPassword.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Applied("Password (empty: none)", s.tcpPassword, password = true) { v -> change { it.copy(tcpPassword = v) } }
        }

        // Bluetooth
        Toggle("On Bluetooth (serial port profile)", s.bluetooth) { v ->
            if (v && !hasBt) askBt.launch(BluetoothBridge.permissions()) else change { it.copy(bluetooth = v) }
        }
        if (s.bluetooth) {
            Text(btText(btState), style = MaterialTheme.typography.bodySmall)
            Choice("Other devices connect to this phone", s.btConnectTo.isEmpty()) { change { it.copy(btConnectTo = "") } }
            Choice(
                "Connect to a paired device" + (s.btConnectTo.takeIf { it.isNotEmpty() }?.let { a ->
                    ": " + (paired.firstOrNull { it.second == a }?.first ?: a)
                } ?: ""),
                s.btConnectTo.isNotEmpty(),
            ) {
                paired = BluetoothBridge.paired(context)
                pickDevice = true
            }
            if (s.btConnectTo.isEmpty()) {
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Make the phone visible for pairing") }
            }
            if (pickDevice) {
                Column(Modifier.padding(start = 16.dp)) {
                    if (paired.isEmpty()) Text("No paired devices: pair one in Android's Bluetooth settings", style = MaterialTheme.typography.bodySmall)
                    for ((name, address) in paired) {
                        Text(
                            "$name  ($address)",
                            Modifier.fillMaxWidth().clickable {
                                pickDevice = false
                                change { it.copy(btConnectTo = address) }
                            }.padding(vertical = 8.dp),
                        )
                    }
                    TextButton(onClick = { pickDevice = false }) { Text("Cancel") }
                }
            }
        }

        // USB
        Toggle("On a USB serial adapter (OTG)", s.usb) { v -> change { it.copy(usb = v) } }
        if (s.usb) {
            Text(usbText(usbState), style = MaterialTheme.typography.bodySmall)
            Applied("Speed (bit/s)", s.usbBaud.toString(), number = true) { v ->
                v.toIntOrNull()?.takeIf { it in 50..4_000_000 }?.let { b -> change { it.copy(usbBaud = b) } }
            }
            Applied("Format (data bits, parity, stop bits)", s.usbFormat) { v ->
                v.uppercase().takeIf { Regex("[5-8][NEOMS][12]").matches(it) }?.let { f -> change { it.copy(usbFormat = f) } }
            }
            Toggle("Its DTR is the modem's carrier (the computer's DCD)", s.usbCarrierOnDtr) { v -> change { it.copy(usbCarrierOnDtr = v) } }
            Toggle("The computer dropping DTR hangs up", s.usbDtrHangsUp) { v -> change { it.copy(usbDtrHangsUp = v) } }
        }
        if (s.usbGadget || com.neodesktop.dmodem.bridge.UsbGadget.rootAvailable()) {
            Toggle("This phone's USB port as a serial port to a computer (root)", s.usbGadget) { v -> change { it.copy(usbGadget = v) } }
            if (s.usbGadget) Text(gadgetText(gadgetState), style = MaterialTheme.typography.bodySmall)
        }

        Toggle("Hang up when the client disconnects (drops DTR)", s.hangUpOnClose) { v -> change { it.copy(hangUpOnClose = v) } }
    }
}

private fun tcpText(st: TcpBridge.State, s: BridgeSettings): String = when (st) {
    TcpBridge.State.Stopped -> "Starts with the modem"
    is TcpBridge.State.Listening -> "Listening on " + (if (s.tcpAnyAddress) "port" else "localhost port") + " ${st.port}" +
        if (s.tcpTelnet) " (Telnet)" else " (raw)"
    is TcpBridge.State.Failed -> "Can't listen: ${st.why}"
}

private fun usbText(st: com.neodesktop.dmodem.bridge.UsbBridge.State): String = when (st) {
    com.neodesktop.dmodem.bridge.UsbBridge.State.Stopped -> "Starts with the modem"
    com.neodesktop.dmodem.bridge.UsbBridge.State.Waiting -> "Waiting for an adapter to be plugged in"
    is com.neodesktop.dmodem.bridge.UsbBridge.State.NeedsPermission -> "Allow D-Modem to use ${st.device} (Android asks)"
    is com.neodesktop.dmodem.bridge.UsbBridge.State.Connected -> "On ${st.device}"
    is com.neodesktop.dmodem.bridge.UsbBridge.State.Failed -> st.why
}

private fun gadgetText(st: com.neodesktop.dmodem.bridge.UsbGadget.State): String = when (st) {
    com.neodesktop.dmodem.bridge.UsbGadget.State.Stopped -> "Starts with the modem"
    com.neodesktop.dmodem.bridge.UsbGadget.State.Ready -> "Ready: the computer sees a modem on its USB (COM port, /dev/ttyACM0)"
    com.neodesktop.dmodem.bridge.UsbGadget.State.Connected -> "The computer is using the modem"
    is com.neodesktop.dmodem.bridge.UsbGadget.State.Failed -> st.why
}

private fun btText(st: BluetoothBridge.State): String = when (st) {
    BluetoothBridge.State.Stopped -> "Starts with the modem"
    is BluetoothBridge.State.Listening -> "Waiting for a device to connect to \"${st.name}\""
    is BluetoothBridge.State.Connecting -> "Connecting to ${st.device}…"
    is BluetoothBridge.State.Connected -> "Connected to ${st.device}"
    is BluetoothBridge.State.Failed -> st.why
}

/** the phone's IPv4 addresses (not the loopback) */
private fun addresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
}.getOrDefault(emptyList())

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

/** a field whose value is used when Set is pressed (a port half typed isn't listened on) */
@Composable
private fun Applied(label: String, value: String, number: Boolean = false, password: Boolean = false, onSet: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = if (number) it.filter(Char::isDigit).take(5) else it },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { onSet(text) }, enabled = text != value) { Text("Set") }
    }
}
