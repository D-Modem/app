// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ppp

import org.dmodem.app.ppp.Hdlc
import org.dmodem.app.ppp.Ppp
import org.dmodem.app.android.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone as a dial-up ISP for a computer on a serial line, no call
 * made: to the computer a modem (AT commands answered OK, ATD answered
 * CONNECT), Windows' direct cable connection's CLIENT answered
 * CLIENTSERVER, or PPP straight away; then a PPP server ([Ppp] in its
 * server role) giving it [peer] and the DNS at [local], its traffic on
 * the phone's own network ([NatStack]). The PPP link over, NO CARRIER and
 * commands again.
 *
 * A [TerminalLink], as the modem's DTE is: what the computer sends is
 * written to it, what it says goes to its listeners.
 */
class PppHost(
    private val dnsServers: () -> List<InetAddress>,
    private val connect: (InetSocketAddress) -> Socket = { a -> Socket().apply { connect(a, 15_000) } },
    private val log: (String) -> Unit = {},
    val local: Inet4Address = InetAddress.getByName("10.64.64.1") as Inet4Address,
    val peer: Inet4Address = InetAddress.getByName("10.64.64.2") as Inet4Address,
    /** what CONNECT says (the speed the computer's dialer shows) */
    private val connectSpeed: Int = 115200,
    private val dnsPort: Int = 53,
) : TerminalLink {
    private val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()
    private val _state = MutableStateFlow<TerminalLink.State>(TerminalLink.State.Open)
    override val state: StateFlow<TerminalLink.State> = _state.asStateFlow()

    @Volatile private var ppp: Ppp? = null
    @Volatile private var nat: NatStack? = null
    private val line = StringBuilder()
    private var echo = true
    private var verbose = true
    private val lock = Any()

    /** the PPP link up (the computer online: its DCD) */
    val carrier: Boolean get() = ppp != null

    private val _online = MutableStateFlow(false)
    /** whether the computer has its address (the link up) */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** [l] hears what the host says to the computer */
    override fun addListener(l: TerminalLink.Listener) { listeners.add(l) }
    /** [l] no longer hears the host */
    override fun removeListener(l: TerminalLink.Listener) { listeners.remove(l) }

    /** [b] to the computer (every listener) */
    private fun say(b: ByteArray) {
        for (l in listeners) l.onData(b, b.size)
    }

    /** [s] (ISO-8859-1) to the computer */
    private fun say(s: String) = say(s.toByteArray(Charsets.ISO_8859_1))

    /** result code [s] to the computer, framed as V1 or V0 says */
    private fun result(s: String) = say(if (verbose) "\r\n$s\r\n" else "$s\r")

    /** the computer's octets */
    override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        synchronized(lock) {
            ppp?.let { it.input(data, offset, length); return true }
            for (i in offset until offset + length) {
                val c = data[i].toInt() and 0xff
                if (c == Hdlc.FLAG) {
                    // PPP with no dial first (a direct link): it starts now, with this frame
                    log("PPP from the computer: the link starts")
                    startPpp()
                    ppp?.input(data, i, offset + length - i)
                    return true
                }
                if (echo && c != '\n'.code) say(byteArrayOf(c.toByte()))
                when (c) {
                    '\r'.code -> {
                        val l = line.toString().trim()
                        line.setLength(0)
                        command(l)
                        if (ppp != null) {
                            // (what came after the dial's CR is PPP's)
                            if (i + 1 < offset + length) ppp?.input(data, i + 1, offset + length - i - 1)
                            return true
                        }
                    }
                    '\n'.code -> {}
                    8, 127 -> if (line.isNotEmpty()) line.setLength(line.length - 1)
                    else -> if (line.length < 256) line.append(c.toChar())
                }
            }
        }
        return true
    }

    /** the computer's command line [l]: E, V, Z and &F kept; a dial or ATA brings up PPP, CLIENT too (a direct cable connection); I answered; else OK */
    private fun command(l: String) {
        val u = l.uppercase()
        when {
            u.isEmpty() -> {}
            // Windows' direct cable connection
            u == "CLIENT" -> { say("CLIENTSERVER"); startPpp() }
            !u.startsWith("AT") -> {}
            else -> {
                val cmd = u.substring(2).replace(" ", "")
                if (cmd.contains("E0")) echo = false
                if (cmd.contains("E1")) echo = true
                if (cmd.contains("V0")) verbose = false
                if (cmd.contains("V1")) verbose = true
                if (cmd.startsWith("Z") || cmd.startsWith("&F")) { echo = true; verbose = true }
                when {
                    // a dial: D not &D (DTR's setting), not in a + command
                    !cmd.contains('+') && Regex("(^|[^&A-Z])D").containsMatchIn(cmd) -> {
                        log("the computer dials: ${l.substring(2).trim()}")
                        result("CONNECT $connectSpeed")
                        startPpp()
                    }
                    cmd.startsWith("I") -> { result("D-Modem internet host"); result("OK") }
                    cmd.startsWith("A") && cmd.length == 1 -> { result("CONNECT $connectSpeed"); startPpp() }
                    else -> result("OK")
                }
            }
        }
    }

    /** the PPP server and its NAT started, if not already */
    private fun startPpp() {
        if (ppp != null) return
        lateinit var n: NatStack
        val p = Ppp(
            send = { b -> say(b) },
            events = object : Ppp.Events {
                /** the computer has its address: the NAT's MSS from the link's MTU, online */
                override fun up(config: Ppp.IpConfig) {
                    n.mss = config.mtu - 40
                    log("the computer is online: ${peer.hostAddress}")
                    _online.value = true
                }
                /** the computer's IP packet to the NAT */
                override fun ip(packet: ByteArray) = n.input(packet)
                /** the link down: the NAT closed, offline, NO CARRIER to the computer */
                override fun down(reason: String) {
                    log("the computer's link down: $reason")
                    synchronized(lock) {
                        n.close()
                        if (ppp != null) {
                            ppp = null
                            nat = null
                            _online.value = false
                            result("NO CARRIER")
                        }
                    }
                }
                /** PPP's [line] to the host's log */
                override fun log(line: String) = this@PppHost.log("ppp: $line")
            },
            server = Ppp.Server(local, peer, local),
        )
        n = NatStack({ pk -> p.sendIp(pk) }, local, dnsServers, connect, log, dnsPort)
        ppp = p
        nat = n
        p.start()
    }

    /** the computer's DTR dropped (or the line gone): the link over */
    fun hangUp() {
        ppp?.stop("the computer hung up")
    }

    /** the link hung up, the NAT closed, the link closed */
    override fun close() {
        hangUp()
        nat?.close()
        _state.value = TerminalLink.State.Closed(null)
    }
}
