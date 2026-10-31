// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import org.dmodem.app.android.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The modem as a serial port on TCP: raw and Telnet clients, one at a
 * time, a password, RFC 2217 (pyserial's rfc2217:// client): the speed
 * set, the carrier seen, DTR dropped hanging up.
 */
class TcpBridgeTest {

    /** a modem: what it was sent, and "OK" for each AT command */
    private class FakeModem : TerminalLink {
        override val state: StateFlow<TerminalLink.State> = MutableStateFlow(TerminalLink.State.Open)
        val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()
        val got = ByteArrayOutputStream()
        /** [l] told the modem's output */
        override fun addListener(l: TerminalLink.Listener) { listeners.add(l) }
        /** [l] told no more */
        override fun removeListener(l: TerminalLink.Listener) { listeners.remove(l) }
        /** the bytes kept, and OK replied to an AT command */
        override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
            synchronized(got) { got.write(data, offset, length) }
            val s = String(data, offset, length, Charsets.ISO_8859_1)
            if (s.contains("AT") && s.endsWith("\r")) emit("\r\nOK\r\n".toByteArray())
            return true
        }
        /** [b] from the modem to its listeners */
        fun emit(b: ByteArray) = listeners.forEach { it.onData(b, b.size) }
        /** what was written to the modem */
        fun sent(): ByteArray = synchronized(got) { got.toByteArray() }
    }

    private val modem = FakeModem()
    private val signals = MutableStateFlow(LineSignals(ready = true))
    private val hangUps = AtomicInteger()
    private val serial = SerialBridge(modem, signals, { hangUps.incrementAndGet() })
    private val bridges = ArrayList<TcpBridge>()

    /** every bridge the test started stopped */
    @After
    fun tearDown() = bridges.forEach { it.stop() }

    /** a bridge on a free port, started (and stopped after the test) */
    private fun bridge(telnet: Boolean = false, password: String = ""): TcpBridge =
        TcpBridge(serial, 0, telnet = telnet, password = password).also { assertTrue(it.start()); bridges.add(it) }

    /** a client connected to [b] on localhost, reads timing out in 5 s */
    private fun connect(b: TcpBridge) = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", b.localPort), 2000)
        soTimeout = 5000
    }

    /** what comes until [want] is in it */
    private fun readUntil(s: Socket, want: String): String {
        val sb = StringBuilder()
        val buf = ByteArray(1024)
        val end = System.currentTimeMillis() + 5000
        while (!sb.contains(want) && System.currentTimeMillis() < end) {
            val n = s.getInputStream().read(buf)
            if (n < 0) break
            sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
        }
        assertTrue("\"$want\" in \"$sb\"", sb.contains(want))
        return sb.toString()
    }

    /** [ok] waited for (5 s at most), failing with [what] if it never holds */
    private fun waitUntil(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(what, ok())
    }

    /** raw TCP carries every byte value unchanged both ways; the client leaving frees the bridge without a hang-up */
    @Test
    fun rawBothWaysEveryByte() {
        val b = bridge()
        connect(b).use { s ->
            waitUntil("claimed") { serial.client.value != null }
            assertEquals("tcp", serial.client.value!!.via)
            s.getOutputStream().write("ATDT5551212\r".toByteArray())
            readUntil(s, "OK")
            // every byte value, as it is, each way (no Telnet: 0xff isn't special)
            val all = ByteArray(256) { it.toByte() }
            modem.emit(all)
            val got = ByteArrayOutputStream()
            val buf = ByteArray(512)
            while (got.size() < 256) got.write(buf, 0, s.getInputStream().read(buf))
            assertArrayEquals(all, got.toByteArray())
            s.getOutputStream().write(all)
            waitUntil("the modem got them") { modem.sent().size >= 12 + 256 }
            assertArrayEquals(all, modem.sent().copyOfRange(12, 12 + 256))
        }
        // the client gone: the client off the bridge; not off hook, so no hang-up
        waitUntil("released") { serial.client.value == null }
        assertEquals(0, hangUps.get())
    }

    /** a second client gets BUSY while one has the modem; the first leaving in a call hangs it up and frees the bridge */
    @Test
    fun oneClientAtATimeAndTheCallHungUpWhenItGoes() {
        val b = bridge()
        val first = connect(b)
        waitUntil("claimed") { serial.client.value != null }
        connect(b).use { second -> readUntil(second, "BUSY") }
        signals.value = LineSignals(ready = true, offhook = true, carrier = true)
        first.close()
        waitUntil("hung up") { hangUps.get() == 1 }
        // free again
        connect(b).use { waitUntil("claimed again") { serial.client.value != null } }
    }

    /** with a password the client is asked it first, a wrong one refused, and it never reaches the modem */
    @Test
    fun aPasswordFirst() {
        val b = bridge(password = "s3cret")
        connect(b).use { s ->
            readUntil(s, "Password: ")
            s.getOutputStream().write("wrong\r".toByteArray())
            // (the next prompt with it)
            assertTrue(readUntil(s, "\r\nWrong password\r\nPassword: ").isNotEmpty())
            assertEquals(null, serial.client.value)
            s.getOutputStream().write("s3cret\r".toByteArray())
            waitUntil("claimed") { serial.client.value != null }
            s.getOutputStream().write("AT\r".toByteArray())
            readUntil(s, "OK")
            // (the password never reached the modem)
            assertFalse(String(modem.sent()).contains("s3cret"))
        }
    }

    /** Telnet mode offers its options, takes the client's IAC IAC as one 0xff and doubles the modem's */
    @Test
    fun telnetDoublesIacAndNegotiates() {
        val b = bridge(telnet = true)
        connect(b).use { s ->
            val i = s.getInputStream()
            // the server's offer: WILL BINARY, DO BINARY, WILL ECHO, WILL SGA, DO SGA
            val offer = ByteArray(15)
            var n = 0
            while (n < 15) n += i.read(offer, n, 15 - n)
            assertEquals(0xff, offer[0].toInt() and 0xff)
            assertEquals(Telnet.ECHO, offer[8].toInt())
            // our answers, then data with an IAC doubled in it, and CR NUL as a client not in binary has it
            val o = s.getOutputStream()
            o.write(byteArrayOf(-1, -3, 0, -1, -5, 0, -1, -3, 1, -1, -3, 3, -1, -5, 3)) // DO BIN, WILL BIN, DO ECHO, DO SGA, WILL SGA
            o.write(byteArrayOf('A'.code.toByte(), -1, -1, 'B'.code.toByte()))
            waitUntil("the modem got A 0xff B") { modem.sent().size >= 3 }
            assertArrayEquals(byteArrayOf('A'.code.toByte(), -1, 'B'.code.toByte()), modem.sent())
            // the modem's 0xff, doubled to the client
            modem.emit(byteArrayOf(1, -1, 2))
            val got = ByteArray(4)
            n = 0
            while (n < 4) n += i.read(got, n, 4 - n)
            assertArrayEquals(byteArrayOf(1, -1, -1, 2), got)
        }
    }

    /** pyserial's rfc2217:// client sets the speed, sees the carrier and DSR, and dropping DTR in a call hangs up */
    @Test
    fun rfc2217WithPyserial() {
        assumeTrue("python3 with pyserial", runCatching {
            ProcessBuilder("python3", "-c", "import serial.rfc2217").start().waitFor() == 0
        }.getOrDefault(false))
        val b = bridge(telnet = true)
        val script = File.createTempFile("rfc2217", ".py").apply {
            writeText(
                """
                import serial, sys, time
                s = serial.serial_for_url('rfc2217://127.0.0.1:${b.localPort}', baudrate=9600, timeout=3)
                print('baud', s.baudrate, flush=True)
                s.write(b'ATI\r')
                r = s.read_until(b'OK')
                print('reply', r.strip().decode(), flush=True)
                # the carrier comes (the test's cue: 'carrier?')
                print('carrier?', flush=True)
                end = time.time() + 5
                while not s.cd and time.time() < end:
                    time.sleep(0.1)
                print('cd', s.cd, 'dsr', s.dsr, flush=True)
                s.dtr = False
                time.sleep(1)
                print('dtr off', flush=True)
                s.close()
                """.trimIndent(),
            )
            deleteOnExit()
        }
        val p = ProcessBuilder("python3", script.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader()
        val lines = ArrayList<String>()
        while (true) {
            val l = out.readLine() ?: break
            lines.add(l)
            if (l == "carrier?") signals.value = LineSignals(ready = true, offhook = true, carrier = true)
        }
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        val all = lines.joinToString("\n")
        assertEquals(all, 0, p.exitValue())
        assertTrue(all, lines.contains("baud 9600"))
        assertTrue(all, lines.contains("reply OK"))
        assertTrue(all, lines.contains("cd True dsr True"))
        // the speed the client set, kept; DTR dropped while online: the call hung up
        assertTrue(all, hangUps.get() >= 1)
        assertTrue(String(modem.sent()).startsWith("ATI\r"))
    }
}
