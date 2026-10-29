// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.jobs

import com.neodesktop.dmodem.fax.Class1
import com.neodesktop.dmodem.io.IOException
import com.neodesktop.dmodem.io.Latch
import com.neodesktop.dmodem.io.background
import com.neodesktop.dmodem.io.dotted
import com.neodesktop.dmodem.io.monotonicMs
import com.neodesktop.dmodem.io.sleepMs
import com.neodesktop.dmodem.ppp.ChatScript
import com.neodesktop.dmodem.ppp.Ppp
import com.neodesktop.dmodem.transfer.TransferCancelled
import kotlin.concurrent.Volatile

/** where the link's packets go on the phone (iOS: its packet tunnel) */
interface PacketTunnel {
    /** a packet from the link */
    fun packet(p: ByteArray)

    /** the link up with [address] and [dns] (dotted): false when it can't take its packets */
    fun up(address: String, dns: List<String>, mtu: Int): Boolean

    /** the link over (whether or not it came up) */
    fun close()
}

/**
 * Dial-up internet on the modem (the Android app's Internet, for iOS):
 * the ISP dialled, its login script if it has one, PPP; once it has an
 * address the phone's packets through [PacketTunnel]. [disconnect], the
 * line dropped ([lineDropped]) or PPP down, it all comes down and the call
 * hangs up.
 */
class DialUpJob(out: DteOut, log: LogOut, changed: Changed) : DteJob(out, log, changed) {
    data class State(
        /** what it's doing; "" when connected */
        val phase: String,
        /** the link's address and DNS servers, once up */
        val address: String = "",
        val dns: List<String> = emptyList(),
        /** the CONNECT the modem said */
        val connect: String = "",
        /** when it came up (the monotonic clock's ms) */
        val since: Long = 0,
        val bytesIn: Long = 0,
        val bytesOut: Long = 0,
        /** why it ended (null while it goes) */
        val ended: String? = null,
    ) {
        val connected: Boolean get() = address.isNotEmpty() && ended == null
        val running: Boolean get() = ended == null
    }

    @Volatile var state = State("Starting")
        private set
    @Volatile private var ppp: Ppp? = null
    @Volatile private var stopping = false

    /** the state changed by [f], and said so */
    private fun set(f: (State) -> State) {
        state = f(state)
        changed()
    }

    /** the modem's octets: to PPP once it runs, else to the AT commands' reader */
    override fun feed(latin1: String) {
        // (the AT commands' answers until CONNECT, then PPP's)
        val p = ppp
        if (p != null) p.input(Latin1.bytes(latin1)) else super.feed(latin1)
    }

    /** milliseconds it has been up (0 while it isn't) */
    val upMs: Long get() = state.since.let { if (it == 0L || !state.connected) 0 else monotonicMs() - it }

    /**
     * [number] dialled, [script] run (if not blank) with [user] and
     * [password], PPP brought up; [initCommands] (";" or lines between)
     * first
     */
    fun connect(number: String, user: String, password: String, script: String, initCommands: String, tunnel: PacketTunnel) {
        state = State("Dialling $number")
        changed()
        background("internet") {
            var why: String? = null
            var online = false
            try {
                val c = Class1(io) { log("internet: $it") }
                // (data mode; X3: a SIP line has no dial tone)
                if (c.command("E0V1X3+FCLASS=0") != "OK") throw IOException("the modem didn't answer")
                for (cmd in initCommands.split(Regex("[\\r\\n;]+")).map { it.trim() }.filter { it.isNotEmpty() }) {
                    c.command(cmd.removePrefix("AT").removePrefix("at"))
                }
                val r = c.command("D$number", 90_000)
                if (r == null || !r.startsWith("CONNECT")) throw IOException(dialResult(r, "no modem answered"))
                online = true
                set { it.copy(phase = "Logging in", connect = r) }
                if (script.isNotBlank()) {
                    set { it.copy(phase = "Running the login script") }
                    ChatScript(script, user, password).run(io) { log("script: $it") }
                    set { it.copy(phase = "Logging in") }
                }
                val done = Latch()
                val p = Ppp(
                    send = { b -> io.write(b) },
                    user = user,
                    password = password,
                    events = object : Ppp.Events {
                        /** PPP has an address: the tunnel brought up with it (PPP stopped if it can't be) */
                        override fun up(config: Ppp.IpConfig) {
                            val address = config.address.dotted
                            val dns = config.dns.map { it.dotted }
                            val ok = try { tunnel.up(address, dns, config.mtu) } catch (_: Exception) { false }
                            if (!ok) {
                                why = "the VPN couldn't start"
                                ppp?.stop(why!!)
                                return
                            }
                            set { it.copy(phase = "", address = address, dns = dns, since = monotonicMs()) }
                        }

                        /** a packet from the link, to the phone's tunnel */
                        override fun ip(packet: ByteArray) = tunnel.packet(packet)

                        /** PPP down: its [reason] kept (unless there was one already) and the wait ended */
                        override fun down(reason: String) {
                            if (why == null) why = reason
                            done.countDown()
                        }

                        /** PPP's [line], to the job's log */
                        override fun log(line: String) = this@DialUpJob.log("ppp: $line")
                    },
                )
                // (the octets that came after CONNECT, before PPP was there to take them)
                ppp = p
                val early = io.leftover()
                p.start()
                if (early.isNotEmpty()) p.input(early)
                if (stopping) p.stop("disconnected")
                while (!done.await(1000)) {
                    set { it.copy(bytesIn = p.bytesIn, bytesOut = p.bytesOut) }
                }
            } catch (e: TransferCancelled) {
                why = "disconnected"
            } catch (e: IOException) {
                why = e.message ?: e.toString()
            } catch (e: RuntimeException) {
                why = e.toString()
            } finally {
                ppp = null
                runCatching { tunnel.close() }
                // the call hung up: +++ between its guard times, ATH
                if (online) {
                    sleepMs(1200)
                    raw("+++")
                    sleepMs(1200)
                }
                raw("\rATH\r")
                sleepMs(500)
                leftover = Latin1.string(io.leftover())
                set { it.copy(phase = "", ended = why ?: "disconnected") }
            }
        }
    }

    /** a packet from the phone to the link: false when it isn't up */
    fun sendIp(packet: ByteArray): Boolean = ppp?.sendIp(packet) ?: false

    /** the link closed and the call hung up */
    fun disconnect() = stop("disconnected")

    /** the carrier gone (the modem on hook, or stopped): the link down */
    fun lineDropped() = stop("the line dropped")

    /** the link stopped with [why] (once): PPP if it runs, else the dialling cancelled */
    private fun stop(why: String) {
        if (stopping) return
        stopping = true
        val p = ppp
        if (p != null) p.stop(why) else io.cancel(why)
    }
}
