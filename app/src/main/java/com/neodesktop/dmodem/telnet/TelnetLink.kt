// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.telnet

import com.neodesktop.dmodem.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Telnet connection (RFC 854): the server's options answered (BINARY,
 * ECHO and SUPPRESS-GO-AHEAD taken, TERMINAL-TYPE and NAWS offered, the
 * rest refused), IAC escaped both ways, CR sent as CR NUL outside binary
 * mode, the window's size to the server (NAWS) as it changes.
 */
class TelnetLink(
    val host: String,
    val port: Int = 23,
    /** what TERMINAL-TYPE says: "XTERM-256COLOR", or "ANSI" for a BBS */
    private val termType: String = "XTERM-256COLOR",
    @Volatile private var cols: Int = 80,
    @Volatile private var rows: Int = 24,
) : TerminalLink {

    private val _state = MutableStateFlow<TerminalLink.State>(TerminalLink.State.Connecting)
    override val state: StateFlow<TerminalLink.State> = _state.asStateFlow()
    private val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closing = false

    /** options in effect: the server's (it WILL), ours (we WILL) */
    private val remote = BooleanArray(256)
    private val local = BooleanArray(256)
    @Volatile private var nawsOn = false

    /** [l] told the server's data from now on */
    override fun addListener(l: TerminalLink.Listener) {
        listeners.add(l)
    }

    /** [l] told no more */
    override fun removeListener(l: TerminalLink.Listener) {
        listeners.remove(l)
    }

    /** the connection made, in a thread of its own */
    fun connect() {
        Thread({ run() }, "telnet-$host").apply { isDaemon = true }.start()
    }

    /** the connection's life on its thread: connect, then read until it ends */
    private fun run() {
        try {
            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(host, port), 20_000)
            s.tcpNoDelay = true
            out = s.getOutputStream()
            _state.value = TerminalLink.State.Open
            read(s)
            closed(if (closing) null else "connection closed by $host")
        } catch (e: IOException) {
            closed(if (closing) null else (e.message ?: e.javaClass.simpleName))
        }
    }

    // the input: data, and the commands (IAC ...) in it

    private enum class In { DATA, IAC, OPT, SB, SB_IAC }

    /** the server's bytes until it closes: data (CR NUL a CR, IAC IAC an 0xff) to the listeners, its commands answered */
    private fun read(s: Socket) {
        val inp = s.getInputStream()
        val buf = ByteArray(8192)
        val data = ByteArray(8192)
        val sb = ByteArrayOutputStream()
        var st = In.DATA
        var verb = 0
        var lastCr = false
        while (true) {
            val n = inp.read(buf)
            if (n < 0) return
            var k = 0
            for (i in 0 until n) {
                val b = buf[i].toInt() and 0xff
                when (st) {
                    In.DATA -> when {
                        b == IAC -> st = In.IAC
                        // (CR NUL is a CR: the NUL goes)
                        b == 0 && lastCr && !remote[BINARY] -> {}
                        else -> data[k++] = b.toByte()
                    }
                    In.IAC -> when (b) {
                        IAC -> { data[k++] = 0xff.toByte(); st = In.DATA }
                        WILL, WONT, DO, DONT -> { verb = b; st = In.OPT }
                        SB -> { sb.reset(); st = In.SB }
                        else -> st = In.DATA            // NOP, GA, AYT...: nothing to do
                    }
                    In.OPT -> { option(verb, b); st = In.DATA }
                    In.SB -> if (b == IAC) st = In.SB_IAC else sb.write(b)
                    In.SB_IAC -> when (b) {
                        SE -> { subnegotiation(sb.toByteArray()); st = In.DATA }
                        IAC -> { sb.write(IAC); st = In.SB }
                        else -> st = In.SB
                    }
                }
                lastCr = st == In.DATA && b == '\r'.code
            }
            if (k > 0) for (l in listeners) l.onData(data, k)
        }
    }

    /** the server's WILL/WONT/DO/DONT: answered once per change (no loops, RFC 1143's spirit) */
    private fun option(verb: Int, opt: Int) {
        when (verb) {
            WILL -> {
                val ok = opt == BINARY || opt == ECHO || opt == SGA
                if (ok != remote[opt] || !ok) {
                    remote[opt] = ok
                    send(IAC, if (ok) DO else DONT, opt)
                }
            }
            WONT -> if (remote[opt]) {
                remote[opt] = false
                send(IAC, DONT, opt)
            }
            DO -> {
                val ok = opt == BINARY || opt == SGA || opt == TTYPE || opt == NAWS
                if (ok != local[opt] || !ok) {
                    local[opt] = ok
                    send(IAC, if (ok) WILL else WONT, opt)
                }
                if (ok && opt == NAWS) {
                    nawsOn = true
                    sendNaws()
                }
            }
            DONT -> if (local[opt]) {
                local[opt] = false
                if (opt == NAWS) nawsOn = false
                send(IAC, WONT, opt)
            }
        }
    }

    /** the server's subnegotiation answered: TERMINAL-TYPE SEND with IS [termType] */
    private fun subnegotiation(sb: ByteArray) {
        // TERMINAL-TYPE SEND: IS <type>
        if (sb.size >= 2 && (sb[0].toInt() and 0xff) == TTYPE && sb[1].toInt() == 1) {
            val t = termType.toByteArray(Charsets.US_ASCII)
            send(*(intArrayOf(IAC, SB, TTYPE, 0) + t.map { it.toInt() and 0xff } + intArrayOf(IAC, SE)))
        }
    }

    /** the window's size sent to the server (NAWS) */
    private fun sendNaws() {
        val c = cols.coerceIn(0, 65535)
        val r = rows.coerceIn(0, 65535)
        val v = intArrayOf(c shr 8, c and 0xff, r shr 8, r and 0xff)
        // (an 0xff in the sizes doubled)
        val body = v.flatMap { if (it == IAC) listOf(IAC, IAC) else listOf(it) }
        send(*(intArrayOf(IAC, SB, NAWS) + body + intArrayOf(IAC, SE)))
    }

    /** the bytes [v] sent as they are, ignoring a failure */
    private fun send(vararg v: Int) {
        val o = out ?: return
        try {
            synchronized(o) {
                o.write(ByteArray(v.size) { v[it].toByte() })
                o.flush()
            }
        } catch (_: IOException) {
        }
    }

    /** [length] bytes of [data] sent with IAC doubled and a lone CR as CR NUL outside binary; false if not open or it failed */
    override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        val o = out ?: return false
        if (_state.value !is TerminalLink.State.Open) return false
        val e = ByteArrayOutputStream(length + 8)
        val binary = local[BINARY]
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xff
            e.write(b)
            if (b == IAC) e.write(IAC)
            if (b == '\r'.code && !binary && (i + 1 >= offset + length || data[i + 1].toInt() != '\n'.code)) e.write(0)
        }
        return try {
            synchronized(o) {
                o.write(e.toByteArray())
                o.flush()
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    /** the new size kept, and sent on a thread of its own if NAWS is on */
    override fun resize(cols: Int, rows: Int) {
        this.cols = cols
        this.rows = rows
        if (nawsOn) Thread({ sendNaws() }, "telnet-naws").start()
    }

    /** the link closed, with [reason] (null: the user's doing), and its socket; once */
    private fun closed(reason: String?) {
        if (_state.value is TerminalLink.State.Closed) return
        _state.value = TerminalLink.State.Closed(reason)
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    /** the link closed by the user: no reason given */
    override fun close() {
        closing = true
        closed(null)
    }

    companion object {
        const val IAC = 255
        const val DONT = 254
        const val DO = 253
        const val WONT = 252
        const val WILL = 251
        const val SB = 250
        const val SE = 240
        const val BINARY = 0
        const val ECHO = 1
        const val SGA = 3
        const val TTYPE = 24
        const val NAWS = 31
    }
}
