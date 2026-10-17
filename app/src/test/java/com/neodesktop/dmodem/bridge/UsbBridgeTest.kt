// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.neodesktop.dmodem.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The modem on a USB serial adapter (a port of its driver's, faked: what
 * the computer sends, its DTR on the adapter's DSR) and on the phone's
 * own USB port as a gadget (a pty for /dev/ttyGS0, root faked).
 */
class UsbBridgeTest {

    private class FakeModem : TerminalLink {
        override val state: StateFlow<TerminalLink.State> = MutableStateFlow(TerminalLink.State.Open)
        val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()
        val got = ByteArrayOutputStream()
        override fun addListener(l: TerminalLink.Listener) { listeners.add(l) }
        override fun removeListener(l: TerminalLink.Listener) { listeners.remove(l) }
        override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
            synchronized(got) { got.write(data, offset, length) }
            if (String(data, offset, length).endsWith("\r")) {
                val ok = "\r\nOK\r\n".toByteArray()
                listeners.forEach { it.onData(ok, ok.size) }
            }
            return true
        }
        fun sent() = synchronized(got) { String(got.toByteArray()) }
    }

    /** an adapter's port: what the computer sends (to read), what it got, its lines */
    private class FakePort : UsbSerialPort {
        val fromComputer = LinkedBlockingQueue<ByteArray>()
        val toComputer = ByteArrayOutputStream()
        @Volatile var dtr = false
        @Volatile var rts = false
        @Volatile var dsr = true
        @Volatile var closed = false
        override fun read(dest: ByteArray, timeout: Int): Int {
            if (closed) throw IOException("closed")
            val b = fromComputer.poll(timeout.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            System.arraycopy(b, 0, dest, 0, b.size)
            return b.size
        }
        override fun read(dest: ByteArray, length: Int, timeout: Int): Int = read(dest, timeout)
        override fun write(src: ByteArray, timeout: Int) { synchronized(toComputer) { toComputer.write(src) } }
        override fun write(src: ByteArray, length: Int, timeout: Int) { synchronized(toComputer) { toComputer.write(src, 0, length) } }
        override fun setDTR(value: Boolean) { dtr = value }
        override fun setRTS(value: Boolean) { rts = value }
        override fun getDSR() = dsr
        override fun getDTR() = dtr
        override fun getRTS() = rts
        override fun getCD() = dsr
        override fun getCTS() = true
        override fun getRI() = false
        override fun close() { closed = true }
        override fun isOpen() = !closed
        override fun open(connection: UsbDeviceConnection?) {}
        override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {}
        override fun getDriver(): UsbSerialDriver? = null
        override fun getDevice(): UsbDevice? = null
        override fun getPortNumber() = 0
        override fun getWriteEndpoint(): UsbEndpoint? = null
        override fun getReadEndpoint(): UsbEndpoint? = null
        override fun getSerial(): String? = null
        override fun getControlLines(): EnumSet<UsbSerialPort.ControlLine> = EnumSet.noneOf(UsbSerialPort.ControlLine::class.java)
        override fun getSupportedControlLines(): EnumSet<UsbSerialPort.ControlLine> = EnumSet.allOf(UsbSerialPort.ControlLine::class.java)
        override fun setFlowControl(flowControl: UsbSerialPort.FlowControl?) {}
        override fun getFlowControl() = UsbSerialPort.FlowControl.NONE
        override fun getSupportedFlowControl(): EnumSet<UsbSerialPort.FlowControl> = EnumSet.of(UsbSerialPort.FlowControl.NONE)
        override fun getXON() = true
        override fun purgeHwBuffers(purgeWriteBuffers: Boolean, purgeReadBuffers: Boolean) {}
        override fun setBreak(value: Boolean) {}
        fun said() = synchronized(toComputer) { String(toComputer.toByteArray()) }
    }

    private fun waitUntil(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(what, ok())
    }

    @Test
    fun anAdapterCarriesTheModemAndItsLines() {
        val modem = FakeModem()
        val signals = MutableStateFlow(LineSignals(ready = true))
        val hangUps = AtomicInteger()
        val bridge = SerialBridge(modem, signals, { hangUps.incrementAndGet() }, hangUpOnClose = false)
        val port = FakePort()
        val link = UsbPortLink(port, bridge, signals, "FTDI FT232R")
        val t = Thread { link.serve() }.apply { start() }
        waitUntil("claimed") { bridge.client.value?.via == "usb" }
        assertEquals("FTDI FT232R", bridge.client.value?.name)
        // the computer's command in, the modem's answer out
        port.fromComputer.add("ATDT5551212\r".toByteArray())
        waitUntil("OK to the computer") { port.said().contains("OK") }
        assertEquals("ATDT5551212\r", modem.sent())
        // RTS on; DTR (the computer's DCD) off until a carrier, then on
        assertTrue(port.rts)
        assertTrue(!port.dtr)
        signals.value = LineSignals(ready = true, offhook = true, carrier = true)
        waitUntil("DCD on") { port.dtr }
        // the computer drops its DTR: hung up
        port.dsr = false
        waitUntil("hung up") { hangUps.get() == 1 }
        // unplugged: the port closed, the bridge free
        link.close()
        t.join(5000)
        assertEquals(null, bridge.client.value)
    }

    @Test
    fun thePhonesUsbPortAsAGadget() {
        assumeTrue(File("/usr/bin/python3").canExecute())
        // a pty pair: its slave as /dev/ttyGS0, its master (the computer's side) through python
        val py = ProcessBuilder(
            "python3", "-u", "-c",
            """
import os, pty, tty, sys, threading
m, s = pty.openpty(); tty.setraw(s); tty.setraw(m)
print(os.ttyname(s), flush=True)
def out():
    while True:
        d = os.read(m, 4096)
        sys.stdout.write(d.decode('latin-1').replace('\r', '<CR>').replace('\n', '<LF>') + '\n'); sys.stdout.flush()
threading.Thread(target=out, daemon=True).start()
for line in sys.stdin:
    os.write(m, line.rstrip('\n').replace('<CR>', '\r').encode())
            """.trimIndent(),
        ).start()
        try {
            val r = py.inputStream.bufferedReader()
            val tty = r.readLine()
            val modem = FakeModem()
            val signals = MutableStateFlow(LineSignals(ready = true))
            val bridge = SerialBridge(modem, signals, {}, hangUpOnClose = false)
            val commands = CopyOnWriteArrayList<String>()
            val g = UsbGadget(bridge, root = { cmd -> commands.add(cmd); 0 to "ok" }, tty = tty)
            assertTrue(g.start())
            assertTrue(commands.single().contains("functions/acm.gs0"))
            waitUntil("claimed") { bridge.client.value?.via == "usb" }
            assertEquals(UsbGadget.State.Connected, g.state.value)
            // the computer types on its COM port
            py.outputStream.write("ATI<CR>\n".toByteArray())
            py.outputStream.flush()
            val seen = StringBuilder()
            val end = System.currentTimeMillis() + 5000
            while (!seen.contains("OK") && System.currentTimeMillis() < end) seen.append(r.readLine())
            assertTrue(seen.toString(), seen.contains("OK"))
            assertEquals("ATI\r", modem.sent())
            g.stop()
            assertTrue(commands.last().contains("rm "))
        } finally {
            py.destroy()
        }
        // the root scripts at least parse
        for (script in listOf(UsbGadget.ENABLE, UsbGadget.DISABLE)) {
            val p = ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString()
            assertEquals(out, 0, p.waitFor())
        }
    }

    @Test
    fun formats() {
        assertEquals(SerialFormat(9600, 7, 'E', 1), Bridges.format(9600, "7e1"))
        assertEquals(SerialFormat(115200), Bridges.format(115200, "bad"))
    }
}
