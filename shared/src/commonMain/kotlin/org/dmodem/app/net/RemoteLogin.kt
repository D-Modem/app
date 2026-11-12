// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.net

/**
 * A remote login's protocol on a TCP connection someone else makes:
 * Telnet (RFC 854) or rlogin (RFC 1282). It does no I/O: hand it what the
 * socket read ([feed]), write what [output] gives, show what [screen]
 * gives, and pass on what is typed ([type]).
 */
interface LoginCodec {
    /** the connection made: rlogin's handshake, Telnet's first offers */
    fun connected()
    /** octets the socket read */
    fun feed(data: ByteArray, length: Int = data.size)
    /** an urgent (out-of-band) octet the socket read: rlogin's control byte */
    fun urgent(b: Int) {}
    /** octets typed */
    fun type(data: ByteArray, length: Int = data.size)
    /** the screen's new size, told to the server */
    fun resize(cols: Int, rows: Int)
    /** the octets for the terminal, taken */
    fun screen(): ByteArray
    /** the octets for the socket, taken */
    fun output(): ByteArray
}

private class Octets {
    private var b = ByteArray(256)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == b.size) b = b.copyOf(b.size * 2)
        b[size++] = v.toByte()
    }

    fun add(v: ByteArray, n: Int = v.size) {
        for (i in 0 until n) add(v[i].toInt() and 0xff)
    }

    fun take(): ByteArray = b.copyOf(size).also { size = 0 }
    fun clear() { size = 0 }
    fun bytes(): ByteArray = b.copyOf(size)
}

/**
 * Telnet: the server's options answered (BINARY, ECHO and
 * SUPPRESS-GO-AHEAD taken; TERMINAL-TYPE and NAWS offered; the rest
 * refused) once per change, so that no two ends loop; IAC doubled both
 * ways; a typed CR as CR NUL outside binary mode; the window's size
 * (NAWS) sent as it changes.
 */
class TelnetCodec(
    /** what TERMINAL-TYPE says */
    private val termType: String = "XTERM-256COLOR",
    private var cols: Int = 80,
    private var rows: Int = 24,
) : LoginCodec {
    private val scr = Octets()
    private val out = Octets()
    private val sb = Octets()
    private val remote = BooleanArray(256)
    private val local = BooleanArray(256)
    private var naws = false
    private var st = 0
    private var verb = 0
    private var lastCr = false

    override fun connected() {
        put(IAC, WILL, TTYPE)
        put(IAC, WILL, NAWS)
        put(IAC, DO, SGA)
        // (asked: the server's yes is not answered again)
        local[TTYPE] = true
        local[NAWS] = true
        remote[SGA] = true
    }

    private fun put(vararg v: Int) = v.forEach { out.add(it) }

    private fun sendNaws() {
        put(IAC, SB, NAWS)
        for (v in intArrayOf(cols, rows)) {
            val c = v.coerceIn(0, 65535)
            for (b in intArrayOf(c shr 8, c and 0xff)) {
                out.add(b)
                if (b == IAC) out.add(IAC)
            }
        }
        put(IAC, SE)
    }

    private fun option(verb: Int, opt: Int) {
        when (verb) {
            WILL -> {
                val ok = opt == BINARY || opt == ECHO || opt == SGA
                if (ok != remote[opt] || !ok) {
                    remote[opt] = ok
                    put(IAC, if (ok) DO else DONT, opt)
                }
            }
            WONT -> if (remote[opt]) {
                remote[opt] = false
                put(IAC, DONT, opt)
            }
            DO -> {
                val ok = opt == BINARY || opt == SGA || opt == TTYPE || opt == NAWS
                if (ok != local[opt] || !ok) {
                    local[opt] = ok
                    put(IAC, if (ok) WILL else WONT, opt)
                }
                if (ok && opt == NAWS) {
                    naws = true
                    sendNaws()
                }
            }
            DONT -> {
                if (opt == NAWS) naws = false
                if (local[opt]) {
                    local[opt] = false
                    put(IAC, WONT, opt)
                }
            }
        }
    }

    private fun subnegotiation() {
        val b = sb.bytes()
        if (b.size >= 2 && (b[0].toInt() and 0xff) == TTYPE && b[1].toInt() == 1) {
            put(IAC, SB, TTYPE, 0)
            termType.forEach { out.add(it.code and 0xff) }
            put(IAC, SE)
        }
    }

    override fun feed(data: ByteArray, length: Int) {
        for (i in 0 until length) {
            val b = data[i].toInt() and 0xff
            when (st) {
                S_DATA -> when {
                    b == IAC -> st = S_IAC
                    // (CR NUL is a CR: the NUL goes)
                    b == 0 && lastCr && !remote[BINARY] -> {}
                    else -> scr.add(b)
                }
                S_IAC -> when (b) {
                    IAC -> { scr.add(IAC); st = S_DATA }
                    WILL, WONT, DO, DONT -> { verb = b; st = S_OPT }
                    SB -> { sb.clear(); st = S_SB }
                    else -> st = S_DATA
                }
                S_OPT -> { option(verb, b); st = S_DATA }
                S_SB -> if (b == IAC) st = S_SB_IAC else if (sb.size < 1024) sb.add(b)
                S_SB_IAC -> when (b) {
                    SE -> { subnegotiation(); st = S_DATA }
                    else -> { if (b == IAC && sb.size < 1024) sb.add(IAC); st = S_SB }
                }
            }
            lastCr = st == S_DATA && b == '\r'.code
        }
    }

    override fun type(data: ByteArray, length: Int) {
        for (i in 0 until length) {
            val b = data[i].toInt() and 0xff
            out.add(b)
            if (b == IAC) out.add(IAC)
            if (b == '\r'.code && !local[BINARY] && (i + 1 >= length || data[i + 1].toInt() != '\n'.code)) out.add(0)
        }
    }

    override fun resize(cols: Int, rows: Int) {
        if (cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        if (naws) sendNaws()
    }

    override fun screen() = scr.take()
    override fun output() = out.take()

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
        private const val S_DATA = 0
        private const val S_IAC = 1
        private const val S_OPT = 2
        private const val S_SB = 3
        private const val S_SB_IAC = 4
    }
}

/**
 * rlogin: the handshake (NUL, the user as both the local and the remote
 * name, "term/speed"), the server's NUL that answers it taken off the
 * stream, and the window's size ("\377\377ss" and four 16-bit numbers)
 * sent once the server has asked with its urgent byte 0x80.
 *
 * Where the socket can't read urgent data apart (Java's, Apple's
 * Network), [inlineUrgent]: the urgent byte arrives in the stream, and a
 * 0x80 straight after the server's NUL is taken as that request.
 */
class RloginCodec(
    private val user: String,
    private val term: String = "xterm",
    private var cols: Int = 80,
    private var rows: Int = 24,
    private val inlineUrgent: Boolean = false,
) : LoginCodec {
    private val scr = Octets()
    private val out = Octets()
    private var ready = false
    private var sawData = false
    private var wantSize = false

    override fun connected() {
        out.add(0)
        repeat(2) {
            out.add(user.encodeToByteArray())
            out.add(0)
        }
        out.add("$term/38400".encodeToByteArray())
        out.add(0)
    }

    private fun window() {
        out.add("ÿÿss".map { it.code.toByte() }.toByteArray())
        for (v in intArrayOf(rows, cols, cols * 8, rows * 16)) {
            val c = v.coerceIn(0, 65535)
            out.add(c shr 8)
            out.add(c and 0xff)
        }
    }

    override fun feed(data: ByteArray, length: Int) {
        var i = 0
        if (!ready && length > 0) {
            ready = true
            if (data[0].toInt() == 0) i = 1
        }
        while (i < length) {
            val b = data[i++].toInt() and 0xff
            if (inlineUrgent && !sawData && b == 0x80) {
                urgent(b)
                continue
            }
            sawData = true
            scr.add(b)
        }
    }

    override fun urgent(b: Int) {
        if (b and 0x80 != 0) {
            wantSize = true
            window()
        }
        if (b and 0x02 != 0) scr.clear()
    }

    override fun type(data: ByteArray, length: Int) = out.add(data, length)

    override fun resize(cols: Int, rows: Int) {
        if (cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        if (wantSize) window()
    }

    override fun screen() = scr.take()
    override fun output() = out.take()
}

/**
 * A [LoginCodec] for Swift: octets as Latin-1 strings, one character
 * each (as TerminalBridge has them). [kind] is "telnet" or "rlogin".
 */
class LoginBridge(kind: String, user: String, term: String, cols: Int, rows: Int, inlineUrgent: Boolean) {
    private val codec: LoginCodec =
        if (kind == "rlogin") RloginCodec(user, term, cols, rows, inlineUrgent) else TelnetCodec(term, cols, rows)

    fun connected() = codec.connected()
    fun feed(latin1: String) = codec.feed(octets(latin1))
    fun urgent(b: Int) = codec.urgent(b)
    fun type(latin1: String) = codec.type(octets(latin1))
    fun resize(cols: Int, rows: Int) = codec.resize(cols, rows)
    fun screen(): String = latin1(codec.screen())
    fun output(): String = latin1(codec.output())

    private fun octets(s: String) = ByteArray(s.length) { s[it].code.toByte() }
    private fun latin1(b: ByteArray): String {
        val sb = StringBuilder(b.size)
        for (x in b) sb.append((x.toInt() and 0xff).toChar())
        return sb.toString()
    }

    companion object {
        /** the protocol's port: 22, 23, 513 */
        fun defaultPort(kind: String): Int = when (kind) {
            "telnet" -> 23
            "rlogin" -> 513
            else -> 22
        }
    }
}
