// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.terminal.BackspaceMode
import com.neodesktop.dmodem.terminal.DialEntry
import com.neodesktop.dmodem.terminal.EntryKind
import com.neodesktop.dmodem.terminal.SshAuth
import com.neodesktop.dmodem.terminal.Snippet
import com.neodesktop.dmodem.terminal.TermCharset
import com.neodesktop.dmodem.terminal.Terminal
import kotlinx.coroutines.launch

/** The dialing directory: the systems to call, each with its own init commands and character set. */
@Composable
fun DirectoryScreen(modifier: Modifier = Modifier, onDialed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by Terminal.directory.items.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<DialEntry?>(null) }
    var deleting by remember { mutableStateOf<DialEntry?>(null) }
    var dialing by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("No systems yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add a BBS, a host or an ISP: by the modem (its number or SIP user and the AT commands it needs), SSH or Telnet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 88.dp),
        ) {
            items(entries, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name.ifBlank { e.address }, style = MaterialTheme.typography.titleMedium)
                            Text(e.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            val details = listOfNotNull(
                                e.kind.takeIf { it != EntryKind.MODEM }?.label,
                                e.initCommands.takeIf { it.isNotBlank() && e.kind == EntryKind.MODEM },
                                e.charset?.label,
                                if (e.lastDialed > 0) {
                                    "called " + DateUtils.getRelativeTimeSpanString(e.lastDialed) + " (${e.dialCount}×)"
                                } else {
                                    null
                                },
                            ).joinToString(" · ")
                            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall)
                            if (e.notes.isNotBlank()) Text(e.notes, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { editing = e }) { Icon(Icons.Filled.Edit, "Edit") }
                        IconButton(onClick = { deleting = e }) { Icon(Icons.Filled.Delete, "Delete") }
                        IconButton(
                            enabled = !dialing,
                            onClick = {
                                dialing = true
                                scope.launch {
                                    val err = Terminal.dial(context, e)
                                    dialing = false
                                    if (err != null) {
                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    } else {
                                        onDialed()
                                    }
                                }
                            },
                        ) { Icon(Icons.Filled.Call, "Dial") }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = DialEntry() },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Add") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { e ->
        EntryDialog(
            entry = e,
            onSave = { Terminal.directory.put(it); editing = null },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${e.name.ifBlank { e.address }}?") },
            confirmButton = {
                TextButton(onClick = {
                    Terminal.directory.remove(e)
                    Terminal.passwords.remove(e.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EntryDialog(entry: DialEntry, onSave: (DialEntry) -> Unit, onDismiss: () -> Unit) {
    var e by remember(entry.id) { mutableStateOf(entry) }
    val keys by Terminal.keys.keys.collectAsStateWithLifecycle()
    var hasPassword by remember(entry.id) { mutableStateOf(Terminal.passwords.get(entry.id) != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.name.isEmpty() && entry.address.isEmpty()) "New system" else "Edit system") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(e.name, { e = e.copy(name = it) }, label = { Text("Name") }, singleLine = true)
                Choice("Connect by", e.kind.label, EntryKind.entries.map { it.label to it }) { e = e.copy(kind = it) }
                when (e.kind) {
                    EntryKind.MODEM -> {
                        OutlinedTextField(e.number, { e = e.copy(number = it) }, label = { Text("Number or SIP user") }, singleLine = true)
                        OutlinedTextField(
                            e.initCommands, { e = e.copy(initCommands = it) },
                            label = { Text("AT commands before dialling (; between)") }, singleLine = true,
                        )
                    }
                    EntryKind.SSH, EntryKind.TELNET -> {
                        OutlinedTextField(e.host, { e = e.copy(host = it.trim()) }, label = { Text("Host") }, singleLine = true)
                        OutlinedTextField(
                            if (e.port > 0) e.port.toString() else "",
                            { v -> e = e.copy(port = v.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0) },
                            label = { Text("Port (${if (e.kind == EntryKind.SSH) 22 else 23})") }, singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        )
                        if (e.kind == EntryKind.SSH) {
                            OutlinedTextField(e.user, { e = e.copy(user = it.trim()) }, label = { Text("User") }, singleLine = true)
                            Choice("Log in with", e.auth.label, SshAuth.entries.map { it.label to it }) { e = e.copy(auth = it) }
                            if (e.auth == SshAuth.KEY) {
                                if (keys.isEmpty()) {
                                    Text("No keys yet: make or import one in Settings, SSH keys.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Choice(
                                        "Key", keys.firstOrNull { it.id == e.keyId }?.name ?: "Choose a key",
                                        keys.map { "${it.name} (${it.algorithm})" to it.id },
                                    ) { e = e.copy(keyId = it) }
                                }
                            } else if (hasPassword) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("A password is kept for it", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { Terminal.passwords.remove(entry.id); hasPassword = false }) { Text("Forget") }
                                }
                            }
                        }
                    }
                }
                Choice(
                    "Character set", e.charset?.label ?: "Default",
                    listOf("Default" to null) + TermCharset.entries.map { it.label to it },
                ) { e = e.copy(charset = it) }
                Choice(
                    "Backspace", e.backspace?.label ?: "Default",
                    listOf("Default" to null) + BackspaceMode.entries.map { it.label to it },
                ) { e = e.copy(backspace = it) }
                OutlinedTextField(e.notes, { e = e.copy(notes = it) }, label = { Text("Notes") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(e) }, enabled = e.complete) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** a labelled dropdown of choices */
@Composable
fun <T> Choice(label: String, current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(current) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((name, v) in options) {
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(v) })
                }
            }
        }
    }
}

/** The snippets: a tap sends one; they can be added, edited and deleted. */
@Composable
fun SnippetsDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    val snippets by Terminal.snippets.items.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Snippet?>(null) }

    val ed = editing
    if (ed != null) {
        var sn by remember(ed.id) { mutableStateOf(ed) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Snippet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(sn.name, { sn = sn.copy(name = it) }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(sn.text, { sn = sn.copy(text = it) }, label = { Text("Text") })
                    Text(
                        "\\r Enter, \\n line feed, \\t tab, \\e ESC, \\xHH any octet, ^C Ctrl-C",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { Terminal.snippets.put(sn); editing = null }, enabled = sn.text.isNotEmpty()) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    if (snippets.any { it.id == sn.id }) {
                        TextButton(onClick = { Terminal.snippets.remove(sn); editing = null }) { Text("Delete") }
                    }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Snippets") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(snippets, key = { it.id }) { sn ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSend(Snippet.unescape(sn.text)) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(sn.name.ifBlank { sn.text }, style = MaterialTheme.typography.bodyLarge)
                            Text(sn.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { editing = sn }) { Icon(Icons.Filled.Edit, "Edit") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { editing = Snippet() }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
