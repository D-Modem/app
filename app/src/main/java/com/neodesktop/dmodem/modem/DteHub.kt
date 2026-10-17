// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import android.os.ParcelFileDescriptor
import com.neodesktop.dmodem.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The modem's DTE side, shared: what the modem sends goes to every
 * listener (the app's console, the terminal, the TCP bridge), and any of
 * them can write to the modem. The modem behind it can change (the
 * service restarts it when the network changes): [attach] the new one's
 * streams, and the listeners stay.
 */
class DteHub : TerminalLink {

    private val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()

    /** who has the modem to itself now (a fax call): only it hears the modem, only it may write */
    @Volatile private var owner: TerminalLink.Listener? = null

    /** the modem to [l] alone until [release] (false: someone else has it) */
    @Synchronized
    fun claim(l: TerminalLink.Listener): Boolean {
        if (owner != null) return false
        owner = l
        return true
    }

    fun release(l: TerminalLink.Listener) {
        synchronized(this) { if (owner === l) owner = null }
    }

    val claimed: Boolean get() = owner != null

    /** octets from the modem (its RD) and to it (its SD), for the lights */
    @Volatile var bytesIn = 0L
        private set
    @Volatile var bytesOut = 0L
        private set

    /** octets to the modem from its owner ([claim]) */
    fun writeAs(l: TerminalLink.Listener, data: ByteArray): Boolean =
        if (owner === l) writeRaw(data, 0, data.size) else false
    private val _state = MutableStateFlow<TerminalLink.State>(TerminalLink.State.Closed(null))
    /** open while a modem is attached */
    override val state: StateFlow<TerminalLink.State> = _state.asStateFlow()
    private val outLock = Any()
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var reader: Thread? = null

    override fun addListener(l: TerminalLink.Listener) {
        listeners.add(l)
    }

    override fun removeListener(l: TerminalLink.Listener) {
        listeners.remove(l)
    }

    /** The modem's end of its DTE socket: the hub's from now (closed by [detach]). */
    fun attach(pfd: ParcelFileDescriptor) {
        attach(
            ParcelFileDescriptor.AutoCloseInputStream(pfd),
            ParcelFileDescriptor.AutoCloseOutputStream(pfd.dup()),
        )
    }

    /** The same with streams (the tests' pipes). */
    @Synchronized
    fun attach(inStream: InputStream, outStream: OutputStream) {
        detach()
        input = inStream
        synchronized(outLock) { output = outStream }
        _state.value = TerminalLink.State.Open
        reader = Thread({ read(inStream) }, "dte-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** The streams closed (the modem sees its DTE gone), the reader ended. */
    @Synchronized
    fun detach() {
        val r = reader
        synchronized(outLock) {
            try {
                output?.close()
            } catch (_: IOException) {
            }
            output = null
        }
        try {
            input?.close()
        } catch (_: IOException) {
        }
        input = null
        reader = null
        _state.value = TerminalLink.State.Closed(null)
        if (r != null && r != Thread.currentThread()) r.join(2000)
    }

    val attached: Boolean get() = synchronized(outLock) { output != null }

    /** Octets to the modem: false if there is no modem, or it has gone (or a fax call has it). */
    override fun write(data: ByteArray, offset: Int, length: Int): Boolean =
        if (owner != null) false else writeRaw(data, offset, length)

    private fun writeRaw(data: ByteArray, offset: Int, length: Int): Boolean =
        synchronized(outLock) {
            bytesOut += length
            val o = output ?: return false
            try {
                o.write(data, offset, length)
                o.flush()
                true
            } catch (_: IOException) {
                false
            }
        }

    fun write(text: String): Boolean = write(text.toByteArray(Charsets.ISO_8859_1))

    private fun read(s: InputStream) {
        val buf = ByteArray(4096)
        while (true) {
            val n = try {
                s.read(buf)
            } catch (_: IOException) {
                -1
            }
            if (n < 0) break
            if (n == 0) continue
            bytesIn += n
            val o = owner
            if (o != null) o.onData(buf, n) else for (l in listeners) l.onData(buf, n)
        }
    }
}
