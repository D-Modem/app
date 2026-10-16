// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.settings.BellMode
import com.neodesktop.dmodem.settings.CursorShape
import com.neodesktop.dmodem.settings.TerminalSettings
import com.neodesktop.dmodem.settings.TerminalSettingsStore
import com.neodesktop.dmodem.settings.VolumeKeys
import com.neodesktop.dmodem.terminal.BackspaceMode
import com.neodesktop.dmodem.terminal.EnterMode
import com.neodesktop.dmodem.terminal.TermCharset
import com.neodesktop.dmodem.terminal.TermTheme
import kotlinx.coroutines.launch

/** The terminal's settings: each change saved (and on the screen) at once. */
@Composable
fun TerminalSettingsSection() {
    val context = LocalContext.current
    val store = remember { TerminalSettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val current by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = current ?: return
    fun set(n: TerminalSettings) {
        scope.launch { store.save(n) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Terminal", style = MaterialTheme.typography.titleMedium)
        Choice("Colours", TermTheme.byId(s.themeId).name, TermTheme.ALL.map { it.name to it.id }) { set(s.copy(themeId = it)) }
        Choice(
            "Width", if (s.fixedColumns > 0) "${s.fixedColumns} columns" else "By font size",
            listOf("80 columns (BBS)" to 80, "132 columns" to 132, "40 columns" to 40, "By font size" to 0),
        ) { set(s.copy(fixedColumns = it)) }
        if (s.fixedColumns == 0) {
            Text("Font size: ${"%.1f".format(s.fontSize)} sp (or pinch the screen)", style = MaterialTheme.typography.bodyMedium)
            Slider(value = s.fontSize, onValueChange = { set(s.copy(fontSize = it)) }, valueRange = 6f..28f)
        }
        Choice("Character set", s.charset.label, TermCharset.entries.map { it.label to it }) { set(s.copy(charset = it)) }
        Choice("Backspace sends", s.backspace.label, BackspaceMode.entries.map { it.label to it }) { set(s.copy(backspace = it)) }
        Choice("Enter sends", s.enter.label, EnterMode.entries.map { it.label to it }) { set(s.copy(enter = it)) }
        Choice("Cursor", s.cursor.label, CursorShape.entries.map { it.label to it }) { set(s.copy(cursor = it)) }
        Choice("Bell", s.bell.label, BellMode.entries.map { it.label to it }) { set(s.copy(bell = it)) }
        Choice("Volume keys", s.volumeKeys.label, VolumeKeys.entries.map { it.label to it }) { set(s.copy(volumeKeys = it)) }
        Flag("Blinking cursor", s.cursorBlink) { set(s.copy(cursorBlink = it)) }
        Flag("Bold as bright colours (DOS, BBS)", s.boldBright) { set(s.copy(boldBright = it)) }
        Flag("iCE colours (blink as bright background)", s.iceColors) { set(s.copy(iceColors = it)) }
        Flag("Draw line and block characters (gapless ANSI art)", s.drawBoxes) { set(s.copy(drawBoxes = it)) }
        Flag("Local echo", s.localEcho) { set(s.copy(localEcho = it)) }
        Flag("Extra keys bar", s.extraKeys) { set(s.copy(extraKeys = it)) }
        Flag("Keep the screen on", s.keepScreenOn) { set(s.copy(keepScreenOn = it)) }
        var scrollback by remember(s.scrollback) { mutableStateOf(s.scrollback.toString()) }
        OutlinedTextField(
            value = scrollback,
            onValueChange = { v ->
                scrollback = v.filter { it.isDigit() }.take(6)
                scrollback.toIntOrNull()?.let { set(s.copy(scrollback = it.coerceIn(0, 100_000))) }
            },
            label = { Text("Scrollback lines") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        var answerback by remember(s.answerback) { mutableStateOf(s.answerback) }
        OutlinedTextField(
            value = answerback,
            onValueChange = { answerback = it; set(s.copy(answerback = it)) },
            label = { Text("Answerback (sent for ^E)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Flag(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
