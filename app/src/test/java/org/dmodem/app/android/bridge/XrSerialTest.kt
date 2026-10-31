// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import com.hoho.android.usbserial.driver.UsbSerialPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Exar UARTs' register protocol, the chip faked: the requests it's
 * sent recorded, its registers' reads answered.
 */
class XrSerialTest {

    /** one control transfer: its setup, the bytes sent (a write's) */
    data class Req(val type: Int, val request: Int, val value: Int, val index: Int, val data: List<Int> = emptyList())

    private class FakeChip : XrControl {
        val sent = mutableListOf<Req>()
        /** the reads' answers, by wIndex */
        val regs = mutableMapOf<Int, Int>()

        /** a read answered from [regs] by wIndex (-1 if none there), a write's bytes kept; each request recorded */
        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?, length: Int): Int {
            if (requestType and 0x80 != 0) {
                val v = regs[index] ?: return -1
                for (i in 0 until length) data!![i] = (v shr (8 * i)).toByte()
                sent += Req(requestType, request, value, index)
                return length
            }
            sent += Req(requestType, request, value, index, data?.take(length)?.map { it.toInt() and 0xff }.orEmpty())
            return length
        }
    }

    /** a vendor write of [value] to register [index], as a V141x is sent it */
    private fun w(index: Int, value: Int) = Req(0x40, 0x00, value, index)

    /** each product ID's chip family, and the driver's 11 IDs under Exar's vendor ID */
    @Test
    fun families() {
        assertEquals(XrChip.XR2280X, XrChip.of(0x1400))
        assertEquals(XrChip.XR2280X, XrChip.of(0x1403))
        assertEquals(XrChip.V141X, XrChip.of(0x1410))
        assertEquals(XrChip.B1411, XrChip.of(0x1411))
        assertEquals(XrChip.V141X, XrChip.of(0x1414))
        assertEquals(XrChip.B142X, XrChip.of(0x1422))
        assertNull(XrChip.of(0x1415))
        val ids = XrSerialDriver.getSupportedDevices()
        assertEquals(setOf(0x04e2), ids.keys)
        assertEquals(11, ids.getValue(0x04e2).size)
    }

    /** a V141x's registers are written and read a byte at a time, the block in wIndex */
    @Test
    fun v141xRegistersAreBytesWithTheBlockInTheIndex() {
        val f = FakeChip()
        val u = XrUart(XrChip.V141X, f, 0)
        u.write(0x0b, 0x08)
        f.regs[0x001f] = 0xff
        u.read(0x1f)
        assertEquals(listOf(Req(0x40, 0x00, 0x08, 0x000b), Req(0xC0, 0x01, 0, 0x001f)), f.sent)
    }

    /** a B142x's registers are words, to the interface's requests */
    @Test
    fun b142xRegistersAreWordsToTheInterface() {
        val f = FakeChip()
        val u = XrUart(XrChip.B142X, f, 0)
        f.regs[0x0010] = 0x1234
        assertEquals(0x1234, u.read(0x10))
        u.write(0x0e, 0x0128)
        assertEquals(listOf(Req(0xC1, 0x00, 0, 0x0010), Req(0x41, 0x00, 0x0128, 0x000e)), f.sent)
    }

    /** an XR2280x and a B1411 are sent their own requests and register addresses */
    @Test
    fun xr2280xAndB1411UseTheirOwnRequests() {
        val f = FakeChip()
        XrUart(XrChip.XR2280X, f, 0).write(XrChip.XR2280X.gpioSet, 0x08)
        XrUart(XrChip.B1411, f, 0).write(XrChip.B1411.gpioSet, 0x08)
        f.regs[0x0c10] = 0x3f
        assertEquals(0x3f, XrUart(XrChip.B1411, f, 0).read(XrChip.B1411.gpioStatus))
        assertEquals(
            listOf(Req(0x40, 0x05, 0x08, 0x004e), Req(0x40, 0x00, 0x08, 0x0c0e), Req(0xC0, 0x01, 0, 0x0c10)),
            f.sent,
        )
    }

    /** a V141x opened: the UART off, the pins and lines set, 9600 8N1, the FIFOs reset and on again */
    @Test
    fun v141xOpens() {
        val f = FakeChip()
        XrUart(XrChip.V141X, f, 0).open()
        assertEquals(
            listOf(
                w(0x0003, 0), w(0x0410, 0), // UART off, its FIFOs off
                w(0x000c, 0), // no flow control
                w(0x001a, 0), w(0x001b, 0x28), // pins as GPIO, DTR and RTS out
                w(0x001d, 0x28), // DTR and RTS dropped (active low)
                w(0x0004, 0x88), w(0x0005, 0x13), w(0x0006, 0x00), // 9600: 48 MHz / 5000
                w(0x0007, 0), w(0x0008, 0), w(0x0009, 0), w(0x000a, 0),
                w(0x000b, 0x08), // 8N1
                w(0x041c, 1), w(0x0418, 1), // FIFOs reset
                w(0x0410, 1), w(0x0003, 3), w(0x0410, 3), // on: TX FIFO, UART, RX FIFO
            ),
            f.sent,
        )
    }

    /** a B142x opened with DTR and RTS: its custom driver on, the line set by CDC SET_LINE_CODING */
    @Test
    fun b142xOpensWithCdcLineCoding() {
        val f = FakeChip()
        XrUart(XrChip.B142X, f, 0).open(dtr = true, rts = true)
        val r = { idx: Int, v: Int -> Req(0x41, 0x00, v, idx) }
        assertEquals(
            listOf(
                r(0x0060, 1), // custom driver active
                r(0x0000, 0),
                r(0x0006, 0),
                r(0x000c, 0x300), r(0x000d, 0x28),
                r(0x000f, 0x28), // DTR and RTS asserted (GPIO_CLEAR)
                Req(0x21, 0x20, 0, 0, listOf(0x80, 0x25, 0, 0, 0, 0, 8)),
                r(0x0040, 1), r(0x0043, 1),
                r(0x0000, 3),
            ),
            f.sent,
        )
    }

    /** a V141x's divisor and fraction masks for its speeds, and the registers a new line sets */
    @Test
    fun v141xBaudRates() {
        // divisor = 48 MHz / baud, its fraction in 32nds picking the masks (RX's by the divisor's oddness)
        assertEquals(XrUart.Clock(160000, 0, 0), XrUart.v141xClock(300))
        assertEquals(XrUart.Clock(5000, 0, 0), XrUart.v141xClock(9600))
        assertEquals(XrUart.Clock(833, 0x912, 0x924), XrUart.v141xClock(57600)) // 833 + 10/32, odd
        assertEquals(XrUart.Clock(416, 0xb6d, 0xb6a), XrUart.v141xClock(115200)) // 416 + 21/32, even
        assertEquals(XrUart.Clock(52, 0x100, 0x000), XrUart.v141xClock(921600)) // 52 + 2/32

        val f = FakeChip()
        XrUart(XrChip.V141X, f, 0).setLine(115200, 7, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_EVEN)
        assertEquals(
            listOf(
                w(0x0003, 0), w(0x0410, 0),
                w(0x0004, 0xa0), w(0x0005, 0x01), w(0x0006, 0x00),
                w(0x0007, 0x6d), w(0x0008, 0x0b), w(0x0009, 0x6a), w(0x000a, 0x0b),
                w(0x000b, 0x07 or 0x20 or 0x80),
                w(0x0410, 1), w(0x0003, 3), w(0x0410, 3),
            ),
            f.sent,
        )

        f.sent.clear()
        XrUart(XrChip.V141X, f, 0).setLine(300, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        assertTrue(w(0x0004, 0x00) in f.sent && w(0x0005, 0x71) in f.sent && w(0x0006, 0x02) in f.sent)
    }

    /** a V141x's format register for each data bits, stop bits and parity, the unsupported ones refused */
    @Test
    fun v141xFormats() {
        /** the format register a V141x is sent for these */
        fun format(bits: Int, stop: Int, parity: Int): Int {
            val f = FakeChip()
            XrUart(XrChip.V141X, f, 0).setLine(9600, bits, stop, parity)
            return f.sent.single { it.index == 0x000b }.value
        }
        assertEquals(0x08, format(8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE))
        assertEquals(0x17, format(7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_ODD))
        assertEquals(0x38, format(8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_MARK))
        assertEquals(0xc8, format(8, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_SPACE))
        assertThrows<UnsupportedOperationException> { format(5, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
        assertThrows<UnsupportedOperationException> { format(8, UsbSerialPort.STOPBITS_1_5, UsbSerialPort.PARITY_NONE) }
        assertThrows<IllegalArgumentException> { format(9, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
    }

    /** the CDC families' SET_LINE_CODING for a line, to the port's interface */
    @Test
    fun cdcFamiliesCodeTheLine() {
        /** the SET_LINE_CODING request [chip] is sent for this line */
        fun coding(chip: XrChip, baud: Int, bits: Int, stop: Int, parity: Int, itf: Int = 0): Req {
            val f = FakeChip()
            XrUart(chip, f, itf).setLine(baud, bits, stop, parity)
            return f.sent.single { it.type == 0x21 }
        }
        assertEquals(
            Req(0x21, 0x20, 0, 0, listOf(0x00, 0x10, 0x0e, 0x00, 2, 2, 7)), // 921600 7E2
            coding(XrChip.B1411, 921600, 7, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_EVEN),
        )
        assertEquals(
            Req(0x21, 0x20, 0, 4, listOf(0x2c, 0x01, 0, 0, 0, 1, 5)), // 300 5O1, to port 2's interface
            coding(XrChip.B142X, 300, 5, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_ODD, itf = 4),
        )
        assertEquals(
            Req(0x21, 0x20, 0, 0, listOf(0x00, 0xc2, 0x01, 0x00, 0, 0, 8)), // 115200 8N1
            coding(XrChip.XR2280X, 115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE),
        )
        assertThrows<UnsupportedOperationException> { coding(XrChip.XR2280X, 9600, 6, UsbSerialPort.STOPBITS_1, 0) }
    }

    /** DTR and RTS set through GPIO set and clear, and the status lines read, active low */
    @Test
    fun linesOutAndIn() {
        val f = FakeChip()
        val u = XrUart(XrChip.V141X, f, 0)
        u.lines(dtr = true, rts = false)
        u.lines(dtr = false, rts = true)
        u.lines(dtr = true, rts = true)
        assertEquals(
            listOf(w(0x001e, 0x08), w(0x001d, 0x20), w(0x001e, 0x20), w(0x001d, 0x08), w(0x001e, 0x28)),
            f.sent,
        )
        assertTrue(u.dtr && u.rts)

        // active low: CTS (bit 4) and CD (bit 1) low, DSR (2) and RI (0) high
        f.regs[0x001f] = 0xff and (0x10 or 0x02).inv()
        assertEquals(XrUart.Status(cts = true, dsr = false, cd = true, ri = false), u.status())
        f.regs[0x001f] = 0xff and (0x04 or 0x01).inv()
        assertEquals(XrUart.Status(cts = false, dsr = true, cd = false, ri = true), u.status())

        val b = FakeChip()
        b.regs[0x0010] = 0xffff and 0x04.inv()
        assertTrue(XrUart(XrChip.B142X, b, 0).status().dsr)
    }

    /** a break set and cleared, RTS/CTS and XON/XOFF flow control set, DTR/DSR refused */
    @Test
    fun breakAndFlow() {
        val f = FakeChip()
        val u = XrUart(XrChip.V141X, f, 0)
        u.setBreak(true)
        u.setBreak(false)
        assertEquals(listOf(w(0x0014, 0xff), w(0x0014, 0)), f.sent)

        f.sent.clear()
        u.setFlow(UsbSerialPort.FlowControl.RTS_CTS)
        assertEquals(
            listOf(w(0x0003, 0), w(0x0410, 0), w(0x000c, 1), w(0x001a, 1), w(0x0410, 1), w(0x0003, 3), w(0x0410, 3)),
            f.sent,
        )
        f.sent.clear()
        u.setFlow(UsbSerialPort.FlowControl.XON_XOFF)
        assertTrue(w(0x0010, 0x11) in f.sent && w(0x0011, 0x13) in f.sent && w(0x000c, 2) in f.sent && w(0x001a, 0) in f.sent)
        assertThrows<UnsupportedOperationException> { u.setFlow(UsbSerialPort.FlowControl.DTR_DSR) }

        val b = FakeChip()
        XrUart(XrChip.B142X, b, 0).setBreak(true)
        XrUart(XrChip.B1411, b, 0).setBreak(true)
        assertEquals(listOf(Req(0x41, 0, 0xffff, 0x000a), Req(0x40, 0, 0xfff, 0x0c0a)), b.sent)
    }

    /** a multi-port V141x's and a B142x's channels in their requests' addresses */
    @Test
    fun multiPortChannels() {
        // XR21V1414: port n on interfaces 2n/2n+1, channel n in the block byte, its UART manager registers + n
        val f = FakeChip()
        val v = XrUart(XrChip.V141X, f, 6)
        assertEquals(3, v.channel)
        v.lines(dtr = true, rts = true)
        v.resetFifos(tx = true, rx = true)
        v.enable()
        assertEquals(
            listOf(w(0x031e, 0x28), w(0x041f, 1), w(0x041b, 1), w(0x0413, 1), w(0x0303, 3), w(0x0413, 3)),
            f.sent,
        )

        // XR21B1422: the channel is the interface number
        val b = FakeChip()
        val u = XrUart(XrChip.B142X, b, 2)
        assertEquals(2, u.channel)
        u.write(XrChip.B142X.gpioClear, 0x08)
        b.regs[0x0210] = 0
        u.status()
        assertEquals(listOf(Req(0x41, 0, 0x08, 0x020f), Req(0xC1, 0, 0, 0x0210)), b.sent)
    }

    /** each port's comm and data interfaces paired, others skipped */
    @Test
    fun portsPairInterfaces() {
        // two ports: comm 0 + data 1, comm 2 + data 3
        assertEquals(listOf(0 to 1, 2 to 3), XrUart.pairs(listOf(0 to 2, 1 to 10, 2 to 2, 3 to 10)))
        // a UART among other functions
        assertEquals(listOf(1 to 2), XrUart.pairs(listOf(0 to 0xff, 1 to 2, 2 to 10, 3 to 3)))
        // a data interface out of order: the next one's taken
        assertEquals(listOf(0 to 2), XrUart.pairs(listOf(5 to 2, 0 to 0xff, 9 to 10)))
        assertEquals(emptyList<Pair<Int, Int>>(), XrUart.pairs(listOf(0 to 2)))
    }

    /** a failed read throws, nothing recorded */
    @Test
    fun failedTransfersThrow() {
        val f = FakeChip()
        assertThrows<java.io.IOException> { XrUart(XrChip.V141X, f, 0).read(0x1f) }
        assertFalse(f.sent.isNotEmpty())
    }

    /** fails unless [f] throws a [T] */
    private inline fun <reified T : Throwable> assertThrows(f: () -> Unit) {
        try {
            f()
        } catch (t: Throwable) {
            if (t is T) return
            throw AssertionError("expected ${T::class.simpleName}, got $t")
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }
}
