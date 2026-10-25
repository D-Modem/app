// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neodesktop.dmodem.transfer.Protocol
import com.neodesktop.dmodem.transfer.TransferState

/**
 * A transfer to start: the protocol, and what to type at the remote to
 * start its end (its rz, sz with the files' names, kermit…; empty: it's
 * started already). [sending]: files from here.
 */
@Composable
fun TransferDialog(
    sending: Boolean,
    onStart: (protocol: Protocol, command: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var protocol by remember { mutableStateOf(Protocol.ZMODEM) }
    var command by remember { mutableStateOf(protocol.remoteCommand(remoteSends = !sending)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (sending) "Send files" else "Receive files") },
        text = {
            Column {
                for (p in Protocol.entries) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            protocol = p
                            command = p.remoteCommand(remoteSends = !sending)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = p == protocol, onClick = null)
                        Text(
                            p.label + if (!p.batch) "  (one file)" else "",
                            Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(if (sending) "Typed at the remote first" else "Typed at the remote first (add the files)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (sending) "Then pick the files to send. Empty: the remote is already receiving."
                    else "Empty: start the remote's end yourself. A remote sz or kermit -s is noticed by itself.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (protocol.hexFormat != null) {
                    Text(
                        if (sending) "The file goes as ${protocol.label}, a line each (a file of them already goes as it is), " +
                            "for an EPROM programmer, a monitor or a boot loader."
                        else "The records that come (their checksums checked) are saved as the binary image they hold, " +
                            "gaps as 0xFF: from the end record, or once they pause for 10 s.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(protocol, command.trim()) }) { Text(if (sending) "Pick files…" else "Receive") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** a transfer as it goes (cancel it), or how it ended (dismissed) */
@Composable
fun TransferBanner(t: TransferState, savedTo: String, onCancel: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().padding(12.dp), tonalElevation = 6.dp, shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            val p = t.progress
            val what = t.protocol.label + if (t.sending) " sending" else " receiving"
            Text(
                when {
                    t.running -> if (p.file.isNotEmpty()) "$what ${p.file}" else "$what: waiting for the other side…"
                    t.result == "" -> if (t.sending) "${t.protocol.label}: sent ${p.files} file(s)" else "${t.protocol.label}: received ${t.received.size} file(s)"
                    else -> "${t.protocol.label} stopped: ${t.result}"
                },
                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (t.running) {
                Spacer(Modifier.height(6.dp))
                if (p.size > 0) {
                    LinearProgressIndicator(progress = { (p.bytes.toFloat() / p.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        t.running -> sizeText(p.bytes) + (if (p.size > 0) " of " + sizeText(p.size) else "") +
                            (if (p.files > 0) " · ${p.files} done" else "") + (if (p.errors > 0) " · ${p.errors} retried" else "")
                        !t.sending && t.received.isNotEmpty() -> "In $savedTo"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                if (t.running) TextButton(onClick = onCancel) { Text("Cancel") } else TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}

private fun sizeText(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / 1048576.0)
}
