// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.peer

import com.neodesktop.dmodem.io.asSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Two phones' modems calling each other through [PeerLine] on TCP: each
 * phone the app's engine on a peer line (native-test's peer_host: cmake
 * -S native-test -B build-host && cmake --build build-host, or
 * DMODEM_PEER_HOST), its Kotlin side ours. A call made and answered,
 * data both ways, hung up; over a slow and uneven link (Bluetooth's
 * kind); busy; nobody there; a call not answered.
 */
class PeerLineTest {
    private val closers = ArrayList<() -> Unit>()

    @After
    fun tearDown() = closers.asReversed().forEach { runCatching { it() } }

    /** a phone: its modem (peer_host), its DTE, its line on TCP */
    private inner class Phone(name: String, listen: Boolean, leadMs: Int = 100, var callTo: String = "") :
        TestModem(name, TcpPeerTransport(0, InetAddress.getLoopbackAddress()), listen, leadMs, closers) {
        override fun target(dial: String) = PeerLine.wifiTarget(dial, callTo)
        val port: Int get() = (transport as TcpPeerTransport).localPort
    }

    private fun connectLine(p: Phone): String = p.expect("CONNECT", 90) + p.expect("\r\n", 5)

    @Test
    fun aCallBetweenTwoPhones() {
        val b = Phone("B", listen = true)
        val a = Phone("A", listen = false, callTo = "127.0.0.1:${b.port}")
        // (the modem as it starts, X4: it waits for the dial tone the line plays it)
        a.command("ATE0")
        b.command("ATE0S0=1")
        a.send("ATDT5551234\r")
        b.expect("RING", 20)
        val ca = connectLine(a)
        val cb = connectLine(b)
        assertTrue(ca, Regex("CONNECT \\d+").containsMatchIn(ca))
        assertTrue(cb, Regex("CONNECT \\d+").containsMatchIn(cb))
        assertTrue(a.line.state.value is PeerLine.State.Connected)
        assertTrue(b.line.state.value is PeerLine.State.Connected)
        assertTrue(a.events.contains("ANSWERED"))
        // the caller's speaker (ATM1): on from the dial to CONNECT, the dial tone, ringback and training heard
        val spk = a.statuses.filter { it.startsWith("SPEAKER") }
        assertTrue(spk.toString(), spk.first().startsWith("SPEAKER 3"))
        assertTrue(spk.toString(), spk.last().startsWith("SPEAKER 0"))
        assertTrue(spk.toString(), spk.last().split(' ')[2].toLong() > 3 * 8000)
        // data both ways
        a.send("hello from A\r\n")
        b.expect("hello from A")
        b.send("and from B\r\n")
        a.expect("and from B")
        // A hangs up: B's NO CARRIER, both lines ready again
        Thread.sleep(1200)
        a.send("+++")
        a.expect("OK", 10)
        a.command("ATH")
        b.expect("NO CARRIER", 20)
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end && b.line.state.value != PeerLine.State.Ready(true)) Thread.sleep(50)
        assertEquals(PeerLine.State.Ready(true), b.line.state.value)
        assertEquals(PeerLine.State.Ready(false), a.line.state.value)
        // and again, dialled by its address (192*168..., here the loopback's): B rings, ATA
        a.callTo = ""
        b.command("ATS0=0")
        a.send("ATDT127*0*0*1#${b.port}\r")
        b.expect("RING", 20)
        b.send("ATA\r")
        connectLine(a)
        connectLine(b)
    }

    /** the link between the phones: [delayMs] each way and up to [jitterMs] more, in order */
    private fun slowLink(to: Int, delayMs: Long, jitterMs: Int): Int {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        closers.add { server.close() }
        Thread {
            val a = try { server.accept() } catch (_: IOException) { return@Thread }
            val b = Socket("127.0.0.1", to)
            closers.add { a.close(); b.close() }
            for ((x, y) in listOf(a to b, b to a)) {
                val q = LinkedBlockingQueue<Pair<Long, ByteArray>>()
                Thread {
                    val buf = ByteArray(1024)
                    var last = 0L
                    while (true) {
                        val n = try { x.getInputStream().read(buf) } catch (_: IOException) { -1 }
                        if (n < 0) { q.add(0L to ByteArray(0)); break }
                        // (never before what went first: a stream)
                        last = maxOf(last, System.currentTimeMillis() + delayMs + Random.nextInt(jitterMs + 1))
                        q.add(last to buf.copyOf(n))
                    }
                }.apply { isDaemon = true; start() }
                Thread {
                    while (true) {
                        val (at, d) = q.take()
                        if (d.isEmpty()) { runCatching { y.shutdownOutput() }; break }
                        val wait = at - System.currentTimeMillis()
                        if (wait > 0) Thread.sleep(wait)
                        try { y.getOutputStream().write(d) } catch (_: IOException) { break }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        return server.localPort
    }

    @Test
    fun overASlowUnevenLink() {
        // (Bluetooth's kind of link: the phones' lead as the app has it there)
        val b = Phone("B", listen = true, leadMs = 200)
        val proxy = slowLink(b.port, delayMs = 60, jitterMs = 40)
        val a = Phone("A", listen = false, leadMs = 200, callTo = "127.0.0.1:$proxy")
        a.command("ATE0")
        b.command("ATE0S0=1")
        a.send("ATD1\r")
        val ca = connectLine(a)
        connectLine(b)
        val rate = Regex("CONNECT (\\d+)").find(ca)!!.groupValues[1].toInt()
        assertTrue("connected at $rate", rate >= 28800)
        // 8 KB that doesn't compress, every byte there and in order
        val data = Random(5).nextBytes(8192).map { (32 + (it.toInt() and 0x3f)).toChar() }.joinToString("")
        a.send(data)
        assertTrue(b.expect(data.takeLast(64), 60).endsWith(data))
    }

    @Test
    fun aPhoneInACallIsBusy() {
        val b = Phone("B", listen = true)
        val a = Phone("A", listen = false, callTo = "127.0.0.1:${b.port}")
        val c = Phone("C", listen = false, callTo = "127.0.0.1:${b.port}")
        b.command("ATE0S0=1")
        a.send("ATD1\r")
        connectLine(a)
        c.send("ATD1\r")
        c.expect("BUSY", 20)
        assertTrue(c.events.contains("FAILED 486"))
        // A and B still on: data
        a.send("still here\r\n")
        b.expect("still here")
    }

    @Test
    fun nobodyThereAndNoAnswer() {
        // nothing listening: NO ANSWER
        val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val a = Phone("A", listen = false, callTo = "127.0.0.1:$closed")
        a.send("ATD1\r")
        a.expect("NO ANSWER", 20)
        assertTrue(a.line.lastError.value!!.startsWith("can't reach"))
        // a phone that rings but isn't answered: RING there until the caller gives up, then not
        val b = Phone("B", listen = true)
        a.callTo = "127.0.0.1:${b.port}"
        a.command("ATS7=8")
        a.send("ATD1\r")
        b.expect("RING", 10)
        b.expect("RING", 10)
        // (the modem's own S7 wait: NO ANSWER from its call progress)
        a.expect("NO ", 30)
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end && !b.events.contains("CANCEL")) Thread.sleep(50)
        assertTrue(b.events.contains("CANCEL"))
        assertEquals(PeerLine.State.Ready(true), b.line.state.value)
    }

    @Test
    fun dialStrings() {
        assertEquals("192.168.1.20", PeerLine.wifiTarget("T192*168*1*20", "x"))
        assertEquals("10.0.0.2:6000", PeerLine.wifiTarget("10.0.0.2#6000", ""))
        assertEquals("phone.local:5070", PeerLine.wifiTarget("T5551234", "phone.local:5070"))
        assertNull(PeerLine.wifiTarget("5551234", ""))
        assertEquals("x", PeerLine.wifiTarget("999*1*1*1", "x"))
        assertEquals("fe80::1" to 5070, TcpPeerTransport.parse("fe80::1", 5070))
        assertEquals("fe80::1" to 6000, TcpPeerTransport.parse("[fe80::1]:6000", 5070))
        assertEquals("host" to 6000, TcpPeerTransport.parse("host:6000", 5070))
        // (the handshake's lines)
        assertEquals("ANSWER", PeerLine.readLine("ANSWER\r\nxx".byteInputStream().asSource()))
        assertNull(PeerLine.readLine("ANS".byteInputStream().asSource()))
    }
}
