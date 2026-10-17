// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ppp

import com.neodesktop.dmodem.terminal.TerminalLink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The phone as a dial-up ISP for a serial computer ([PppHost]): the
 * computer's dialer (AT, ATD, Windows' CLIENT, PPP straight away), our
 * PPP client as the computer's, its address and DNS; TCP to a server on
 * this host through the NAT (a segment lost on the way, retransmitted),
 * UDP, DNS, a ping to the gateway.
 */
class PppHostTest {
    private val closers = ArrayList<() -> Unit>()

    @After
    fun tearDown() = closers.forEach { runCatching { it() } }

    /** the computer: what the host says (text, then PPP's frames to its client) */
    private inner class Computer(dnsPort: Int = 53) {
        val fromHost = LinkedBlockingQueue<ByteArray>()
        val host = PppHost({ listOf(InetAddress.getLoopbackAddress()) }, dnsPort = dnsPort)
        val packets = LinkedBlockingQueue<ByteArray>()
        val up = ArrayBlockingQueue<Ppp.IpConfig>(1)
        lateinit var ppp: Ppp
        @Volatile var pppOn = false
        /** packets to the computer dropped, when this says so (a line losing one) */
        @Volatile var drop: (ByteArray) -> Boolean = { false }

        // (each way on its own thread, as a serial line's two ends are)
        private val toHost = LinkedBlockingQueue<ByteArray>()
        private val toComputer = LinkedBlockingQueue<ByteArray>()

        init {
            closers.add { host.close() }
            host.addListener(TerminalLink.Listener { d, n -> toComputer.add(d.copyOf(n)) })
            Thread {
                while (true) {
                    val b = try { toComputer.take() } catch (_: InterruptedException) { break }
                    if (pppOn) ppp.input(b) else fromHost.add(b)
                }
            }.apply { isDaemon = true; start() }
            Thread {
                while (true) {
                    val b = try { toHost.take() } catch (_: InterruptedException) { break }
                    host.write(b)
                }
            }.apply { isDaemon = true; start() }
        }

        fun type(s: String) { toHost.add(s.toByteArray()) }

        /** text from the host until [want] */
        fun expect(want: String): String {
            val sb = StringBuilder()
            val end = System.currentTimeMillis() + 5000
            while (!sb.contains(want) && System.currentTimeMillis() < end) {
                val b = fromHost.poll(100, TimeUnit.MILLISECONDS) ?: continue
                sb.append(String(b, Charsets.ISO_8859_1))
            }
            assertTrue("\"$want\" in \"$sb\"", sb.contains(want))
            return sb.toString()
        }

        /** the computer's PPP client started on the line */
        fun startPpp(): Ppp.IpConfig {
            ppp = Ppp({ b -> toHost.add(b) }, events = object : Ppp.Events {
                override fun up(config: Ppp.IpConfig) { up.add(config) }
                override fun ip(packet: ByteArray) { if (!drop(packet)) packets.add(packet) }
                override fun down(reason: String) {}
            }, retryMs = 500)
            pppOn = true
            // (what came before PPP was there: its frames too)
            while (true) { val b = fromHost.poll() ?: break; ppp.input(b) }
            ppp.start()
            return up.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no link")
        }
    }

    @Test
    fun aDialerDialsAndGetsAnAddress() {
        val c = Computer()
        c.type("ATZ\r")
        c.expect("OK")
        c.type("AT&F E0 V1 &D2 S0=0\r")
        c.expect("OK")
        c.type("ATI\r")
        c.expect("D-Modem internet host")
        c.type("ATDT5551234\r")
        c.expect("CONNECT 115200")
        val cfg = c.startPpp()
        assertEquals("10.64.64.2", cfg.address.hostAddress)
        assertEquals(listOf("10.64.64.1"), cfg.dns.map { it.hostAddress })
        // (the host's side of the link up too: a moment after the computer's, perhaps)
        val end = System.currentTimeMillis() + 3000
        while (!c.host.online.value && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(c.host.online.value)
        // the computer hangs up (DTR): NO CARRIER, and commands again
        c.pppOn = false
        c.host.hangUp()
        c.expect("NO CARRIER")
        c.type("AT\r")
        c.expect("OK")
    }

    @Test
    fun windowsDirectCableAndPppStraightAway() {
        val c = Computer()
        c.type("CLIENT")
        c.type("\r")
        c.expect("CLIENTSERVER")
        assertEquals("10.64.64.2", c.startPpp().address.hostAddress)
        // a computer whose PPP just starts (no dial): the host's PPP starts with its first frame
        val d = Computer()
        assertEquals("10.64.64.2", d.startPpp().address.hostAddress)
    }

    /** a TCP connection by hand, from the computer's side */
    private class Tcp(val c: Computer, val dst: ByteArray, val dport: Int) {
        val me = byteArrayOf(10, 64, 64, 2)
        val sport = 40000 + Random.nextInt(1000)
        var seq = Random.nextInt()
        var ack = 0

        fun seg(flags: Int, data: ByteArray = ByteArray(0), window: Int = 8192) =
            c.ppp.sendIp(Ip.tcp(me, dst, sport, dport, seq, ack, flags, window, data, if (flags and Ip.SYN != 0) 1460 else 0))

        fun next(): ByteArray = c.packets.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("nothing came")
        fun flags(p: ByteArray) = p[33].toInt() and 0xff
        fun pseq(p: ByteArray) = java.nio.ByteBuffer.wrap(p, 24, 4).int
        fun data(p: ByteArray): ByteArray = p.copyOfRange(20 + ((p[32].toInt() and 0xff) ushr 4) * 4, p.size)

        fun open() {
            seg(Ip.SYN)
            seq += 1
            val sa = next()
            assertEquals(Ip.SYN or Ip.ACK, flags(sa))
            ack = pseq(sa) + 1
            seg(Ip.ACK)
        }
    }

    @Test
    fun tcpThroughTheNat() {
        val c = Computer()
        c.type("ATD\r")
        c.expect("CONNECT")
        c.startPpp()
        // a server on this host: "HELLO" back for what it's sent, then 200 KB, then it closes
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        closers.add { server.close() }
        val big = Random(9).nextBytes(200_000)
        Thread {
            val s = server.accept()
            val buf = ByteArray(5)
            var n = 0
            while (n < 5) n += s.getInputStream().read(buf, n, 5 - n)
            s.getOutputStream().write(String(buf).uppercase().toByteArray() + big)
            s.close()
        }.start()
        val t = Tcp(c, byteArrayOf(127, 0, 0, 1), server.localPort)
        t.open()
        t.seg(Ip.PSH or Ip.ACK, "hello".toByteArray())
        t.seq += 5
        // what comes, acknowledged; one segment lost on the way (the 4th), and sent again
        val got = ByteArrayOutputStream()
        var segments = 0
        var lost = false
        var fin = false
        while (!fin) {
            val p = t.next()
            val f = t.flags(p)
            if (f and Ip.ACK != 0 && t.data(p).isEmpty() && f and Ip.FIN == 0) continue
            segments++
            if (segments == 4 && !lost) { lost = true; continue }
            if (t.pseq(p) != t.ack) { t.seg(Ip.ACK); continue }
            val d = t.data(p)
            got.write(d)
            t.ack += d.size
            if (f and Ip.FIN != 0) { t.ack += 1; fin = true }
            t.seg(Ip.ACK)
        }
        val all = got.toByteArray()
        assertEquals("HELLO", String(all, 0, 5))
        assertArrayEquals(big, all.copyOfRange(5, all.size))
        assertTrue(lost)
        // our FIN, and the connection's gone
        t.seg(Ip.FIN or Ip.ACK)
    }

    @Test
    fun aRefusedConnectionIsReset() {
        val c = Computer()
        c.type("ATD\r")
        c.expect("CONNECT")
        c.startPpp()
        val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val t = Tcp(c, byteArrayOf(127, 0, 0, 1), closed)
        t.seg(Ip.SYN)
        val r = t.next()
        assertTrue(t.flags(r) and Ip.RST != 0)
    }

    @Test
    fun udpDnsAndPing() {
        // a "DNS server" on this host (its answer: the query back with "A")
        val dns = DatagramSocket(0, InetAddress.getLoopbackAddress())
        closers.add { dns.close() }
        Thread {
            val d = DatagramPacket(ByteArray(512), 512)
            dns.receive(d)
            dns.send(DatagramPacket(d.data.copyOf(d.length) + "A".toByteArray(), d.length + 1, d.socketAddress))
        }.start()
        val echo = DatagramSocket(0, InetAddress.getLoopbackAddress())
        closers.add { echo.close() }
        Thread {
            val d = DatagramPacket(ByteArray(512), 512)
            echo.receive(d)
            echo.send(DatagramPacket("echo:".toByteArray() + d.data.copyOf(d.length), d.length + 5, d.socketAddress))
        }.start()
        val c = Computer(dnsPort = dns.localPort)
        c.type("ATD\r")
        c.expect("CONNECT")
        c.startPpp()
        val me = byteArrayOf(10, 64, 64, 2)
        val gw = byteArrayOf(10, 64, 64, 1)
        // DNS to the gateway: the phone's DNS server's answer, from the gateway
        c.ppp.sendIp(Ip.udp(me, gw, 5353, 53, "query".toByteArray()))
        val a = c.packets.poll(5, TimeUnit.SECONDS)!!
        assertEquals("queryA", String(Ip.udpPayload(a)))
        assertArrayEquals(gw, a.copyOfRange(12, 16))
        // UDP elsewhere: as it is
        c.ppp.sendIp(Ip.udp(me, byteArrayOf(127, 0, 0, 1), 6000, echo.localPort, "hi".toByteArray()))
        val e = c.packets.poll(5, TimeUnit.SECONDS)!!
        assertEquals("echo:hi", String(Ip.udpPayload(e)))
        // a ping to the gateway
        c.ppp.sendIp(Ip.icmp(me, gw, 8, 0, byteArrayOf(0, 1, 0, 1) + "ping".toByteArray()))
        val pong = c.packets.poll(5, TimeUnit.SECONDS)!!
        assertEquals(0, pong[20].toInt())
        assertEquals("ping", String(pong, 28, 4))
    }
}
