// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.bridge

import org.dmodem.app.android.local.LocalShellTest
import org.dmodem.app.android.local.Pty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A USB serial adapter as /dev/ttyUSB0 ([SerialTty]): a program opening the pty's slave gets the
 * adapter opened at the port's settings, its octets to the adapter and back; its stty (speed, RTS/CTS,
 * B0's hang-up) to the adapter as it changes; closing the port closes the adapter; no adapter, nothing.
 */
class SerialTtyTest {
    private val log = CopyOnWriteArrayList<String>()
    @Volatile private var line: FakeSerialLine? = FakeSerialLine()
    private val opened = CopyOnWriteArrayList<SerialFormat>()
    private val tty = SerialTty("/dev/ttyUSB0", { f -> opened.add(f); line }, { log.add(it) })

    /** the pty library built for the host, loaded */
    @Before
    fun setUp() = Pty.load(LocalShellTest.hostPty())

    /** [cond] waited for ([secs] at most), failing with [what] (and the log) if it never holds */
    private fun until(what: String, secs: Int = 5, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + secs * 1000L
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("$what\n${log.joinToString("\n")}")
            Thread.sleep(20)
        }
    }

    /** stty run on the port with [args] ([check]: it must say it did all of it) */
    private fun stty(slave: String, vararg args: String, check: Boolean = true) {
        val p = ProcessBuilder(listOf("stty", "-F", slave) + args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        if (check) assertEquals(out, 0, p.exitValue())
    }

    /** text from the port until [want] */
    private fun RandomAccessFile.expect(want: String) {
        val sb = StringBuilder()
        val buf = ByteArray(256)
        while (!sb.contains(want)) {
            val n = read(buf)
            if (n < 0) break
            sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
        }
        assertTrue("\"$want\" in \"$sb\"", sb.contains(want))
    }

    /** opened: the adapter at the port's speed, DTR up; octets both ways; stty's changes to it; closed, the adapter let go */
    @Test
    fun aProgramHasTheAdapter() {
        val l = line!!
        val slave = tty.slave()
        assertNotNull(slave)
        stty(slave!!, "9600")
        val port = RandomAccessFile(slave, "rw")
        until("the adapter opened") { l.format != null }
        assertEquals(9600, opened.first().baud)
        assertEquals(SerialFormat(9600), l.format)
        assertEquals(true, l.dtr)
        assertEquals(false, l.rtsCts)
        assertTrue(tty.inUse())

        port.write("ATI\r".toByteArray())
        until("the program's octets at the adapter") { l.sent().contains("ATI\r") }
        l.incoming.add("\r\nU.S. Robotics 56K\r\nOK\r\n".toByteArray())
        port.expect("OK")

        // (minicom's, pppd's settings: a speed, RTS/CTS, 2 stop bits)
        stty(slave, "115200", "crtscts", "cstopb")
        until("the new speed at the adapter") { l.format == SerialFormat(115200, 8, 'N', 2) && l.rtsCts == true }
        // (B0: hang up, DTR down; a speed again, up)
        // (a pty takes B0, but stty reading it back complains)
        stty(slave, "0", check = false)
        until("DTR down") { l.dtr == false }
        assertEquals(115200, l.format?.baud)
        stty(slave, "57600")
        until("DTR up") { l.dtr == true && l.format?.baud == 57600 }

        port.close()
        until("the adapter closed") { l.closed }
    }

    /** no adapter: the port opens, what's written goes nowhere; one plugged in later is the next program's */
    @Test
    fun noAdapter() {
        line = null
        val slave = tty.slave()!!
        RandomAccessFile(slave, "rw").use { p ->
            until("the adapter looked for") { opened.isNotEmpty() }
            p.write("hello\r".toByteArray())
        }
        // (the port seen closed before it's opened again)
        until("the port closed") { !tty.inUse() }
        Thread.sleep(1000)
        val l = FakeSerialLine()
        line = l
        RandomAccessFile(slave, "rw").use { p ->
            until("the adapter opened") { l.format != null }
            p.write("again\r".toByteArray())
            until("the octets at it") { l.sent() == "again\r" }
        }
        until("the adapter closed") { l.closed }
    }

    /** the adapter unplugged under the program: let go, the program's port quiet until it closes it */
    @Test
    fun unplugged() {
        val l = line!!
        val slave = tty.slave()!!
        RandomAccessFile(slave, "rw").use { p ->
            until("the adapter opened") { l.format != null }
            l.gone = true
            until("the adapter let go") { l.closed }
            p.write("into nothing\r".toByteArray())
        }
        assertTrue(log.joinToString("\n"), log.any { it.contains("gone") })
    }
}
