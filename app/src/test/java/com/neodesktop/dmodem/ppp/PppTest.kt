// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.ppp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * PPP: its framing; PAP and CHAP-MD5 against a server in the test (a
 * wrong password said), the address and DNS from IPCP. (The packets
 * themselves, on a modem call: InternetLineTest.)
 */
class PppTest {

    /** FCS-16 as RFC 1662's, frames encoded (flags and control characters escaped) and decoded in pieces, a damaged one dropped and counted */
    @Test
    fun framingBothWays() {
        // FCS-16 of "123456789": 0x906e (RFC 1662's, complemented)
        assertEquals(0x906e, Hdlc.fcs("123456789".toByteArray()) xor 0xffff)
        val got = ArrayList<ByteArray>()
        val dec = Hdlc.Decoder { got.add(it) }
        val frames = (0 until 20).map { Random(it).nextBytes(Random(it).nextInt(4, 1600)) } + listOf(ByteArray(300) { 0x7e }, ByteArray(300) { 0x7d })
        for ((i, f) in frames.withIndex()) {
            val e = Hdlc.encode(f, if (i % 2 == 0) -1 else 0)
            // no flags inside, control characters escaped when asked
            assertTrue(e.drop(1).dropLast(1).none { it.toInt() == 0x7e })
            if (i % 2 == 0) assertTrue(e.drop(1).dropLast(1).none { (it.toInt() and 0xff) < 0x20 })
            // fed a few octets at a time
            var k = 0
            while (k < e.size) { val n = minOf(7, e.size - k); dec.feed(e, k, n); k += n }
        }
        assertEquals(frames.size, got.size)
        for (i in frames.indices) assertArrayEquals(frames[i], got[i])
        // a damaged frame: dropped and counted
        val bad = Hdlc.encode(frames[0], -1).also { it[5] = (it[5].toInt() xor 1).toByte() }
        dec.feed(bad)
        assertEquals(frames.size, got.size)
        assertEquals(1, dec.bad)
    }

    private class Events : Ppp.Events {
        val up = ArrayBlockingQueue<Ppp.IpConfig>(1)
        val down = ArrayBlockingQueue<String>(1)
        val packets = ArrayBlockingQueue<ByteArray>(100)
        val lines = ArrayList<String>()
        /** the link up: its addresses, kept */
        override fun up(config: Ppp.IpConfig) { up.add(config) }
        /** a packet, kept */
        override fun ip(packet: ByteArray) { packets.add(packet) }
        /** the link down: why, kept */
        override fun down(reason: String) { down.add(reason) }
        /** a line of PPP's log, kept */
        override fun log(line: String) { synchronized(lines) { lines.add(line) } }
    }

    /** our PPP logging in as alice with [password] to a [TestIsp] asking for [auth], the two joined directly: its events, and the ISP */
    private fun login(auth: Int, password: String): Pair<Events, TestIsp> {
        val ev = Events()
        lateinit var isp: TestIsp
        lateinit var ppp: Ppp
        // the two joined directly, each on its own thread's turn
        val toIsp = ArrayBlockingQueue<ByteArray>(1000)
        val toUs = ArrayBlockingQueue<ByteArray>(1000)
        ppp = Ppp({ toIsp.add(it) }, user = "alice", password = password, events = ev, retryMs = 500, maxRetries = 4)
        isp = TestIsp(auth, "alice", "s3cret") { toUs.add(it) }
        Thread { while (true) isp.input(toIsp.take()) }.apply { isDaemon = true; start() }
        Thread { while (true) ppp.input(toUs.take()) }.apply { isDaemon = true; start() }
        isp.start()
        ppp.start()
        return ev to isp
    }

    /** PAP and CHAP both logged in, the address, DNS and peer from IPCP */
    @Test
    fun papAndChap() {
        for (auth in listOf(Ppp.PROTO_PAP, Ppp.PROTO_CHAP)) {
            val (ev, isp) = login(auth, "s3cret")
            val cfg = ev.up.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("%04x: not up: ${ev.lines}".format(auth))
            assertTrue(isp.loggedIn)
            assertEquals("192.168.7.42", cfg.address.hostAddress)
            assertEquals(listOf("192.168.7.1"), cfg.dns.map { it.hostAddress })
            assertEquals("192.168.7.1", cfg.peer?.hostAddress)
        }
    }

    /** a wrong password, with PAP or CHAP: the link down saying the login was refused, never up */
    @Test
    fun aWrongPasswordIsSaid() {
        for (auth in listOf(Ppp.PROTO_PAP, Ppp.PROTO_CHAP)) {
            val (ev, _) = login(auth, "wrong")
            val why = ev.down.poll(10, TimeUnit.SECONDS)
            assertTrue("%04x: $why".format(auth), why != null && why.startsWith("the login was refused"))
            assertEquals(null, ev.up.poll())
        }
    }
}
