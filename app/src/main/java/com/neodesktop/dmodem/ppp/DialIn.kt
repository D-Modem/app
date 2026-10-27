// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ppp

import android.content.Context
import com.neodesktop.dmodem.bridge.Bridges
import com.neodesktop.dmodem.fax.Fax
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.FaxSettingsStore
import com.neodesktop.dmodem.settings.IspSettingsStore
import com.neodesktop.dmodem.terminal.TerminalLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dial-in internet: the phone as an ISP for whoever calls the modem (on
 * SIP, or any line), as [PppHost] is for a computer on a serial bridge.
 * With it on, a call that rings is answered; once it's a data call
 * (CONNECT, after a RING) the modem's DTE is taken, and a PPP server on
 * the call gives the caller [peer], the DNS at [local] and the phone's
 * network through its NAT ([NatStack]). The caller's PPP over (or none
 * started), the call is hung up; the call over, PPP too.
 */
object DialIn {
    data class State(
        val phase: String,
        /** the modem's CONNECT line */
        val connect: String = "",
        /** the caller has its address */
        val online: Boolean = false,
        val since: Long = 0,
        /** why it ended (null while it goes) */
        val ended: String? = null,
    ) {
        val running: Boolean get() = ended == null
    }

    private val _state = MutableStateFlow<State?>(null)
    /** the last dial-in call: going, or how it ended (null: none yet) */
    val state: StateFlow<State?> = _state.asStateFlow()

    /** callers get the internet (the settings' dial-in) */
    @Volatile var enabled = false
    /** answer each call that rings (unless the fax settings answer them already) */
    @Volatile var answers: () -> Boolean = { true }

    /** the phone's DNS servers, its network's sockets: the app's (the tests' own) */
    @Volatile var dnsServers: () -> List<InetAddress> = { emptyList() }
    @Volatile var dnsPort = 53
    @Volatile var connect: (InetSocketAddress) -> Socket = { a -> Socket().apply { connect(a, 15_000) } }

    val local: Inet4Address = InetAddress.getByName("10.64.64.1") as Inet4Address
    val peer: Inet4Address = InetAddress.getByName("10.64.64.2") as Inet4Address

    private const val GUARD_MS = 1200L

    /** a call rang since the modem was last idle: a CONNECT now is a caller's */
    @Volatile private var rang = false
    @Volatile private var answered = false
    @Volatile private var call: Call? = null
    private var watching = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** [s] to the app's log, as dial-in's */
    private fun log(s: String) = ModemState.log.add("dial-in: $s")

    /** the settings watched, the modem's calls too (the app's start) */
    fun init(context: Context) {
        val app = context.applicationContext
        dnsServers = { Bridges.phoneDns(app) }
        answers = {
            // (the fax settings' answering does it, adaptively: fax or data as each call turns out)
            runCatching { runBlocking { FaxSettingsStore(app).current() } }.getOrNull()?.answer != true
        }
        scope.launch { IspSettingsStore(app).settings.collect { enabled = it.dialIn } }
        watch()
    }

    /** the modem's text watched for its calls (without the app's context: the tests') */
    @Synchronized
    fun watch() {
        if (watching) return
        watching = true
        ModemState.dte.addListener(TerminalLink.Listener { data, n -> modemText(data, n) })
        scope.launch {
            ModemState.status.collect { st ->
                if (!st.offhook) {
                    rang = false
                    answered = false
                    call?.gone("the call ended")
                }
            }
        }
    }

    /** whether a call may be taken now: nothing else has the modem */
    private fun free(): Boolean = call == null && !ModemState.dte.claimed && !Internet.running && !Fax.running &&
        Bridges.serial.client.value == null

    /** the modem's line being read (its results come in pieces) */
    private val line = StringBuilder()

    /** the modem's [n] octets of [data], read a line at a time: at a caller's CONNECT, its PPP started on what follows */
    private fun modemText(data: ByteArray, n: Int) {
        if (!enabled) {
            line.setLength(0)
            return
        }
        for (i in 0 until n) {
            val ch = (data[i].toInt() and 0xff).toChar()
            if (ch != '\n') {
                if (ch != '\r' && line.length < 128) line.append(ch)
                continue
            }
            val l = line.toString().trim()
            line.setLength(0)
            if (modemLine(l)) {
                // (what came after the CONNECT's line: the caller's PPP, perhaps)
                start(l, data.copyOfRange(i + 1, n))
                return
            }
        }
    }

    /** a line of the modem's: whether it's a caller's CONNECT to take */
    private fun modemLine(l: String): Boolean {
        when {
            l == "NO CARRIER" -> {
                rang = false
                answered = false
            }
            l == "RING" -> {
                rang = true
                if (!answered && free() && answers()) {
                    answered = true
                    log("a call rings: answered")
                    Thread { ModemState.answer() }.start()
                }
            }
            // (a fax call's CONNECTs are the fax receiver's: it has the modem from its FAX)
            l == "FAX" -> rang = false
            l.startsWith("CONNECT") -> return rang && free()
        }
        return false
    }

    /** the call's PPP server started, the DTE taken (what came after the CONNECT its first) */
    private fun start(connectLine: String, first: ByteArray) {
        val c = Call(connectLine)
        if (!c.begin(first)) return
        log("$connectLine: PPP for the caller")
    }

    /** a call rang (the tests' line has no ring signal: its modems answer at once) */
    internal fun rings() {
        rang = true
    }

    /** the caller's link given up (the app's Disconnect): the call hung up */
    fun disconnect() {
        call?.end("disconnected")
    }

    /** the caller's bytes so far, each way (in, out) */
    fun bytes(): Pair<Long, Long> = call?.ppp?.let { it.bytesIn to it.bytesOut } ?: (0L to 0L)

    /** the last call's state cleared, unless one is going */
    fun dismiss() {
        if (call == null) _state.value = null
    }

    private class Call(val connectLine: String) {
        val over = AtomicBoolean(false)
        lateinit var listener: TerminalLink.Listener
        var ppp: Ppp? = null
        var nat: NatStack? = null

        /** the DTE claimed and the call's PPP server and NAT started ([first] its first input): false if the modem is someone else's */
        fun begin(first: ByteArray): Boolean {
            val hub = ModemState.dte
            listener = TerminalLink.Listener { d, n -> ppp?.input(d, 0, n) }
            if (!hub.claim(listener)) return false
            call = this
            rang = false
            lateinit var n: NatStack
            val p = Ppp(
                send = { b -> hub.writeAs(listener, b) },
                events = object : Ppp.Events {
                    /** the caller online: the NAT's MSS from the link's MTU, the state Online */
                    override fun up(config: Ppp.IpConfig) {
                        n.mss = config.mtu - 40
                        DialIn.log("the caller is online: ${peer.hostAddress}")
                        _state.value = State("Online", connectLine, online = true, since = System.currentTimeMillis())
                    }
                    /** the caller's IP packet to the NAT */
                    override fun ip(packet: ByteArray) = n.input(packet)
                    /** PPP down: the call ended (off PPP's thread) */
                    override fun down(reason: String) {
                        // (off PPP's thread: the hang-up waits out the guard times)
                        Thread { end(reason) }.start()
                    }
                    /** PPP's [line] to the log */
                    override fun log(line: String) = DialIn.log("ppp: $line")
                },
                server = Ppp.Server(local, peer, local),
            )
            n = NatStack({ pk -> p.sendIp(pk) }, local, dnsServers, connect, ::log, dnsPort)
            ppp = p
            nat = n
            _state.value = State("Starting PPP", connectLine)
            p.start()
            if (first.isNotEmpty()) p.input(first, 0, first.size)
            return true
        }

        /** the link over: the call hung up (+++, ATH), the DTE given back */
        fun end(why: String) {
            if (!over.compareAndSet(false, true)) return
            ppp?.stop(why)
            nat?.close()
            val hub = ModemState.dte
            Thread.sleep(GUARD_MS)
            hub.writeAs(listener, "+++".toByteArray())
            Thread.sleep(GUARD_MS)
            hub.writeAs(listener, "ATH\r".toByteArray())
            finish(why)
        }

        /** the call already over (the caller hung up): PPP stopped, the DTE given back */
        fun gone(why: String) {
            if (!over.compareAndSet(false, true)) return
            ppp?.stop(why)
            nat?.close()
            finish(why)
        }

        /** the call's end logged and shown, the DTE released */
        private fun finish(why: String) {
            log("over: $why")
            ModemState.dte.release(listener)
            call = null
            answered = false
            _state.value = (_state.value ?: State("")).copy(phase = "", online = false, ended = why)
        }
    }
}
