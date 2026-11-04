// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.resume

/**
 * A serial port the app has open, as the modem's line (an external modem on it) and the local Linux's
 * /dev/ttyUSB0 use it: its octets, its speed and format, its lines. A USB serial adapter's
 * ([UsbSerialLine]), or the tests' own.
 */
interface SerialLine : Closeable {
    /** what to call it ("Prolific PL2303GS") */
    val name: String

    /** what came, into [buf]: 0 when nothing did in [timeoutMs]; an IOException once it's gone */
    fun read(buf: ByteArray, timeoutMs: Int): Int

    /** [len] octets of [b] from [off] out; an IOException once it's gone */
    fun write(b: ByteArray, off: Int = 0, len: Int = b.size)

    /** its speed and character format */
    fun setFormat(f: SerialFormat)

    /** RTS/CTS flow control on or off: false if it can't */
    fun setRtsCts(on: Boolean): Boolean

    /** its DTR out */
    fun setDtr(on: Boolean)

    /** its RTS out */
    fun setRts(on: Boolean)

    /** DCD in (the modem there has a carrier), or null if it can't say */
    fun carrier(): Boolean?
}

/** A USB serial adapter's port as a [SerialLine] (usb-serial-for-android's driver for it). */
class UsbSerialLine(private val port: UsbSerialPort, override val name: String, private val device: String) : SerialLine {
    @Volatile private var open = true

    /** what came in [timeoutMs] (0: nothing); an IOException once closed or unplugged */
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        if (!open) throw IOException("$name is closed")
        return port.read(buf, timeoutMs)
    }

    /** out to the adapter (2 s at most for it to take them) */
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!open) throw IOException("$name is closed")
        port.write(if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len), 2000)
    }

    /** the adapter's speed and format set (a speed it can't do: an IOException) */
    override fun setFormat(f: SerialFormat) = port.setParameters(f.baud, f.dataBits, f.stopCode, f.parityCode)

    /** the adapter's own RTS/CTS flow control, if its chip has it */
    override fun setRtsCts(on: Boolean): Boolean {
        val want = if (on) UsbSerialPort.FlowControl.RTS_CTS else UsbSerialPort.FlowControl.NONE
        if (want !in port.supportedFlowControl) return !on
        return runCatching { port.setFlowControl(want) }.isSuccess
    }

    /** DTR out */
    override fun setDtr(on: Boolean) = port.setDTR(on)

    /** RTS out */
    override fun setRts(on: Boolean) = port.setRTS(on)

    /** DCD, where the chip's driver can read it */
    override fun carrier(): Boolean? =
        if (UsbSerialPort.ControlLine.CD in runCatching { port.supportedControlLines }.getOrDefault(emptySet<UsbSerialPort.ControlLine>())) {
            runCatching { port.cd }.getOrNull()
        } else null

    /** closed (its DTR drops), and the adapter free for others again */
    override fun close() {
        if (!open) return
        open = false
        runCatching { port.close() }
        SerialAdapters.release(device)
    }
}

/**
 * Who has each USB serial adapter plugged in (the modem's line, the local Linux's /dev/ttyUSB0, the
 * USB bridge), so that two of them never open the same one; and an adapter opened for one of them.
 */
object SerialAdapters {
    private val holders = HashMap<String, String>()

    /** adapter [device] (its device name) to [who], unless another has it */
    @Synchronized
    fun take(device: String, who: String): Boolean {
        val h = holders[device]
        if (h != null && h != who) return false
        holders[device] = who
        return true
    }

    /** adapter [device] free again */
    @Synchronized
    fun release(device: String) {
        holders.remove(device)
    }

    /** who has adapter [device], or null */
    @Synchronized
    fun holder(device: String): String? = holders[device]

    /** an adapter opened, or why none was */
    sealed interface Opened {
        class Line(val line: UsbSerialLine) : Opened
        class Failed(val why: String) : Opened
    }

    private const val ACTION_PERMISSION = "org.dmodem.app.android.USB_SERIAL_PERMISSION"

    /** [d]'s maker and product, else its USB IDs */
    fun label(d: UsbDevice): String =
        (listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { null })
            ?: "USB %04x:%04x".format(d.vendorId, d.productId)

    /** the adapters plugged in that no one has: their names */
    fun free(context: Context): List<String> {
        val m = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return UsbSerialAdapters.prober.findAllDrivers(m).map { it.device }.filter { holder(it.deviceName) == null }.map { label(it) }
    }

    /**
     * The first USB serial adapter plugged in that no one else has, opened for [who] at [format]:
     * Android asked first if the app may use it (the user has [askMs] to say), unless it has said so.
     */
    suspend fun open(context: Context, who: String, format: SerialFormat, askMs: Long = 60_000): Opened {
        val m = context.getSystemService(UsbManager::class.java) ?: return Opened.Failed("this phone has no USB host")
        val drivers = UsbSerialAdapters.prober.findAllDrivers(m)
        if (drivers.isEmpty()) return Opened.Failed("no USB serial adapter plugged in")
        val driver = drivers.firstOrNull { holder(it.device.deviceName).let { h -> h == null || h == who } }
            ?: return Opened.Failed("the USB serial adapter is in use (${drivers.mapNotNull { holder(it.device.deviceName) }.distinct().joinToString()})")
        val dev = driver.device
        val label = label(dev)
        if (!m.hasPermission(dev)) {
            val allowed = withTimeoutOrNull(askMs) { ask(context, m, dev) } ?: false
            if (!allowed) return Opened.Failed("$label wasn't allowed: allow D-Modem to use it, then try again")
        }
        if (!take(dev.deviceName, who)) return Opened.Failed("$label is in use (${holder(dev.deviceName)})")
        val conn = m.openDevice(dev)
        if (conn == null) {
            release(dev.deviceName)
            return Opened.Failed("can't open $label")
        }
        val port = driver.ports.first()
        return try {
            port.open(conn)
            port.setParameters(format.baud, format.dataBits, format.stopCode, format.parityCode)
            Opened.Line(UsbSerialLine(port, label, dev.deviceName))
        } catch (e: IOException) {
            runCatching { port.close() }
            release(dev.deviceName)
            Opened.Failed("$label: ${e.message}")
        }
    }

    /** Android's question for [dev]: whether it was allowed */
    private suspend fun ask(context: Context, m: UsbManager, dev: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                /** Android's answer: the question's receiver let go, [cont] resumed with it */
                override fun onReceive(c: Context, i: Intent) {
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            m.requestPermission(dev, PendingIntent.getBroadcast(context, 3, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags))
        }
}
