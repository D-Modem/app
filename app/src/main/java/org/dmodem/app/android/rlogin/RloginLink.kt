// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.rlogin

import org.dmodem.app.android.terminal.TerminalLink
import org.dmodem.app.net.RloginCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An rlogin connection (RFC 1282), the protocol the shared [RloginCodec]:
 * the handshake with [user] as both names and [term], the window's size
 * once the server asks. Java's sockets can't read urgent data apart, so
 * it comes in the stream (the codec's inlineUrgent).
 */
class RloginLink(
    val host: String,
    val port: Int = 513,
    private val user: String,
    term: String = "xterm",
    cols: Int = 80,
    rows: Int = 24,
) : TerminalLink {

    private val _state = MutableStateFlow<TerminalLink.State>(TerminalLink.State.Connecting)
    override val state: StateFlow<TerminalLink.State> = _state.asStateFlow()
    private val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()
    private val codec = RloginCodec(user, term, cols, rows, inlineUrgent = true)

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closing = false

    /** a listener added, for the server's data */
    override fun addListener(l: TerminalLink.Listener) {
        listeners.add(l)
    }

    /** a listener taken off */
    override fun removeListener(l: TerminalLink.Listener) {
        listeners.remove(l)
    }

    /** the connection made, in a thread of its own */
    fun connect() {
        Thread({ run() }, "rlogin-$host").apply { isDaemon = true }.start()
    }

    /** connect, log in and read until the connection closes (on the link's thread) */
    private fun run() {
        try {
            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(host, port), 20_000)
            s.tcpNoDelay = true
            // (rlogind's urgent byte: in the stream, where we can see it)
            s.oobInline = true
            out = s.getOutputStream()
            synchronized(codec) { codec.connected() }
            flush()
            _state.value = TerminalLink.State.Open
            val inp = s.getInputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                val screen = synchronized(codec) {
                    codec.feed(buf, n)
                    codec.screen()
                }
                flush()
                if (screen.isNotEmpty()) for (l in listeners) l.onData(screen, screen.size)
            }
            closed(if (closing) null else "connection closed by $host")
        } catch (e: IOException) {
            closed(if (closing) null else (e.message ?: e.javaClass.simpleName))
        }
    }

    /** what the codec has for the server, written */
    private fun flush(): Boolean {
        val o = out ?: return false
        val b = synchronized(codec) { codec.output() }
        if (b.isEmpty()) return true
        return try {
            synchronized(o) {
                o.write(b)
                o.flush()
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    /** what's typed, to the server */
    override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        if (_state.value !is TerminalLink.State.Open) return false
        synchronized(codec) { codec.type(data.copyOfRange(offset, offset + length)) }
        return flush()
    }

    /** the new size, sent (on a thread of its own) if the server asked for sizes */
    override fun resize(cols: Int, rows: Int) {
        synchronized(codec) { codec.resize(cols, rows) }
        Thread({ flush() }, "rlogin-size").start()
    }

    /** the link closed, with why (null when asked to) */
    private fun closed(reason: String?) {
        if (_state.value is TerminalLink.State.Closed) return
        _state.value = TerminalLink.State.Closed(reason)
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    /** the connection closed by the user */
    override fun close() {
        closing = true
        closed(null)
    }
}
