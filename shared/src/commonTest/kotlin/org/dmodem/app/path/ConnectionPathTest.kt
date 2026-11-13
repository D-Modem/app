// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.path

import org.dmodem.app.path.ConnectionPath.Call
import org.dmodem.app.path.ConnectionPath.Config
import org.dmodem.app.path.ConnectionPath.Kind
import org.dmodem.app.path.ConnectionPath.Line
import org.dmodem.app.path.ConnectionPath.Live
import org.dmodem.app.path.ConnectionPath.Run
import org.dmodem.app.path.ConnectionPath.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The connection path: each line's steps, idle as set up, and in a call as it is. */
class ConnectionPathTest {
    private fun kinds(p: ConnectionPath.Path) = p.steps.map { it.kind }
    private fun states(p: ConnectionPath.Path) = p.steps.map { it.state }

    @Test
    fun sipWithAnAccountRegisters() {
        val p = ConnectionPath.of(Config(Line.SIP, sipUser = "alice", sipDomain = "sip.example.com", listen = true))
        assertEquals(listOf(Kind.TERMINAL, Kind.SOFTMODEM, Kind.SIP, Kind.PHONE_NETWORK), kinds(p))
        assertEquals("Registers as alice@sip.example.com", p.steps[2].detail)
        assertTrue(states(p).all { it == State.READY })
        assertEquals("Ready to dial", p.headline)
        assertEquals(listOf("Program", "Modem", "Line", "Reaches"), p.steps.map { it.role })
    }

    @Test
    fun sipWithNoAccountNeedsSettingUp() {
        val p = ConnectionPath.of(Config(Line.SIP))
        assertEquals(State.SETUP, p.steps[2].state)
        assertEquals(State.SETUP, p.link(1))
        assertEquals("Not ready: SIP (no account set up)", p.headline)
    }

    @Test
    fun anonymousSipReachesSipAddresses() {
        val p = ConnectionPath.of(Config(Line.SIP, sipAnonymous = true))
        assertEquals(Kind.SIP_ADDRESS, p.steps[3].kind)
        assertEquals(State.READY, p.steps[2].state)
        assertTrue(p.steps[2].detail.startsWith("Anonymous"))
    }

    @Test
    fun peersNeedPairingAndChoosing() {
        val none = ConnectionPath.of(Config(Line.BLUETOOTH))
        assertEquals(listOf(Kind.TERMINAL, Kind.SOFTMODEM, Kind.BLUETOOTH, Kind.PHONE), kinds(none))
        assertEquals("Pair and choose a phone", none.steps[2].detail)
        assertEquals(State.SETUP, none.steps[3].state)
        val chosen = ConnectionPath.of(Config(Line.WIFI, peerName = "Pixel", peerAddress = "192.168.1.5"))
        assertEquals("Pixel", chosen.steps[3].detail)
        assertEquals(State.READY, chosen.steps[3].state)
        // (taking the other phone's calls needs none chosen)
        assertEquals(State.READY, ConnectionPath.of(Config(Line.WIFI, listen = true)).steps[3].state)
    }

    @Test
    fun aModemOfItsOwnIsReachedByItsAdapter() {
        val p = ConnectionPath.of(Config(Line.SERIAL_MODEM, serialBaud = 57600, usbDevice = "FT232R"))
        assertEquals(listOf(Kind.TERMINAL, Kind.SERIAL, Kind.MODEM, Kind.PHONE_LINE), kinds(p))
        assertEquals("FT232R, 57600 bit/s, RTS/CTS", p.steps[1].detail)
        assertEquals(listOf("Program", "Adapter", "Modem", "Line"), p.steps.map { it.role })
        val unplugged = ConnectionPath.of(Config(Line.SERIAL_MODEM, usbDevice = ""))
        assertEquals(State.SETUP, unplugged.steps[1].state)
        val bt = ConnectionPath.of(Config(Line.BT_MODEM, btModemName = "Courier", btModemAddress = "00:11:22:33:44:55"))
        assertEquals(listOf(Kind.TERMINAL, Kind.BLUETOOTH, Kind.MODEM, Kind.PHONE_LINE), kinds(bt))
        assertEquals("Courier", bt.steps[2].detail)
    }

    @Test
    fun noLineIsOff() {
        val p = ConnectionPath.of(Config(Line.NONE))
        assertEquals(State.OFF, p.steps[2].state)
        assertEquals(State.OFF, p.link(1))
        assertEquals(State.READY, p.link(0))
    }

    @Test
    fun whatSharesTheModemIsOnTheTerminal() {
        val p = ConnectionPath.of(Config(Line.SIP, sipAnonymous = true, isp = true, dialIn = true, bridges = listOf("TCP")))
        assertEquals("Also dial-up internet, dial-in, TCP bridge", p.steps[0].detail)
    }

    @Test
    fun aCallLightsThePath() {
        val c = Config(Line.SIP, sipUser = "alice", sipDomain = "example.com")
        val online = ConnectionPath.of(c, Live(Run.RUNNING, Call.ONLINE, rate = "48000/26400 bit/s"))
        assertTrue(states(online).all { it == State.ACTIVE })
        assertEquals("Online at 48000/26400 bit/s", online.headline)
        assertEquals("48000/26400 bit/s", online.steps[1].detail)
        val dialling = ConnectionPath.of(c, Live(Run.RUNNING, Call.CONNECTING))
        assertTrue(states(dialling).all { it == State.WORKING })
        val internet = ConnectionPath.of(c, Live(Run.RUNNING, Call.ONLINE, internet = true))
        assertEquals(Kind.PPP, internet.steps[0].kind)
        // (the modem starting: its step works, the rest as set up)
        val starting = ConnectionPath.of(c, Live(Run.STARTING))
        assertEquals(listOf(State.READY, State.WORKING, State.READY, State.READY), states(starting))
    }
}
