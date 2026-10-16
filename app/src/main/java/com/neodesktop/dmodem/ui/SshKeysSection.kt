// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.ssh.KeyKind
import com.neodesktop.dmodem.ssh.SshKey
import com.neodesktop.dmodem.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The SSH keys (made here or imported) and the hosts' keys kept. */
@Composable
fun SshKeysSection() {
    val context = LocalContext.current
    val keys by Terminal.keys.keys.collectAsStateWithLifecycle()
    var generating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SshKey?>(null) }
    var hosts by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SSH keys", style = MaterialTheme.typography.titleMedium)
        if (keys.isEmpty()) {
            Text("None yet. Make one here and put its public key in the server's ~/.ssh/authorized_keys.", style = MaterialTheme.typography.bodySmall)
        }
        for (k in keys) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${k.name} · ${k.algorithm}", style = MaterialTheme.typography.titleSmall)
                    Text(k.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row {
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("public key", k.publicKey))
                            Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy public key") }
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, k.publicKey)
                            context.startActivity(Intent.createChooser(send, "Public key"))
                        }) { Text("Share") }
                        TextButton(onClick = { deleting = k }) { Text("Delete") }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { generating = true }) { Text("Make a key") }
            OutlinedButton(onClick = { importing = true }) { Text("Import") }
            OutlinedButton(onClick = { hosts = true }) { Text("Known hosts") }
        }
    }

    if (generating) GenerateDialog { generating = false }
    if (importing) ImportDialog { importing = false }
    if (hosts) KnownHostsDialog { hosts = false }
    deleting?.let { k ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${k.name}?") },
            text = { Text("Servers that let it in won't any more from this phone; entries using it need another key.") },
            confirmButton = { TextButton(onClick = { Terminal.keys.delete(k.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GenerateDialog(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("${android.os.Build.MODEL.replace(' ', '-')}-dmodem") }
    var kind by remember { mutableStateOf(KeyKind.ED25519) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Make a key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Choice("Type", kind.label, KeyKind.entries.map { it.label to it }) { kind = it }
                Text("Ed25519 is the one to use unless the server is old (RSA).", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val r = runCatching { withContext(Dispatchers.Default) { Terminal.keys.generate(kind, name) } }
                    busy = false
                    r.exceptionOrNull()?.let { Toast.makeText(context, "Couldn't make it: ${it.message}", Toast.LENGTH_LONG).show() }
                    onDone()
                }
            }) { Text(if (busy) "Making…" else "Make") }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDialog(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(64 * 1024).toByteArray().decodeToString() }
            }.getOrNull() ?: ""
            if (name.isEmpty()) name = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Import a private key") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Choose a file…") }
                OutlinedTextField(
                    text, { text = it }, label = { Text("…or paste it (BEGIN OPENSSH PRIVATE KEY)") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 4, maxLines = 8,
                )
                OutlinedTextField(
                    passphrase, { passphrase = it }, label = { Text("Passphrase (if it has one)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text("It is kept encrypted on this device; the passphrase isn't kept.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                scope.launch {
                    val r = runCatching {
                        withContext(Dispatchers.Default) { Terminal.keys.import(text.trim(), passphrase.ifEmpty { null }, name.trim()) }
                    }
                    r.exceptionOrNull()?.let { error = "Not a key it can read (or a wrong passphrase): ${it.message}" }
                    if (r.isSuccess) onDone()
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun KnownHostsDialog(onDone: () -> Unit) {
    var list by remember { mutableStateOf(Terminal.knownHosts.all()) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Known hosts") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (list.isEmpty()) Text("No host keys kept yet.")
                for ((host, type, fp) in list) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(host, style = MaterialTheme.typography.titleSmall)
                            Text("$type\n$fp", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            Terminal.knownHosts.forget(host, type)
                            list = Terminal.knownHosts.all()
                        }) { Text("Forget") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
}
