// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import com.neodesktop.dmodem.tones.CountryTones
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.ModemSettings
import com.neodesktop.dmodem.settings.SettingsStore
import kotlinx.coroutines.launch

/** Settings' pages: the menu's rows, each a page of its own. */
enum class SettingsPage(val title: String) {
    LINE("Line"),
    MODEM("Modem"),
    BRIDGES("Serial bridges"),
    FAX("Fax"),
    INTERNET("Dial-up internet"),
    APPEARANCE("Appearance"),
    TERMINAL("Terminal"),
    SSH("SSH keys"),
    LINUX("Linux"),
    ;

    /** its icon in the menu */
    val icon: androidx.compose.ui.graphics.vector.ImageVector
        get() = when (this) {
            LINE -> SettingsIcons.Line
            MODEM -> SettingsIcons.Modem
            BRIDGES -> SettingsIcons.Bridges
            FAX -> SettingsIcons.Fax
            INTERNET -> SettingsIcons.Internet
            APPEARANCE -> SettingsIcons.Appearance
            TERMINAL -> SettingsIcons.Terminal
            SSH -> SettingsIcons.Keys
            LINUX -> SettingsIcons.Linux
        }
}

/** which settings page is open (null: the menu); Home's buttons open theirs */
object SettingsNav {
    val page = kotlinx.coroutines.flow.MutableStateFlow<SettingsPage?>(null)

    /** [p] opened (null: the menu) */
    fun open(p: SettingsPage?) {
        page.value = p
    }
}

/**
 * Settings: a menu of its pages (the line, the modem, the bridges, fax,
 * dial-up, appearance, the terminal, SSH keys, Linux), each with what it
 * is set to; back from a page to the menu. The line's and the modem's
 * settings are saved with their Save button, and apply when the modem next
 * starts; their edits are kept across the two pages until then.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onOpenTerminal: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val run by ModemState.run.collectAsStateWithLifecycle()
    val page by SettingsNav.page.collectAsStateWithLifecycle()

    var loaded by remember { mutableStateOf<ModemSettings?>(null) }
    LaunchedEffect(Unit) { loaded = store.current() }
    val initial = loaded ?: return

    var s by remember(initial) { mutableStateOf(initial) }
    var savedAs by remember(initial) { mutableStateOf(initial) }
    var login by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    // back: a page to the menu (the menu's own back is the app's: Home)
    androidx.activity.compose.BackHandler(enabled = page != null) { SettingsNav.open(null) }

    val p = page
    if (p == null) {
        SettingsMenu(modifier, s, s != savedAs)
        return
    }

    /** the line's and the modem's Save button, and whether they're saved (and need a restart to apply) */
    @Composable
    fun SaveRow() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                scope.launch {
                    store.save(s)
                    savedAs = s
                    saved = true
                }
            }, enabled = s != savedAs) { Text("Save") }
            if (s != savedAs) {
                Text("Not saved yet", style = MaterialTheme.typography.bodyMedium)
            } else if (saved) {
                Text(
                    if (run == ModemState.Run.STOPPED) "Saved" else "Saved: restart the modem to apply",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // the page's title, and back to the menu
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = { SettingsNav.open(null) }) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to settings",
                )
            }
            Text(p.title, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (p) {
                SettingsPage.LINE -> {
                    LineChoice(s) { s = it }
                    if (s.peer) {
                        PeerLineFields(s) { s = it }
                    } else if (s.usb) {
                        UsbLineFields()
                        Toggle("Automatic transmit level", s.usbTxAuto) { s = s.copy(usbTxAuto = it) }
                        if (s.usbTxAuto) {
                            Text(
                                "Set for each call from how loud the other modem's answer tone arrives (louder on a longer line, " +
                                    "softer if it distorts); the log says what it chose.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            SignedNumberField("Transmit level (dB, -20 to 20)", s.usbTxGain) { s = s.copy(usbTxGain = it.coerceIn(-20, 20)) }
                            Text(
                                "The modem's sound to the line louder (+) or softer (-) than D-Modem's own: for a line or modem " +
                                    "that hears it too faintly (0: as it is). Too loud distorts it.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Toggle("Record each call (for a bug report)", s.recordLine) { s = s.copy(recordLine = it) }
                        Text(
                            "Each call's sound, off hook to on hook, as a stereo WAV file (the line's, the modem's); " +
                                "the log screen shares the latest. The last ${com.neodesktop.dmodem.modem.LineRecordings.KEEP} are kept.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (s.btModem) {
                        BluetoothModemFields(s) { s = it }
                    } else {
                        Text("SIP account", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = login,
                            onValueChange = { v ->
                                login = v
                                ModemSettings.parseLogin(v.trim())?.let { (u, pw, d) ->
                                    s = s.copy(user = u, password = pw, domain = d)
                                }
                            },
                            label = { Text("Paste user:password@host (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Field("User", s.user) { s = s.copy(user = it) }
                        OutlinedTextField(
                            value = s.password,
                            onValueChange = { s = s.copy(password = it) },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Field("Server (host[:port])", s.domain) { s = s.copy(domain = it) }
                        NumberField("Local SIP port", s.sipPort) { s = s.copy(sipPort = it) }
                        Toggle("Take incoming calls (register)", s.listen) { s = s.copy(listen = it) }
                        Toggle("Fax over T.38", s.t38) { s = s.copy(t38 = it) }
                        NumberField("SIP log level (0-4)", s.sipLogLevel) { s = s.copy(sipLogLevel = it.coerceIn(0, 4)) }
                    }
                    SaveRow()
                }
                SettingsPage.MODEM -> {
                    Toggle("Speaker: hear the dialling and the handshake", s.speaker) { s = s.copy(speaker = it) }
                    Text(
                        "As a modem's speaker: on from off hook until CONNECT (ATM1). ATM0 keeps it quiet, ATM2 on all the call; " +
                            "ATL1-3 its volume, on top of the phone's media volume.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Toggle("Fax or data, as each call turns out", s.adaptiveAnswer) { s = s.copy(adaptiveAnswer = it) }
                    Text(
                        "A call answered (ATA) is a fax machine's or a modem's by what it sends (adaptive answer, AT+FAA=1): " +
                            "a fax goes to the fax receiver by itself, a modem's call to the terminal.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Field("Init commands (sent at start)", s.initCommands) { s = s.copy(initCommands = it) }
                    CountryPicker(s.country) { s = s.copy(country = it) }
                    Toggle("Call progress tones: dial, ringing, busy, congestion", s.callTones) { s = s.copy(callTones = it) }
                    Text(
                        "The country's tones, as its phone network plays them: a second of dial tone as an entry is dialled, " +
                            "the ringing tone while the other phone rings, the busy or congestion tone when a call can't be made.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    NumberField("Modem log level (0-5)", s.logLevel) { s = s.copy(logLevel = it.coerceIn(0, 5)) }
                    Field("Log time stamp", s.logTimestamp) { s = s.copy(logTimestamp = it) }
                    Text(
                        if (com.neodesktop.dmodem.modem.LogFormat.valid(s.logTimestamp)) {
                            "Before each line of the log: a date pattern (HH:mm:ss.SSS the time to the millisecond, " +
                                "yyyy-MM-dd the date), empty for none. Each day starts with a line of its own."
                        } else {
                            "Not a date pattern: the log keeps ${com.neodesktop.dmodem.modem.LogFormat.DEFAULT}. " +
                                "Letters other than the pattern's go in quotes ('at')."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (com.neodesktop.dmodem.modem.LogFormat.valid(s.logTimestamp)) androidx.compose.ui.graphics.Color.Unspecified
                        else MaterialTheme.colorScheme.error,
                    )
                    SaveRow()
                }
                SettingsPage.BRIDGES -> BridgeSection()
                SettingsPage.FAX -> FaxSettingsSection()
                SettingsPage.INTERNET -> IspSettingsSection()
                SettingsPage.APPEARANCE -> AppearanceSection()
                SettingsPage.TERMINAL -> TerminalSettingsSection()
                SettingsPage.SSH -> SshKeysSection()
                SettingsPage.LINUX -> LinuxSection(onOpened = onOpenTerminal)
            }
        }
    }
}

/** Settings' menu: a row for each page, what it's set to beneath */
@Composable
private fun SettingsMenu(modifier: Modifier, s: ModemSettings, unsaved: Boolean) {
    val rows = listOf(
        SettingsPage.LINE to lineSummary(s) + if (unsaved) " (not saved)" else "",
        SettingsPage.MODEM to "${CountryTones.find(s.country)?.label ?: s.country}; speaker " +
            (if (s.speaker) "on" else "off") + "; " + (if (s.adaptiveAnswer) "fax or data as calls turn out" else "calls answered as data"),
        SettingsPage.BRIDGES to "The modem for another computer: TCP, Bluetooth, USB serial",
        SettingsPage.FAX to "This fax's number and name, answering, the rates",
        SettingsPage.INTERNET to "The ISP to dial; dial-in for callers",
        SettingsPage.APPEARANCE to "Theme, dark mode, colours",
        SettingsPage.TERMINAL to "Emulation, fonts, colours, keys",
        SettingsPage.SSH to "This phone's keys for SSH",
        SettingsPage.LINUX to "Linux on the phone, the modem at /dev/ttyDM0",
    )
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // (the title, as each page has its own)
        Text(
            "Settings", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.heightIn(min = 48.dp).padding(start = 4.dp).wrapContentHeight(Alignment.CenterVertically),
        )
        for ((page, summary) in rows) SettingsCard(page, summary)
    }
}

/** a page's card in the menu: its icon on a tile, its title, what it's set to; opens it */
@Composable
private fun SettingsCard(page: SettingsPage, summary: String) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        onClick = { SettingsNav.open(page) },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(56.dp).background(colors.surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(page.icon, contentDescription = null, tint = colors.primary)
            }
            Column(Modifier.weight(1f).padding(end = 4.dp)) {
                Text(page.title, style = MaterialTheme.typography.titleMedium, color = colors.primary)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            }
        }
    }
}

/** what the line is, in a line */
private fun lineSummary(s: ModemSettings): String = when (s.line) {
    ModemSettings.LINE_WIFI -> "Another phone on Wi-Fi" + s.peerName.ifBlank { s.peerAddress }.let { if (it.isBlank()) "" else ": $it" }
    ModemSettings.LINE_BLUETOOTH -> "Another phone on Bluetooth" + s.peerName.let { if (it.isBlank()) "" else ": $it" }
    ModemSettings.LINE_CELL -> "This phone's mobile calls"
    ModemSettings.LINE_NONE -> "No line: the modem on its own"
    ModemSettings.LINE_USB -> "A phone line, through a USB modem"
    ModemSettings.LINE_BT_MODEM -> "A Bluetooth modem" + s.btModemName.let { if (it.isBlank()) "" else ": $it" }
    else -> if (s.complete) "SIP: ${s.user}@${s.domain}" + (if (s.listen) ", taking calls" else "") else "SIP: no account set up yet"
}

/** a one-line text field */
@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

/** a number field taking a sign and up to three digits */
@Composable
private fun SignedNumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            // (a sign, then digits)
            text = (if (v.startsWith("-")) "-" else "") + v.filter { it.isDigit() }.take(3)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** a number field taking up to five digits */
@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** a labelled switch */
@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/**
 * the modem's country (its line settings, how it hears busy) and the call
 * progress tones': D-Modem-RE's ITU list, searched by name, each with its
 * tones; the value its name ("" for the default, the USA)
 */
@Composable
private fun CountryPicker(value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val now = CountryTones.find(value)
    OutlinedTextField(
        value = now?.label ?: value,
        onValueChange = {},
        readOnly = true,
        label = { Text("Country") },
        supportingText = { Text(now?.let { "Dial tone ${it.dial}; busy ${it.busy}" } ?: "Not in the list: the USA's tones") },
        trailingIcon = { TextButton(onClick = { open = true }) { Text("Change") } },
        modifier = Modifier.fillMaxWidth().clickable { open = true },
    )
    TextButton(onClick = { ModemState.tones.preview(now ?: CountryTones.find("")!!) }) {
        Text("Hear its tones: dial, ringing, busy, congestion")
    }
    if (!open) return
    var query by remember { mutableStateOf("") }
    val shown = remember(query) {
        val q = query.trim().lowercase()
        CountryTones.all.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.name.lowercase().contains(q.replace(' ', '_')) }
            .sortedBy { it.label }
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Country") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.name }) { c ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                onPick(if (c.name == CountryTones.DEFAULT) "" else c.name)
                                open = false
                            }.padding(vertical = 8.dp),
                        ) {
                            Text(c.label, fontWeight = if (c.name == now?.name) FontWeight.Bold else FontWeight.Normal)
                            Text("Ringing ${c.ringing}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}
