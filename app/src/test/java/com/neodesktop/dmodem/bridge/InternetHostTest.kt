// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.bridge

import com.neodesktop.dmodem.ppp.Ip
import com.neodesktop.dmodem.ppp.Ppp
import com.neodesktop.dmodem.ppp.PppHost
import com.neodesktop.dmodem.terminal.TerminalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bridges in internet mode: a computer on the TCP bridge dials, gets
 * PPP from the phone (no modem touched) and a TCP connection through to
 * a server on this host; its DTR dropped (RFC 2217) ends the link.
 */
class InternetHostTest {
    private val closers = ArrayList<() -> Unit>()

    /** everything the test opened closed */
    @After
    fun tearDown() = closers.forEach { runCatching { it() } }

    /** a modem that mustn't be used */
    private class Untouched : TerminalLink {
        override val state: StateFlow<TerminalLink.State> = MutableStateFlow(TerminalLink.State.Open)
        val writes = AtomicInteger()
        /** no listeners kept: it never answers */
        override fun addListener(l: TerminalLink.Listener) {}
        /** nothing to remove */
        override fun removeListener(l: TerminalLink.Listener) {}
        /** a write counted (there should be none) */
        override fun write(data: ByteArray, offset: Int, length: Int): Boolean { writes.incrementAndGet(); return true }
    }

    /** a computer dialling on the TCP bridge gets PPP and a TCP connection through the phone; dropping DTR ends it, the modem untouched */
    @Test
    fun aComputerOnTheTcpBridgeGetsTheInternet() {
        val modem = Untouched()
        val hangUps = AtomicInteger()
        val serial = SerialBridge(modem, MutableStateFlow(LineSignals(ready = true)), { hangUps.incrementAndGet() })
        serial.host = { PppHost({ listOf(InetAddress.getLoopbackAddress()) }) }
        val tcp = TcpBridge(serial, 0, telnet = false)
        assertTrue(tcp.start())
        closers.add { tcp.stop() }

        // the web server the computer reaches
        val web = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        closers.add { web.close() }
        Thread {
            val s = web.accept()
            val req = ByteArray(64)
            val n = s.getInputStream().read(req)
            s.getOutputStream().write("HTTP/1.0 200 OK\r\n\r\nhello, ${String(req, 0, n).substringBefore(' ')}".toByteArray())
            s.close()
        }.start()

        // the computer: its serial port is the socket
        val sock = Socket()
        sock.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), tcp.localPort))
        closers.add { sock.close() }
        val out = sock.getOutputStream()
        val inp = sock.getInputStream()
        out.write("ATZ\rATDT18005551000\r".toByteArray())
        val text = StringBuilder()
        val buf = ByteArray(4096)
        while (!text.contains("CONNECT")) {
            val n = inp.read(buf)
            text.append(String(buf, 0, n, Charsets.ISO_8859_1))
        }
        assertTrue(text.toString(), text.contains("OK") && text.contains("CONNECT 115200"))
        assertEquals("tcp", serial.client.value?.via)

        // its PPP, and a TCP connection to the web server through the phone
        val up = ArrayBlockingQueue<Ppp.IpConfig>(1)
        val packets = LinkedBlockingQueue<ByteArray>()
        val ppp = Ppp({ b -> synchronized(out) { out.write(b); out.flush() } }, events = object : Ppp.Events {
            /** the link's address kept */
            override fun up(config: Ppp.IpConfig) { up.add(config) }
            /** an IP packet from the phone kept */
            override fun ip(packet: ByteArray) { packets.add(packet) }
            /** nothing to do when the link goes down */
            override fun down(reason: String) {}
        }, retryMs = 500)
        Thread {
            while (true) { val n = try { inp.read(buf) } catch (_: Exception) { -1 }; if (n < 0) break; ppp.input(buf, 0, n) }
        }.apply { isDaemon = true; start() }
        ppp.start()
        val cfg = up.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no link")
        assertEquals("10.64.64.2", cfg.address.hostAddress)
        assertTrue(serial.lines().carrier)

        val me = cfg.address.address
        val dst = byteArrayOf(127, 0, 0, 1)
        var seq = 1000
        ppp.sendIp(Ip.tcp(me, dst, 41000, web.localPort, seq, 0, Ip.SYN, 8192, mss = 1460))
        seq++
        val synAck = packets.poll(10, TimeUnit.SECONDS)!!
        var ack = java.nio.ByteBuffer.wrap(synAck, 24, 4).int + 1
        ppp.sendIp(Ip.tcp(me, dst, 41000, web.localPort, seq, ack, Ip.ACK, 8192))
        ppp.sendIp(Ip.tcp(me, dst, 41000, web.localPort, seq, ack, Ip.PSH or Ip.ACK, 8192, "GET / HTTP/1.0\r\n\r\n".toByteArray()))
        seq += 18
        val page = StringBuilder()
        while (!page.contains("hello")) {
            val p = packets.poll(10, TimeUnit.SECONDS) ?: break
            val d = p.copyOfRange(20 + ((p[32].toInt() and 0xff) ushr 4) * 4, p.size)
            if (d.isNotEmpty()) {
                page.append(String(d))
                ack += d.size
                ppp.sendIp(Ip.tcp(me, dst, 41000, web.localPort, seq, ack, Ip.ACK, 8192))
            }
        }
        assertTrue(page.toString(), page.contains("200 OK") && page.contains("hello, GET"))

        // the computer hangs up: its link over, NO CARRIER; the modem never used
        serial.dropDtr()
        val end = System.currentTimeMillis() + 5000
        while (serial.lines().carrier && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(!serial.lines().carrier)
        assertEquals(0, modem.writes.get())
        assertEquals(0, hangUps.get())
    }
}
