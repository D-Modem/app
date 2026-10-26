// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** a serial line's character format and speed */
data class SerialFormat(val baud: Int = 115200, val dataBits: Int = 8, val parity: Char = 'N', val stopBits: Int = 1) {
    override fun toString() = "$baud $dataBits$parity$stopBits"

    val parityCode: Int get() = when (parity) {
        'E' -> UsbSerialPort.PARITY_EVEN
        'O' -> UsbSerialPort.PARITY_ODD
        'M' -> UsbSerialPort.PARITY_MARK
        'S' -> UsbSerialPort.PARITY_SPACE
        else -> UsbSerialPort.PARITY_NONE
    }

    val stopCode: Int get() = if (stopBits == 2) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1
}

/**
 * An open USB serial adapter's port serving the modem through [bridge],
 * cabled as a modem is (a null modem to the computer): its DTR out the
 * modem's carrier (the computer's DCD) when [carrierOnDtr], RTS on; the
 * computer's DTR (on the adapter's DSR) dropped hangs up when
 * [dtrHangsUp]. An adapter with more ([extra]: a pico-serial board, a CDC
 * adapter's notifications) drives the modem's lines itself (DCD, DSR and
 * RI each its own) and tells the computer's DTR where the port can't.
 * Until the port fails, or [close].
 */
class UsbPortLink(
    private val port: UsbSerialPort,
    private val bridge: SerialBridge,
    private val signals: StateFlow<LineSignals>,
    private val name: String,
    private val carrierOnDtr: Boolean = true,
    private val dtrHangsUp: Boolean = true,
    private val extra: AdapterLines? = null,
) {
    @Volatile private var open = true

    fun close() {
        open = false
        runCatching { extra?.close() }
        runCatching { port.close() }
    }

    private val input = object : InputStream() {
        private val buf = ByteArray(4096)
        private var have = 0
        private var at = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (open) {
                if (at < have) {
                    val n = minOf(len, have - at)
                    System.arraycopy(buf, at, b, off, n)
                    at += n
                    return n
                }
                // (a timeout: the port polled again, so a close is seen)
                have = try { port.read(buf, 200) } catch (_: IOException) { return -1 }
                at = 0
            }
            return -1
        }
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!open) throw IOException("the adapter is closed")
            port.write(b.copyOfRange(off, off + len), 2000)
        }
    }

    /** served until the adapter goes (false: another client had the modem) */
    fun serve(): Boolean {
        val p = Port("usb", name, input, output) { close() }
        if (!bridge.tryClaim(p)) {
            close()
            return false
        }
        val lines = Thread({ watchLines() }, "usb-lines").apply { isDaemon = true; start() }
        try {
            bridge.serve(p, null)
        } finally {
            open = false
            lines.interrupt()
        }
        return true
    }

    /** the modem's lines out, the computer's DTR in */
    private fun watchLines() {
        var lastCarrier: Boolean? = null
        var computerDtr: Boolean? = null
        runCatching { port.setRTS(true) }
        while (open) {
            val s = bridge.lines()
            val carrier = if (carrierOnDtr) s.carrier else s.ready
            // (the adapter's own lines when it has them; its DTR on, the port open to it)
            val wanted = if (runCatching { extra?.drive(dcd = carrier, dsr = s.ready, ri = s.ringing) }.getOrNull() == true) true else carrier
            if (wanted != lastCarrier) {
                runCatching { port.setDTR(wanted) }
                lastCarrier = wanted
            }
            if (dtrHangsUp) {
                // (CDC drivers can't read DSR: the adapter's own word for it first)
                val dsr = runCatching { extra?.computerDtr() }.getOrNull() ?: runCatching { port.dsr }.getOrNull()
                if (dsr != null) {
                    // (its fall: the computer's DTR dropped)
                    if (computerDtr == true && !dsr) bridge.dropDtr()
                    computerDtr = dsr
                }
            }
            try { Thread.sleep(150) } catch (_: InterruptedException) { break }
        }
    }
}

/**
 * The modem on a USB serial adapter plugged into the phone (FTDI, CP210x,
 * CH34x, PL2303, CDC-ACM; a pico-serial board's lines and settings too):
 * the first one found, again whenever one is
 * plugged in while it's on; Android's permission for it asked for.
 */
@SuppressLint("UnspecifiedRegisterReceiverFlag")
class UsbBridge(
    private val context: Context,
    private val bridge: SerialBridge,
    private val signals: StateFlow<LineSignals>,
    val format: SerialFormat = SerialFormat(),
    private val carrierOnDtr: Boolean = true,
    private val dtrHangsUp: Boolean = true,
) {
    sealed interface State {
        data object Stopped : State
        data object Waiting : State
        data class NeedsPermission(val device: String) : State
        data class Connected(val device: String) : State
        data class Failed(val why: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    /** the pico-serial board plugged in, while there's one */
    private val _board = MutableStateFlow<PicoBoard?>(null)
    val board: StateFlow<PicoBoard?> = _board.asStateFlow()
    @Volatile private var pico: PicoSerialLines? = null

    /** [f] done to the pico-serial board (its settings), its state read again after; false when there's none */
    fun withBoard(f: (PicoSerial) -> Boolean): Boolean {
        val p = pico ?: return false
        val ok = f(p.pico)
        p.refresh()
        return ok
    }

    private val usb: UsbManager? get() = context.getSystemService(UsbManager::class.java)
    @Volatile private var link: UsbPortLink? = null
    @Volatile private var running = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, ACTION_PERMISSION -> if (link == null) Thread { find() }.start()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    link?.close()
                }
            }
        }
    }

    fun start() {
        if (running) return
        if (usb == null) {
            _state.value = State.Failed("this phone has no USB host")
            return
        }
        running = true
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, f)
        Thread { find() }.start()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { context.unregisterReceiver(receiver) }
        link?.close()
        link = null
        _state.value = State.Stopped
    }

    /** the adapters there are: (a name, the device) */
    fun adapters(): List<Pair<String, UsbDevice>> =
        usb?.let { m -> UsbSerialAdapters.prober.findAllDrivers(m).map { label(it.device) to it.device } }.orEmpty()

    @Synchronized
    private fun find() {
        if (!running || link != null) return
        val m = usb ?: return
        val driver = UsbSerialAdapters.prober.findAllDrivers(m).firstOrNull()
        if (driver == null) {
            _state.value = State.Waiting
            return
        }
        val dev = driver.device
        if (!m.hasPermission(dev)) {
            _state.value = State.NeedsPermission(label(dev))
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
            m.requestPermission(dev, pi)
            return
        }
        val conn = m.openDevice(dev)
        if (conn == null) {
            _state.value = State.Failed("can't open ${label(dev)}")
            return
        }
        val port = driver.ports.first()
        try {
            port.open(conn)
            port.setParameters(format.baud, format.dataBits, format.stopCode, format.parityCode)
        } catch (e: IOException) {
            runCatching { port.close() }
            _state.value = State.Failed("${label(dev)}: ${e.message}")
            return
        }
        // (an Exar's lines are its driver's, from its GPIOs)
        val extra = if (driver is XrSerialDriver) null else extraLines(dev, conn)
        val boardJob = (extra as? PicoSerialLines)?.let { p ->
            pico = p
            Thread({
                // (the board's state to the app as the link reads it)
                try {
                    while (pico === p) {
                        _board.value = p.board.value
                        Thread.sleep(300)
                    }
                } catch (_: InterruptedException) {
                }
            }, "usb-board").apply { isDaemon = true; start() }
        }
        val l = UsbPortLink(port, bridge, signals, label(dev), carrierOnDtr, dtrHangsUp, extra)
        link = l
        _state.value = State.Connected(label(dev))
        Thread({
            val served = l.serve()
            pico = null
            boardJob?.interrupt()
            _board.value = null
            link = null
            _state.value = if (!served) State.Failed("the modem is in use") else State.Waiting
            // (another adapter, or this one again once replugged)
            if (running) find()
        }, "usb-bridge").apply { isDaemon = true; start() }
    }

    /**
     * what [dev] has beyond its port: a pico-serial board's control
     * interface, else a CDC adapter's notifications, else nothing
     */
    private fun extraLines(dev: UsbDevice, conn: android.hardware.usb.UsbDeviceConnection): AdapterLines? {
        PicoSerial.controlInterface(dev)?.let { itf ->
            if (conn.claimInterface(itf, false)) {
                val p = PicoSerial(AndroidUsbControl(conn, itf.id))
                if (p.info()?.protocol == PicoSerialProtocol.VERSION) return PicoSerialLines(p)
            }
        }
        val ep = CdcLineState.endpoint(dev) ?: return null
        return CdcLineState { buf, ms -> conn.bulkTransfer(ep, buf, buf.size, ms) }.start()
    }

    private fun label(d: UsbDevice): String =
        (listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { null })
            ?: "USB %04x:%04x".format(d.vendorId, d.productId)

    companion object {
        const val ACTION_PERMISSION = "com.neodesktop.dmodem.USB_PERMISSION"
    }
}
