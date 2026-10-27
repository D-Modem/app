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
import com.neodesktop.dmodem.ssh.KeyGen
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
    var details by remember { mutableStateOf<SshKey?>(null) }
    var hosts by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SSH keys", style = MaterialTheme.typography.titleMedium)
        if (keys.isEmpty()) {
            Text("None yet. Make one here and put its public key in the server's ~/.ssh/authorized_keys.", style = MaterialTheme.typography.bodySmall)
        }
        for (k in keys) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${k.name} · ${keyTypeLabel(k)}", style = MaterialTheme.typography.titleSmall)
                    Terminal.keys.comment(k).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
                        TextButton(onClick = { details = k }) { Text("Details") }
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
    details?.let { k ->
        // (the key as it is now: renamed, its comment changed)
        val now = keys.firstOrNull { it.id == k.id }
        if (now == null) details = null else KeyDetailsDialog(now, onDelete = { details = null; deleting = now }) { details = null }
    }
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

/** a new key made: its name, type and comment, with the ssh-keygen line for the same; any failure in a toast */
@Composable
private fun GenerateDialog(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("${android.os.Build.MODEL.replace(' ', '-')}-dmodem") }
    var kind by remember { mutableStateOf(KeyKind.ED25519) }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Make a key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Choice("Type", kind.label, KeyKind.entries.map { it.label to it }) { kind = it }
                OutlinedTextField(
                    comment, { comment = it }, label = { Text("Comment (empty: the name)") }, singleLine = true,
                )
                Text("Ed25519 is the one to use unless the server is old (RSA).", style = MaterialTheme.typography.bodySmall)
                // what ssh-keygen would be told for the same key
                val t = when (kind.algorithm) { "Ed25519" -> "ed25519"; "EC" -> "ecdsa"; else -> "rsa" }
                val b = if (kind.algorithm == "Ed25519") "" else " -b ${kind.bits}"
                Text(
                    "ssh-keygen -t $t$b -C \"${comment.ifEmpty { name.replace(' ', '-') }}\"",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val r = runCatching { withContext(Dispatchers.Default) { Terminal.keys.generate(kind, name, comment.trim().ifEmpty { null }) } }
                    busy = false
                    r.exceptionOrNull()?.let { Toast.makeText(context, "Couldn't make it: ${it.message}", Toast.LENGTH_LONG).show() }
                    onDone()
                }
            }) { Text(if (busy) "Making…" else "Make") }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") } },
    )
}

/** a private key imported from a file or pasted (OpenSSH, PuTTY or PEM), with its passphrase; closed once it's read */
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
                    text, { text = it }, label = { Text("…or paste it (OpenSSH, PuTTY .ppk or PEM)") },
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

/** "Ed25519", "ECDSA 384", "RSA 3072": the key's type and size */
private fun keyTypeLabel(k: SshKey): String {
    val f = k.publicKey.split(" ")
    return when {
        f[0] == "ssh-ed25519" -> "Ed25519"
        f[0].startsWith("ecdsa-sha2-nistp") -> "ECDSA " + f[0].removePrefix("ecdsa-sha2-nistp")
        f[0] == "ssh-rsa" -> runCatching { "RSA " + KeyGen.bits(Terminal.keys.keyPair(k.id).public) }.getOrDefault("RSA")
        else -> k.algorithm
    }
}

/** A key as ssh-keygen -lv tells it (its fingerprints, its randomart); renamed, its comment changed, exported, deleted. */
@Composable
internal fun KeyDetailsDialog(k: SshKey, onDelete: () -> Unit, onDone: () -> Unit) {
    val pub = remember(k.id) { runCatching { Terminal.keys.keyPair(k.id).public }.getOrNull() }
    var name by remember(k.id) { mutableStateOf(k.name) }
    var comment by remember(k.id) { mutableStateOf(Terminal.keys.comment(k)) }
    var exporting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(k.name) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(keyTypeLabel(k), style = MaterialTheme.typography.labelLarge)
                Text(k.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (pub != null) {
                    Text(KeyGen.md5(pub), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(
                        KeyGen.randomart(pub), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.05f),
                    )
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(comment, { comment = it }, label = { Text("Comment (in the public key)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exporting = true }) { Text("Export private key") }
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && name != k.name) Terminal.keys.rename(k.id, name.trim())
                if (comment != Terminal.keys.comment(k)) Terminal.keys.setComment(k.id, comment)
                onDone()
            }) { Text("Done") }
        },
    )
    if (exporting) ExportDialog(k) { exporting = false }
}

private enum class ExportFormat(val label: String) {
    OPENSSH("OpenSSH (ssh, ssh-keygen)"),
    PPK3("PuTTY .ppk (PuTTY 0.75 and on)"),
    PPK2("PuTTY .ppk version 2 (older PuTTY, WinSCP)"),
}

/**
 * The private key out, in OpenSSH's form (for ssh on a computer) or
 * PuTTY's, with a passphrase (as ssh-keygen or PuTTYgen would ask for):
 * saved to a file the user picks.
 */
@Composable
internal fun ExportDialog(k: SshKey, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(ExportFormat.OPENSSH) }
    val fileName = when {
        k.publicKey.startsWith("ssh-ed25519") -> "id_ed25519"
        k.publicKey.startsWith("ecdsa") -> "id_ecdsa"
        else -> "id_rsa"
    } + if (format == ExportFormat.OPENSSH) "" else ".ppk"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            scope.launch {
                val r = runCatching {
                    withContext(Dispatchers.Default) {
                        val text = when (format) {
                            ExportFormat.OPENSSH -> Terminal.keys.exportPrivate(k.id, pass)
                            ExportFormat.PPK3 -> Terminal.keys.exportPpk(k.id, pass, 3)
                            ExportFormat.PPK2 -> Terminal.keys.exportPpk(k.id, pass, 2)
                        }
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                            ?: throw java.io.IOException("can't write there")
                    }
                }
                Toast.makeText(
                    context,
                    r.exceptionOrNull()?.let { "Not saved: ${it.message}" }
                        ?: if (format == ExportFormat.OPENSSH) "Saved: chmod 600 it before ssh uses it" else "Saved",
                    Toast.LENGTH_LONG,
                ).show()
                onDone()
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Export ${k.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Format", format.label, ExportFormat.entries.map { it.label to it }) { format = it }
                Text(
                    "The private key: whoever has the file (and its passphrase) can log in as you.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    again, { again = it }, label = { Text("Passphrase again") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = again.isNotEmpty() && again != pass,
                )
                if (pass.isEmpty()) {
                    Text("With no passphrase the file is the key itself, unprotected.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { save.launch(fileName) }, enabled = pass == again) { Text("Save as…") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
