// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import com.neodesktop.dmodem.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What a serial port's modem lines say: the modem there (DSR), off hook, a carrier (DCD), ringing (RI). */
data class LineSignals(
    val ready: Boolean = false,
    val offhook: Boolean = false,
    val carrier: Boolean = false,
    val ringing: Boolean = false,
)

/** a client on a bridge, for the UI */
data class BridgeClient(val via: String, val name: String, val since: Long = System.currentTimeMillis())

/** a connection a client came on: its streams, and how to end it */
class Port(val via: String, val name: String, val input: InputStream, val output: OutputStream, val close: () -> Unit)

/**
 * The modem's DTE handed to one client at a time, as a serial cable
 * would: what the modem sends goes to the client, what the client sends
 * to the modem (through [Telnet] when the connection speaks it). The
 * client going (or dropping DTR, with RFC 2217) is DTR dropped: the call
 * hung up, if [hangUpOnClose].
 */
class SerialBridge(
    private val dte: TerminalLink,
    private val signals: StateFlow<LineSignals>,
    /** the call hung up (the modem online or off hook): blocks for the guard times */
    private val hangUp: () -> Unit,
    @Volatile var hangUpOnClose: Boolean = true,
) {
    /**
     * clients given the internet instead of the modem: each its own PPP
     * host ([PppHost]: the phone as the ISP, no call), when set
     */
    @Volatile var host: (() -> com.neodesktop.dmodem.ppp.PppHost)? = null
    @Volatile private var currentHost: com.neodesktop.dmodem.ppp.PppHost? = null

    /** the lines the client sees now: the modem's, or its PPP host's (carrier: its link up) */
    fun lines(): LineSignals = currentHost?.let { h -> LineSignals(ready = true, offhook = h.carrier, carrier = h.carrier) } ?: signals.value

    private val _client = MutableStateFlow<BridgeClient?>(null)
    /** who has the modem now, through which bridge */
    val client: StateFlow<BridgeClient?> = _client.asStateFlow()
    private val lock = Any()
    private var current: Port? = null

    /** another client has it: [p] turned away (true: it got the modem) */
    fun tryClaim(p: Port): Boolean = synchronized(lock) {
        if (current != null) return false
        current = p
        _client.value = BridgeClient(p.via, p.name)
        true
    }

    /** the client off (its connection closed: the other side sees it end) */
    fun kick() {
        synchronized(lock) { current }?.let { runCatching { it.close() } }
    }

    /**
     * [p] served until it ends (claimed first with [tryClaim]); [telnet]: it
     * speaks Telnet (and maybe RFC 2217)
     */
    fun serve(p: Port, telnet: Telnet?) {
        // the modem's DTE, or the client's own internet host
        val h = host?.invoke()
        currentHost = h
        val dte: TerminalLink = h ?: this.dte
        val out = LinkedBlockingQueue<ByteArray>()
        val listener = TerminalLink.Listener { data, n ->
            val b = data.copyOf(n)
            out.put(telnet?.encode(b) ?: b)
        }
        val on = java.util.concurrent.atomic.AtomicBoolean(true)
        val writer = Thread({
            try {
                while (on.get()) {
                    val b = out.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    p.output.write(b)
                    // (what else is waiting, in the same write)
                    while (true) p.output.write(out.poll() ?: break)
                    p.output.flush()
                }
            } catch (_: IOException) {
                runCatching { p.close() }
            }
        }, "bridge-out-${p.via}").apply { isDaemon = true; start() }

        // RFC 2217: the modem's lines as they change
        val watcher = telnet?.let { t ->
            Thread({
                while (on.get()) {
                    // (null unless they changed, or the client has just asked for RFC 2217)
                    t.modemState(lines())?.let { out.put(it) }
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }, "bridge-lines-${p.via}").apply { isDaemon = true; start() }
        }
        telnet?.onDtr = { on -> if (!on) dropDtr() }
        telnet?.reply = { b -> out.put(b) }

        dte.addListener(listener)
        try {
            telnet?.start()?.let { out.put(it) }
            val buf = ByteArray(4096)
            while (true) {
                val n = try { p.input.read(buf) } catch (_: IOException) { -1 }
                if (n < 0) break
                if (n == 0) continue
                val data = telnet?.decode(buf, n) ?: buf.copyOf(n)
                if (data.isNotEmpty()) dte.write(data)
            }
        } finally {
            dte.removeListener(listener)
            on.set(false)
            writer.join(1000)
            watcher?.interrupt()
            runCatching { p.close() }
            synchronized(lock) {
                if (current === p) {
                    current = null
                    _client.value = null
                }
            }
            if (h != null) {
                // (its link over with it, whatever the setting)
                currentHost = null
                h.close()
            } else if (hangUpOnClose) {
                dropDtr()
            }
        }
    }

    /** DTR off: the call over (as AT&D2 has it) */
    fun dropDtr() {
        currentHost?.let { it.hangUp(); return }
        val s = signals.value
        if (s.offhook || s.carrier) Thread({ hangUp() }, "bridge-hangup").apply { isDaemon = true; start() }
    }
}

/**
 * The server side of Telnet for a serial port: binary both ways, the
 * echo the modem's (WILL ECHO, so the client doesn't), and RFC 2217's
 * COM-PORT-OPTION: the speed and the character format kept (and
 * answered: a modem on SIP has no speed of its own), DTR dropped as a
 * hang-up, the modem's lines notified (DCD, RI, DSR, CTS).
 */
class Telnet {
    /** DTR set by the client (RFC 2217 SET-CONTROL 8/9) */
    var onDtr: (Boolean) -> Unit = {}
    /** answers to the client (negotiation, RFC 2217's replies) */
    var reply: (ByteArray) -> Unit = {}

    var baud = 115_200
        private set
    var dataSize = 8
        private set
    var parity = 1
        private set
    var stopSize = 1
        private set
    var dtr = true
        private set
    /** the client said it does RFC 2217 */
    var comPort = false
        private set
    private var modemMask = 0xff
    /** the modem's lines as last told */
    @Volatile private var lines = LineSignals()

    private fun lineBits(s: LineSignals) =
        (if (s.carrier) 0x80 else 0) or (if (s.ringing) 0x40 else 0) or (if (s.ready) 0x30 else 0)
    private var lastModem = -1

    private var state = 0
    private var cmd = 0
    private val sb = java.io.ByteArrayOutputStream()
    private var crPending = false
    private val ours = HashSet<Int>()
    private val theirs = HashSet<Int>()

    private var started = false

    /** what's sent first: the options we'll have (once: empty after) */
    fun start(): ByteArray {
        if (started) return ByteArray(0)
        started = true
        ours.addAll(listOf(BINARY, ECHO, SGA))
        theirs.addAll(listOf(BINARY, SGA))
        return byteArrayOf(
            IAC, WILL, BINARY.toByte(), IAC, DO, BINARY.toByte(),
            IAC, WILL, ECHO.toByte(), IAC, WILL, SGA.toByte(), IAC, DO, SGA.toByte(),
        )
    }

    /** the modem's octets, IAC doubled */
    fun encode(b: ByteArray): ByteArray {
        if (b.none { it == IAC }) return b
        val o = java.io.ByteArrayOutputStream(b.size + 8)
        for (x in b) { o.write(x.toInt()); if (x == IAC) o.write(0xff) }
        return o.toByteArray()
    }

    /** the client's octets: the data in them, the commands acted on */
    fun decode(b: ByteArray, n: Int): ByteArray {
        val o = java.io.ByteArrayOutputStream(n)
        for (i in 0 until n) {
            val c = b[i].toInt() and 0xff
            when (state) {
                0 -> when {
                    c == 0xff -> state = 1
                    // (CR NUL, CR LF in NVT: a CR, when the client isn't binary)
                    crPending -> { crPending = false; if (!(c == 0 && BINARY !in theirs)) o.write(c) }
                    c == 13 && BINARY !in theirs -> { crPending = true; o.write(c) }
                    else -> o.write(c)
                }
                1 -> when (c) {
                    0xff -> { o.write(0xff); state = 0 }
                    WILL_I, WONT_I, DO_I, DONT_I -> { cmd = c; state = 2 }
                    SB_I -> { sb.reset(); state = 3 }
                    else -> state = 0 // NOP, BRK, AYT…
                }
                2 -> { negotiate(cmd, c); state = 0 }
                3 -> if (c == 0xff) state = 4 else sb.write(c)
                4 -> when (c) {
                    0xff -> { sb.write(0xff); state = 3 }
                    SE_I -> { subnegotiation(sb.toByteArray()); state = 0 }
                    else -> state = 3
                }
            }
        }
        return o.toByteArray()
    }

    private fun negotiate(cmd: Int, opt: Int) {
        when (cmd) {
            DO_I -> if (opt in OFFER) {
                if (ours.add(opt)) send(WILL, opt)
                if (opt == COM_PORT) comPort = true
            } else send(WONT, opt)
            DONT_I -> if (ours.remove(opt)) send(WONT, opt)
            WILL_I -> if (opt in ACCEPT) {
                if (theirs.add(opt)) send(DO, opt)
                if (opt == COM_PORT) comPort = true
            } else send(DONT, opt)
            WONT_I -> if (theirs.remove(opt)) send(DONT, opt)
        }
    }

    private fun send(verb: Byte, opt: Int) = reply(byteArrayOf(IAC, verb, opt.toByte()))

    private fun subnegotiation(s: ByteArray) {
        if (s.isEmpty() || (s[0].toInt() and 0xff) != COM_PORT || s.size < 2) return
        comPort = true
        val c = s[1].toInt() and 0xff
        val v = s.copyOfRange(2, s.size)
        fun u8() = if (v.isNotEmpty()) v[0].toInt() and 0xff else 0
        when (c) {
            SIGNATURE -> answer(c, "D-Modem".toByteArray())
            SET_BAUDRATE -> {
                if (v.size >= 4) {
                    val b = ((v[0].toInt() and 0xff) shl 24) or ((v[1].toInt() and 0xff) shl 16) or
                        ((v[2].toInt() and 0xff) shl 8) or (v[3].toInt() and 0xff)
                    if (b != 0) baud = b
                }
                answer(c, byteArrayOf((baud ushr 24).toByte(), (baud ushr 16).toByte(), (baud ushr 8).toByte(), baud.toByte()))
            }
            SET_DATASIZE -> { if (u8() in 5..8) dataSize = u8(); answer(c, byteArrayOf(dataSize.toByte())) }
            SET_PARITY -> { if (u8() in 1..5) parity = u8(); answer(c, byteArrayOf(parity.toByte())) }
            SET_STOPSIZE -> { if (u8() in 1..3) stopSize = u8(); answer(c, byteArrayOf(stopSize.toByte())) }
            SET_CONTROL -> {
                val r = when (val x = u8()) {
                    0 -> 1                 // flow control: none
                    4 -> 6                 // BREAK: off
                    7 -> if (dtr) 8 else 9 // DTR
                    8, 9 -> {
                        val on = x == 8
                        if (on != dtr) { dtr = on; onDtr(on) }
                        x
                    }
                    10 -> 11               // RTS: on
                    13 -> 14               // inbound flow control: none
                    else -> x              // (the rest as asked)
                }
                answer(c, byteArrayOf(r.toByte()))
            }
            SET_LINESTATE_MASK -> answer(c, byteArrayOf(u8().toByte()))
            SET_MODEMSTATE_MASK -> { modemMask = u8(); answer(c, byteArrayOf(modemMask.toByte())); lastModem = -1 }
            PURGE_DATA -> answer(c, byteArrayOf(u8().toByte()))
            // (asked for, as pyserial polls them: the lines now; the line's state: nothing waiting to go)
            NOTIFY_MODEMSTATE -> answer(c, byteArrayOf((lineBits(lines) and modemMask).toByte()))
            NOTIFY_LINESTATE -> answer(c, byteArrayOf(0x60))
            FLOWCONTROL_SUSPEND, FLOWCONTROL_RESUME -> {}
        }
    }

    private fun answer(c: Int, v: ByteArray) = reply(subneg(c + 100, v))

    private fun subneg(c: Int, v: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write(0xff); o.write(SB_I); o.write(COM_PORT); o.write(c)
        for (x in v) { o.write(x.toInt() and 0xff); if (x == IAC) o.write(0xff) }
        o.write(0xff); o.write(SE_I)
        return o.toByteArray()
    }

    /** the modem's lines for the client (RFC 2217 NOTIFY-MODEMSTATE), when it does RFC 2217 and they changed */
    fun modemState(s: LineSignals): ByteArray? {
        lines = s
        if (!comPort) return null
        var v = lineBits(s)
        if (lastModem >= 0) {
            if ((v xor lastModem) and 0x80 != 0) v = v or 0x08
            if (lastModem and 0x40 != 0 && v and 0x40 == 0) v = v or 0x04
            if ((v xor lastModem) and 0x20 != 0) v = v or 0x02
            if ((v xor lastModem) and 0x10 != 0) v = v or 0x01
        }
        val same = (v and 0xf0) == (lastModem and 0xf0)
        lastModem = v and 0xf0
        if (same) return null
        return subneg(NOTIFY_MODEMSTATE + 100, byteArrayOf((v and modemMask).toByte()))
    }

    companion object {
        const val IAC: Byte = -1
        const val WILL: Byte = -5
        const val WONT: Byte = -4
        const val DO: Byte = -3
        const val DONT: Byte = -2
        const val WILL_I = 251
        const val WONT_I = 252
        const val DO_I = 253
        const val DONT_I = 254
        const val SB_I = 250
        const val SE_I = 240

        const val BINARY = 0
        const val ECHO = 1
        const val SGA = 3
        const val COM_PORT = 44
        val OFFER = setOf(BINARY, ECHO, SGA, COM_PORT)
        val ACCEPT = setOf(BINARY, SGA, COM_PORT)

        // RFC 2217's commands (client to server; the server's answers 100 more)
        const val SIGNATURE = 0
        const val SET_BAUDRATE = 1
        const val SET_DATASIZE = 2
        const val SET_PARITY = 3
        const val SET_STOPSIZE = 4
        const val SET_CONTROL = 5
        const val NOTIFY_LINESTATE = 6
        const val NOTIFY_MODEMSTATE = 7
        const val FLOWCONTROL_SUSPEND = 8
        const val FLOWCONTROL_RESUME = 9
        const val SET_LINESTATE_MASK = 10
        const val SET_MODEMSTATE_MASK = 11
        const val PURGE_DATA = 12
    }
}
