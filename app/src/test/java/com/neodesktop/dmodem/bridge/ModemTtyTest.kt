// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import com.neodesktop.dmodem.local.LocalShellTest
import com.neodesktop.dmodem.local.Pty
import com.neodesktop.dmodem.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The modem as /dev/ttyDM0 ([ModemTty]): a program opening the pty's
 * slave has the modem (its AT commands to it, its answers back), as a
 * serial bridge's client; closing it drops DTR (the call hung up); opened
 * again, the modem again; while another client has the modem, nothing.
 */
class ModemTtyTest {
    /** a modem: what it was sent, and "OK" for each AT command */
    private class FakeModem : TerminalLink {
        override val state: StateFlow<TerminalLink.State> = MutableStateFlow(TerminalLink.State.Open)
        val listeners = CopyOnWriteArrayList<TerminalLink.Listener>()
        val got = ByteArrayOutputStream()
        override fun addListener(l: TerminalLink.Listener) { listeners.add(l) }
        override fun removeListener(l: TerminalLink.Listener) { listeners.remove(l) }
        override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
            synchronized(got) { got.write(data, offset, length) }
            val s = String(data, offset, length, Charsets.ISO_8859_1)
            if (s.contains("AT") && s.endsWith("\r")) emit("\r\nOK\r\n".toByteArray())
            return true
        }
        fun emit(b: ByteArray) = listeners.forEach { it.onData(b, b.size) }
        fun sent(): String = synchronized(got) { String(got.toByteArray(), Charsets.ISO_8859_1) }
    }

    private val modem = FakeModem()
    private val signals = MutableStateFlow(LineSignals(ready = true))
    private val hangUps = AtomicInteger()
    private val serial = SerialBridge(modem, signals, { hangUps.incrementAndGet() })
    private val tty = ModemTty(serial)

    @Before
    fun setUp() = Pty.load(LocalShellTest.hostPty())

    private fun until(what: String, secs: Int = 5, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + secs * 1000L
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError(what)
            Thread.sleep(20)
        }
    }

    /** text from the port until [want] */
    private fun RandomAccessFile.expect(want: String): String {
        val sb = StringBuilder()
        val buf = ByteArray(256)
        while (!sb.contains(want)) {
            val n = read(buf)
            if (n < 0) break
            sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
        }
        assertTrue("\"$want\" in \"$sb\"", sb.contains(want))
        return sb.toString()
    }

    @Test
    fun aProgramOnTheTtyHasTheModem() {
        val path = tty.slave()
        assertNotNull(path)
        assertTrue(path!!, path.startsWith("/dev/pts/"))
        assertEquals(path, tty.slave())

        // a program opens it: the modem is its (even before it sends anything)
        val port = RandomAccessFile(path, "rw")
        until("the bridge's client") { serial.client.value?.name == "ttyDM0" }
        // (raw: what the modem sends isn't echoed back to it)
        port.write("ATI\r".toByteArray())
        port.expect("OK")
        until("the modem got ATI") { modem.sent().contains("ATI\r") }
        assertTrue(modem.sent(), !modem.sent().contains("OK"))
        // the modem's own news (RING) reaches it unasked
        modem.emit("\r\nRING\r\n".toByteArray())
        port.expect("RING")

        // closed in a call: DTR dropped, the call hung up, the modem free
        signals.value = LineSignals(ready = true, offhook = true, carrier = true)
        port.close()
        until("the hang-up") { hangUps.get() == 1 }
        until("the bridge free") { serial.client.value == null }
        signals.value = LineSignals(ready = true)

        // opened again: the modem again
        val again = RandomAccessFile(path, "rw")
        until("the bridge's client again") { serial.client.value?.name == "ttyDM0" }
        again.write("ATZ\r".toByteArray())
        again.expect("OK")
        again.close()
        until("the bridge free again") { serial.client.value == null }
    }

    @Test
    fun anotherClientsModemIsntTheTtys() {
        val other = Port("tcp", "a TCP client", java.io.ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()) {}
        assertTrue(serial.tryClaim(other))
        val path = tty.slave()!!
        val port = RandomAccessFile(path, "rw")
        port.write("ATI\r".toByteArray())
        Thread.sleep(700)
        assertEquals("a TCP client", serial.client.value?.name)
        assertTrue(modem.sent(), !modem.sent().contains("ATI"))
        port.close()
    }
}
