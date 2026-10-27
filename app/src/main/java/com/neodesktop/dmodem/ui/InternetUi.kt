// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.ppp.DialIn
import com.neodesktop.dmodem.ppp.Internet
import com.neodesktop.dmodem.ppp.PppVpnService
import com.neodesktop.dmodem.settings.IspSettings
import com.neodesktop.dmodem.settings.IspSettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Home's dial-up internet: connect (the VPN asked for first), how it goes, disconnect. */
@Composable
fun InternetCard(onSetUp: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { IspSettingsStore(context.applicationContext) }
    val isp by store.settings.collectAsStateWithLifecycle(initialValue = IspSettings())
    val state by Internet.state.collectAsStateWithLifecycle()
    val run by ModemState.run.collectAsStateWithLifecycle()

    /** the ISP dialled with the stored settings, its packets through the VPN; why not, if it can't, in a toast */
    fun dial() {
        val app = context.applicationContext
        scope.launch {
            Internet.connect(store.current()) { cfg -> PppVpnService.open(app, cfg) }
                ?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
    // the system's VPN consent, the first time
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) dial() else Toast.makeText(context, "The VPN wasn't allowed", Toast.LENGTH_LONG).show()
    }

    val dialIn by DialIn.state.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            dialIn?.let { d -> DialInRows(d) }
            val st = state
            when {
                st == null || !st.running -> {
                    Text("Dial-up internet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        st?.ended?.let { "Disconnected: $it" }
                            ?: if (isp.complete) "Dials ${isp.number}; the phone's traffic goes over the call" else "Set up an ISP's number to dial",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isp.complete) {
                            Button(onClick = {
                                val ask = VpnService.prepare(context)
                                if (ask != null) consent.launch(ask) else dial()
                            }, enabled = run == ModemState.Run.RUNNING) { Text("Connect") }
                        }
                        TextButton(onClick = onSetUp) { Text(if (isp.complete) "ISP settings" else "Set up") }
                        if (st?.ended != null) TextButton(onClick = { Internet.dismiss() }) { Text("OK") }
                    }
                }
                !st.connected -> {
                    Text("Dial-up internet", style = MaterialTheme.typography.titleSmall)
                    Text(st.phase, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { Internet.disconnect() }) { Text("Cancel") }
                }
                else -> {
                    val cfg = st.config!!
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                    val secs = ((now - st.since) / 1000).coerceAtLeast(0)
                    Text("Connected to the internet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${cfg.address.hostAddress} · ${st.connect.removePrefix("CONNECT").trim().ifEmpty { "connected" }} · " +
                            "%d:%02d".format(secs / 60, secs % 60),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "↓ ${size(st.bytesIn)}  ↑ ${size(st.bytesOut)}" +
                            (if (cfg.dns.isNotEmpty()) "  · DNS ${cfg.dns.joinToString { it.hostAddress ?: "" }}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { Internet.disconnect() }) { Text("Disconnect") }
                }
            }
        }
    }
}

/** a caller on the internet through the phone (DialIn), or how its call ended */
@Composable
private fun DialInRows(d: DialIn.State) {
    if (d.running) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
        Text("Dial-in: " + if (d.online) "a caller is online" else d.phase, style = MaterialTheme.typography.titleSmall)
        val (bIn, bOut) = remember(now) { DialIn.bytes() }
        if (d.online) {
            val secs = ((now - d.since) / 1000).coerceAtLeast(0)
            Text(
                "${DialIn.peer.hostAddress} · ${d.connect.removePrefix("CONNECT").trim().ifEmpty { "connected" }} · " +
                    "%d:%02d · ↓ ${size(bIn)}  ↑ ${size(bOut)}".format(secs / 60, secs % 60),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = { Thread { DialIn.disconnect() }.start() }) { Text("Hang up") }
    } else {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Dial-in call over: ${d.ended}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { DialIn.dismiss() }) { Text("OK") }
        }
    }
}

/** a byte count in B, KB or MB */
private fun size(n: Long) = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / 1048576.0)
}

/** The settings' ISP: its number, the login, AT commands before the dial. */
@Composable
fun IspSettingsSection() {
    val context = LocalContext.current
    val store = remember { IspSettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val current by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val loaded = current ?: return
    var s by remember(loaded) { mutableStateOf(loaded) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Dial-up internet (ISP)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = s.number, onValueChange = { s = s.copy(number = it) }, label = { Text("The ISP's number") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(value = s.user, onValueChange = { s = s.copy(user = it) }, label = { Text("User name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = s.password, onValueChange = { s = s.copy(password = it) }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.initCommands, onValueChange = { s = s.copy(initCommands = it) }, label = { Text("AT commands before the dial (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.script, onValueChange = { s = s.copy(script = it) },
            label = { Text("Login script before PPP (optional)") },
            placeholder = { Text("ogin: \\U assword: \\P") },
            supportingText = {
                Text(
                    "As chat has them: what to wait for, then what to send, in turn; \\U the user, \\P the password, " +
                        "\\d a second's wait, \\c no return after; ABORT text, TIMEOUT seconds; \"\" waits for nothing",
                )
            },
            minLines = 2,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Dial-in", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Callers get the internet through this phone", Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = s.dialIn, onCheckedChange = { s = s.copy(dialIn = it) })
        }
        Text(
            "The phone as an ISP for whoever calls the modem: a call that rings is answered, and once it connects " +
                "the caller's dialer gets PPP (no login), the address ${DialIn.peer.hostAddress} and the phone's " +
                "network. Calls in need the line to take them (on SIP: \"Take calls\", registered).",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = {
            scope.launch { store.save(s) }
            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
        }, enabled = s != loaded) { Text("Save") }
    }
}
