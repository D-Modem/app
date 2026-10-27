// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.jobs

import com.neodesktop.dmodem.fax.FakeFaxLine
import com.neodesktop.dmodem.fax.FaxImage
import com.neodesktop.dmodem.fax.FaxPage
import com.neodesktop.dmodem.fax.FaxTestPage
import com.neodesktop.dmodem.io.ByteQueue
import com.neodesktop.dmodem.io.Bytes
import com.neodesktop.dmodem.io.Inet4
import com.neodesktop.dmodem.io.Sink
import com.neodesktop.dmodem.io.Source
import com.neodesktop.dmodem.io.background
import com.neodesktop.dmodem.io.inet4
import com.neodesktop.dmodem.io.monotonicMs
import com.neodesktop.dmodem.io.sleepMs
import com.neodesktop.dmodem.ppp.Ip
import com.neodesktop.dmodem.ppp.Ppp
import com.neodesktop.dmodem.transfer.Incoming
import com.neodesktop.dmodem.transfer.Outgoing
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The jobs iOS lends the modem's DTE to, as its Swift drives them (octets
 * as Latin-1, calls back through the interfaces): a fax both ways on the
 * fake class 1 line, a ZMODEM transfer between two jobs, dial-up
 * internet to a fake ISP (PPP's server) with the tunnel's packets both
 * ways and the hang-up; grey images as fax pages.
 */
class JobsTest {
    private class Memory : Incoming {
        val files = LinkedHashMap<String, Bytes>()
        /** a new file [name] in memory: its stream and that name */
        override fun create(name: String, size: Long): Pair<Sink, String> = Bytes().let { b -> files[name] = b; b to name }
        /** file [name] forgotten */
        override fun abandon(name: String) { files.remove(name) }
    }

    private class Log : LogOut {
        val lock = SynchronizedObject()
        val lines = ArrayList<String>()
        /** [line] kept, in order */
        override fun log(line: String) { synchronized(lock) { lines.add(line) } }
    }

    /** waits up to [ms] for [cond], else fails for want of [what] */
    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        val end = monotonicMs() + ms
        while (!cond()) {
            if (monotonicMs() > end) throw AssertionError("no $what")
            sleepMs(20)
        }
    }

    /** a fax sent by one FaxJob and received by another, ECM, as a PDF, with the remote's identity and changes reported */
    @Test
    fun aFaxBothWays() {
        val line = FakeFaxLine()
        val pages = listOf(FaxTestPage.of(true, rows = 300, seed = 1), FaxTestPage.of(true, rows = 200, seed = 2))
        var changes = 0
        val tx = FaxJob({ s -> line.a.io.write(Latin1.bytes(s)); true }, Log(), { changes++ })
        val rx = FaxJob({ s -> line.b.io.write(Latin1.bytes(s)); true }, Log(), { })
        line.a.tap = { b -> tx.feed(Latin1.string(b)) }
        line.b.tap = { b -> rx.feed(Latin1.string(b)) }
        val into = Memory()
        try {
            rx.answer("+1 555 0199", ecm = true, maxBps = 14400, into = into, fileName = "fax.pdf")
            sleepMs(200)
            tx.send("5550199", pages, "+1 555 0100", ecm = true, maxBps = 14400)
            waitFor("the fax sent") { tx.state?.running == false }
            waitFor("the fax received") { rx.state?.running == false }
        } finally {
            line.stop()
        }
        val sent = assertNotNull(tx.state)
        assertEquals("", sent.result, sent.progress.phase)
        assertEquals(2, sent.progress.pages)
        assertTrue(sent.progress.ecm)
        assertEquals("+1 555 0199", sent.progress.remote)
        val got = assertNotNull(rx.state)
        assertEquals("", got.result)
        assertEquals("fax.pdf", got.file)
        assertEquals(2, got.progress.pages)
        val pdf = assertNotNull(into.files["fax.pdf"]).toByteArray()
        assertEquals("%PDF", pdf.copyOf(4).decodeToString())
        assertTrue(changes > 3)
    }

    /** a modem that answers ERROR to every fax command: that's the result, and the modem is put back to data */
    @Test
    fun noFaxModeAnswered() {
        // a modem that answers ERROR to everything: why, and the modem back to data
        lateinit var job: FaxJob
        val said = ArrayList<String>()
        job = FaxJob({ s ->
            said.add(s)
            if (s.endsWith("\r")) job.feed("\r\nERROR\r\n")
            true
        }, Log(), { })
        job.send("1", listOf(FaxTestPage.of(false, rows = 10)), "", ecm = true, maxBps = 14400)
        waitFor("its end") { job.state?.running == false }
        assertEquals("the modem has no fax mode", job.state?.result)
        assertTrue("AT+FCLASS=0\r" in said, "$said")
    }

    /** [data] as a file [name] to send */
    private fun outgoing(name: String, data: ByteArray) = Outgoing(name, data.size.toLong(), 0) {
        object : Source {
            var at = 0
            /** the next of [data], into [b]: how much, -1 at its end */
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= data.size) return -1
                val n = minOf(len, data.size - at)
                data.copyInto(b, off, at, at + n)
                at += n
                return n
            }
        }
    }

    /** a ZMODEM file from one TransferJob to another arrives whole, and a ZMODEM start is detected in the stream */
    @Test
    fun aTransferBetweenTwoJobs() {
        val data = Random(7).nextBytes(40_000)
        lateinit var tx: TransferJob
        lateinit var rx: TransferJob
        tx = TransferJob("ZMODEM", true, { s -> rx.feed(s); true }, Log(), { })
        rx = TransferJob("ZMODEM", false, { s -> tx.feed(s); true }, Log(), { })
        val into = Memory()
        rx.receive(into, "x.bin")
        tx.send(listOf(outgoing("hello.bin", data)))
        waitFor("the transfer") { !tx.state.running && !rx.state.running }
        assertEquals("", tx.state.result)
        assertEquals("", rx.state.result)
        assertEquals(listOf("hello.bin"), rx.state.received)
        assertContentEquals(data, into.files["hello.bin"]!!.toByteArray())
        assertEquals("ZMODEM_SEND", TransferJob.detect("x" + Latin1.string(byteArrayOf(0x2a, 0x2a, 0x18, 0x42, 0x30, 0x30)) + "y"))
        assertEquals("", TransferJob.detect("hello"))
        assertTrue("KERMIT" in TransferJob.protocols)
    }

    /** a modem dialling an ISP: OK to commands, CONNECT to ATD, then PPP's server; +++ and ATH heard */
    private class FakeIsp {
        lateinit var job: DialUpJob
        val heard = ByteQueue()
        val said = StringBuilder()
        @Volatile var online = false
        @Volatile var stopped = false
        val lock = SynchronizedObject()
        val serverPackets = ByteQueue()

        /** an address of four octets */
        private fun a(vararg b: Int): Inet4 = inet4(ByteArray(4) { b[it].toByte() })

        val server = Ppp({ b -> job.feed(Latin1.string(b)) }, events = object : Ppp.Events {
            /** (the server's link up: nothing to do) */
            override fun up(config: Ppp.IpConfig) {}
            /** a packet from the client, kept for the test */
            override fun ip(packet: ByteArray) = serverPackets.put(packet)
            /** the link gone down: offline */
            override fun down(reason: String) { online = false }
        }, retryMs = 300, server = Ppp.Server(a(10, 64, 64, 1), a(10, 64, 64, 2), a(10, 64, 64, 1)))

        /** the ISP's thread: commands answered, ATD connected and PPP started, then the line to PPP's server */
        fun start() = background("isp") {
            val cmd = StringBuilder()
            while (!stopped) {
                val b = heard.poll(50) ?: continue
                val text = Latin1.string(b)
                synchronized(lock) { said.append(text) }
                if (online) { server.input(b); continue }
                for (c in text) {
                    if (c != '\r') { cmd.append(c); continue }
                    val l = cmd.toString().trim().uppercase()
                    cmd.clear()
                    if (l.isEmpty()) continue
                    if (l.startsWith("ATD")) {
                        job.feed("\r\nCONNECT 33600\r\n")
                        online = true
                        server.start()
                    } else {
                        job.feed("\r\nOK\r\n")
                    }
                }
            }
        }

        /** what the ISP has heard so far */
        fun said() = synchronized(lock) { said.toString() }
    }

    private class Tunnel : PacketTunnel {
        @Volatile var address = ""
        @Volatile var dns = emptyList<String>()
        @Volatile var closed = false
        val packets = ByteQueue()
        /** a packet from the link, kept for the test */
        override fun packet(p: ByteArray) = packets.put(p)
        /** the link's address and DNS noted: up */
        override fun up(address: String, dns: List<String>, mtu: Int): Boolean { this.address = address; this.dns = dns; return true }
        /** the tunnel noted closed */
        override fun close() { closed = true }
    }

    /** dial-up through the fake ISP: the link's address and DNS to the tunnel, a packet each way, counts, and the hang-up with +++ and ATH */
    @Test
    fun dialUpInternet() {
        val isp = FakeIsp()
        val log = Log()
        val job = DialUpJob({ s -> isp.heard.put(Latin1.bytes(s)); true }, log, { })
        isp.job = job
        isp.start()
        val tunnel = Tunnel()
        try {
            job.connect("5551234", "me", "pw", "", "ATM1;ATL2", tunnel)
            waitFor("the link") { job.state.connected }
            assertEquals("10.64.64.2", job.state.address)
            assertEquals("10.64.64.2", tunnel.address)
            assertEquals(listOf("10.64.64.1"), tunnel.dns)
            assertEquals("CONNECT 33600", job.state.connect)
            assertTrue("ATM1\r" in isp.said() && "ATL2\r" in isp.said(), isp.said())
            // a packet each way
            val me = byteArrayOf(10, 64, 64, 2)
            val gw = byteArrayOf(10, 64, 64, 1)
            assertTrue(job.sendIp(Ip.udp(me, gw, 5000, 53, "up".encodeToByteArray())))
            assertEquals("up", Ip.udpPayload(assertNotNull(isp.serverPackets.poll(5000))).decodeToString())
            assertTrue(isp.server.sendIp(Ip.udp(gw, me, 53, 5000, "down".encodeToByteArray())))
            assertEquals("down", Ip.udpPayload(assertNotNull(tunnel.packets.poll(5000))).decodeToString())
            waitFor("the counts") { job.state.bytesIn > 0 && job.state.bytesOut > 0 }
            // hung up: PPP down, +++ and ATH, the tunnel closed
            job.disconnect()
            waitFor("its end") { !job.state.running }
            assertEquals("disconnected", job.state.ended)
            assertTrue(tunnel.closed)
            assertTrue("+++" in isp.said() && isp.said().endsWith("\rATH\r"), isp.said())
        } finally {
            isp.stopped = true
        }
    }

    /** a BUSY to the dial ends the call as busy, and the tunnel is closed */
    @Test
    fun dialUpBusy() {
        lateinit var job: DialUpJob
        job = DialUpJob({ s ->
            if (s.startsWith("ATD")) job.feed("\r\nBUSY\r\n") else if (s.endsWith("\r")) job.feed("\r\nOK\r\n")
            true
        }, Log(), { })
        val tunnel = Tunnel()
        job.connect("1", "", "", "", "", tunnel)
        waitFor("its end") { !job.state.running }
        assertEquals("busy", job.state.ended)
        assertTrue(tunnel.closed)
    }

    /** grey images as fax pages: thresholded, white to the fax's width, dithered about half black, and A4's rows */
    @Test
    fun greyAsFaxPages() {
        // a 4 by 2 image: black, white, dark grey, light grey
        val g = byteArrayOf(0, 255.toByte(), 60, 200.toByte(), 0, 0, 0, 0)
        val p = FaxImage.page(g, 4, 2, fine = true, dither = false)
        assertEquals(FaxPage.WIDTH, p.width)
        assertEquals(2, p.rows)
        assertEquals(listOf(true, false, true, false), (0 until 4).map { p.black(it, 0) })
        // (the rest of the width white)
        assertTrue((4 until FaxPage.WIDTH).none { p.black(it, 0) })
        assertTrue((0 until 4).all { p.black(it, 1) })
        // mid grey dithered: about half black
        val grey = ByteArray(FaxPage.WIDTH * 20) { 128.toByte() }
        val d = FaxImage.pageLatin1(Latin1.string(grey), FaxPage.WIDTH, 20, fine = false, dither = true)
        val black = (0 until 20).sumOf { y -> (0 until FaxPage.WIDTH).count { d.black(it, y) } }
        assertTrue(black in FaxPage.WIDTH * 20 * 4 / 10..FaxPage.WIDTH * 20 * 6 / 10, "$black")
        // an A4 page's rows at the fax's width
        assertEquals(830, FaxImage.rows(FaxPage.WIDTH, FaxPage.WIDTH, fine = false))
    }
}
