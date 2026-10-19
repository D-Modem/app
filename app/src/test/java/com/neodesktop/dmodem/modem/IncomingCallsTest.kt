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
}
