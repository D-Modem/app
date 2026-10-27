// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ppp

import com.neodesktop.dmodem.NativeModem
import com.neodesktop.dmodem.bridge.Bridges
import com.neodesktop.dmodem.fax.Class1
import com.neodesktop.dmodem.fax.Fax
import com.neodesktop.dmodem.modem.ModemState
import com.neodesktop.dmodem.settings.IspSettings
import com.neodesktop.dmodem.terminal.TerminalLink
import com.neodesktop.dmodem.transfer.TransferCancelled
import com.neodesktop.dmodem.transfer.TransferIo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Dial-up internet on the modem: the ISP dialled, PPP on the call
 * ([Ppp]), and once it has an address the phone's traffic through it
 * (a [Tunnel]: the VPN service's). The call has the modem's DTE to itself
 * while it lasts; the carrier lost, or PPP down, it all comes down.
 */
object Internet {
    /** where the link's packets go on the phone: the VPN's interface */
    interface Tunnel {
        /** a packet from the link */
        fun write(packet: ByteArray)
        /** the tunnel let go (the link's down) */
        fun close()
    }

    data class State(
        /** what it's doing; "" when connected */
        val phase: String,
        val config: Ppp.IpConfig? = null,
        /** the CONNECT the modem said ("CONNECT 52000/V92 …") */
        val connect: String = "",
        val since: Long = 0,
        val bytesIn: Long = 0,
        val bytesOut: Long = 0,
        /** why it ended (null while it goes) */
        val ended: String? = null,
    ) {
        val connected: Boolean get() = config != null && ended == null
        val running: Boolean get() = ended == null
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    @Volatile private var ppp: Ppp? = null
    @Volatile private var line: TransferIo? = null
    @Volatile private var tunnel: Tunnel? = null
    @Volatile private var stopping = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watch: Job? = null

    val running: Boolean get() = _state.value?.running == true

    /** the state changed by [f], if there is one */
    private fun set(f: (State) -> State) {
        _state.value = _state.value?.let(f)
    }

    /** why it can't start now, or null */
    fun busy(): String? = when {
        ModemState.run.value != ModemState.Run.RUNNING -> "the modem is off"
        running -> "it's connected already"
        Fax.running -> "a fax call is going on"
        ModemState.status.value.offhook -> "the modem is in a call"
        Bridges.serial.client.value != null -> "the modem is in use by ${Bridges.serial.client.value?.name}"
        else -> null
    }

    /**
     * the ISP dialled and PPP brought up; [openTunnel] gets the link's
     * address and DNS servers (its packets to [sendIp]): null when it
     * can't (the link comes down again). Null when it has started, else
     * why it can't.
     */
    fun connect(isp: IspSettings, openTunnel: (Ppp.IpConfig) -> Tunnel?): String? {
        busy()?.let { return it }
        if (!isp.complete) return "no number for the ISP"
        val hub = ModemState.dte
        lateinit var listener: TerminalLink.Listener
        val io = TransferIo { b -> if (!hub.writeAs(listener, b)) throw IOException("the modem has gone") }
        // (the modem's octets: the AT commands' until CONNECT, then PPP's)
        listener = TerminalLink.Listener { d, n -> ppp?.input(d, 0, n) ?: io.feed(d, 0, n) }
        if (!hub.claim(listener)) return "the modem is in use"
        line = io
        stopping = false
        _state.value = State("Dialling ${isp.number}")
        Thread({
            var why: String? = null
            var online = false
            try {
                val c = Class1(io) { ModemState.log.add("internet: $it") }
                // (data mode; X3: a SIP line has no dial tone)
                if (c.command("E0V1X3+FCLASS=0") != "OK") throw IOException("the modem didn't answer")
                for (cmd in isp.initCommands.split(Regex("[\\r\\n;]+")).map { it.trim() }.filter { it.isNotEmpty() }) {
                    c.command(cmd.removePrefix("AT").removePrefix("at"))
                }
                val r = c.command("D${isp.number}", 90_000)
                if (r == null || !r.startsWith("CONNECT")) throw IOException(dialResult(r))
                online = true
                set { it.copy(phase = "Logging in", connect = r) }
                // the ISP's login script first, if it has one (a text login before PPP)
                if (isp.script.isNotBlank()) {
                    set { it.copy(phase = "Running the login script") }
                    ChatScript(isp.script, isp.user, isp.password).run(io) { ModemState.log.add("script: $it") }
                    set { it.copy(phase = "Logging in") }
                }
                val done = java.util.concurrent.CountDownLatch(1)
                val p = Ppp(
                    send = { b -> if (!hub.writeAs(listener, b)) throw IOException("the modem has gone") },
                    user = isp.user,
                    password = isp.password,
                    events = object : Ppp.Events {
                        /** the link has its address: the tunnel opened on it, or PPP stopped if it can't be */
                        override fun up(config: Ppp.IpConfig) {
                            val t = try { openTunnel(config) } catch (e: Exception) { null }
                            if (t == null) {
                                why = "the VPN couldn't start"
                                ppp?.stop(why!!)
                                return
                            }
                            tunnel = t
                            set { it.copy(phase = "", config = config, since = System.currentTimeMillis()) }
                        }
                        /** a packet from the link to the tunnel */
                        override fun ip(packet: ByteArray) { tunnel?.write(packet) }
                        /** PPP down: [reason] kept (unless there's one already), the call let end */
                        override fun down(reason: String) {
                            if (why == null) why = reason
                            done.countDown()
                        }
                        /** PPP's [line] to the log */
                        override fun log(line: String) { ModemState.log.add("ppp: $line") }
                    },
                )
                // (the octets that came after CONNECT, before PPP was there to take them)
                ppp = p
                val early = io.leftover()
                p.start()
                if (early.isNotEmpty()) p.input(early)
                watchCarrier()
                while (!done.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    set { it.copy(bytesIn = p.bytesIn, bytesOut = p.bytesOut) }
                }
            } catch (e: TransferCancelled) {
                why = "disconnected"
            } catch (e: IOException) {
                why = e.message ?: e.toString()
            } catch (e: RuntimeException) {
                why = e.toString()
            } finally {
                watch?.cancel()
                runCatching { tunnel?.close() }
                tunnel = null
                ppp = null
                // the call hung up: +++ between its guard times, ATH
                if (online) {
                    Thread.sleep(1200)
                    hub.writeAs(listener, "+++".toByteArray())
                    Thread.sleep(1200)
                }
                hub.writeAs(listener, "\rATH\r".toByteArray())
                Thread.sleep(500)
                line = null
                hub.release(listener)
                set { it.copy(phase = "", ended = why ?: "disconnected") }
            }
        }, "internet").apply { isDaemon = true; start() }
        return null
    }

    /** a packet from the phone (the VPN's) to the link */
    fun sendIp(packet: ByteArray): Boolean = ppp?.sendIp(packet) ?: false

    /** the link closed and the call hung up */
    fun disconnect() {
        if (stopping) return
        stopping = true
        val p = ppp
        if (p != null) p.stop("disconnected") else line?.cancel("disconnected")
    }

    /** (for the screenshots: a link's state shown) */
    internal fun show(s: State?) {
        _state.value = s
    }

    /** the last link's state cleared, unless one is going */
    fun dismiss() {
        if (!running) _state.value = null
    }

    /** the carrier gone (the modem back on hook): the link down */
    private fun watchCarrier() {
        watch?.cancel()
        var seen = false
        watch = scope.launch {
            // (the modem switched off: the link with it)
            launch { ModemState.run.collect { r -> if (r != ModemState.Run.RUNNING) ppp?.stop("the modem stopped") } }
            ModemState.status.collect { s ->
                if (s.link == NativeModem.Link.ONLINE) seen = true
                // (once the modem has said it's online: a modem that doesn't say isn't watched)
                if (seen && s.link != NativeModem.Link.ONLINE && ModemState.run.value == ModemState.Run.RUNNING) {
                    // (a moment: the status catches up with the CONNECT)
                    kotlinx.coroutines.delay(3000)
                    if (ModemState.status.value.link != NativeModem.Link.ONLINE) ppp?.stop("the line dropped")
                }
            }
        }
    }

    /** why a dial ended with result [r], in a word for the user */
    private fun dialResult(r: String?) = when (r) {
        null -> "no answer"
        "BUSY" -> "busy"
        "NO ANSWER" -> "no answer"
        "NO CARRIER" -> "no modem answered"
        "NO DIALTONE", "NO DIAL TONE" -> "no line"
        else -> r.lowercase()
    }
}
