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
                    "Add a BBS, a host or an ISP: its number (or SIP user), the AT commands it needs and its character set.",
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
                            Text(e.name.ifBlank { e.number }, style = MaterialTheme.typography.titleMedium)
                            Text(e.number, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            val details = listOfNotNull(
                                e.initCommands.takeIf { it.isNotBlank() },
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
            title = { Text("Delete ${e.name.ifBlank { e.number }}?") },
            confirmButton = { TextButton(onClick = { Terminal.directory.remove(e); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EntryDialog(entry: DialEntry, onSave: (DialEntry) -> Unit, onDismiss: () -> Unit) {
    var e by remember(entry.id) { mutableStateOf(entry) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.name.isEmpty() && entry.number.isEmpty()) "New system" else "Edit system") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(e.name, { e = e.copy(name = it) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(e.number, { e = e.copy(number = it) }, label = { Text("Number or SIP user") }, singleLine = true)
                OutlinedTextField(
                    e.initCommands, { e = e.copy(initCommands = it) },
                    label = { Text("AT commands before dialling (; between)") }, singleLine = true,
                )
                Choice(
                    "Character set", e.charset?.label ?: "Terminal's",
                    listOf("Terminal's" to null) + TermCharset.entries.map { it.label to it },
                ) { e = e.copy(charset = it) }
                Choice(
                    "Backspace", e.backspace?.label ?: "Terminal's",
                    listOf("Terminal's" to null) + BackspaceMode.entries.map { it.label to it },
                ) { e = e.copy(backspace = it) }
                OutlinedTextField(e.notes, { e = e.copy(notes = it) }, label = { Text("Notes") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(e) }, enabled = e.number.isNotBlank()) { Text("Save") }
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
