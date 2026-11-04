// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.modem

import org.dmodem.app.android.NativeModem
import org.dmodem.app.android.bridge.FakeSerialLine
import org.dmodem.app.android.settings.ModemSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** a modem on a USB serial adapter ([SerialModem]): its lines held up, its octets, its DCD; its status from both */
class SerialModemTest {
    /** started: DTR, RTS and RTS/CTS on; octets each way; DCD's changes told; closed: DTR down, the adapter let go */
    @Test
    fun linesOctetsAndCarrier() {
        val line = FakeSerialLine()
        val m = SerialModem(line)
        val carrier = LinkedBlockingQueue<Boolean>()
        line.cd = false
        m.start(rtsCts = true) { carrier.add(it) }
        assertEquals(true, line.dtr)
        assertEquals(true, line.rts)
        assertEquals(true, line.rtsCts)

        m.output.write("ATDT5551234\r".toByteArray())
        assertEquals("ATDT5551234\r", line.sent())
        line.incoming.add("CONNECT 28800\r\n".toByteArray())
        val buf = ByteArray(64)
        val n = m.input.read(buf)
        assertEquals("CONNECT 28800\r\n", String(buf, 0, n))

        // (idle at the start isn't news; on, then off, is)
        line.cd = true
        assertEquals(true, carrier.poll(2, TimeUnit.SECONDS))
        line.cd = false
        assertEquals(false, carrier.poll(2, TimeUnit.SECONDS))

        m.close()
        assertEquals(false, line.dtr)
        assertTrue(line.closed)
        assertEquals(-1, m.input.read(buf))
    }

    /** the adapter unplugged: its input ends (the service sees the modem gone) */
    @Test
    fun unplugged() {
        val line = FakeSerialLine()
        val m = SerialModem(line)
        m.start(rtsCts = false)
        line.gone = true
        assertEquals(-1, m.input.read(ByteArray(8)))
        m.close()
    }

    /** DCD and result codes together: online from either, idle from either, a CONNECT after DCD's drop online again */
    @Test
    fun statusFromCarrierAndResults() {
        val seen = mutableListOf<NativeModem.Status>()
        val r = ResultCodeStatus { seen += it }
        /** [s] fed to the status as the modem's output */
        fun say(s: String) = s.toByteArray().let { r.feed(it, it.size) }
        r.carrier(true)
        assertEquals(NativeModem.Status(NativeModem.Link.ONLINE, true, 0, 0), seen.last())
        say("\r\nCONNECT 33600/ARQ\r\n")
        assertEquals(NativeModem.Status(NativeModem.Link.ONLINE, true, 33600, 33600), seen.last())
        // (DCD on again while online: its speed kept)
        r.carrier(true)
        assertEquals(2, seen.size)
        // (DCD gone with no NO CARRIER: ATQ1, say)
        r.carrier(false)
        assertEquals(ModemState.IDLE, seen.last())
        say("\r\nCONNECT 33600/ARQ\r\n")
        assertEquals(NativeModem.Status(NativeModem.Link.ONLINE, true, 33600, 33600), seen.last())
    }

    /** the line: no SIP account needed, no peer line, a modem of its own */
    @Test
    fun theLine() {
        val s = ModemSettings(line = ModemSettings.LINE_SERIAL_MODEM)
        assertTrue(s.serialModem)
        assertFalse(s.peer)
        assertFalse(s.sip)
        assertTrue(s.complete)
        assertEquals(115200, s.serialModemBaud)
    }
}
