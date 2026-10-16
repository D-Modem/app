// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.ssh.ForwardSpec
import com.neodesktop.dmodem.ssh.KeyGen
import com.neodesktop.dmodem.ssh.SshLink
import com.neodesktop.dmodem.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** an entry's port forwards: each with its line and what it does, removable; more added */
@Composable
fun ForwardsEditor(forwards: List<String>, onChange: (List<String>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Port forwards", style = MaterialTheme.typography.labelMedium)
        if (forwards.isEmpty()) {
            Text("None", style = MaterialTheme.typography.bodySmall)
        }
        for (line in forwards) {
            val spec = ForwardSpec.parse(line)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    Text(spec?.describe() ?: "not a forward", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { onChange(forwards - line) }) { Icon(Icons.Filled.Close, "Remove $line") }
            }
        }
        OutlinedButton(onClick = { adding = true }) { Text("Add a forward") }
    }
    if (adding) {
        ForwardDialog(
            onAdd = { s -> onChange((forwards + s.toString()).distinct()); adding = false },
            onDismiss = { adding = false },
        )
    }
}

/** a forward made: local, remote or SOCKS; its ports and where it goes */
@Composable
fun ForwardDialog(onAdd: (ForwardSpec) -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(ForwardSpec.Type.LOCAL) }
    var bind by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("localhost") }
    var hostPort by remember { mutableStateOf("") }
    val p = port.toIntOrNull()
    val hp = hostPort.toIntOrNull()
    val spec = when {
        p == null || p !in 1..65535 -> null
        type == ForwardSpec.Type.DYNAMIC -> ForwardSpec(type, bind.trim().ifEmpty { null }, p)
        hp == null || hp !in 1..65535 || host.isBlank() -> null
        else -> ForwardSpec(type, bind.trim().ifEmpty { null }, p, host.trim(), hp)
    }
    // (Android lets apps listen on 1024 and up only)
    val lowPort = spec != null && type != ForwardSpec.Type.REMOTE && spec.port < 1024
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Port forward") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ForwardSpec.Type.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = type == t, onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(i, ForwardSpec.Type.entries.size),
                        ) { Text(if (t == ForwardSpec.Type.DYNAMIC) "SOCKS" else t.label, maxLines = 1) }
                    }
                }
                Text(
                    when (type) {
                        ForwardSpec.Type.LOCAL -> "A port on this phone, its connections made from the server (ssh -L)"
                        ForwardSpec.Type.REMOTE -> "A port on the server, its connections made from this phone (ssh -R)"
                        ForwardSpec.Type.DYNAMIC -> "A SOCKS 4/5 proxy on this phone: apps set to use it reach the network from the server (ssh -D)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                val number = KeyboardOptions(keyboardType = KeyboardType.Number)
                OutlinedTextField(
                    port, { v -> port = v.filter { it.isDigit() }.take(5) },
                    label = { Text(if (type == ForwardSpec.Type.REMOTE) "Port on the server" else "Port on this phone") },
                    singleLine = true, keyboardOptions = number, modifier = Modifier.fillMaxWidth(),
                )
                if (type != ForwardSpec.Type.DYNAMIC) {
                    OutlinedTextField(
                        host, { host = it.trim() },
                        label = { Text(if (type == ForwardSpec.Type.LOCAL) "To host (as the server sees it)" else "To host (as this phone sees it)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        hostPort, { v -> hostPort = v.filter { it.isDigit() }.take(5) },
                        label = { Text("To port") }, singleLine = true, keyboardOptions = number, modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    bind, { bind = it.trim() },
                    label = { Text(if (type == ForwardSpec.Type.REMOTE) "Listen on (empty: the server's default)" else "Listen on (empty: this phone only; * for all)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (lowPort) Text("Ports below 1024 can't be listened on by apps", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                spec?.let {
                    Text(it.toString(), fontFamily = FontFamily.Monospace)
                    Text(it.describe(), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { spec?.let(onAdd) }, enabled = spec != null && !lowPort) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * An open SSH connection's extras: its forwards as they run (more added, or
 * stopped), the agent, what they did, and a key put on the server
 * (ssh-copy-id).
 */
@Composable
fun SshSessionDialog(link: SshLink, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val forwards by link.forwards.collectAsStateWithLifecycle()
    val events by link.events.collectAsStateWithLifecycle()
    val keys by Terminal.keys.keys.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${link.user}@${link.host}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Port forwards", style = MaterialTheme.typography.titleSmall)
                if (forwards.isEmpty()) Text("None", style = MaterialTheme.typography.bodySmall)
                for (f in forwards) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.spec.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            val shown = if (f.boundPort > 0 && f.boundPort != f.spec.port) " on port ${f.boundPort}" else ""
                            Text(
                                when {
                                    f.error != null -> "Failed: ${f.error}"
                                    f.listening -> "Listening$shown · ${f.open} open, ${f.total} in all"
                                    else -> "Stopped"
                                },
                                color = if (f.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { scope.launch(Dispatchers.IO) { link.removeForward(f.spec) } }) {
                            Icon(Icons.Filled.Close, "Stop ${f.spec}")
                        }
                    }
                }
                OutlinedButton(onClick = { adding = true }) { Text("Add a forward") }
                HorizontalDivider()
                Text("Agent", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (link.agentForwarded) "Forwarded: the server can sign with this app's keys" else "Not forwarded (set it in the entry)",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Text("Keys", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { copying = true }, enabled = keys.isNotEmpty()) { Text("Put a key on this server (ssh-copy-id)") }
                if (events.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Recent", style = MaterialTheme.typography.titleSmall)
                    for (e in events.takeLast(8).reversed()) Text(e, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    if (adding) {
        ForwardDialog(
            onAdd = { s ->
                adding = false
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { link.addForward(s) }
                    if (!ok) Toast.makeText(context, "Couldn't start $s", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { adding = false },
        )
    }
    if (copying) {
        AlertDialog(
            onDismissRequest = { copying = false },
            title = { Text("Put a key on ${link.host}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Added to ~/.ssh/authorized_keys (once), to log in with it from now on.", style = MaterialTheme.typography.bodySmall)
                    for (k in keys) {
                        TextButton(onClick = {
                            copying = false
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { link.exec(KeyGen.copyIdCommand(k.publicKey)) } }
                                val res = r.getOrNull()
                                val msg = when {
                                    r.isFailure -> "Failed: ${r.exceptionOrNull()?.message}"
                                    res == null -> "Not connected"
                                    res.first == 0 -> "\"${k.name}\" is on ${link.host}"
                                    else -> "Failed (${res.first}): ${res.second.trim().take(200)}"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }) { Text("${k.name} (${k.algorithm})") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { copying = false }) { Text("Cancel") } },
        )
    }
}

/** a labelled switch, a line under it */
@Composable
fun SwitchRow(label: String, detail: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
