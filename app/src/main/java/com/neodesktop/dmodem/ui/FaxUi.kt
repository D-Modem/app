// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.fax.Fax
import com.neodesktop.dmodem.fax.FaxRecord
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.FaxSettingsStore
import com.neodesktop.dmodem.transfer.DownloadsIncoming
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The home screen's fax: send one, answer a ringing call as one, the call as it goes, the last few. */
@Composable
fun FaxSection() {
    val context = LocalContext.current
    val state by Fax.state.collectAsStateWithLifecycle()
    val run by ModemState.run.collectAsStateWithLifecycle()
    val history by remember { Fax.history(context) }.items.collectAsStateWithLifecycle()
    var sendDialog by remember { mutableStateOf(false) }
    val on = run == ModemState.Run.RUNNING

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state?.let { st -> FaxCallCard(st) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { sendDialog = true }, enabled = on && state?.running != true) { Text("Send a fax…") }
            OutlinedButton(onClick = {
                Fax.answer(context)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }, enabled = on && state?.running != true) { Text("Answer as fax") }
        }
        if (!on) Text("The modem is off: start it to fax", style = MaterialTheme.typography.bodySmall)
        for (r in history.sortedByDescending { it.time }.take(3)) FaxRow(r)
    }
    if (sendDialog) {
        SendFaxDialog(onDismiss = { sendDialog = false }) { number, docs ->
            sendDialog = false
            Fax.send(context, number, docs)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
}

@Composable
private fun FaxCallCard(st: Fax.State) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val p = st.progress
            val what = if (st.sending) "Sending a fax" + (st.number.takeIf { it.isNotEmpty() }?.let { " to $it" } ?: "") else "Receiving a fax"
            Text(what, style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    st.running -> p.phase
                    st.result == "" -> if (st.sending) "Sent ${p.pages} page(s)" else "Received ${p.pages} page(s)"
                    else -> "Stopped: ${st.result}"
                } + (if (p.remote.isNotEmpty()) " · ${p.remote}" else "") + (if (p.bps > 0 && st.running) " · ${p.bps} bit/s" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (st.running) {
                if (p.total > 0) LinearProgressIndicator(progress = { p.pages.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st.running) {
                    TextButton(onClick = { Fax.cancel() }) { Text("Cancel") }
                } else {
                    if (st.file.isNotEmpty()) TextButton(onClick = { open(context, st.file) }) { Text("Open") }
                    TextButton(onClick = { Fax.dismiss() }) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun FaxRow(r: FaxRecord) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = r.file.isNotEmpty()) { open(context, r.file) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (r.sending) "↗" else "↙", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.weight(1f)) {
            Text(
                (r.number.ifEmpty { r.remote }.ifEmpty { if (r.sending) "Sent" else "Received" }) +
                    " · ${r.pages} page${if (r.pages == 1) "" else "s"}",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.time)) +
                    if (r.result.isNotEmpty()) " · ${r.result}" else "",
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** a number and the documents to fax there */
@Composable
private fun SendFaxDialog(onDismiss: () -> Unit, onSend: (String, List<Uri>) -> Unit) {
    var number by remember { mutableStateOf("") }
    var docs by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { docs = docs + it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send a fax") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = number, onValueChange = { number = it }, label = { Text("Fax number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf", "image/*", "text/plain")) }) {
                    Text(if (docs.isEmpty()) "Pick a PDF, image or text…" else "Add more…")
                }
                for (d in docs) Text(d.lastPathSegment?.substringAfterLast('/') ?: d.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onSend(number, docs) }, enabled = number.isNotBlank() && docs.isNotEmpty()) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** a received fax (by its name in Downloads/D-Modem) opened in a PDF viewer */
private fun open(context: Context, name: String) {
    val uri: Uri? = if (Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
    } else {
        null
    }
    if (uri == null) {
        Toast.makeText(context, "$name is in ${DownloadsIncoming.where(context)}", Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { Toast.makeText(context, "No app to open PDFs", Toast.LENGTH_LONG).show() }
}

/** The settings' fax section: this machine's number, answering calls as faxes, the resolution, the rate. */
@Composable
fun FaxSettingsSection() {
    val context = LocalContext.current
    val store = remember { FaxSettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val current by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = current ?: return
    var ident by remember(s.ident) { mutableStateOf(s.ident) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Fax", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = ident,
            onValueChange = { v ->
                ident = v.filter { it.isDigit() || it == '+' || it == ' ' }.take(20)
                scope.launch { store.update { it.copy(ident = ident) } }
            },
            label = { Text("This fax's number (sent to the other machine)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Answer calls that ring as faxes", Modifier.weight(1f))
            Switch(checked = s.answer, onCheckedChange = { v -> scope.launch { store.update { it.copy(answer = v) } } })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Fine resolution (196 lines per inch)", Modifier.weight(1f))
            Switch(checked = s.fine, onCheckedChange = { v -> scope.launch { store.update { it.copy(fine = v) } } })
        }
        Text("Fastest rate", style = MaterialTheme.typography.bodyMedium)
        for ((bps, label) in listOf(14400 to "14400 (V.17)", 9600 to "9600 (V.29)", 4800 to "4800 (V.27ter: poor lines)")) {
            Row(
                Modifier.fillMaxWidth().clickable { scope.launch { store.update { it.copy(maxBps = bps) } } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.maxBps == bps, onClick = null)
                Text(label, Modifier.padding(start = 8.dp))
            }
        }
    }
}
