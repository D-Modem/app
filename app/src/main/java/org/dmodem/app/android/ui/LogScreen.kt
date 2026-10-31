// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dmodem.app.android.modem.ModemState

/** The modem's and SIP's log, the last lines (their levels in the settings): cleared, copied, or saved to a file. */
@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val entries by ModemState.log.entries.collectAsStateWithLifecycle()
    val store = androidx.compose.runtime.remember { org.dmodem.app.android.settings.SettingsStore(context.applicationContext) }
    val settings by store.settings.collectAsStateWithLifecycle(org.dmodem.app.android.settings.ModemSettings())
    // (each after its time stamp, the days between)
    val lines = androidx.compose.runtime.remember(entries, settings.logTimestamp) {
        org.dmodem.app.android.modem.LogFormat.render(entries, settings.logTimestamp)
    }
    // (where it's saved: the user's pick, the system's file picker; the lines as they were when Save was pressed)
    var toSave by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<String>>(emptyList()) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(logText(toSave).toByteArray()) } ?: error("can't open it")
        }
        android.widget.Toast.makeText(
            context,
            ok.fold({ "Log saved" }, { "The log wasn't saved: ${it.message}" }),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1)
    }
    Column(modifier.fillMaxSize().padding(8.dp)) {
        val clipboard = LocalClipboardManager.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ModemState.log.clear() }) { Text("Clear") }
            // (all of it, for a bug report; a line's text can be selected too)
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }, enabled = lines.isNotEmpty()) {
                Text("Copy")
            }
            OutlinedButton(onClick = {
                toSave = lines
                save.launch(logFileName())
            }, enabled = lines.isNotEmpty()) { Text("Save") }
            // (a call on a USB modem's line, recorded: the settings' "Record each call")
            val recording = androidx.compose.runtime.remember(lines.size) {
                org.dmodem.app.android.modem.LineRecordings.all(context).firstOrNull()
            }
            if (recording != null) {
                OutlinedButton(onClick = { shareCapture(context, recording) }) { Text("Recording") }
            }
        }
        SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(lines) { l ->
                    Text(l, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** the log as a file's text: a line each, a newline at the end */
internal fun logText(lines: List<String>): String = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")

/** the name a saved log is offered: dmodem-<date>-<time>.log */
internal fun logFileName(now: java.util.Date = java.util.Date()): String =
    "dmodem-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(now) + ".log"

