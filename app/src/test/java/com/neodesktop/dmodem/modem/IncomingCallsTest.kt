// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A call ringing in: from the modem's RINGs to answered, given up, ignored. */
class IncomingCallsTest {
    private var clock = 1_000_000L
    private val calls = IncomingCalls { clock }

    /** a RING, split or not, a call ringing; the next one counted, since unchanged; off hook it's over, a RING then no call */
    @Test
    fun ringingThenAnswered() {
        calls.modemText("\r\nRI")
        assertNull(calls.state.value)
        calls.modemText("NG\r\n")
        val c = calls.state.value!!
        assertEquals(null, c.from)
        assertEquals(1, c.rings)
        clock += 6000
        calls.modemText("\r\nRING\r\n")
        assertEquals(2, calls.state.value!!.rings)
        assertEquals(c.since, calls.state.value!!.since)
        // answered (here, or by a computer on a bridge, or the modem's S0): over
        calls.hook(offhook = true)
        assertNull(calls.state.value)
        // (a RING echoed while off hook isn't a call)
        calls.modemText("RING\r\n")
        assertNull(calls.state.value)
    }

    /** the other phone's name, given before the first RING or after it, as the call's caller */
    @Test
    fun theOtherPhoneByName() {
        calls.caller("D-Modem on Pixel 8")
        calls.modemText("RING\r\n")
        assertEquals("D-Modem on Pixel 8", calls.state.value!!.from)
        // the name after the first RING: taken too
        calls.end()
        calls.caller(null)
        calls.modemText("RING\r\n")
        calls.caller("192.168.1.20")
        assertEquals("192.168.1.20", calls.state.value!!.from)
    }

    /** no RING for 9 s: the caller gave up; 7 s still ringing */
    @Test
    fun theCallerGivesUp() {
        calls.modemText("RING\r\n")
        clock += 7000
        calls.tick()
        assertTrue(calls.state.value != null)
        clock += 2000
        calls.tick()
        assertNull(calls.state.value)
    }

    /** ignored, it rings on out of sight; once over, the next call shows; RINGING isn't RING */
    @Test
    fun ignoredUntilItsOver() {
        calls.modemText("RING\r\n")
        calls.ignore()
        assertTrue(calls.state.value!!.ignored)
        // it rings on, out of sight
        clock += 6000
        calls.modemText("RING\r\n")
        assertTrue(calls.state.value!!.ignored)
        // over; the next call shows again
        clock += 9000
        calls.tick()
        calls.modemText("RING\r\n")
        assertTrue(!calls.state.value!!.ignored)
        // (other lines aren't RING)
        calls.end()
        calls.modemText("RINGING\r\nNO CARRIER\r\n")
        assertNull(calls.state.value)
    }

    /** caller ID after the first RING (AT+VCID=1): the name shown, the number under it; the next call starts afresh */
    @Test
    fun callerIdFormatted() {
        calls.modemText("\r\nRING\r\n\r\nDATE = 1030\r\nTIME = 1043\r\nNMBR = +15551234\r\nNAME = Alice Smith\r\n")
        val c = calls.state.value!!
        assertEquals("+15551234", c.number)
        assertEquals("Alice Smith", c.who)
        assertEquals("+15551234", c.detail)
        calls.end()
        calls.modemText("RING\r\n")
        assertNull(calls.state.value!!.who)
    }

    /** a withheld caller (P), a name not known (O): the number alone, or "Private number" */
    @Test
    fun callerIdWithheld() {
        calls.modemText("RING\r\n\r\nNMBR = 5551000\r\nNAME = O\r\n")
        assertEquals("5551000", calls.state.value!!.who)
        assertNull(calls.state.value!!.detail)
        calls.end()
        calls.modemText("RING\r\n\r\nNMBR = P\r\nNAME = P\r\n")
        assertEquals("Private number", calls.state.value!!.who)
        // (the other phone's name wins over a withheld caller ID)
        calls.end()
        calls.caller("D-Modem on Pixel 8")
        calls.modemText("RING\r\nNMBR = P\r\n")
        assertEquals("D-Modem on Pixel 8", calls.state.value!!.who)
    }

    /** voice mode's packet (<DLE>X ... <DLE>.), and +VCID=2's MESG (MDMF), decoded; caller ID before the RING kept for it */
    @Test
    fun callerIdPacketAndHex() {
        calls.modemText("RING\r\n\u0010XNMBR = 5551000\r\nNAME = Bob\r\n\u0010.")
        assertEquals("Bob", calls.state.value!!.who)
        assertEquals("5551000", calls.state.value!!.detail)
        calls.end()
        calls.modemText("\r\nMESG = 800C02073535353130303008014FB3\r\n")
        assertNull(calls.state.value)
        calls.modemText("RING\r\n")
        assertEquals("5551000", calls.state.value!!.number)
        assertEquals("O", calls.state.value!!.name)
        assertEquals("5551000", calls.state.value!!.who)
        // SDMF: the date and time, then the number
        assertEquals(Pair("5551000", null), IncomingCalls.decode("040F31303330313034333535353130303000"))
        assertNull(IncomingCalls.decode("zz"))
        assertNull(IncomingCalls.decode("8010"))
    }
}
