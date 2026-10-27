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
import com.neodesktop.dmodem.terminal.FontCache
import com.neodesktop.dmodem.terminal.TerminalFont
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The terminal's settings: each change saved (and on the screen) at once. */
@Composable
fun TerminalSettingsSection() {
    val context = LocalContext.current
    val store = remember { TerminalSettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val current by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = current ?: return
    /** [change] applied to the stored terminal settings */
    fun set(change: (TerminalSettings) -> TerminalSettings) {
        scope.launch { store.update(change) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Terminal", style = MaterialTheme.typography.titleMedium)
        Choice("Colours", TermTheme.byId(s.themeId).name, TermTheme.ALL.map { it.name to it.id }) { set { t -> t.copy(themeId = it) } }
        Choice("Font", s.font.label, TerminalFont.entries.map { it.label to it }) { set { t -> t.copy(font = it) } }
        FontPreview(s.font, TermTheme.byId(s.themeId))
        if (s.font.ligatures) {
            Flag("Ligatures (-> != ==)", s.ligatures) { set { t -> t.copy(ligatures = it) } }
        }
        var showLicenses by remember { mutableStateOf(false) }
        TextButton(onClick = { showLicenses = true }) { Text("Font licences") }
        if (showLicenses) FontLicenses { showLicenses = false }
        Choice(
            "Width", if (s.fixedColumns > 0) "${s.fixedColumns} columns" else "By font size",
            listOf("80 columns (BBS)" to 80, "132 columns" to 132, "40 columns" to 40, "By font size" to 0),
        ) { set { t -> t.copy(fixedColumns = it) } }
        if (s.fixedColumns == 0) {
            Text("Font size: ${"%.1f".format(s.fontSize)} sp (or pinch the screen)", style = MaterialTheme.typography.bodyMedium)
            Slider(value = s.fontSize, onValueChange = { set { t -> t.copy(fontSize = it) } }, valueRange = 6f..28f)
        }
        Choice("Character set", s.charset.label, TermCharset.entries.map { it.label to it }) { set { t -> t.copy(charset = it) } }
        Choice("Backspace sends", s.backspace.label, BackspaceMode.entries.map { it.label to it }) { set { t -> t.copy(backspace = it) } }
        Choice("Enter sends", s.enter.label, EnterMode.entries.map { it.label to it }) { set { t -> t.copy(enter = it) } }
        Choice("Cursor", s.cursor.label, CursorShape.entries.map { it.label to it }) { set { t -> t.copy(cursor = it) } }
        Choice("Bell", s.bell.label, BellMode.entries.map { it.label to it }) { set { t -> t.copy(bell = it) } }
        Choice("Volume keys", s.volumeKeys.label, VolumeKeys.entries.map { it.label to it }) { set { t -> t.copy(volumeKeys = it) } }
        Flag("Blinking cursor", s.cursorBlink) { set { t -> t.copy(cursorBlink = it) } }
        Flag("Bold as bright colours (DOS, BBS)", s.boldBright) { set { t -> t.copy(boldBright = it) } }
        Flag("iCE colours (blink as bright background)", s.iceColors) { set { t -> t.copy(iceColors = it) } }
        Flag("Draw line and block characters (gapless ANSI art)", s.drawBoxes) { set { t -> t.copy(drawBoxes = it) } }
        Flag("Local echo", s.localEcho) { set { t -> t.copy(localEcho = it) } }
        Flag("Extra keys bar", s.extraKeys) { set { t -> t.copy(extraKeys = it) } }
        Flag("Keep the screen on", s.keepScreenOn) { set { t -> t.copy(keepScreenOn = it) } }
        var scrollback by remember(s.scrollback) { mutableStateOf(s.scrollback.toString()) }
        OutlinedTextField(
            value = scrollback,
            onValueChange = { v ->
                scrollback = v.filter { it.isDigit() }.take(6)
                scrollback.toIntOrNull()?.let { set { t -> t.copy(scrollback = it.coerceIn(0, 100_000)) } }
            },
            label = { Text("Scrollback lines") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        var answerback by remember(s.answerback) { mutableStateOf(s.answerback) }
        OutlinedTextField(
            value = answerback,
            onValueChange = { answerback = it; set { t -> t.copy(answerback = it) } },
            label = { Text("Answerback (sent for ^E)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** a labelled switch */
@Composable
private fun Flag(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** a line of text in the font, in the theme's colours */
@Composable
private fun FontPreview(font: TerminalFont, theme: TermTheme) {
    val context = LocalContext.current
    val family = remember(font) {
        val path = font.asset
        if (path == null) {
            FontFamily.Monospace
        } else if (font.variable) {
            FontFamily(
                Font(path, context.assets, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
                Font(path, context.assets, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
            )
        } else {
            FontFamily(Font(path, context.assets))
        }
    }
    Column(
        Modifier.fillMaxWidth().background(Color(theme.bgArgb)).padding(8.dp),
    ) {
        Text("AaBbCc 0O1lI| {}[] -> != ==", fontFamily = family, color = Color(theme.fgArgb), fontSize = 15.sp)
        Text(
            "ATDT5551234  CONNECT 56000/V92",
            fontFamily = family, color = Color(theme.palette[10]), fontSize = 15.sp, fontWeight = FontWeight.Bold,
        )
    }
}

/** the bundled fonts' licences */
@Composable
private fun FontLicenses(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        TerminalFont.entries.filter { it.license != null }.joinToString("\n\n") { f ->
            "── ${f.label} ──\n\n" + (FontCache.license(context, f) ?: "")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Font licences") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "The terminal's fonts are under the SIL Open Font License 1.1, Droid Sans Mono under " +
                        "the Apache License 2.0. OpenDyslexic Mono is built from its upstream source. " +
                        "Sauce Code Pro is Nerd Fonts' patched Source Code Pro.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
