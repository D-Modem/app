// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import com.hoho.android.usbserial.driver.CommonUsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.security.MessageDigest
import java.util.EnumSet

/*
 * Texas Instruments' TUSB3410 USB UART, as the MultiTech MT9234MU and
 * MT9234ZBA-USB fax modems have it between USB and their modem.
 *
 * The chip has no firmware of its own (no EEPROM with it): plugged in, it
 * shows its ROM's boot configuration (one interface, one bulk OUT pipe),
 * and the host downloads the firmware into its RAM through that pipe. Then
 * the chip is reset and comes back with two configurations; the second
 * one has the UART (bulk IN and OUT, an interrupt pipe). The UART is set
 * up with vendor requests to the device: the command in bRequest, the
 * module (UART 1: 3) in wIndex.
 *
 * The facts (the requests, the configuration block, the UART's registers
 * in the chip's XDATA, the firmware's header) are from TI's TUSB3410 data
 * sheet and Linux's ti_usb_3410_5052 driver, which also lists the
 * MultiTech IDs. Only the facts are taken from those; the driver is our own.
 */

/** a control transfer on endpoint 0: the bytes moved, or < 0 */
fun interface TiControl {
    /** the transfer done, [data] (up to [length]) out or in: the bytes moved, or < 0 */
    fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?, length: Int): Int
}

/** the USB IDs of the modems with a TUSB3410 that the app drives */
object TiIds {
    const val MULTITECH = 0x06E0

    /** MultiTech MultiMobile MT9234MU */
    const val MT9234MU = 0xF114

    /** MultiTech MT9234ZBA-USB */
    const val MT9234ZBA = 0xF115

    /** an older MT9234ZBA-USB */
    const val MT9234ZBA_OLD = 0x0319

    val products = intArrayOf(MT9234MU, MT9234ZBA, MT9234ZBA_OLD)

    /** whether [vendor]:[product] is one of them */
    fun known(vendor: Int, product: Int): Boolean = vendor == MULTITECH && product in products

    /** the model's name, for the user */
    fun name(product: Int): String = when (product) {
        MT9234MU -> "MultiTech MT9234MU"
        else -> "MultiTech MT9234ZBA-USB"
    }
}

/**
 * The firmware of a TUSB3410: checked, its header made right, sent in
 * the boot ROM's 64-octet packets.
 */
object TiFirmware {
    /** the most code the chip's RAM takes (the file less its 3-octet header) */
    const val MAX = 16284

    /** a bulk packet of the download */
    const val PACKET = 64

    /** the chip's two configurations: the boot ROM's, the firmware's */
    const val BOOT_CONFIG = 1
    const val ACTIVE_CONFIG = 2

    /** why [file] can't be a TUSB3410 firmware, or null if it can */
    fun problem(file: ByteArray): String? = when {
        file.size <= 3 -> "the file is empty"
        file.size - 3 > MAX -> "the file is too large for a TUSB3410 (${file.size} octets)"
        else -> null
    }

    /**
     * [file] as the boot ROM takes it: its first 3 octets set to the code's
     * length (little-endian) and the code's checksum (its octets' sum, mod 256)
     */
    fun image(file: ByteArray): ByteArray {
        problem(file)?.let { throw IllegalArgumentException(it) }
        val out = file.copyOf()
        val len = file.size - 3
        var sum = 0
        for (i in 3 until file.size) sum += file[i].toInt() and 0xff
        out[0] = len.toByte()
        out[1] = (len shr 8).toByte()
        out[2] = sum.toByte()
        return out
    }

    /** [image] in the download's packets */
    fun packets(image: ByteArray): List<ByteArray> =
        (image.indices step PACKET).map { image.copyOfRange(it, minOf(it + PACKET, image.size)) }

    /** whether [device] is in its boot ROM (one configuration, one pipe): its firmware not loaded yet */
    fun needsFirmware(device: UsbDevice): Boolean {
        if (device.configurationCount != 1 || device.interfaceCount < 1) return false
        return device.getInterface(0).endpointCount == 1
    }

    /** what [file] is, for the user: one of the known firmware files, by their SHA-1, or its size */
    fun describe(file: ByteArray): String {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(file).joinToString("") { "%02x".format(it) }
        return KNOWN[sha1] ?: "a TUSB3410 firmware that the app does not know (${file.size} octets)"
    }

    /** the firmware files the app knows, by SHA-1 */
    private val KNOWN = mapOf(
        "5537e93217c509129d5a870b9fe089cd58647070" to "MultiTech's mts_mt9234mu.fw",
        "a5044256b783f6385a9e90335df7eb12262311c0" to "MultiTech's mts_mt9234zba.fw (the same code as mts_mt9234mu.fw)",
        "c79af370e324a6a03d07e2def6e2c66fd3764162" to "TI's ti_3410.fw (not tested on a MultiTech modem)",
    )
}

/**
 * The UART of a TUSB3410 that runs its firmware, by [control]: UART 1,
 * the chip's only one.
 */
class TiUart(private val control: TiControl) {
    var dtr = false
        private set
    var rts = false
        private set
    var flow = UsbSerialPort.FlowControl.NONE
        private set
    private var baud = 9600
    private var dataBits = 8
    private var stopBits = UsbSerialPort.STOPBITS_1
    private var parity = UsbSerialPort.PARITY_NONE

    /** command [request] to UART 1 with [value] and [data]; an IOException if it fails */
    private fun out(request: Int, value: Int, data: ByteArray? = null) {
        val n = control.transfer(0x40, request, value, UART1, data, data?.size ?: 0)
        if (n < 0) throw IOException("TUSB3410 command 0x%02x failed".format(request))
    }

    /**
     * made ready, as the firmware wants it: the line set, the port opened
     * and started, both buffers emptied, then set, opened and started again
     * (the firmware is not always ready the first time); DTR and RTS as
     * [dtr] and [rts], [flow] control
     */
    fun open(dtr: Boolean = false, rts: Boolean = false, flow: UsbSerialPort.FlowControl = UsbSerialPort.FlowControl.NONE) {
        this.flow = flow
        config()
        out(OPEN_PORT, OPEN_SETTINGS)
        out(START_PORT, 0)
        out(PURGE_PORT, PURGE_INPUT)
        out(PURGE_PORT, PURGE_OUTPUT)
        config()
        out(OPEN_PORT, OPEN_SETTINGS)
        out(START_PORT, 0)
        lines(dtr, rts)
    }

    /** the port closed */
    fun close() = out(CLOSE_PORT, 0)

    /** the speed and character format; IllegalArgumentException or UnsupportedOperationException if they can't be */
    fun setLine(baud: Int, dataBits: Int, stopBits: Int, parity: Int) {
        if (baud <= 0) throw IllegalArgumentException("Invalid baud rate: $baud")
        val d = divisor(baud)
        if (d < 1 || d > 0xffff) throw UnsupportedOperationException("Unsupported baud rate: $baud")
        if (dataBits !in 5..8) throw IllegalArgumentException("Invalid data bits: $dataBits")
        when (stopBits) {
            UsbSerialPort.STOPBITS_1, UsbSerialPort.STOPBITS_1_5, UsbSerialPort.STOPBITS_2 -> {}
            else -> throw IllegalArgumentException("Invalid stop bits: $stopBits")
        }
        if (parity !in UsbSerialPort.PARITY_NONE..UsbSerialPort.PARITY_SPACE) throw IllegalArgumentException("Invalid parity: $parity")
        this.baud = baud
        this.dataBits = dataBits
        this.stopBits = stopBits
        this.parity = parity
        config()
    }

    /** flow control done by the chip: none, RTS/CTS or XON/XOFF */
    fun setFlow(f: UsbSerialPort.FlowControl) {
        if (f != UsbSerialPort.FlowControl.NONE && f != UsbSerialPort.FlowControl.RTS_CTS && f != UsbSerialPort.FlowControl.XON_XOFF) {
            throw UnsupportedOperationException("Unsupported flow control: $f")
        }
        flow = f
        config()
    }

    /** the configuration block (SET_CONFIG) written; it raises DTR and RTS, so they are set again as they were */
    private fun config() {
        out(SET_CONFIG, 0, configBlock(baud, dataBits, stopBits, parity, flow))
        lines(dtr, rts)
    }

    /** DTR and RTS: the UART's modem control register */
    fun lines(dtr: Boolean, rts: Boolean) {
        val mcr = (if (dtr) MCR_DTR else 0) or (if (rts) MCR_RTS else 0)
        writeXdata(UART1_BASE + OFFSET_MCR, MCR_DTR or MCR_RTS or MCR_LOOP, mcr)
        this.dtr = dtr
        this.rts = rts
    }

    /** a break held on the line, or ended: the line control register's break bit */
    fun setBreak(on: Boolean) = writeXdata(UART1_BASE + OFFSET_LCR, LCR_BREAK, if (on) LCR_BREAK else 0)

    /** [value] in the bits of [mask] of the octet at [addr] of the chip's XDATA */
    private fun writeXdata(addr: Int, mask: Int, value: Int) {
        val d = byteArrayOf(
            ADDR_XDATA.toByte(), DATA_BYTE.toByte(), 1,
            (addr ushr 24).toByte(), (addr ushr 16).toByte(), (addr ushr 8).toByte(), addr.toByte(),
            mask.toByte(), value.toByte(),
        )
        val n = control.transfer(0x40, WRITE_DATA, 0, RAM_PORT, d, d.size)
        if (n < 0) throw IOException("TUSB3410 write to 0x%04x failed".format(addr))
    }

    /** the lines in (CTS, DSR, CD, RI): the port's status */
    fun status(): Status {
        val b = ByteArray(5)
        val n = control.transfer(0xC0, GET_PORT_STATUS, 0, UART1, b, b.size)
        if (n != b.size) throw IOException("TUSB3410 port status failed")
        val msr = b[3].toInt() and 0xff
        return Status(cts = msr and MSR_CTS != 0, dsr = msr and MSR_DSR != 0, cd = msr and MSR_CD != 0, ri = msr and MSR_RI != 0)
    }

    data class Status(val cts: Boolean, val dsr: Boolean, val cd: Boolean, val ri: Boolean)

    /** the chip's buffers emptied, either way */
    fun purge(tx: Boolean, rx: Boolean) {
        if (rx) out(PURGE_PORT, PURGE_INPUT)
        if (tx) out(PURGE_PORT, PURGE_OUTPUT)
    }

    companion object {
        // the commands (bRequest)
        const val GET_PORT_STATUS = 0x02
        const val SET_CONFIG = 0x05
        const val OPEN_PORT = 0x06
        const val CLOSE_PORT = 0x07
        const val START_PORT = 0x08
        const val PURGE_PORT = 0x0B
        const val WRITE_DATA = 0x80

        // the modules (wIndex)
        const val UART1 = 0x03
        const val RAM_PORT = 0x05

        /** the bulk pipes in continuous mode, with a timeout of 2 */
        const val OPEN_SETTINGS = 0x01 or 0x80 or (2 shl 2)

        const val PURGE_OUTPUT = 0x00
        const val PURGE_INPUT = 0x80

        // WRITE_DATA: the address space and the data's size
        const val ADDR_XDATA = 0x30
        const val DATA_BYTE = 0x01

        // UART 1's registers in XDATA
        const val UART1_BASE = 0xFFA0
        const val OFFSET_LCR = 0x02
        const val OFFSET_MCR = 0x04
        const val LCR_BREAK = 0x40
        const val MCR_LOOP = 0x04
        const val MCR_DTR = 0x10
        const val MCR_RTS = 0x20

        // the modem status register
        const val MSR_CTS = 0x10
        const val MSR_DSR = 0x20
        const val MSR_RI = 0x40
        const val MSR_CD = 0x80

        // the configuration block's flags
        const val RTS_IN = 0x0001
        const val PARITY_CHECK = 0x0008
        const val CTS_OUT = 0x0020
        const val X_OUT = 0x0040
        const val X_IN = 0x0100
        const val MS_INTS = 0x2000
        const val AUTO_START_DMA = 0x4000

        /** the TUSB3410's baud divisor for [baud] (its clock: 923077 Hz in these units), rounded */
        fun divisor(baud: Int): Int = (923077 + baud / 2) / baud

        /**
         * the 10-octet configuration block: the divisor and the flags
         * (big-endian), the data bits, the parity, the stop bits, XON,
         * XOFF, and the mode (RS-232)
         */
        fun configBlock(baud: Int, dataBits: Int, stopBits: Int, parity: Int, flow: UsbSerialPort.FlowControl): ByteArray {
            var flags = MS_INTS or AUTO_START_DMA
            if (parity != UsbSerialPort.PARITY_NONE) flags = flags or PARITY_CHECK
            when (flow) {
                UsbSerialPort.FlowControl.RTS_CTS -> flags = flags or RTS_IN or CTS_OUT
                UsbSerialPort.FlowControl.XON_XOFF -> flags = flags or X_IN or X_OUT
                else -> {}
            }
            val d = divisor(baud)
            // (the library's parity codes are the chip's: none, odd, even, mark, space)
            val stop = when (stopBits) {
                UsbSerialPort.STOPBITS_2 -> 2
                UsbSerialPort.STOPBITS_1_5 -> 1
                else -> 0
            }
            return byteArrayOf(
                (d shr 8).toByte(), d.toByte(),
                (flags shr 8).toByte(), flags.toByte(),
                (dataBits - 5).toByte(), parity.toByte(), stop.toByte(),
                UsbSerialPort.CHAR_XON.code.toByte(), UsbSerialPort.CHAR_XOFF.code.toByte(),
                0,
            )
        }
    }
}

/**
 * The port of a MultiTech modem with a TUSB3410 for usb-serial-for-android,
 * once its firmware runs (SerialAdapters loads the firmware first).
 */
class TiSerialDriver(private val device: UsbDevice) : UsbSerialDriver {
    private val port = TiSerialPort()

    /** the device the port is on */
    override fun getDevice(): UsbDevice = device

    /** its one port */
    override fun getPorts(): List<UsbSerialPort> = listOf(port)

    inner class TiSerialPort : CommonUsbSerialPort(device, 0) {
        private var uart: TiUart? = null
        private var itf: UsbInterface? = null
        // (kept while closed, as set before open)
        private var dtr = false
        private var rts = false

        /** the driver the port is of */
        override fun getDriver(): UsbSerialDriver = this@TiSerialDriver

        /** the open port's UART; an IOException when closed */
        private fun uart(): TiUart = uart ?: throw IOException("Port not open")

        /**
         * the port opened: the firmware's configuration selected, its
         * interface claimed, its bulk pipes found, the UART set up with DTR,
         * RTS and flow as set before
         */
        override fun openInt() {
            if (TiFirmware.needsFirmware(device)) throw IOException("its firmware is not loaded")
            val conn = mConnection
            val config = (0 until device.configurationCount).map { device.getConfiguration(it) }
                .firstOrNull { it.id == TiFirmware.ACTIVE_CONFIG }
                ?: throw IOException("no configuration ${TiFirmware.ACTIVE_CONFIG}")
            if (!conn.setConfiguration(config)) throw IOException("could not select configuration ${TiFirmware.ACTIVE_CONFIG}")
            if (config.interfaceCount < 1) throw IOException("no interface")
            val i = config.getInterface(0)
            if (!conn.claimInterface(i, true)) throw IOException("Could not claim interface ${i.id}")
            itf = i
            for (e in 0 until i.endpointCount) {
                val ep = i.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) mReadEndpoint = ep else mWriteEndpoint = ep
            }
            if (mReadEndpoint == null || mWriteEndpoint == null) throw IOException("no bulk pipes")
            val u = TiUart { type, req, value, index, buf, len ->
                conn.controlTransfer(type, req, value, index, buf, len, TIMEOUT)
            }
            u.open(dtr, rts, mFlowControl)
            uart = u
        }

        /** the port closed, its interface let go */
        override fun closeInt() {
            runCatching { uart?.close() }
            uart = null
            itf?.let { i -> runCatching { mConnection.releaseInterface(i) } }
            itf = null
        }

        /** the UART's speed and character format */
        override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) =
            uart().setLine(baudRate, dataBits, stopBits, parity)

        /** CD, from the port's status */
        override fun getCD() = uart().status().cd
        /** CTS, from the port's status */
        override fun getCTS() = uart().status().cts
        /** DSR, from the port's status */
        override fun getDSR() = uart().status().dsr
        /** RI, from the port's status */
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

        /** the lines: DTR and RTS as set, the rest from the port's status */
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

        /** all of them: the modem has every line */
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

        /** the chip's buffers emptied, either way */
        override fun purgeHwBuffers(purgeWriteBuffers: Boolean, purgeReadBuffers: Boolean) =
            uart().purge(tx = purgeWriteBuffers, rx = purgeReadBuffers)

        /** a break held on the line, or ended */
        override fun setBreak(value: Boolean) = uart().setBreak(value)
    }

    companion object {
        private const val TIMEOUT = 1000

        /** usb-serial-for-android's table: the MultiTech modems this driver takes */
        @JvmStatic
        fun getSupportedDevices(): Map<Int, IntArray> = mapOf(TiIds.MULTITECH to TiIds.products)
    }
}
