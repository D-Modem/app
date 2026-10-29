// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.terminal

import com.neodesktop.dmodem.transfer.Incoming
import com.neodesktop.dmodem.transfer.Outgoing
import com.neodesktop.dmodem.transfer.Protocol
import com.neodesktop.dmodem.transfer.Transfer
import com.neodesktop.dmodem.transfer.TransferState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A terminal on a link (the modem's DTE, an SSH channel, a Telnet
 * connection): what the far end sends goes into the emulator (and the
 * capture file, if one is open), what is typed goes to it in the
 * session's character set. It outlives the activity: the screen and its
 * scrollback are there when the app comes back.
 */
class TerminalSession(
    val link: TerminalLink,
    /** what the session's tab says */
    val label: String = "Modem",
    val kind: Kind = Kind.MODEM,
    /** the directory entry it was opened from (SSH, Telnet) */
    val entry: DialEntry? = null,
    /** the Linux system it runs in (LINUX): its id */
    val linuxId: String? = null,
    /** where captures go */
    private val captureDir: () -> File?,
) {
    enum class Kind { MODEM, SSH, TELNET, LOCAL, LINUX }

    /** a program on this phone (Android's shell, or the Linux system's): not a connection */
    val isLocal: Boolean get() = kind == Kind.LOCAL || kind == Kind.LINUX

    val id: String = java.util.UUID.randomUUID().toString()

    /** the emulator; lock on it to read or change it */
    val emulator = TerminalEmulator(80, 25, 5000) { reply -> link.write(reply) }

    val keys = KeyEncoder()

    @Volatile
    var localEcho = false

    /** the view's redraw (any thread) */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** the bell (any thread) */
    @Volatile
    var onBell: (() -> Unit)? = null

    /** the remote put text on the clipboard (OSC 52) */
    var onClipboard: ((String) -> Unit)? = null

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /** Ctrl and Alt latched (the extra keys' and the volume keys'): for the next key only */
    val ctrl = MutableStateFlow(false)
    val alt = MutableStateFlow(false)

    private val _capture = MutableStateFlow<File?>(null)
    /** the file the session is captured to, or null */
    val capture: StateFlow<File?> = _capture.asStateFlow()
    private var captureOut: FileOutputStream? = null
    private val captureLock = Any()

    var charset: TermCharset
        get() = emulator.decoder.charset
        set(v) {
            synchronized(emulator) { emulator.decoder.charset = v; emulator.decoder.reset() }
        }

    // file transfers

    @Volatile
    private var transfer: Transfer? = null
    private val transferLock = Any()
    private val _transferState = MutableStateFlow<TransferState?>(null)
    /** the transfer running, or the last one's end (until [dismissTransfer]) */
    val transferState: StateFlow<TransferState?> = _transferState.asStateFlow()

    /** where files the remote starts sending go (its sz, kermit -s): null, they aren't taken */
    @Volatile
    var incoming: (() -> Incoming?)? = null

    /** the remote's rz is waiting for files: pick some for [sendFiles] */
    @Volatile
    var onSendWanted: ((Protocol) -> Unit)? = null

    /** the remote's own starts (ZMODEM's, Kermit's) noticed */
    @Volatile
    var autoTransfers = true

    val transferRunning: Boolean get() = transfer != null

    private val listener = TerminalLink.Listener { data, length ->
        synchronized(captureLock) {
            try {
                captureOut?.write(data, 0, length)
            } catch (_: IOException) {
                stopCapture()
            }
        }
        transfer?.let {
            it.io.feed(data, 0, length)
            return@Listener
        }
        if (autoTransfers && length >= 4) {
            val d = Transfer.detect(data, length)
            when (d?.second) {
                Transfer.Detected.ZMODEM_SEND, Transfer.Detected.KERMIT_SEND -> {
                    val into = incoming?.invoke()
                    if (into != null) {
                        synchronized(emulator) { emulator.append(data, d.first) }
                        val proto = if (d.second == Transfer.Detected.KERMIT_SEND) Protocol.KERMIT else Protocol.ZMODEM
                        startTransfer(proto, sending = false)?.let { t ->
                            t.io.feed(data, d.first, length - d.first)
                            t.receive(into)
                            onChange?.invoke()
                            return@Listener
                        }
                    }
                }
                Transfer.Detected.ZMODEM_RECEIVE -> onSendWanted?.invoke(Protocol.ZMODEM)
                null -> {}
            }
        }
        synchronized(emulator) { emulator.append(data, length) }
        onChange?.invoke()
    }

    /** a transfer made the running one, over the link: null if one already is */
    private fun startTransfer(protocol: Protocol, sending: Boolean): Transfer? = synchronized(transferLock) {
        if (transfer != null) return null
        val t = Transfer(
            protocol, sending,
            send = { b -> if (!link.write(b)) throw IOException("the line is closed") },
            sendRaw = { b -> link.write(b) },
            onState = { s -> _transferState.value = s },
            onEnd = { s, rest -> endTransfer(s, rest) },
        )
        transfer = t
        t
    }

    /** the transfer's end: it no longer running, its outcome on the screen and the bytes after it to the emulator */
    private fun endTransfer(s: TransferState, rest: ByteArray) {
        synchronized(transferLock) { transfer = null }
        val what = s.protocol.label
        val msg = when {
            s.result != "" -> "$what: ${s.result}"
            s.sending -> "$what: sent ${s.progress.files} file(s)"
            else -> "$what: received ${s.received.size} file(s)"
        }
        synchronized(emulator) {
            emulator.appendText("\r\n[$msg]\r\n")
            if (rest.isNotEmpty()) emulator.append(rest, rest.size)
        }
        onChange?.invoke()
    }

    /** files from the remote ([name]: XMODEM's, which has none): false if one is already running */
    fun receiveFiles(protocol: Protocol, into: Incoming, name: String = "received.bin"): Boolean {
        val t = startTransfer(protocol, sending = false) ?: return false
        t.receive(into, name)
        return true
    }

    /** [files] to the remote (which must be receiving: its rz, rb, rx, kermit -r) */
    fun sendFiles(protocol: Protocol, files: List<Outgoing>): Boolean {
        if (files.isEmpty()) return false
        val t = startTransfer(protocol, sending = true) ?: return false
        t.send(files)
        return true
    }

    /** the running transfer, if any, cancelled */
    fun cancelTransfer() {
        transfer?.cancel()
    }

    /** the last transfer's end no longer shown */
    fun dismissTransfer() {
        if (transfer == null) _transferState.value = null
    }

    init {
        emulator.listener = object : TerminalEmulator.Listener {
            /** the window's title (OSC 0/2) for [title] */
            override fun onTitle(title: String) {
                _title.value = title
            }

            /** the bell to [onBell] */
            override fun onBell() {
                onBell?.invoke()
            }

            /** the remote's clipboard text to [onClipboard] */
            override fun onClipboard(text: String) {
                onClipboard?.invoke(text)
            }
        }
        link.addListener(listener)
    }

    // to the modem

    /** octets to the modem as they are */
    fun sendBytes(b: ByteArray): Boolean = link.write(b)

    /** text to the modem in the session's set (and on the screen, with local echo) */
    fun send(text: String): Boolean {
        if (text.isEmpty()) return true
        // (nothing typed gets into a transfer's packets)
        if (transfer != null) return false
        if (localEcho) {
            synchronized(emulator) { emulator.appendText(text.replace("\r", "\r\n")) }
            onChange?.invoke()
        }
        return link.write(charset.encode(text))
    }

    /** a key, with the latched modifiers and those held */
    fun sendKey(k: Key, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) {
        val a = alt || this.alt.value
        val c = ctrl || this.ctrl.value
        releaseLatches()
        val app = synchronized(emulator) { emulator.appCursorKeys }
        send(keys.key(k, app, shift, a, c))
    }

    /** a character typed, with the latched modifiers and those held */
    fun sendChar(cp: Int, alt: Boolean = false, ctrl: Boolean = false) {
        val a = alt || this.alt.value
        val c = ctrl || this.ctrl.value
        releaseLatches()
        send(keys.char(cp, a, c))
    }

    /** text typed (the soft keyboard's): its first character takes the latched modifiers */
    fun sendTyped(text: String) {
        if (text.isEmpty()) return
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\n'.code) sendKey(Key.ENTER) else sendChar(cp)
        }
    }

    /** the latched Ctrl and Alt let go */
    private fun releaseLatches() {
        ctrl.value = false
        alt.value = false
    }

    /** the clipboard's text: its line ends as Enter's, in xterm's bracketed paste if the remote asked */
    fun paste(text: String) {
        val t = text.replace("\r\n", "\n").replace('\n', '\r')
        val bracketed = synchronized(emulator) { emulator.bracketedPaste }
        send(if (bracketed) "\u001b[200~$t\u001b[201~" else t)
    }

    /**
     * The modem back in command mode (+++ with its guard times) and on hook (ATH).
     * The guard time is S12 (1 s by default): nothing for that long before
     * and after the +++.
     */
    suspend fun hangUp(online: Boolean) {
        if (online) {
            delay(GUARD_MS)
            link.write("+++".toByteArray())
            delay(GUARD_MS)
        }
        link.write("ATH\r".toByteArray())
    }

    // the screen

    /** the screen's new size, and the far end told (SSH's window change, Telnet's NAWS) */
    fun resize(cols: Int, rows: Int) {
        val changed = synchronized(emulator) {
            val c = emulator.cols != cols || emulator.rows != rows
            emulator.resize(cols, rows)
            c
        }
        if (changed) link.resize(cols, rows)
    }

    /** the session over: its listener off the link, the link closed, the capture ended */
    fun close() {
        cancelTransfer()
        link.removeListener(listener)
        stopCapture()
        if (kind != Kind.MODEM) link.close()
    }

    /** the emulator reset (RIS, the scrollback kept) and the view redrawn */
    fun reset() {
        synchronized(emulator) { emulator.reset() }
        onChange?.invoke()
    }

    /** the screen and the scrollback cleared and the view redrawn */
    fun clear() {
        synchronized(emulator) { emulator.clearAll() }
        onChange?.invoke()
    }

    // the capture

    /** everything the modem sends from now into a new file: the file, or null if it can't be made */
    fun startCapture(): File? {
        synchronized(captureLock) {
            stopCaptureLocked()
            val dir = captureDir() ?: return null
            dir.mkdirs()
            val name = "dmodem-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                if (charset == TermCharset.CP437) ".ans" else ".log"
            val f = File(dir, name)
            return try {
                captureOut = FileOutputStream(f)
                _capture.value = f
                f
            } catch (_: IOException) {
                null
            }
        }
    }

    /** the capture file closed: the file, or null if there was none */
    fun stopCapture(): File? = synchronized(captureLock) { stopCaptureLocked() }

    /** the capture closed, with [captureLock] held: its file, or null */
    private fun stopCaptureLocked(): File? {
        val f = _capture.value
        try {
            captureOut?.close()
        } catch (_: IOException) {
        }
        captureOut = null
        _capture.value = null
        return f
    }

    companion object {
        /** a little over the default guard time (S12 = 50: 1 s) */
        const val GUARD_MS = 1200L
    }
}
