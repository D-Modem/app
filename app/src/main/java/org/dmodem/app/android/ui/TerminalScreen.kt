// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ui

import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dmodem.app.android.NativeModem
import org.dmodem.app.android.modem.ModemState
import org.dmodem.app.android.settings.BellMode
import org.dmodem.app.android.settings.TerminalSettingsStore
import org.dmodem.app.terminal.Key
import org.dmodem.app.terminal.TermCharset
import org.dmodem.app.android.terminal.Terminal
import org.dmodem.app.android.terminal.TerminalView
import org.dmodem.app.android.local.Linux
import org.dmodem.app.android.local.LocalLink
import org.dmodem.app.android.terminal.TerminalLink
import org.dmodem.app.android.terminal.TerminalSession
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.dmodem.app.android.transfer.DownloadsIncoming
import org.dmodem.app.transfer.Protocol
import org.dmodem.app.android.transfer.outgoingOf

/** The terminal on the modem: the screen, a bar of what's going on and its actions, the extra keys. */
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { TerminalSettingsStore(context.applicationContext) }
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return
    val sessions by Terminal.sessions.collectAsStateWithLifecycle()
    val active by Terminal.active.collectAsStateWithLifecycle()
    val session = active ?: Terminal.session
    val isModem = session.kind == TerminalSession.Kind.MODEM
    val linkState by session.link.state.collectAsStateWithLifecycle()
    val status by ModemState.status.collectAsStateWithLifecycle()
    val run by ModemState.run.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val capture by session.capture.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var showSnippets by remember { mutableStateOf(false) }
    var showAtReference by remember { mutableStateOf(false) }
    var showSsh by remember { mutableStateOf(false) }
    var showLinuxInstall by remember { mutableStateOf(false) }
    var pickLinux by remember { mutableStateOf<List<org.dmodem.app.android.local.LinuxSystem>?>(null) }
    val ssh = session.link as? org.dmodem.app.android.ssh.SshLink
    val transfer by session.transferState.collectAsStateWithLifecycle()
    // the transfer dialog (true: sending), and a send waiting for its files to be picked
    var askTransfer by remember { mutableStateOf<Boolean?>(null) }
    var pendingSend by remember { mutableStateOf<Pair<Protocol, String>?>(null) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val (proto, command) = pendingSend ?: return@rememberLauncherForActivityResult
        pendingSend = null
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val files = uris.map { outgoingOf(context, it) }.let { if (proto.batch) it else it.take(1) }
        scope.launch {
            if (command.isNotEmpty()) {
                session.sendBytes((command + "\r").toByteArray())
                delay(800)
            }
            if (!session.sendFiles(proto, files)) Toast.makeText(context, "A transfer is running already", Toast.LENGTH_SHORT).show()
        }
    }
    // the remote's rz waiting: files to pick for it
    DisposableEffect(session) {
        session.onSendWanted = { proto ->
            scope.launch(Dispatchers.Main) {
                if (pendingSend == null && !session.transferRunning) {
                    pendingSend = proto to ""
                    pickFiles.launch(arrayOf("*/*"))
                }
            }
        }
        onDispose { session.onSendWanted = null }
    }

    // the modem session's character set follows the settings (a directory entry's own while its call
    // lasts); an SSH or Telnet session keeps its entry's
    LaunchedEffect(s.charset) { Terminal.session.charset = s.charset }

    // the bell
    DisposableEffect(s.bell, view, session) {
        session.onBell = {
            if (s.bell == BellMode.VIBRATE || s.bell == BellMode.BOTH) vibrate(context)
            if (s.bell == BellMode.FLASH || s.bell == BellMode.BOTH) view?.post { view?.flash() }
        }
        onDispose { session.onBell = null }
    }

    // how long the call has been online
    var onlineSince by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.link) {
        onlineSince = if (status.link == NativeModem.Link.ONLINE) System.currentTimeMillis() else 0L
        while (onlineSince != 0L) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        if (!fullscreen && sessions.size > 1) {
            SessionTabs(sessions, session)
        }
        if (!fullscreen) {
            TopBar(
                text = if (isModem) barText(run, status, onlineSince, now, title) else linkText(session, linkState, title),
                isModem = isModem,
                capturing = capture != null,
                onKeyboard = { view?.toggleKeyboard() },
                onPaste = { view?.pasteClipboard() },
                onSnippets = { showSnippets = true },
                onNewLinux = {
                    scope.launch {
                        val systems = withContext(Dispatchers.IO) { Linux.systems(context) }
                        when (systems.size) {
                            0 -> showLinuxInstall = true
                            1 -> Terminal.openLinux(context, systems[0].id)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            else -> pickLinux = systems
                        }
                    }
                },
                onNewShell = {
                    scope.launch { Terminal.openLocal(context)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
                },
                onSsh = if (ssh != null && linkState == TerminalLink.State.Open) ({ showSsh = true }) else null,
                onSendFiles = { askTransfer = true },
                onReceiveFiles = { askTransfer = false },
                onCapture = {
                    if (capture != null) {
                        val f = session.stopCapture()
                        if (f != null) shareCapture(context, f)
                    } else {
                        val f = session.startCapture()
                        Toast.makeText(
                            context, if (f != null) "Capturing to ${f.name}" else "Can't capture", Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onHangUp = {
                    if (isModem) {
                        scope.launch { session.hangUp(status.link == NativeModem.Link.ONLINE) }
                    } else {
                        Terminal.close(session)
                    }
                },
                onClear = { session.clear() },
                onReset = { session.reset() },
                onFullscreen = { onFullscreen(true) },
                onAtReference = { showAtReference = true },
                charset = s.charset,
                onCharset = { cs -> scope.launch { store.save(s.copy(charset = cs)) } },
            )
        }
        // the modem's lights, as its front panel has them
        if (!fullscreen && isModem) ModemLights(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), compact = true)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        AndroidView(
            factory = { ctx ->
                TerminalView(ctx).apply {
                    this.session = session
                    applySettings(s)
                    onFontSizeChanged = { sp ->
                        scope.launch {
                            store.setFontSize(sp)
                            store.setFixedColumns(0)
                        }
                    }
                    view = this
                    post { requestFocus() }
                }
            },
            update = { v ->
                if (v.session !== session) v.session = session
                if (v.settings != s) v.applySettings(s)
            },
            modifier = Modifier.fillMaxSize(),
        )
            val closed = linkState as? TerminalLink.State.Closed
            if (!isModem && closed != null) {
                Disconnected(
                    closed.reason,
                    local = session.isLocal,
                    onReconnect = {
                        scope.launch {
                            Terminal.reconnect(context, session)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                        }
                    },
                    onClose = { Terminal.close(session) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            transfer?.let { t ->
                TransferBanner(
                    t, DownloadsIncoming.where(context),
                    onCancel = { session.cancelTransfer() },
                    onDismiss = { session.dismissTransfer() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        if (s.extraKeys) {
            ExtraKeys(
                onKey = { k -> view?.scrollToBottom(); session.sendKey(k) },
                onText = { t -> view?.scrollToBottom(); session.sendTyped(t) },
                fullscreen = fullscreen,
                onExitFullscreen = { onFullscreen(false) },
            )
        }
    }

    if (showLinuxInstall) {
        AddLinuxDialog(
            onInstalled = { sys ->
                showLinuxInstall = false
                scope.launch { Terminal.openLinux(context, sys.id)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
            },
            onDismiss = { showLinuxInstall = false },
        )
    }
    pickLinux?.let { systems ->
        PickLinuxDialog(
            systems,
            onPick = { sys ->
                pickLinux = null
                scope.launch { Terminal.openLinux(context, sys.id)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
            },
            onAdd = { pickLinux = null; showLinuxInstall = true },
            onDismiss = { pickLinux = null },
        )
    }
    askTransfer?.let { sending ->
        TransferDialog(
            sending,
            onStart = { proto, command ->
                askTransfer = null
                if (sending) {
                    pendingSend = proto to command
                    pickFiles.launch(arrayOf("*/*"))
                } else {
                    // (XMODEM sends no name: the last word typed, if any)
                    val name = command.substringAfterLast(' ').substringAfterLast('/').ifBlank { "received.bin" }
                    if (session.receiveFiles(proto, Terminal.downloads(), name)) {
                        if (command.isNotEmpty() && !command.endsWith(' ')) session.sendBytes((command + "\r").toByteArray())
                    } else {
                        Toast.makeText(context, "A transfer is running already", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { askTransfer = null },
        )
    }
    if (showSsh && ssh != null) {
        SshSessionDialog(ssh) { showSsh = false }
    }
    if (showAtReference) AtReferenceDialog(onDismiss = { showAtReference = false })
    if (showSnippets) {
        SnippetsDialog(
            onSend = { text ->
                view?.scrollToBottom()
                session.send(text)
                showSnippets = false
            },
            onDismiss = { showSnippets = false },
        )
    }
}

/** the sessions as tabs: the modem's, then the connections (each with its close) */
@Composable
private fun SessionTabs(sessions: List<TerminalSession>, active: TerminalSession) {
    ScrollableTabRow(selectedTabIndex = sessions.indexOf(active).coerceAtLeast(0), edgePadding = 4.dp) {
        for (t in sessions) {
            val st by t.link.state.collectAsStateWithLifecycle()
            Tab(
                selected = t === active,
                onClick = { Terminal.select(t) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dot = when {
                            t.kind == TerminalSession.Kind.MODEM -> null
                            st is TerminalLink.State.Open -> Color(0xff2e7d32)
                            st is TerminalLink.State.Connecting -> Color(0xffef6c00)
                            else -> Color(0xff9e9e9e)
                        }
                        if (dot != null) {
                            Box(Modifier.size(8.dp).background(dot, CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        if (t.kind != TerminalSession.Kind.MODEM) {
                            Text(
                                "  ✕",
                                modifier = Modifier.clickable { Terminal.close(t) },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
    }
}

/** a connection's state, for the bar */
private fun linkText(s: TerminalSession, st: TerminalLink.State, title: String): String {
    if (s.isLocal) {
        val state = when (st) {
            TerminalLink.State.Connecting -> "Starting…"
            TerminalLink.State.Open -> s.label
            is TerminalLink.State.Closed -> "Exited" + ((s.link as? LocalLink)?.exitStatus?.takeIf { it != 0 }?.let { " ($it)" } ?: "")
        }
        return if (title.isNotBlank() && st == TerminalLink.State.Open) "$state — $title" else state
    }
    val what = if (s.kind == TerminalSession.Kind.SSH) "SSH" else "Telnet"
    val where = s.entry?.address ?: s.label
    val state = when (st) {
        TerminalLink.State.Connecting -> "Connecting to $where…"
        TerminalLink.State.Open -> "$what $where"
        is TerminalLink.State.Closed -> "Disconnected" + (st.reason?.let { ": $it" } ?: "")
    }
    return if (title.isNotBlank() && st == TerminalLink.State.Open) "$state — $title" else state
}

/** a connection that has ended: why, and to connect again or close it */
@Composable
private fun Disconnected(reason: String?, local: Boolean, onReconnect: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Surface(modifier.fillMaxWidth().padding(12.dp), tonalElevation = 6.dp, shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (local) "The shell has exited" else "Disconnected", style = MaterialTheme.typography.titleSmall)
                if (reason != null && !local) Text(reason, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onClose) { Text("Close") }
            Button(onClick = onReconnect) { Text(if (local) "New shell" else "Reconnect") }
        }
    }
}

/** the bar's line: the modem's state (online with its rates and the call's time), then the window's title if any */
private fun barText(
    run: ModemState.Run,
    st: NativeModem.Status,
    since: Long,
    now: Long,
    title: String,
): String {
    val state = when {
        run != ModemState.Run.RUNNING -> "Modem off"
        st.link == NativeModem.Link.ONLINE -> {
            val secs = ((now - since) / 1000).coerceAtLeast(0)
            "Online ${st.rxRate}/${st.txRate} · %d:%02d".format(secs / 60, secs % 60)
        }
        st.link == NativeModem.Link.CONNECTING -> "Connecting…"
        st.link == NativeModem.Link.HANGING_UP -> "Hanging up…"
        st.offhook -> "Off hook"
        else -> "Ready"
    }
    return if (title.isNotBlank()) "$state — $title" else state
}

/** the terminal's bar: its line, the keyboard, snippets and capture buttons, and a menu of the rest (hang up, paste, new shells, files, character set, clear, reset, full screen, the AT command reference) */
@Composable
private fun TopBar(
    text: String,
    isModem: Boolean,
    capturing: Boolean,
    onKeyboard: () -> Unit,
    onPaste: () -> Unit,
    onSnippets: () -> Unit,
    onNewShell: () -> Unit,
    onNewLinux: () -> Unit,
    /** an SSH connection's forwards, agent and keys (null: not one) */
    onSsh: (() -> Unit)?,
    onSendFiles: () -> Unit,
    onReceiveFiles: () -> Unit,
    onCapture: () -> Unit,
    onHangUp: () -> Unit,
    onClear: () -> Unit,
    onReset: () -> Unit,
    onFullscreen: () -> Unit,
    /** the AT command reference */
    onAtReference: () -> Unit,
    charset: TermCharset,
    onCharset: (TermCharset) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onKeyboard) { Text("⌨", fontSize = 20.sp) }
            IconButton(onClick = onSnippets) { Icon(Icons.Filled.Star, "Snippets") }
            TextButton(onClick = onCapture) {
                Text(if (capturing) "■ REC" else "● REC", color = if (capturing) Color(0xffe53935) else MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (isModem) "Hang up" else "Disconnect") }, onClick = { menu = false; onHangUp() })
                    DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; onPaste() })
                    DropdownMenuItem(text = { Text("New local shell") }, onClick = { menu = false; onNewShell() })
                    DropdownMenuItem(text = { Text("New Linux shell…") }, onClick = { menu = false; onNewLinux() })
                    if (onSsh != null) {
                        DropdownMenuItem(text = { Text("Port forwards, agent, keys") }, onClick = { menu = false; onSsh() })
                    }
                    DropdownMenuItem(text = { Text("Send files…") }, onClick = { menu = false; onSendFiles() })
                    DropdownMenuItem(text = { Text("Receive files…") }, onClick = { menu = false; onReceiveFiles() })
                    HorizontalDivider()
                    for (cs in TermCharset.entries) {
                        DropdownMenuItem(
                            text = { Text((if (cs == charset) "✓ " else "    ") + cs.label) },
                            onClick = { menu = false; onCharset(cs) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Clear screen and scrollback") }, onClick = { menu = false; onClear() })
                    DropdownMenuItem(text = { Text("Reset terminal") }, onClick = { menu = false; onReset() })
                    DropdownMenuItem(text = { Text("Full screen") }, onClick = { menu = false; onFullscreen() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("AT command reference") }, onClick = { menu = false; onAtReference() })
                }
            }
        }
    }
}

/** The keys a phone's keyboard hasn't got, as JuiceSSH's bar had them: Ctrl and Alt stay down for the next key. */
@Composable
private fun ExtraKeys(
    onKey: (Key) -> Unit,
    onText: (String) -> Unit,
    fullscreen: Boolean,
    onExitFullscreen: () -> Unit,
) {
    val session = Terminal.active.collectAsStateWithLifecycle().value ?: Terminal.session
    val ctrl by session.ctrl.collectAsStateWithLifecycle()
    val alt by session.alt.collectAsStateWithLifecycle()
    var fn by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(2.dp)) {
            if (!fn) {
                KeyRow {
                    KeyButton("ESC") { onKey(Key.ESCAPE) }
                    KeyButton("/") { onText("/") }
                    KeyButton("-") { onText("-") }
                    KeyButton("HOME") { onKey(Key.HOME) }
                    KeyButton("↑", repeat = true) { onKey(Key.UP) }
                    KeyButton("END") { onKey(Key.END) }
                    KeyButton("PGUP") { onKey(Key.PAGE_UP) }
                    KeyButton("|") { onText("|") }
                }
                KeyRow {
                    KeyButton("TAB") { onKey(Key.TAB) }
                    KeyButton("CTRL", latched = ctrl) { session.ctrl.value = !ctrl }
                    KeyButton("ALT", latched = alt) { session.alt.value = !alt }
                    KeyButton("←", repeat = true) { onKey(Key.LEFT) }
                    KeyButton("↓", repeat = true) { onKey(Key.DOWN) }
                    KeyButton("→", repeat = true) { onKey(Key.RIGHT) }
                    KeyButton("PGDN") { onKey(Key.PAGE_DOWN) }
                    KeyButton(if (fullscreen) "EXIT" else "Fn") { if (fullscreen) onExitFullscreen() else fn = true }
                }
            } else {
                KeyRow {
                    for (i in 0 until 6) KeyButton("F${i + 1}") { onKey(Key.entries[Key.F1.ordinal + i]) }
                    KeyButton("INS") { onKey(Key.INSERT) }
                    KeyButton("~") { onText("~") }
                }
                KeyRow {
                    for (i in 6 until 12) KeyButton("F${i + 1}") { onKey(Key.entries[Key.F1.ordinal + i]) }
                    KeyButton("DEL") { onKey(Key.DELETE) }
                    KeyButton("ABC", latched = true) { fn = false }
                }
            }
        }
    }
}

/** a row of the extra keys, 40 dp high */
@Composable
private fun KeyRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/** a key: a tap sends it; with repeat, held down it repeats */
@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyButton(
    label: String,
    latched: Boolean = false,
    repeat: Boolean = false,
    onPress: () -> Unit,
) {
    val bg = if (latched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (latched) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .weight(1f)
            .fillMaxSize()
            .background(bg, RoundedCornerShape(4.dp))
            .pointerInput(label, repeat) {
                awaitEachGesture {
                    awaitFirstDown()
                    onPress()
                    if (repeat) {
                        val job = scope.launch {
                            delay(REPEAT_DELAY_MS)
                            while (true) {
                                onPress()
                                delay(REPEAT_MS)
                            }
                        }
                        waitForUpOrCancellation()
                        job.cancel()
                    } else {
                        waitForUpOrCancellation()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1)
    }
}

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_MS = 60L

/** the bell as a 60 ms vibration */
private fun vibrate(context: Context) {
    val v: Vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
}

/** a capture file to another app (save it, mail it, open it in an ANSI viewer) */
fun shareCapture(context: Context, f: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, f.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share ${f.name}"))
}
