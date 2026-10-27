// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import com.hoho.android.usbserial.driver.CommonUsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.util.EnumSet

/*
 * Exar (MaxLinear) USB UARTs: XR21V141x, XR21B1411, XR21B142x, XR2280x.
 *
 * They look like CDC-ACM ports (a communications interface, a data
 * interface with the bulk pipes), but their UART is set up through vendor
 * register requests; the XR21V141x ignores CDC's line coding altogether.
 *
 * The register map is from Exar's datasheets (XR21V1410/1412/1414: the UART
 * and UART manager register tables and the fractional baud rate generator's
 * clock masks, section 3.3.4; XR21B1411, XR21B1420/1422/1424 and
 * XR22800/1/2/4: their UART register tables), as Linux's xr_serial also
 * lists them. Only the facts are taken from those; the driver is our own.
 */

/** a control transfer on endpoint 0: the bytes moved, or < 0 */
fun interface XrControl {
    /** the transfer done, [data] (up to [length]) out or in: the bytes moved, or < 0 */
    fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?, length: Int): Int
}

/** the four Exar families: where their registers are and how they're reached */
enum class XrChip(
    /** register width in bits (a read is 1 byte for 8, else 2) */
    val width: Int,
    /** requests to the device (false: to the interface) */
    val toDevice: Boolean,
    val setRequest: Int,
    val getRequest: Int,
    val uartEnable: Int,
    val flowControl: Int,
    val xonChar: Int,
    val xoffChar: Int,
    val txBreak: Int,
    val gpioMode: Int,
    val gpioDirection: Int,
    val gpioSet: Int,
    val gpioClear: Int,
    val gpioStatus: Int,
    /** the FIFO resets (the V141x has them in its UART manager block instead) */
    val txFifoReset: Int = -1,
    val rxFifoReset: Int = -1,
    /** the "custom driver active" register: CDC's line state requests off (none on the V141x) */
    val customDriver: Int = -1,
    /** 5 and 6 bit characters */
    val shortChars: Boolean = false,
    /** the TX/RX activity pins (GPIO mode bits 8 and 9) kept as toggles */
    val toggles: Boolean = false,
) {
    V141X(8, true, 0x00, 0x01, 0x03, 0x0c, 0x10, 0x11, 0x14, 0x1a, 0x1b, 0x1d, 0x1e, 0x1f),
    B1411(12, true, 0x00, 0x01, 0xc00, 0xc06, 0xc07, 0xc08, 0xc0a, 0xc0c, 0xc0d, 0xc0e, 0xc0f, 0xc10,
        txFifoReset = 0xc80, rxFifoReset = 0xcc0, customDriver = 0x20d),
    B142X(16, false, 0x00, 0x00, 0x00, 0x06, 0x07, 0x08, 0x0a, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        txFifoReset = 0x40, rxFifoReset = 0x43, customDriver = 0x60, shortChars = true, toggles = true),
    XR2280X(16, true, 0x05, 0x05, 0x40, 0x46, 0x47, 0x48, 0x4a, 0x4c, 0x4d, 0x4e, 0x4f, 0x50,
        txFifoReset = 0x60, rxFifoReset = 0x63, customDriver = 0x81);

    /** the register channel of the port on communications interface [itf] */
    fun channel(itf: Int): Int = if (this == V141X) itf / 2 else itf

    companion object {
        const val VENDOR = 0x04e2

        /** the family of Exar product [pid], or null */
        fun of(pid: Int): XrChip? = when (pid) {
            in 0x1400..0x1403 -> XR2280X
            0x1410, 0x1412, 0x1414 -> V141X
            0x1411 -> B1411
            0x1420, 0x1422, 0x1424 -> B142X
            else -> null
        }

        val products = intArrayOf(0x1400, 0x1401, 0x1402, 0x1403, 0x1410, 0x1411, 0x1412, 0x1414, 0x1420, 0x1422, 0x1424)
    }
}

/**
 * One Exar UART's registers, by [control]: the port on communications
 * interface [itf] of a [chip].
 */
class XrUart(val chip: XrChip, private val control: XrControl, val itf: Int) {
    val channel = chip.channel(itf)

    private val recipient = if (chip.toDevice) 0 else 1

    /** register [reg] of this channel set to [value]; an IOException if the request fails */
    fun write(reg: Int, value: Int) {
        val n = control.transfer(0x40 or recipient, chip.setRequest, value and 0xffff, (channel shl 8) or reg, null, 0)
        if (n < 0) throw IOException("register 0x%x write failed".format(reg))
    }

    /** register [reg] of this channel (1 byte for 8-bit chips, else 2); an IOException if the read fails */
    fun read(reg: Int): Int {
        val len = if (chip.width == 8) 1 else 2
        val buf = ByteArray(len)
        val n = control.transfer(0xC0 or recipient, chip.getRequest, 0, (channel shl 8) or reg, buf, len)
        if (n != len) throw IOException("register 0x%x read failed".format(reg))
        return if (len == 1) buf[0].toInt() and 0xff else (buf[0].toInt() and 0xff) or ((buf[1].toInt() and 0xff) shl 8)
    }

    /** a V141x UART manager register (block 4, one per channel from [base]) */
    private fun manager(base: Int, value: Int) {
        val n = control.transfer(0x40, chip.setRequest, value, (UM_BLOCK shl 8) or (base + channel), null, 0)
        if (n < 0) throw IOException("UART manager 0x%x write failed".format(base + channel))
    }

    var dtr = false
        private set
    var rts = false
        private set
    var flow = UsbSerialPort.FlowControl.NONE
        private set

    /**
     * made ready: off, DTR and RTS outputs as [dtr] and [rts], the rest
     * inputs, [flow] control, 9600 8N1, FIFOs emptied, on
     */
    fun open(dtr: Boolean = false, rts: Boolean = false, flow: UsbSerialPort.FlowControl = UsbSerialPort.FlowControl.NONE) {
        if (chip.customDriver >= 0) write(chip.customDriver, 1)
        disable()
        this.flow = flow
        flowChars()
        write(chip.flowControl, flowMode(flow))
        write(chip.gpioMode, gpioMode())
        write(chip.gpioDirection, GPIO_DTR or GPIO_RTS)
        lines(dtr, rts)
        line(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        resetFifos(tx = true, rx = true)
        enable()
    }

    /** the UART off */
    fun close() = disable()

    /** the UART on, TX and RX (on the V141x its FIFOs too, in the datasheet's order) */
    fun enable() {
        if (chip == XrChip.V141X) {
            // (the order the datasheet asks: TX FIFO, the UART, then RX FIFO)
            manager(UM_FIFO_ENABLE, UM_TX_FIFO)
            write(chip.uartEnable, UART_TX or UART_RX)
            manager(UM_FIFO_ENABLE, UM_TX_FIFO or UM_RX_FIFO)
        } else {
            write(chip.uartEnable, UART_TX or UART_RX)
        }
    }

    /** the UART off (on the V141x its FIFOs too) */
    fun disable() {
        write(chip.uartEnable, 0)
        if (chip == XrChip.V141X) manager(UM_FIFO_ENABLE, 0)
    }

    /** the TX and/or RX FIFOs emptied */
    fun resetFifos(tx: Boolean, rx: Boolean) {
        if (chip == XrChip.V141X) {
            if (tx) manager(UM_TX_FIFO_RESET, 1)
            if (rx) manager(UM_RX_FIFO_RESET, 1)
        } else {
            if (tx) write(chip.txFifoReset, 1)
            if (rx) write(chip.rxFifoReset, 1)
        }
    }

    /** the speed and character format, the UART off meanwhile */
    fun setLine(baud: Int, dataBits: Int, stopBits: Int, parity: Int) {
        check(baud, dataBits, stopBits, parity)
        disable()
        line(baud, dataBits, stopBits, parity)
        enable()
    }

    /** [baud] and the format checked for this chip: IllegalArgumentException if invalid, UnsupportedOperationException if the chip can't */
    private fun check(baud: Int, dataBits: Int, stopBits: Int, parity: Int) {
        if (baud <= 0) throw IllegalArgumentException("Invalid baud rate: $baud")
        if (chip == XrChip.V141X && (baud < V141X_MIN || baud > OSC_HZ)) throw UnsupportedOperationException("Unsupported baud rate: $baud")
        when (dataBits) {
            7, 8 -> {}
            5, 6 -> if (!chip.shortChars) throw UnsupportedOperationException("Unsupported data bits: $dataBits")
            else -> throw IllegalArgumentException("Invalid data bits: $dataBits")
        }
        when (stopBits) {
            UsbSerialPort.STOPBITS_1, UsbSerialPort.STOPBITS_2 -> {}
            UsbSerialPort.STOPBITS_1_5 -> throw UnsupportedOperationException("Unsupported stop bits: 1.5")
            else -> throw IllegalArgumentException("Invalid stop bits: $stopBits")
        }
        if (parity !in UsbSerialPort.PARITY_NONE..UsbSerialPort.PARITY_SPACE) throw IllegalArgumentException("Invalid parity: $parity")
    }

    /** the speed and format written: the V141x's own registers, the others' by CDC's SET_LINE_CODING */
    private fun line(baud: Int, dataBits: Int, stopBits: Int, parity: Int) {
        if (chip == XrChip.V141X) {
            val c = v141xClock(baud)
            write(V141X_DIVISOR, c.divisor and 0xff)
            write(V141X_DIVISOR + 1, (c.divisor shr 8) and 0xff)
            write(V141X_DIVISOR + 2, (c.divisor shr 16) and 0xff)
            write(V141X_TX_MASK, c.txMask and 0xff)
            write(V141X_TX_MASK + 1, c.txMask shr 8)
            write(V141X_RX_MASK, c.rxMask and 0xff)
            write(V141X_RX_MASK + 1, c.rxMask shr 8)
            // FORMAT: data bits in 3:0, parity in 6:4 (the library's codes are the chip's), two stop bits in 7
            val stop = if (stopBits == UsbSerialPort.STOPBITS_2) 0x80 else 0
            write(V141X_FORMAT, dataBits or (parity shl 4) or stop)
        } else {
            // CDC's SET_LINE_CODING to the port's communications interface
            val stop = if (stopBits == UsbSerialPort.STOPBITS_2) 2 else 0
            val coding = byteArrayOf(
                baud.toByte(), (baud shr 8).toByte(), (baud shr 16).toByte(), (baud shr 24).toByte(),
                stop.toByte(), parity.toByte(), dataBits.toByte(),
            )
            val n = control.transfer(0x21, SET_LINE_CODING, 0, itf, coding, coding.size)
            if (n < 0) throw IOException("SET_LINE_CODING failed")
        }
    }

    /** GPIO_MODE's value: RTS/CTS on the pins for hardware flow, the activity toggles on chips that keep them */
    private fun gpioMode(): Int {
        val sel = if (flow == UsbSerialPort.FlowControl.RTS_CTS) GPIO_SEL_RTS_CTS else 0
        return sel or (if (chip.toggles) GPIO_TX_TOGGLE or GPIO_RX_TOGGLE else 0)
    }

    /** flow control done by the chip: none, RTS/CTS or XON/XOFF; the UART off meanwhile */
    fun setFlow(f: UsbSerialPort.FlowControl) {
        val mode = flowMode(f)
        disable()
        flow = f
        flowChars()
        write(chip.flowControl, mode)
        write(chip.gpioMode, gpioMode())
        enable()
    }

    /** the chip's FLOW_CONTROL mode for [f] */
    private fun flowMode(f: UsbSerialPort.FlowControl) = when (f) {
        UsbSerialPort.FlowControl.NONE -> FLOW_NONE
        UsbSerialPort.FlowControl.RTS_CTS -> FLOW_HW
        UsbSerialPort.FlowControl.XON_XOFF -> FLOW_SW
        else -> throw UnsupportedOperationException("Unsupported flow control: $f")
    }

    /** the XON and XOFF characters set, for software flow */
    private fun flowChars() {
        if (flow != UsbSerialPort.FlowControl.XON_XOFF) return
        write(chip.xonChar, UsbSerialPort.CHAR_XON.code)
        write(chip.xoffChar, UsbSerialPort.CHAR_XOFF.code)
    }

    /** DTR and RTS (the pins are active low: GPIO_CLEAR asserts, GPIO_SET drops) */
    fun lines(dtr: Boolean, rts: Boolean) {
        val on = (if (dtr) GPIO_DTR else 0) or (if (rts) GPIO_RTS else 0)
        val off = (GPIO_DTR or GPIO_RTS) and on.inv()
        if (on != 0) write(chip.gpioClear, on)
        if (off != 0) write(chip.gpioSet, off)
        this.dtr = dtr
        this.rts = rts
    }

    /** the lines in (CTS, DSR, CD, RI), read from GPIO_STATUS: true asserted */
    fun status(): Status {
        val s = read(chip.gpioStatus)
        return Status(cts = (s and GPIO_CTS) == 0, dsr = (s and GPIO_DSR) == 0, cd = (s and GPIO_CD) == 0, ri = (s and GPIO_RI) == 0)
    }

    data class Status(val cts: Boolean, val dsr: Boolean, val cd: Boolean, val ri: Boolean)

    /** a break held (TX_BREAK all ones: until cleared), or ended */
    fun setBreak(on: Boolean) = write(chip.txBreak, if (on) (1 shl chip.width) - 1 else 0)

    /** the V141x's baud generator: a whole divisor of the 48 MHz clock, the 1/32 fraction spread by its masks */
    data class Clock(val divisor: Int, val txMask: Int, val rxMask: Int)

    companion object {
        const val OSC_HZ = 48_000_000
        const val V141X_MIN = 46

        const val SET_LINE_CODING = 0x20

        // XR21V141x UART registers (block = channel)
        const val V141X_DIVISOR = 0x04
        const val V141X_TX_MASK = 0x07
        const val V141X_RX_MASK = 0x09
        const val V141X_FORMAT = 0x0b

        // XR21V141x UART manager (block 4), each register + the channel
        const val UM_BLOCK = 4
        const val UM_FIFO_ENABLE = 0x10
        const val UM_RX_FIFO_RESET = 0x18
        const val UM_TX_FIFO_RESET = 0x1c
        const val UM_TX_FIFO = 0x01
        const val UM_RX_FIFO = 0x02

        const val UART_TX = 0x01
        const val UART_RX = 0x02

        const val FLOW_NONE = 0
        const val FLOW_HW = 1
        const val FLOW_SW = 2

        // GPIO pins (status, set, clear, direction)
        const val GPIO_RI = 0x01
        const val GPIO_CD = 0x02
        const val GPIO_DSR = 0x04
        const val GPIO_DTR = 0x08
        const val GPIO_CTS = 0x10
        const val GPIO_RTS = 0x20

        // GPIO_MODE: pin function select in 2:0, activity toggles
        const val GPIO_SEL_RTS_CTS = 0x1
        const val GPIO_TX_TOGGLE = 0x100
        const val GPIO_RX_TOGGLE = 0x200

        // the datasheet's clock masks, by the divisor's 1/32 fraction: TX, RX for an even divisor, RX for an odd one
        private val TX_MASK = intArrayOf(
            0x000, 0x000, 0x100, 0x020, 0x010, 0x208, 0x104, 0x844, 0x444, 0x122, 0x912, 0x492, 0x252, 0x94a, 0x52a, 0xaaa,
            0xaaa, 0x555, 0xb55, 0x6b5, 0x5b5, 0xb6d, 0x76d, 0xedd, 0xddd, 0x7bb, 0xf7b, 0xdf7, 0x7f7, 0xfdf, 0xf7f, 0xfff,
        )
        private val RX_MASK_EVEN = intArrayOf(
            0x000, 0x000, 0x000, 0x400, 0x100, 0x040, 0x820, 0x210, 0x110, 0x888, 0x448, 0x248, 0x928, 0x4a4, 0xaa4, 0x954,
            0x554, 0xad4, 0xab4, 0x5ac, 0xd6c, 0xb6a, 0x6da, 0xdda, 0xbba, 0xf7a, 0xef6, 0xbf6, 0xfee, 0xfbe, 0xefe, 0xffe,
        )
        private val RX_MASK_ODD = intArrayOf(
            0x000, 0x000, 0x100, 0x020, 0x010, 0x208, 0x108, 0x884, 0x444, 0x224, 0x924, 0x492, 0x292, 0xa52, 0x54a, 0x4aa,
            0xaaa, 0x5aa, 0x55a, 0xb56, 0x6d6, 0xdb6, 0xbb6, 0x76e, 0xeee, 0xdde, 0x7de, 0xf7e, 0xefe, 0x7fe, 0xffe, 0xffd,
        )

        /** the V141x's divisor and clock masks for [baud] */
        fun v141xClock(baud: Int): Clock {
            val divisor = OSC_HZ / baud
            val fraction = ((32L * OSC_HZ) / baud).toInt() and 0x1f
            val rx = if ((divisor and 1) == 0) RX_MASK_EVEN[fraction] else RX_MASK_ODD[fraction]
            return Clock(divisor, TX_MASK[fraction], rx)
        }

        /**
         * the ports of a device whose interfaces are [itfs] (number, class)
         * in order: each communications interface's index and its data
         * interface's (the next number's, else the next data interface)
         */
        fun pairs(itfs: List<Pair<Int, Int>>): List<Pair<Int, Int>> = itfs.indices
            .filter { itfs[it].second == UsbConstants.USB_CLASS_COMM }
            .mapNotNull { c ->
                val data = itfs.indices.firstOrNull { itfs[it].first == itfs[c].first + 1 && itfs[it].second == UsbConstants.USB_CLASS_CDC_DATA }
                    ?: (c + 1 until itfs.size).firstOrNull { itfs[it].second == UsbConstants.USB_CLASS_CDC_DATA }
                data?.let { c to it }
            }
    }
}

/**
 * The Exar ports of [device] for usb-serial-for-android: one per CDC
 * communications/data interface pair (port n on interfaces 2n and 2n+1 on
 * the multi-port parts).
 */
class XrSerialDriver(private val device: UsbDevice) : UsbSerialDriver {
    private val chip = XrChip.of(device.productId) ?: XrChip.V141X

    private val ports: List<UsbSerialPort> = run {
        val itfs = (0 until device.interfaceCount).map { device.getInterface(it) }
            // (alternate settings: the first of each)
            .distinctBy { it.id }
        XrUart.pairs(itfs.map { it.id to it.interfaceClass }).mapIndexed { n, (c, d) -> XrSerialPort(n, itfs[c], itfs[d]) }
    }

    /** the device the ports are on */
    override fun getDevice(): UsbDevice = device

    /** its ports, one per communications/data interface pair */
    override fun getPorts(): List<UsbSerialPort> = ports

    inner class XrSerialPort(n: Int, private val comm: UsbInterface, private val data: UsbInterface) :
        CommonUsbSerialPort(device, n) {
        private var uart: XrUart? = null
        // (kept while closed, as set before open)
        private var dtr = false
        private var rts = false

        /** the driver the port is of */
        override fun getDriver(): UsbSerialDriver = this@XrSerialDriver

        /** the open port's UART; an IOException when closed */
        private fun uart(): XrUart = uart ?: throw IOException("Port not open")

        /** the port opened: its interfaces claimed, its bulk endpoints found, the UART set up with DTR, RTS and flow as set before */
        override fun openInt() {
            val conn = mConnection
            if (!conn.claimInterface(comm, true)) throw IOException("Could not claim interface ${comm.id}")
            if (!conn.claimInterface(data, true)) throw IOException("Could not claim interface ${data.id}")
            for (i in 0 until data.endpointCount) {
                val ep = data.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) mReadEndpoint = ep else mWriteEndpoint = ep
            }
            val u = XrUart(chip, { type, req, value, index, buf, len ->
                conn.controlTransfer(type, req, value, index, buf, len, TIMEOUT)
            }, comm.id)
            u.open(dtr, rts, mFlowControl)
            uart = u
        }

        /** the UART off, its interfaces let go */
        override fun closeInt() {
            runCatching { uart?.close() }
            uart = null
            runCatching { mConnection.releaseInterface(data) }
            runCatching { mConnection.releaseInterface(comm) }
        }

        /** the UART's speed and character format */
        override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) =
            uart().setLine(baudRate, dataBits, stopBits, parity)

        /** CD, from the UART's status */
        override fun getCD() = uart().status().cd
        /** CTS, from the UART's status */
        override fun getCTS() = uart().status().cts
        /** DSR, from the UART's status */
        override fun getDSR() = uart().status().dsr
        /** RI, from the UART's status */
        override fun getRI() = uart().status().ri
        /** DTR as last set */
        override fun getDTR() = dtr
        /** RTS as last set */
        override fun getRTS() = rts

        /** DTR set, kept for the next open if closed */
        override fun setDTR(value: Boolean) {
            uart?.lines(value, rts)
            dtr = value
        }

        /** RTS set, kept for the next open if closed */
        override fun setRTS(value: Boolean) {
            uart?.lines(dtr, value)
            rts = value
        }

        /** the lines: DTR and RTS as set, the rest from the UART's status */
        override fun getControlLines(): EnumSet<UsbSerialPort.ControlLine> {
            val s = uart().status()
            val l = EnumSet.noneOf(UsbSerialPort.ControlLine::class.java)
            if (rts) l.add(UsbSerialPort.ControlLine.RTS)
            if (s.cts) l.add(UsbSerialPort.ControlLine.CTS)
            if (dtr) l.add(UsbSerialPort.ControlLine.DTR)
            if (s.dsr) l.add(UsbSerialPort.ControlLine.DSR)
            if (s.cd) l.add(UsbSerialPort.ControlLine.CD)
            if (s.ri) l.add(UsbSerialPort.ControlLine.RI)
            return l
        }

        /** all of them: the chip has every line */
        override fun getSupportedControlLines(): EnumSet<UsbSerialPort.ControlLine> =
            EnumSet.allOf(UsbSerialPort.ControlLine::class.java)

        /** flow control done by the chip (kept for the next open if closed) */
        override fun setFlowControl(flowControl: UsbSerialPort.FlowControl) {
            if (flowControl !in supportedFlowControl) throw UnsupportedOperationException("Unsupported flow control: $flowControl")
            uart?.setFlow(flowControl)
            mFlowControl = flowControl
        }

        /** none, RTS/CTS and XON/XOFF */
        override fun getSupportedFlowControl(): EnumSet<UsbSerialPort.FlowControl> = EnumSet.of(
            UsbSerialPort.FlowControl.NONE, UsbSerialPort.FlowControl.RTS_CTS, UsbSerialPort.FlowControl.XON_XOFF,
        )

        /** the chip's FIFOs emptied, either way */
        override fun purgeHwBuffers(purgeWriteBuffers: Boolean, purgeReadBuffers: Boolean) =
            uart().resetFifos(tx = purgeWriteBuffers, rx = purgeReadBuffers)

        /** a break held on the line, or ended */
        override fun setBreak(value: Boolean) = uart().setBreak(value)
    }

    companion object {
        private const val TIMEOUT = 5000

        /** usb-serial-for-android's table: Exar's products this driver takes */
        @JvmStatic
        fun getSupportedDevices(): Map<Int, IntArray> = mapOf(XrChip.VENDOR to XrChip.products)
    }
}
