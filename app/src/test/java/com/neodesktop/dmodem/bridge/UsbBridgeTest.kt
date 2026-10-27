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
 * own USB port as a gadget (a pty for /dev/ttyGS0, root faked); a
 * pico-serial board's lines and protocol (the board faked as its firmware
 * answers), a CDC adapter's SERIAL_STATE.
 */
class UsbBridgeTest {

    private class FakeModem : TerminalLink {
        override val state: StateFlow<TerminalLink.State> = MutableStateFlow(TerminalLink.State.Open)
        val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()
        val got = ByteArrayOutputStream()
        /** takes [l] for what the modem says */
        override fun addListener(l: TerminalLink.Listener) { listeners.add(l) }
        /** no more of it to [l] */
        override fun removeListener(l: TerminalLink.Listener) { listeners.remove(l) }
        /** kept as sent; a command's CR answered OK */
        override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
            synchronized(got) { got.write(data, offset, length) }
            if (String(data, offset, length).endsWith("\r")) {
                val ok = "\r\nOK\r\n".toByteArray()
                listeners.forEach { it.onData(ok, ok.size) }
            }
            return true
        }
        /** what the computer sent it, so far */
        fun sent() = synchronized(got) { String(got.toByteArray()) }
    }

    /** an adapter's port: what the computer sends (to read), what it got, its lines */
    private class FakePort : UsbSerialPort {
        val fromComputer = LinkedBlockingQueue<ByteArray>()
        val toComputer = ByteArrayOutputStream()
        @Volatile var dtr = false
        @Volatile var rts = false
        @Volatile var dsr = true
        /** (a CDC adapter's driver: no DSR to read) */
        @Volatile var noDsr = false
        @Volatile var closed = false
        /** the computer's next bytes, waiting up to [timeout] ms; 0 if none */
        override fun read(dest: ByteArray, timeout: Int): Int {
            if (closed) throw IOException("closed")
            val b = fromComputer.poll(timeout.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            System.arraycopy(b, 0, dest, 0, b.size)
            return b.size
        }
        /** as the other read, [length] aside */
        override fun read(dest: ByteArray, length: Int, timeout: Int): Int = read(dest, timeout)
        /** kept, as said to the computer */
        override fun write(src: ByteArray, timeout: Int) { synchronized(toComputer) { toComputer.write(src) } }
        /** its first [length] kept, as said to the computer */
        override fun write(src: ByteArray, length: Int, timeout: Int) { synchronized(toComputer) { toComputer.write(src, 0, length) } }
        /** the computer's DCD, the modem's carrier */
        override fun setDTR(value: Boolean) { dtr = value }
        /** kept, for the test to see */
        override fun setRTS(value: Boolean) { rts = value }
        /** the computer's DTR; none for a CDC adapter's driver */
        override fun getDSR(): Boolean = if (noDsr) throw UnsupportedOperationException() else dsr
        /** the DTR last set */
        override fun getDTR() = dtr
        /** the RTS last set */
        override fun getRTS() = rts
        /** (the computer's DTR again) */
        override fun getCD() = dsr
        /** always clear to send */
        override fun getCTS() = true
        /** never ringing */
        override fun getRI() = false
        /** closed: reads fail from now on */
        override fun close() { closed = true }
        /** until closed */
        override fun isOpen() = !closed
        /** nothing to open */
        override fun open(connection: UsbDeviceConnection?) {}
        /** any format taken */
        override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {}
        /** no driver */
        override fun getDriver(): UsbSerialDriver? = null
        /** no device */
        override fun getDevice(): UsbDevice? = null
        /** the only port */
        override fun getPortNumber() = 0
        /** no endpoint */
        override fun getWriteEndpoint(): UsbEndpoint? = null
        /** no endpoint */
        override fun getReadEndpoint(): UsbEndpoint? = null
        /** no serial number */
        override fun getSerial(): String? = null
        /** none of the lines */
        override fun getControlLines(): EnumSet<UsbSerialPort.ControlLine> = EnumSet.noneOf(UsbSerialPort.ControlLine::class.java)
        /** all of the lines */
        override fun getSupportedControlLines(): EnumSet<UsbSerialPort.ControlLine> = EnumSet.allOf(UsbSerialPort.ControlLine::class.java)
        /** any flow control taken */
        override fun setFlowControl(flowControl: UsbSerialPort.FlowControl?) {}
        /** no flow control */
        override fun getFlowControl() = UsbSerialPort.FlowControl.NONE
        /** only none */
        override fun getSupportedFlowControl(): EnumSet<UsbSerialPort.FlowControl> = EnumSet.of(UsbSerialPort.FlowControl.NONE)
        /** always XON */
        override fun getXON() = true
        /** nothing to purge */
        override fun purgeHwBuffers(purgeWriteBuffers: Boolean, purgeReadBuffers: Boolean) {}
        /** no break */
        override fun setBreak(value: Boolean) {}
        /** what was said to the computer, so far */
        fun said() = synchronized(toComputer) { String(toComputer.toByteArray()) }
    }

    /** [ok] within 5 s, or fails saying [what] */
    private fun waitUntil(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(what, ok())
    }

    /** an adapter's port carries the computer's commands and the modem's answers, DCD follows the carrier, its DTR dropped hangs up */
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

    /** the phone's USB gadget, a pty for /dev/ttyGS0, carries the computer's commands; its root scripts parse */
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

    /**
     * a pico-serial board's control requests as its firmware answers them
     * (firmware/src/core/core.c): DCE, the computer's DTR and RTS read, the
     * outputs SET_OUTPUTS sets
     */
    private class FakeBoard : UsbControl {
        @Volatile var role = 1
        @Volatile var computer = PicoSerialProtocol.DTR or PicoSerialProtocol.RTS
        @Volatile var outputs = 0
        @Volatile var mode = 0
        val requests = CopyOnWriteArrayList<String>()

        /** GET_INFO, GET_INPUTS and GET_STATS answered as the firmware does, at most [length] bytes; null for others */
        override fun read(request: Int, value: Int, length: Int): ByteArray? = when (request) {
            PicoSerialProtocol.GET_INFO -> byteArrayOf(1, 0, 1, 2, 0, mode.toByte(), role.toByte(), 0, 0x0c, 0, 0x4b, 0, 0, 0)
            PicoSerialProtocol.GET_INPUTS -> byteArrayOf(role.toByte(), computer.toByte(), outputs.toByte(), 1, 1)
            PicoSerialProtocol.GET_STATS -> ByteArray(32)
            else -> null
        }?.copyOf(minOf(length, 64)).also { requests.add("in $request") }?.let { it.copyOf(minOf(it.size, length)) }

        /** SET_OUTPUTS and SET_MODE as the firmware does them, each request kept */
        override fun write(request: Int, value: Int): Boolean {
            requests.add("out $request %04x".format(value))
            when (request) {
                PicoSerialProtocol.SET_OUTPUTS -> {
                    val mask = value shr 8
                    outputs = (outputs and mask.inv()) or (value and mask)
                }
                PicoSerialProtocol.SET_MODE -> mode = value
                else -> {}
            }
            return true
        }
    }

    /** the firmware's info, inputs and stats structures parsed, SET_OUTPUTS' wValue, a CDC SERIAL_STATE */
    @Test
    fun picoSerialStructuresAsTheFirmwareHasThem() {
        // (bytes from firmware/src/core/protocol.h's structs, compiled)
        fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val info = PicoSerialProtocol.info(hex("01 00 01 02 00 00 01 00 0c 00 4b 00 00 00"))!!
        assertEquals(1, info.protocol)
        assertEquals("0.1.2", info.firmware)
        assertEquals("pico-serial DB25", info.boardName)
        assertEquals(PicoSerialProtocol.Mode.AUTO, info.mode)
        assertEquals(PicoSerialProtocol.Role.DCE, info.role)
        assertEquals(PicoSerialProtocol.Flow.AUTO, info.flow)
        assertEquals(19200L, info.baud)
        assertTrue(info.learned && info.flowActive && !info.learning)
        assertEquals(PicoSerialProtocol.BaudMode.AUTO, info.baudMode)
        // (a later firmware's longer one read as far as we know it; a short one not at all)
        assertEquals(19200L, PicoSerialProtocol.info(hex("01 00 02 00 00 00 01 00 0c 00 4b 00 00 00 ff ff"))!!.baud)
        assertEquals(null, PicoSerialProtocol.info(hex("01 00 01")))
        val i = PicoSerialProtocol.inputs(hex("01 03 2c 01 01"))!!
        assertEquals(PicoSerialProtocol.Role.DCE, i.role)
        assertEquals(PicoSerialProtocol.DTR or PicoSerialProtocol.RTS, i.inputs)
        assertEquals(PicoSerialProtocol.DCD or PicoSerialProtocol.DSR or PicoSerialProtocol.CTS, i.outputs)
        val st = PicoSerialProtocol.stats(hex("e8 03 00 00 70 11 01 00 01 00 00 00 02 00 00 00 03 00 00 00 04 00 00 00 05 00 00 00 06 00 00 00"))!!
        assertEquals(PicoSerialProtocol.Stats(1000, 70000, 1, 2, 3, 4, 5, 6), st)
        // SET_OUTPUTS' wValue: PROTOCOL.md's own example, RI up
        assertEquals(0x1010, PicoSerialProtocol.outputs(PicoSerialProtocol.RI, PicoSerialProtocol.RI))
        assertEquals(0x1c04, PicoSerialProtocol.outputs(PicoSerialProtocol.DCE_MASK, PicoSerialProtocol.dceOutputs(dcd = true, dsr = false, ri = false)))
        // CDC SERIAL_STATE: DSR (bTxCarrier) and a framing error
        val n = byteArrayOf(0xA1.toByte(), 0x20, 0, 0, 0, 0, 2, 0, 0x12, 0)
        assertEquals(CdcSerialState(dcd = false, dsr = true, brk = false, ring = false, framing = true, parity = false, overrun = false), CdcSerialState.parse(n, 10))
        assertEquals(null, CdcSerialState.parse(byteArrayOf(0xA1.toByte(), 0x2A, 0, 0, 0, 0, 2, 0, 0, 0), 10))
    }

    /** a pico-serial board's DCD, DSR and RI follow the modem, its computer's DTR read and dropped hangs up, the lines sent again after unplugging */
    @Test
    fun aPicoSerialBoardsLinesEachTheirOwn() {
        val modem = FakeModem()
        val signals = MutableStateFlow(LineSignals(ready = true))
        val hangUps = AtomicInteger()
        val bridge = SerialBridge(modem, signals, { hangUps.incrementAndGet() }, hangUpOnClose = false)
        // (a CDC port: its driver reads no DSR; the board tells the computer's DTR)
        val port = FakePort().apply { noDsr = true }
        val board = FakeBoard()
        val lines = PicoSerialLines(PicoSerial(board))
        val link = UsbPortLink(port, bridge, signals, "pico-serial DB25", extra = lines)
        val t = Thread { link.serve() }.apply { start() }
        val dcd = PicoSerialProtocol.DCD
        val dsr = PicoSerialProtocol.DSR
        val ri = PicoSerialProtocol.RI
        // the modem on: DSR to the computer, no DCD; the port's DTR on (the board's lines its own)
        waitUntil("DSR") { board.outputs == dsr }
        assertTrue(port.dtr)
        // RING: RI up, then down again
        signals.value = LineSignals(ready = true, ringing = true)
        waitUntil("RI") { board.outputs == dsr or ri }
        signals.value = LineSignals(ready = true, offhook = true, carrier = true)
        waitUntil("DCD") { board.outputs == dsr or dcd }
        // the board's state for the app
        waitUntil("the board's state") { lines.board.value?.info?.firmware == "0.1.2" }
        assertEquals(PicoSerialProtocol.Role.DCE, lines.board.value?.inputs?.role)
        // the computer drops its DTR (the board reads it): hung up
        board.computer = PicoSerialProtocol.RTS
        waitUntil("hung up") { hangUps.get() == 1 }
        // unplugged from the computer (no role): the lines left to the board, sent again once it's back
        board.computer = PicoSerialProtocol.DTR
        board.role = 0
        Thread.sleep(400)
        val before = board.requests.count { it.startsWith("out 3") }
        board.role = 1
        waitUntil("sent again") { board.requests.count { it.startsWith("out 3") } > before }
        // (only DCD, DSR and RI: CTS stays the board's own flow control)
        assertTrue(board.requests.filter { it.startsWith("out 3") }.all { it.startsWith("out 3 1c") })
        link.close()
        t.join(5000)
        assertEquals(null, bridge.client.value)
        // its settings
        val p = PicoSerial(board)
        assertTrue(p.setMode(PicoSerialProtocol.Mode.DCE))
        assertEquals(PicoSerialProtocol.Mode.DCE, p.info()?.mode)
    }

    /** a CDC adapter's SERIAL_STATE dropping DTR hangs up, though the port reads no DSR */
    @Test
    fun aCdcAdaptersNotificationsHangUp() {
        val modem = FakeModem()
        val signals = MutableStateFlow(LineSignals(ready = true))
        val hangUps = AtomicInteger()
        val bridge = SerialBridge(modem, signals, { hangUps.incrementAndGet() }, hangUpOnClose = false)
        val port = FakePort().apply { noDsr = true }
        // the interrupt endpoint's notifications, as they come
        val notes = LinkedBlockingQueue<ByteArray>()
        val cdc = CdcLineState { buf, ms ->
            val n = notes.poll(ms.toLong(), TimeUnit.MILLISECONDS) ?: return@CdcLineState -1
            System.arraycopy(n, 0, buf, 0, n.size)
            n.size
        }.start()
        /** a SERIAL_STATE notification with [v] as its bits */
        fun state(v: Int) = byteArrayOf(0xA1.toByte(), 0x20, 0, 0, 0, 0, 2, 0, v.toByte(), 0)
        val link = UsbPortLink(port, bridge, signals, "CDC adapter", extra = cdc)
        val t = Thread { link.serve() }.apply { start() }
        waitUntil("claimed") { bridge.client.value != null }
        // (the port's DTR still the modem's carrier: a plain CDC adapter has no lines of its own)
        signals.value = LineSignals(ready = true, offhook = true, carrier = true)
        waitUntil("DCD on") { port.dtr }
        notes.add(state(0x03))
        waitUntil("DTR seen") { cdc.computerDtr() == true }
        // (the link's next look at the lines: DTR up there too)
        Thread.sleep(400)
        assertEquals(0, hangUps.get())
        notes.add(state(0x01))
        waitUntil("hung up") { hangUps.get() == 1 }
        link.close()
        t.join(5000)
    }

    /** a format string read, a bad one 8N1 at the speed */
    @Test
    fun formats() {
        assertEquals(SerialFormat(9600, 7, 'E', 1), Bridges.format(9600, "7e1"))
        assertEquals(SerialFormat(115200), Bridges.format(115200, "bad"))
    }
}
