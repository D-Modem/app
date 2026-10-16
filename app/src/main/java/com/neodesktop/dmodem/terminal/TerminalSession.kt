// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

import com.neodesktop.dmodem.modem.DteHub
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
 * The terminal on the modem's DTE: what the modem sends goes into the
 * emulator (and the capture file, if one is open), what is typed goes to
 * the modem in the session's character set. It outlives the activity:
 * the screen and its scrollback are there when the app comes back.
 */
class TerminalSession(private val dte: DteHub, private val captureDir: () -> File?) {

    /** the emulator; lock on it to read or change it */
    val emulator = TerminalEmulator(80, 25, 5000) { reply -> dte.write(reply) }

    val keys = KeyEncoder()

    @Volatile
    var localEcho = false

    /** the view's redraw (any thread) */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** the bell (any thread) */
    @Volatile
    var onBell: (() -> Unit)? = null

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

    private val listener = DteHub.Listener { data, length ->
        synchronized(captureLock) {
            try {
                captureOut?.write(data, 0, length)
            } catch (_: IOException) {
                stopCapture()
            }
        }
        synchronized(emulator) { emulator.append(data, length) }
        onChange?.invoke()
    }

    init {
        emulator.listener = object : TerminalEmulator.Listener {
            override fun onTitle(title: String) {
                _title.value = title
            }

            override fun onBell() {
                onBell?.invoke()
            }
        }
        dte.addListener(listener)
    }

    // to the modem

    /** octets to the modem as they are */
    fun sendBytes(b: ByteArray): Boolean = dte.write(b)

    /** text to the modem in the session's set (and on the screen, with local echo) */
    fun send(text: String): Boolean {
        if (text.isEmpty()) return true
        if (localEcho) {
            synchronized(emulator) { emulator.appendText(text.replace("\r", "\r\n")) }
            onChange?.invoke()
        }
        return dte.write(charset.encode(text))
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
            dte.write("+++")
            delay(GUARD_MS)
        }
        dte.write("ATH\r")
    }

    // the screen

    fun reset() {
        synchronized(emulator) { emulator.reset() }
        onChange?.invoke()
    }

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
