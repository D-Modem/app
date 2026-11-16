// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import com.hoho.android.usbserial.driver.UsbSerialPort
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TUSB3410's protocol (the MultiTech MT9234MU's UART), the chip faked:
 * the requests it is sent recorded, its port status answered; and its
 * firmware's image and packets.
 */
class TiSerialTest {

    /** one control transfer: its setup, the bytes sent (a write's) */
    data class Req(val type: Int, val request: Int, val value: Int, val index: Int, val data: List<Int> = emptyList())

    private class FakeChip : TiControl {
        val sent = mutableListOf<Req>()
        /** the modem status register that GET_PORT_STATUS answers */
        var msr = 0

        /** a status read answered with [msr], a write's bytes kept; each request recorded */
        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?, length: Int): Int {
            if (requestType and 0x80 != 0) {
                sent += Req(requestType, request, value, index)
                val b = byteArrayOf(request.toByte(), index.toByte(), 0, msr.toByte(), 0x60)
                for (i in 0 until length) data!![i] = b[i]
                return length
            }
            sent += Req(requestType, request, value, index, data?.take(length)?.map { it.toInt() and 0xff }.orEmpty())
            return length
        }
    }

    /** the MCR write, as the chip is sent it: XDATA 0xFFA4, the DTR, RTS and loop bits, [mcr] */
    private fun mcr(mcr: Int) = Req(0x40, 0x80, 0, 0x05, listOf(0x30, 0x01, 0x01, 0x00, 0x00, 0xff, 0xa4, 0x34, mcr))

    /** the MultiTech IDs, and the driver's table */
    @Test
    fun ids() {
        assertTrue(TiIds.known(0x06e0, 0xf114))
        assertTrue(TiIds.known(0x06e0, 0xf115))
        assertTrue(TiIds.known(0x06e0, 0x0319))
        assertFalse(TiIds.known(0x06e0, 0xf110))
        assertFalse(TiIds.known(0x0451, 0x3410))
        assertEquals("MultiTech MT9234MU", TiIds.name(0xf114))
        assertEquals(setOf(0x06e0), TiSerialDriver.getSupportedDevices().keys)
    }

    /** the divisor of TI's clock: 115200 is 8, 9600 is 96 */
    @Test
    fun divisors() {
        assertEquals(8, TiUart.divisor(115200))
        assertEquals(16, TiUart.divisor(57600))
        assertEquals(96, TiUart.divisor(9600))
        assertEquals(1, TiUart.divisor(921600))
    }

    /** the configuration block: big-endian divisor and flags, 8N1, XON/XOFF, RS-232; RTS/CTS sets its two flags */
    @Test
    fun configBlock() {
        val none = TiUart.configBlock(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE, UsbSerialPort.FlowControl.NONE)
        assertArrayEquals(byteArrayOf(0, 8, 0x60, 0, 3, 0, 0, 0x11, 0x13, 0), none)
        val hw = TiUart.configBlock(9600, 7, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_EVEN, UsbSerialPort.FlowControl.RTS_CTS)
        assertArrayEquals(byteArrayOf(0, 96, 0x60, 0x29, 2, 2, 2, 0x11, 0x13, 0), hw)
    }

    /** the open: set, open, start, both purges, then set, open, start again, and DTR and RTS each time after a set */
    @Test
    fun openSequence() {
        val f = FakeChip()
        val u = TiUart(f)
        u.open(dtr = true, rts = true)
        val cmds = f.sent.map { it.request }
        assertEquals(listOf(0x05, 0x80, 0x06, 0x08, 0x0B, 0x0B, 0x05, 0x80, 0x06, 0x08, 0x80), cmds)
        // (the port's commands to UART 1, wIndex 3)
        assertTrue(f.sent.filter { it.request in setOf(0x05, 0x06, 0x08, 0x0B) }.all { it.index == 3 })
        assertEquals(listOf(0x80, 0x00), f.sent.filter { it.request == 0x0B }.map { it.value })
        assertEquals(0x89, f.sent.first { it.request == 0x06 }.value)
        assertEquals(mcr(0x30), f.sent.last())
        assertTrue(u.dtr && u.rts)
    }

    /** DTR and RTS: the MCR bits; a break: the LCR's */
    @Test
    fun linesAndBreak() {
        val f = FakeChip()
        val u = TiUart(f)
        u.lines(dtr = true, rts = false)
        u.lines(dtr = false, rts = false)
        u.setBreak(true)
        assertEquals(mcr(0x10), f.sent[0])
        assertEquals(mcr(0x00), f.sent[1])
        assertEquals(Req(0x40, 0x80, 0, 0x05, listOf(0x30, 0x01, 0x01, 0x00, 0x00, 0xff, 0xa2, 0x40, 0x40)), f.sent[2])
    }

    /** a new speed: one SET_CONFIG with its divisor, DTR and RTS kept */
    @Test
    fun setLineKeepsTheLines() {
        val f = FakeChip()
        val u = TiUart(f)
        u.lines(dtr = true, rts = true)
        f.sent.clear()
        u.setLine(57600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        assertEquals(0x05, f.sent[0].request)
        assertEquals(listOf(0, 16), f.sent[0].data.take(2))
        assertEquals(mcr(0x30), f.sent[1])
    }

    /** a speed the divisor can't make is refused */
    @Test(expected = UnsupportedOperationException::class)
    fun tooFast() {
        TiUart(FakeChip()).setLine(3_000_000, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    /** the lines in, from the port status's MSR */
    @Test
    fun status() {
        val f = FakeChip()
        f.msr = 0x80 or 0x10
        val s = TiUart(f).status()
        assertTrue(s.cd)
        assertTrue(s.cts)
        assertFalse(s.dsr)
        assertFalse(s.ri)
        assertEquals(Req(0xC0, 0x02, 0, 0x03), f.sent.single())
    }

    /** the firmware's header: the code's length and checksum, whatever the file had there */
    @Test
    fun firmwareImage() {
        val file = byteArrayOf(0x55, 0x55, 0x55, 1, 2, 3, 0xff.toByte())
        val img = TiFirmware.image(file)
        assertArrayEquals(byteArrayOf(4, 0, 5, 1, 2, 3, 0xff.toByte()), img)
        assertNull(TiFirmware.problem(file))
        assertEquals("the file is empty", TiFirmware.problem(byteArrayOf(1, 2, 3)))
        assertTrue(TiFirmware.problem(ByteArray(TiFirmware.MAX + 4))!!.contains("too large"))
    }

    /** the download's packets: 64 octets, the last one less */
    @Test
    fun firmwarePackets() {
        val p = TiFirmware.packets(ByteArray(13769))
        assertEquals(216, p.size)
        assertTrue(p.dropLast(1).all { it.size == 64 })
        assertEquals(13769 - 215 * 64, p.last().size)
    }

    /** an unknown file is described by its size */
    @Test
    fun describe() {
        assertEquals("a TUSB3410 firmware that the app does not know (7 octets)", TiFirmware.describe(ByteArray(7)))
    }
}
