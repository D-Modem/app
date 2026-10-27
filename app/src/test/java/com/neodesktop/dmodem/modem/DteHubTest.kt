// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DteHubTest {

    /** a modem stand-in: what the hub writes, and a pipe the "modem" sends on */
    private class FakeModem {
        val written = ByteArrayOutputStream()
        val toHub = PipedOutputStream()
        val hubIn = PipedInputStream(toHub, 65536)
    }

    /** every listener gets all the modem says */
    @Test
    fun everyListenerGetsTheModemsOutput() {
        val hub = DteHub()
        val m = FakeModem()
        val a = StringBuffer()
        val b = StringBuffer()
        val done = CountDownLatch(2)
        hub.addListener { d, n -> a.append(String(d, 0, n, Charsets.ISO_8859_1)); if (a.contains("OK")) done.countDown() }
        hub.addListener { d, n -> b.append(String(d, 0, n, Charsets.ISO_8859_1)); if (b.contains("OK")) done.countDown() }
        hub.attach(m.hubIn, m.written)
        m.toHub.write("\r\nOK\r\n".toByteArray())
        m.toHub.flush()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("\r\nOK\r\n", a.toString())
        assertEquals("\r\nOK\r\n", b.toString())
        hub.detach()
    }

    /** writes reach the modem only while one's attached */
    @Test
    fun writesGoToTheModemUntilDetached() {
        val hub = DteHub()
        val m = FakeModem()
        assertFalse(hub.write("AT\r"))
        hub.attach(m.hubIn, m.written)
        assertTrue(hub.attached)
        assertTrue(hub.write("ATX3\r"))
        assertTrue(hub.write(byteArrayOf(0x00, 0xff.toByte()), 0, 2))
        assertEquals(
            listOf('A', 'T', 'X', '3', '\r').map { it.code.toByte() } + listOf(0x00.toByte(), 0xff.toByte()),
            m.written.toByteArray().toList(),
        )
        hub.detach()
        assertFalse(hub.attached)
        assertFalse(hub.write("AT\r"))
    }

    /** listeners stay across a new modem attached, writes going to the new one only */
    @Test
    fun listenersStayAcrossAModemRestart() {
        val hub = DteHub()
        val got = StringBuffer()
        val second = CountDownLatch(1)
        hub.addListener { d, n ->
            got.append(String(d, 0, n, Charsets.ISO_8859_1))
            if (got.contains("two")) second.countDown()
        }
        val m1 = FakeModem()
        hub.attach(m1.hubIn, m1.written)
        m1.toHub.write("one ".toByteArray()); m1.toHub.flush()
        Thread.sleep(200)
        val m2 = FakeModem()
        hub.attach(m2.hubIn, m2.written)
        m2.toHub.write("two".toByteArray()); m2.toHub.flush()
        assertTrue(second.await(5, TimeUnit.SECONDS))
        assertEquals("one two", got.toString())
        assertTrue(hub.write("AT\r"))
        assertEquals("AT\r", m2.written.toString(Charsets.ISO_8859_1))
        assertEquals("", m1.written.toString(Charsets.ISO_8859_1))
        hub.detach()
    }
}
