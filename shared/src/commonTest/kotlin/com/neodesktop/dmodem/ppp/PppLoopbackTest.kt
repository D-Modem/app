// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.ppp

import com.neodesktop.dmodem.io.ByteQueue
import com.neodesktop.dmodem.io.Inet4
import com.neodesktop.dmodem.io.Md5
import com.neodesktop.dmodem.io.dotted
import com.neodesktop.dmodem.io.inet4
import com.neodesktop.dmodem.io.monotonicMs
import com.neodesktop.dmodem.io.sleepMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PPP between a client and a server (the phone as the computer's ISP) in
 * memory, in common Kotlin (the JVM, and natively as iOS has it): the
 * link up, the client's address and DNS from the server, IP both ways,
 * the hang-up; MD5 as RFC 1321 has it (CHAP's logins).
 */
class PppLoopbackTest {
    private class End(val name: String) : Ppp.Events {
        @Volatile var config: Ppp.IpConfig? = null
        @Volatile var down: String? = null
        val packets = ByteQueue()
        /** the link's configuration, noted */
        override fun up(config: Ppp.IpConfig) { this.config = config }
        /** an IP packet from the link, kept for the test */
        override fun ip(packet: ByteArray) = packets.put(packet)
        /** why the link went down, noted */
        override fun down(reason: String) { down = reason }
    }

    private class Flag { @Volatile var on = false }

    /** waits up to [ms] for [cond], else fails for want of [what] */
    private fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = monotonicMs() + ms
        while (!cond()) {
            if (monotonicMs() > end) throw AssertionError("no $what")
            sleepMs(10)
        }
    }

    /** an address of four octets */
    private fun a(vararg b: Int): Inet4 = inet4(ByteArray(4) { b[it].toByte() })

    /** a client and its server: the link up with the server's address and DNS, a UDP packet each way, and the client's hang-up downing both */
    @Test
    fun aClientAndItsServer() = runBlocking {
        val toServer = ByteQueue()
        val toClient = ByteQueue()
        val c = End("client")
        val s = End("server")
        val client = Ppp({ toServer.put(it) }, events = c, retryMs = 300)
        val server = Ppp({ toClient.put(it) }, events = s, retryMs = 300,
            server = Ppp.Server(a(10, 64, 64, 1), a(10, 64, 64, 2), a(10, 64, 64, 1)))
        val done = Flag()
        // (each way on a thread of its own, as a serial line's two ends are)
        launch(Dispatchers.Default) { while (!done.on) toServer.poll(50)?.let { server.input(it) } }
        launch(Dispatchers.Default) { while (!done.on) toClient.poll(50)?.let { client.input(it) } }
        try {
            server.start()
            client.start()
            waitFor("link") { c.config != null && s.config != null }
            val cfg = assertNotNull(c.config)
            assertEquals("10.64.64.2", cfg.address.dotted)
            assertEquals(listOf("10.64.64.1"), cfg.dns.map { it.dotted })
            assertEquals(Ppp.Phase.OPEN, client.phase)

            // IP both ways: a UDP packet each
            val me = byteArrayOf(10, 64, 64, 2)
            val gw = byteArrayOf(10, 64, 64, 1)
            assertTrue(client.sendIp(Ip.udp(me, gw, 5000, 53, "to the server".encodeToByteArray())))
            val up = assertNotNull(s.packets.poll(5_000))
            assertEquals("to the server", Ip.udpPayload(up).decodeToString())
            assertTrue(server.sendIp(Ip.udp(gw, me, 53, 5000, "to the client".encodeToByteArray())))
            val back = assertNotNull(c.packets.poll(5_000))
            assertEquals("to the client", Ip.udpPayload(back).decodeToString())

            // the client hangs up: both ends down
            client.stop("bye")
            waitFor("the end") { c.down != null && s.down != null }
        } finally {
            done.on = true
            client.stop()
            server.stop()
        }
    }

    /** MD5 gives RFC 1321's digests, over more than one block too */
    @Test
    fun md5() {
        /** [b] in lower-case hex */
        fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hex(Md5.digest(ByteArray(0))))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex(Md5.digest("abc".encodeToByteArray())))
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", hex(Md5.digest("The quick brown fox jumps over the lazy dog".encodeToByteArray())))
        // (more than one block)
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", hex(Md5.digest("12345678901234567890123456789012345678901234567890123456789012345678901234567890".encodeToByteArray())))
    }
}
