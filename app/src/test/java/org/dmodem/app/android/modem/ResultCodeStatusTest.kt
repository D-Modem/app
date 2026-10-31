// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.modem

import org.dmodem.app.android.NativeModem
import org.dmodem.app.android.settings.ModemSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** a Bluetooth modem's status, from what it says */
class ResultCodeStatusTest {
    private val seen = mutableListOf<NativeModem.Status>()
    private val r = ResultCodeStatus { seen += it }

    /** [s] fed to the status as the modem's output */
    private fun say(s: String) = s.toByteArray(Charsets.ISO_8859_1).let { r.feed(it, it.size) }

    /** online from a CONNECT line (with its rate, in pieces), idle from NO CARRIER or BUSY, not from CONNECT mid-line */
    @Test
    fun onlineFromConnectIdleFromItsEnd() {
        say("ATDT5551234\r\r\nOK\r\n")
        assertTrue(seen.isEmpty())
        // (in pieces, as a socket gives them)
        say("\r\nCONN")
        say("ECT 26400/ARQ/V90\r\n")
        assertEquals(NativeModem.Status(NativeModem.Link.ONLINE, true, 26400, 26400), seen.last())
        say("some data, CONNECT in it isn't a line\r\n")
        assertEquals(1, seen.size)
        say("\r\nNO CARRIER\r\n")
        assertEquals(ModemState.IDLE, seen.last())
        say("\r\nCONNECT\r\n")
        assertEquals(NativeModem.Status(NativeModem.Link.ONLINE, true, 0, 0), seen.last())
        say("\r\nBUSY\r\n")
        assertEquals(4, seen.size)
        assertEquals(ModemState.IDLE, seen.last())
    }

    /** a Bluetooth modem line needs no SIP account and is no peer line, complete with its address */
    @Test
    fun theLine() {
        val s = ModemSettings(line = ModemSettings.LINE_BT_MODEM, btModemAddress = "00:11:22:33:44:55")
        assertTrue(s.btModem)
        assertFalse(s.peer)
        assertFalse(s.sip)
        assertTrue(s.complete)
    }
}
