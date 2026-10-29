// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pico-serial board's control protocol (github.com/D-Modem/hardware,
 * firmware/PROTOCOL.md, version 1): vendor control requests on its
 * "pico-serial control" interface (class 0xFF, subclass 0x50, protocol 1),
 * for what a plain CDC serial port doesn't carry: the board's role (DCE to
 * a computer, DTE to a modem), every modem line each way, its settings.
 */
object PicoSerialProtocol {
    const val ITF_CLASS = 0xFF
    const val ITF_SUBCLASS = 0x50
    const val ITF_PROTOCOL = 0x01
    const val VERSION = 1

    const val GET_INFO = 1
    const val SET_MODE = 2
    const val SET_OUTPUTS = 3
    const val GET_INPUTS = 4
    const val SET_FLOW = 5
    const val GET_STATS = 6
    const val CLEAR_STATS = 7
    const val SET_BAUD_MODE = 8
    const val AUTOBAUD_RESTART = 9

    // the modem lines (1: asserted)
    const val DTR = 0x01
    const val RTS = 0x02
    const val DCD = 0x04
    const val DSR = 0x08
    const val RI = 0x10
    const val CTS = 0x20

    // GET_INFO's flags
    const val FLAG_CODING_ADJUSTED = 0x01
    const val FLAG_BAUD_OVER_RATING = 0x02
    const val FLAG_FLOW_ACTIVE = 0x04
    const val FLAG_BAUD_LEARNED = 0x08
    const val FLAG_BAUD_LEARNING = 0x10

    // GET_INPUTS' detect
    const val DETECT_COMPUTER = 0x01
    const val DETECT_MODEM = 0x02

    enum class Mode(val code: Int, val label: String) {
        AUTO(0, "Automatic"), DCE(1, "Modem (DCE)"), DTE(2, "Computer port (DTE)");

        companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } }
    }

    enum class Role(val code: Int, val label: String) {
        NONE(0, "nothing plugged in"), DCE(1, "a modem to a computer (DCE)"), DTE(2, "a computer port to a modem (DTE)");

        companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } ?: NONE }
    }

    enum class Flow(val code: Int, val label: String) {
        AUTO(0, "Automatic"), ON(1, "On"), OFF(2, "Off");

        companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } }
    }

    enum class BaudMode(val code: Int, val label: String) {
        AUTO(0, "Learned from the computer"), HOST(1, "This app's speed"), LEARN(2, "Always learned");

        companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } }
    }

    /** GET_INFO's ps_info_t (14 bytes, more in later firmware) */
    data class Info(
        val protocol: Int,
        val firmware: String,
        /** 0 the DB25 hood board, 1 W (wireless), 2 a Pico bench rig */
        val board: Int,
        val mode: Mode?,
        val role: Role,
        val flow: Flow?,
        val flags: Int,
        /** what the UART runs at now (learned, or the host's) */
        val baud: Long,
        val baudMode: BaudMode?,
    ) {
        val boardName: String get() = when (board) {
            0 -> "pico-serial DB25"
            1 -> "pico-serial W"
            2 -> "pico-serial bench rig"
            else -> "pico-serial (board $board)"
        }
        val learned: Boolean get() = flags and FLAG_BAUD_LEARNED != 0
        val learning: Boolean get() = flags and FLAG_BAUD_LEARNING != 0
        val flowActive: Boolean get() = flags and FLAG_FLOW_ACTIVE != 0
    }

    /** GET_INPUTS' ps_inputs_t (5 bytes) */
    data class Inputs(
        val role: Role,
        /** the lines read from the far end (as DCE: the computer's DTR, RTS) */
        val inputs: Int,
        /** the lines the board drives now */
        val outputs: Int,
        val detect: Int,
        /** DTR, RTS as the host last set them */
        val host: Int,
    )

    /** GET_STATS' ps_stats_t (8 × u32) */
    data class Stats(
        val toRs232: Long,
        val fromRs232: Long,
        val framing: Long,
        val parity: Long,
        val overrun: Long,
        val breaks: Long,
        val dropped: Long,
        val roleChanges: Long,
    )

    /** [b]'s byte at [i], unsigned */
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    /** [b]'s little-endian u32 at [i] */
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    /** GET_INFO's reply as an [Info], or null when it's short or no protocol */
    fun info(b: ByteArray): Info? {
        if (b.size < 14 || u8(b, 0) < 1) return null
        return Info(
            protocol = u8(b, 0),
            firmware = "${u8(b, 1)}.${u8(b, 2)}.${u8(b, 3)}",
            board = u8(b, 4),
            mode = Mode.of(u8(b, 5)),
            role = Role.of(u8(b, 6)),
            flow = Flow.of(u8(b, 7)),
            flags = u8(b, 8),
            baud = u32(b, 9),
            baudMode = BaudMode.of(u8(b, 13)),
        )
    }

    /** GET_INPUTS' reply as [Inputs], or null when it's short */
    fun inputs(b: ByteArray): Inputs? {
        if (b.size < 5) return null
        return Inputs(Role.of(u8(b, 0)), u8(b, 1), u8(b, 2), u8(b, 3), u8(b, 4))
    }

    /** GET_STATS' reply as [Stats], or null when it's short */
    fun stats(b: ByteArray): Stats? {
        if (b.size < 32) return null
        val v = LongArray(8) { u32(b, it * 4) }
        return Stats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
    }

    /** SET_OUTPUTS' wValue: the lines in [mask] to [value] */
    fun outputs(mask: Int, value: Int): Int = (mask and 0xff shl 8) or (value and mask and 0xff)

    /** the modem's lines to the computer: DCD, DSR and RI */
    fun dceOutputs(dcd: Boolean, dsr: Boolean, ri: Boolean): Int =
        (if (dcd) DCD else 0) or (if (dsr) DSR else 0) or (if (ri) RI else 0)

    const val DCE_MASK = DCD or DSR or RI
}

/** a device's control requests to one interface (the board's), as USB has them: IN with data, OUT without */
interface UsbControl {
    /** an IN request: what came back (up to [length]), null if it failed or stalled */
    fun read(request: Int, value: Int, length: Int): ByteArray?

    /** an OUT request with no data: whether it went */
    fun write(request: Int, value: Int): Boolean
}

/** [UsbControl] on Android's USB host: vendor requests to [itf] on [conn] */
class AndroidUsbControl(private val conn: UsbDeviceConnection, private val itf: Int) : UsbControl {
    /** a vendor IN request to [itf]: its data, null if it failed */
    override fun read(request: Int, value: Int, length: Int): ByteArray? {
        val buf = ByteArray(length)
        val n = conn.controlTransfer(0xC1, request, value, itf, buf, length, 500)
        return if (n < 0) null else buf.copyOf(n)
    }

    /** a vendor OUT request to [itf], no data: whether it went */
    override fun write(request: Int, value: Int): Boolean = conn.controlTransfer(0x41, request, value, itf, null, 0, 500) >= 0
}

/** A pico-serial board, by its control requests. */
class PicoSerial(private val control: UsbControl) {
    /** the board's info (GET_INFO), or null */
    fun info(): PicoSerialProtocol.Info? = control.read(PicoSerialProtocol.GET_INFO, 0, 64)?.let(PicoSerialProtocol::info)
    /** the board's lines now (GET_INPUTS), or null */
    fun inputs(): PicoSerialProtocol.Inputs? = control.read(PicoSerialProtocol.GET_INPUTS, 0, 5)?.let(PicoSerialProtocol::inputs)
    /** the board's counts (GET_STATS), or null */
    fun stats(): PicoSerialProtocol.Stats? = control.read(PicoSerialProtocol.GET_STATS, 0, 32)?.let(PicoSerialProtocol::stats)

    /** (saved in the board's flash) */
    fun setMode(m: PicoSerialProtocol.Mode) = control.write(PicoSerialProtocol.SET_MODE, m.code)
    /** the board's flow control (saved in the board's flash) */
    fun setFlow(f: PicoSerialProtocol.Flow) = control.write(PicoSerialProtocol.SET_FLOW, f.code)
    /** where the board's speed comes from (saved in the board's flash) */
    fun setBaudMode(b: PicoSerialProtocol.BaudMode) = control.write(PicoSerialProtocol.SET_BAUD_MODE, b.code)

    /** (not saved) */
    fun setOutputs(mask: Int, value: Int) = control.write(PicoSerialProtocol.SET_OUTPUTS, PicoSerialProtocol.outputs(mask, value))
    /** the board's counts zeroed */
    fun clearStats() = control.write(PicoSerialProtocol.CLEAR_STATS, 0)
    /** the board's speed learned from the computer again */
    fun relearnBaud() = control.write(PicoSerialProtocol.AUTOBAUD_RESTART, 0)

    companion object {
        /** the board's control interface on [d], or null when it isn't a pico-serial */
        fun controlInterface(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull {
            it.interfaceClass == PicoSerialProtocol.ITF_CLASS && it.interfaceSubclass == PicoSerialProtocol.ITF_SUBCLASS &&
                it.interfaceProtocol == PicoSerialProtocol.ITF_PROTOCOL
        }
    }
}

/**
 * What an adapter does beyond its port's own DTR and RTS, for
 * [UsbPortLink]: the modem's lines driven one by one, the computer's DTR
 * read where the port's driver can't.
 */
interface AdapterLines {
    /** the modem's lines to the computer driven by the adapter itself: false when it can't (the port's DTR does DCD) */
    fun drive(dcd: Boolean, dsr: Boolean, ri: Boolean): Boolean

    /** the computer's DTR, or null when it isn't known */
    fun computerDtr(): Boolean?

    /** whatever the adapter holds let go (nothing, by default) */
    fun close() {}
}

/** what the app shows of a pico-serial board */
data class PicoBoard(
    val info: PicoSerialProtocol.Info?,
    val inputs: PicoSerialProtocol.Inputs?,
    val stats: PicoSerialProtocol.Stats?,
)

/**
 * A pico-serial board's lines: as DCE (a modem to a computer) the modem's
 * DCD, DSR and RI each its own (a ring raises RI), the computer's DTR read
 * from the board; its state for the app, read now and then.
 */
class PicoSerialLines(val pico: PicoSerial, private val clock: () -> Long = System::currentTimeMillis) : AdapterLines {
    private val _board = MutableStateFlow<PicoBoard?>(null)
    val board: StateFlow<PicoBoard?> = _board.asStateFlow()

    @Volatile private var last: PicoSerialProtocol.Inputs? = null
    private var sent: Int? = null
    private var sentRole: PicoSerialProtocol.Role? = null
    private var infoAt = 0L
    private var info: PicoSerialProtocol.Info? = null
    private var stats: PicoSerialProtocol.Stats? = null

    /** (each of the link's turns: the board's inputs read first) */
    override fun drive(dcd: Boolean, dsr: Boolean, ri: Boolean): Boolean {
        val i = pico.inputs()
        last = i
        val now = clock()
        if (now - infoAt >= 2000 || i?.role != sentRole) {
            infoAt = now
            info = pico.info() ?: info
            stats = pico.stats() ?: stats
        }
        _board.value = PicoBoard(info, i, stats)
        if (i == null || i.role != PicoSerialProtocol.Role.DCE) {
            // (not a modem to a computer now: sent again once it is)
            sent = null
            sentRole = i?.role
            return false
        }
        val v = PicoSerialProtocol.dceOutputs(dcd, dsr, ri)
        if (v != sent || sentRole != i.role) {
            if (pico.setOutputs(PicoSerialProtocol.DCE_MASK, v)) sent = v
            sentRole = i.role
        }
        return true
    }

    /** the computer's DTR from the inputs the last [drive] read, or null when the board isn't DCE */
    override fun computerDtr(): Boolean? {
        val i = last ?: return null
        if (i.role != PicoSerialProtocol.Role.DCE) return null
        return i.inputs and PicoSerialProtocol.DTR != 0
    }

    /** the board's state read again now (after a setting changed) */
    fun refresh() {
        infoAt = 0
    }
}

/**
 * A CDC-ACM adapter's SERIAL_STATE notifications (its interrupt
 * endpoint), which usb-serial-for-android's CDC driver doesn't read: the
 * computer's DTR (on the adapter's DSR, cabled as a null modem), for any
 * CDC adapter (a pico-serial too, or an Arduino-style one).
 */
class CdcLineState(private val read: (ByteArray, Int) -> Int) : AdapterLines {
    /** the last notification's lines, or null before the first */
    @Volatile var state: CdcSerialState? = null
        private set
    @Volatile private var open = true

    private val thread = Thread({
        val buf = ByteArray(64)
        while (open) {
            val n = try { read(buf, 250) } catch (_: Exception) { -1 }
            if (n < 0) {
                // (a timeout reads as -1 on Android: tried again, until closed)
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                continue
            }
            CdcSerialState.parse(buf, n)?.let { state = it }
        }
    }, "cdc-notify").apply { isDaemon = true }

    /** the notifications read, on a thread of their own */
    fun start(): CdcLineState = apply { thread.start() }

    /** nothing driven: false, the port's DTR does DCD */
    override fun drive(dcd: Boolean, dsr: Boolean, ri: Boolean) = false

    /** the computer's DTR: the adapter's DSR, from the last notification */
    override fun computerDtr(): Boolean? = state?.dsr

    /** the notifications' thread stopped */
    override fun close() {
        open = false
        thread.interrupt()
    }

    companion object {
        /** [d]'s CDC notification endpoint (the communications interface's interrupt IN), or null */
        fun endpoint(d: UsbDevice): UsbEndpoint? {
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_COMM || itf.interfaceSubclass != 2) continue
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN) return ep
                }
            }
            return null
        }
    }
}

/** a CDC SERIAL_STATE notification's lines (USB CDC PSTN 6.5.4) */
data class CdcSerialState(
    /** bRxCarrier: DCD */
    val dcd: Boolean,
    /** bTxCarrier: DSR */
    val dsr: Boolean,
    val brk: Boolean,
    val ring: Boolean,
    val framing: Boolean,
    val parity: Boolean,
    val overrun: Boolean,
) {
    companion object {
        /** [n] bytes of a notification: its lines, or null when it isn't SERIAL_STATE */
        fun parse(b: ByteArray, n: Int): CdcSerialState? {
            // bmRequestType A1, bNotification 20, wValue, wIndex, wLength (2), then the state
            if (n < 10 || b[0].toInt() and 0xff != 0xA1 || b[1].toInt() and 0xff != 0x20) return null
            val v = (b[8].toInt() and 0xff) or ((b[9].toInt() and 0xff) shl 8)
            return CdcSerialState(
                dcd = v and 0x01 != 0, dsr = v and 0x02 != 0, brk = v and 0x04 != 0, ring = v and 0x08 != 0,
                framing = v and 0x10 != 0, parity = v and 0x20 != 0, overrun = v and 0x40 != 0,
            )
        }
    }
}
