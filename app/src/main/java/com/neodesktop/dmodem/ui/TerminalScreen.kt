// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

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
import com.neodesktop.dmodem.NativeModem
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.BellMode
import com.neodesktop.dmodem.settings.TerminalSettingsStore
import com.neodesktop.dmodem.terminal.Key
import com.neodesktop.dmodem.terminal.TermCharset
import com.neodesktop.dmodem.terminal.Terminal
import com.neodesktop.dmodem.terminal.TerminalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

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
    val session = Terminal.session
    val status by ModemState.status.collectAsStateWithLifecycle()
    val run by ModemState.run.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val capture by session.capture.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var showSnippets by remember { mutableStateOf(false) }

    // the session's character set follows the settings (a directory entry's own while its call lasts)
    LaunchedEffect(s.charset) { session.charset = s.charset }

    // the bell
    DisposableEffect(s.bell, view) {
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
        if (!fullscreen) {
            TopBar(
                text = barText(run, status, onlineSince, now, title),
                capturing = capture != null,
                onKeyboard = { view?.toggleKeyboard() },
                onPaste = { view?.pasteClipboard() },
                onSnippets = { showSnippets = true },
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
                onHangUp = { scope.launch { session.hangUp(status.link == NativeModem.Link.ONLINE) } },
                onClear = { session.clear() },
                onReset = { session.reset() },
                onFullscreen = { onFullscreen(true) },
                charset = s.charset,
                onCharset = { cs -> scope.launch { store.save(s.copy(charset = cs)) } },
            )
        }
        AndroidView(
            factory = { ctx ->
                TerminalView(ctx).apply {
                    this.session = Terminal.session
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
            update = { v -> if (v.settings != s) v.applySettings(s) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        if (s.extraKeys) {
            ExtraKeys(
                onKey = { k -> view?.scrollToBottom(); session.sendKey(k) },
                onText = { t -> view?.scrollToBottom(); session.sendTyped(t) },
                fullscreen = fullscreen,
                onExitFullscreen = { onFullscreen(false) },
            )
        }
    }

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

@Composable
private fun TopBar(
    text: String,
    capturing: Boolean,
    onKeyboard: () -> Unit,
    onPaste: () -> Unit,
    onSnippets: () -> Unit,
    onCapture: () -> Unit,
    onHangUp: () -> Unit,
    onClear: () -> Unit,
    onReset: () -> Unit,
    onFullscreen: () -> Unit,
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
                    DropdownMenuItem(text = { Text("Hang up") }, onClick = { menu = false; onHangUp() })
                    DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; onPaste() })
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
    val session = Terminal.session
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
