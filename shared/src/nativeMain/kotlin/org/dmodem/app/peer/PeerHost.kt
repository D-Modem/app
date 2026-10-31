// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.peer

import org.dmodem.app.jobs.LogOut

/**
 * The line to another phone over Wi-Fi as the iOS app's Swift has it:
 * [PeerLine] on [PosixTcpTransport], made from plain values (Swift has
 * none of Kotlin's default arguments, nor its flows: the state as text and
 * flags, asked for when wanted).
 */
class PeerHost(
    /** the port calls come in on (0: the system's, for the tests) */
    port: Int,
    /** the phone ATD calls when its number isn't an address (host[:port]) */
    private val callTo: String,
    /** the PIN between the two (empty: none) */
    pin: String,
    /** to the engine: RING, CANCEL, ANSWERED, FAILED <code>, DROPPED (a line each) */
    events: LogOut,
    log: LogOut,
    ringMs: Long = 3000,
) {
    val transport = PosixTcpTransport(port)
    val line = PeerLine(
        transport,
        events = { e -> events.log(e) },
        target = { dial -> PeerLine.wifiTarget(dial, callTo) },
        log = { l -> log.log(l) },
        ringMs = ringMs,
        pin = pin,
    )

    /** the line started, taking calls if [listen]: null, or why it can't take them (it can still call) */
    fun start(listen: Boolean): String? = try {
        line.start(listen)
        null
    } catch (e: Exception) {
        line.start(false)
        "can't take calls: ${e.message ?: e}"
    }

    /** the line stopped */
    fun stop() = line.stop()

    /** the modem off hook: its line (the engine's socket), dialling [dial] if [caller] */
    fun lineStarted(caller: Boolean, dial: String, fd: Int) = line.lineStarted(caller, dial, FdDuplex(fd, "modem"))

    /** the port it takes calls on (for Bonjour) */
    val localPort: Int get() = transport.localPort

    /** what the line is doing, for the user (the Android app's words) */
    val stateText: String
        get() = when (val st = line.state.value) {
            PeerLine.State.Off -> "Starts with the modem"
            is PeerLine.State.Ready -> if (st.listening) "Waiting for the other phone's calls" else "Ready to call"
            is PeerLine.State.Ringing -> "Ringing: ${st.from} calls"
            is PeerLine.State.Calling -> "Calling ${st.to}…"
            is PeerLine.State.Connected -> "On a call with ${st.with}"
        }

    val connected: Boolean get() = line.state.value is PeerLine.State.Connected

    /** what went wrong last (a call that failed), or null */
    val lastError: String? get() = line.lastError.value
}
