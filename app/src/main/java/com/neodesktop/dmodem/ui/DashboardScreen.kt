// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.NativeModem
import com.neodesktop.dmodem.modem.ModemService
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.ModemSettings
import com.neodesktop.dmodem.settings.SettingsStore
import com.neodesktop.dmodem.terminal.DialEntry
import com.neodesktop.dmodem.terminal.EntryKind
import com.neodesktop.dmodem.terminal.Terminal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home: what the modem is doing and the one thing to do about it, the
 * line it is on, the systems last dialled, and the way to the rest.
 */
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onOpenTerminal: () -> Unit,
    onOpenDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val run by ModemState.run.collectAsStateWithLifecycle()
    val status by ModemState.status.collectAsStateWithLifecycle()
    val error by ModemState.error.collectAsStateWithLifecycle()
    val store = remember { SettingsStore(context.applicationContext) }
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = ModemSettings())
    val entries by Terminal.directory.items.collectAsStateWithLifecycle()
    val network = rememberNetwork(context)
    val looks = remember { com.neodesktop.dmodem.settings.AppearanceStore(context.applicationContext) }
    val appearance by looks.appearance.collectAsStateWithLifecycle(initialValue = null)
    val lightsOnTop = appearance?.lightsOnTop == true

    // the call's time online
    var since by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.link) {
        since = if (status.link == NativeModem.Link.ONLINE) System.currentTimeMillis() else 0L
        while (since != 0L) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (lightsOnTop) ModemLights(bare = true)

        HeroCard(
            run = run,
            status = status,
            elapsed = if (since != 0L) (now - since) / 1000 else -1,
            error = error,
            configured = settings.complete,
            onStart = { ModemService.start(context) },
            onStop = { ModemService.stop(context) },
            onHangUp = { scope.launch { Terminal.session.hangUp(status.link == NativeModem.Link.ONLINE) } },
            onTerminal = onOpenTerminal,
            onSetUp = onOpenSettings,
        )

        if (appearance != null && !lightsOnTop) ModemLights()

        LineCard(settings, run, network, onOpenSettings)

        SectionTitle("Quick dial", action = "Directory", onAction = onOpenDirectory)
        val recent = entries.sortedWith(compareByDescending<DialEntry> { it.lastDialed }.thenBy { it.name }).take(4)
        if (recent.isEmpty()) {
            Card(Modifier.fillMaxWidth().clickable(onClick = onOpenDirectory)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Add a system to dial", style = MaterialTheme.typography.titleSmall)
                        Text("A BBS, a host or an ISP: one tap dials it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            for (e in recent) {
                QuickDial(e, enabled = e.kind != EntryKind.MODEM || (status.link == NativeModem.Link.IDLE && !status.offhook)) {
                    scope.launch {
                        val err = Terminal.dial(context, e)
                        if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show() else onOpenTerminal()
                    }
                }
            }
        }

        SectionTitle("Internet")
        InternetCard(onSetUp = onOpenSettings)

        SectionTitle("Fax")
        FaxSection()

        SectionTitle("Open")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Shortcut(">_", "Terminal", "VT100 / ANSI-BBS", Modifier.weight(1f), onOpenTerminal)
            Shortcut("$", "Shell", "This phone's", Modifier.weight(1f), onClick = {
                scope.launch {
                    val err = Terminal.openLocal(context)
                    if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show() else onOpenTerminal()
                }
            })
            Shortcut(null, "Directory", "${entries.size} system${if (entries.size == 1) "" else "s"}", Modifier.weight(1f), onOpenDirectory) {
                Icon(Icons.AutoMirrored.Filled.List, null)
            }
        }

        ConsoleCard(enabled = run == ModemState.Run.RUNNING)
    }
}

// ----------------------------------------------------------------------

private val Online = Color(0xff2e7d32)
private val Busy = Color(0xffef6c00)
private val Off = Color(0xff757575)

@Composable
private fun HeroCard(
    run: ModemState.Run,
    status: NativeModem.Status,
    elapsed: Long,
    error: String?,
    configured: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHangUp: () -> Unit,
    onTerminal: () -> Unit,
    onSetUp: () -> Unit,
) {
    val online = run == ModemState.Run.RUNNING && status.link == NativeModem.Link.ONLINE
    val (dot, title, sub) = when {
        run == ModemState.Run.STARTING -> Triple(Busy, "Starting…", "Bringing up SIP on this network")
        run == ModemState.Run.STOPPING -> Triple(Busy, "Stopping…", "")
        run == ModemState.Run.STOPPED -> Triple(Off, "Modem off", if (configured) "Ready to start" else "Set up a SIP account to start")
        online -> Triple(Online, "Online", "Connected")
        status.link == NativeModem.Link.CONNECTING -> Triple(Busy, "Connecting…", "Training the line")
        status.link == NativeModem.Link.HANGING_UP -> Triple(Busy, "Hanging up…", "")
        status.offhook -> Triple(Busy, "Off hook", "Dialling")
        else -> Triple(Online, "Ready", "On hook, waiting for a dial")
    }
    Card(
        Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(dot, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (elapsed >= 0) {
                    Text(
                        "%d:%02d".format(elapsed / 60, elapsed % 60),
                        style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (online) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Rate("↓ down", status.rxRate)
                    Rate("↑ up", status.txRate)
                }
            } else if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodyLarge)
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    run == ModemState.Run.STOPPED && !configured ->
                        Button(onClick = onSetUp) { Text("Set up SIP") }
                    run == ModemState.Run.STOPPED ->
                        Button(onClick = onStart) { Text("Start modem") }
                    online || status.offhook ->
                        Button(
                            onClick = onHangUp,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Hang up") }
                    else ->
                        OutlinedButton(onClick = onStop, enabled = run == ModemState.Run.RUNNING) { Text("Stop modem") }
                }
                if (run == ModemState.Run.RUNNING) {
                    FilledTonalButton(onClick = onTerminal) { Text("Terminal") }
                }
            }
        }
    }
}

@Composable
private fun Rate(label: String, bps: Int) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (bps >= 1000) "%.1f".format(bps / 1000.0) else bps.toString(),
                fontSize = 34.sp, fontWeight = FontWeight.Bold,
            )
            Text(if (bps >= 1000) " kbit/s" else " bit/s", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun LineCard(s: ModemSettings, run: ModemState.Run, network: String?, onSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onSettings)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Line", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (s.peer) {
                PeerLineRows(s, run, network)
                return@Column
            }
            InfoRow("SIP", if (s.complete) "${s.user}@${s.domain}" else "not set up")
            InfoRow("Calls in", if (s.listen) "answered (registered)" else "off")
            InfoRow("Network", network ?: "none")
            InfoRow("Fax", if (s.t38) "audio or T.38" else "audio")
            if (run == ModemState.Run.RUNNING && s.listen) {
                Text("Waiting for calls: RING shows in the terminal", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** the line another phone: which, how, what it's doing */
@Composable
private fun PeerLineRows(s: ModemSettings, run: ModemState.Run, network: String?) {
    val st by com.neodesktop.dmodem.peer.Peers.state.collectAsStateWithLifecycle()
    val error by com.neodesktop.dmodem.peer.Peers.error.collectAsStateWithLifecycle()
    val wifi = s.line == ModemSettings.LINE_WIFI
    val cell = s.line == ModemSettings.LINE_CELL
    InfoRow("Via", if (cell) "this phone's mobile calls" else if (wifi) "another phone on Wi-Fi" else "another phone on Bluetooth")
    if (!cell) InfoRow("Calls", s.peerName.ifBlank { s.peerAddress }.ifBlank { if (wifi) "the address dialled" else "no phone chosen" })
    InfoRow("Calls in", if (s.listen) "answered" else "off")
    if (wifi) InfoRow("Network", network ?: "none")
    if (run == ModemState.Run.RUNNING) InfoRow("Now", peerText(st, s))
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun QuickDial(e: DialEntry, enabled: Boolean, onDial: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(e.name.ifBlank { e.address }.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name.ifBlank { e.address }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val last = if (e.lastDialed > 0) " · " + DateUtils.getRelativeTimeSpanString(e.lastDialed) else ""
                val via = if (e.kind == EntryKind.MODEM) "" else e.kind.label + " · "
                Text(via + e.address + last, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            FilledTonalButton(onClick = onDial, enabled = enabled) {
                Icon(Icons.Filled.Call, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (e.kind == EntryKind.MODEM) "Dial" else "Open")
            }
        }
    }
}

@Composable
private fun Shortcut(
    glyph: String?,
    title: String,
    sub: String,
    modifier: Modifier,
    onClick: () -> Unit,
    icon: @Composable (() -> Unit)? = null,
) {
    Card(modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            if (icon != null) icon() else Text(glyph ?: "", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

/** the modem's raw output and a command line to it: folded away until wanted */
@Composable
private fun ConsoleCard(enabled: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    val console by ModemState.console.flow.collectAsStateWithLifecycle()
    val lines = console.replace("\r\n", "\n").replace("\r", "").lines().filter { it.isNotBlank() }
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Modem console", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { open = !open }) {
                    Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (open) "Fold" else "Unfold")
                }
            }
            val shown = if (open) lines.takeLast(40) else lines.takeLast(3)
            Text(
                if (shown.isEmpty()) "(nothing yet)" else shown.joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 12.dp),
            )
            if (open) {
                var line by remember { mutableStateOf("") }
                val send = {
                    if (line.isNotEmpty()) {
                        ModemState.dte.write(line + "\r")
                        line = ""
                    }
                }
                Row(Modifier.padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = line, onValueChange = { line = it }, label = { Text("AT command") },
                        singleLine = true, enabled = enabled, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = send, enabled = enabled) { Text("Send") }
                }
                TextButton(onClick = { ModemState.console.clear() }) { Text("Clear") }
            }
        }
    }
}

/** the default network as words ("Wi-Fi", "Mobile data"), following it */
@Composable
private fun rememberNetwork(context: Context): String? {
    var name by remember { mutableStateOf(networkName(context, null)) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                name = networkName(context, caps)
            }

            override fun onLost(network: Network) {
                name = null
            }
        }
        try {
            cm?.registerDefaultNetworkCallback(cb)
        } catch (_: RuntimeException) {
        }
        onDispose {
            try {
                cm?.unregisterNetworkCallback(cb)
            } catch (_: RuntimeException) {
            }
        }
    }
    return name
}

private fun networkName(context: Context, given: NetworkCapabilities?): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val caps = given ?: cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return null
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Connected"
    }
}
