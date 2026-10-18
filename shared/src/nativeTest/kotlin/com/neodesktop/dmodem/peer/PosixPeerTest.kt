// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.peer

import com.neodesktop.dmodem.io.background
import com.neodesktop.dmodem.io.monotonicMs
import com.neodesktop.dmodem.io.sleepMs
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.AF_UNIX
import platform.posix.SOCK_STREAM
import platform.posix.socketpair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The line to another phone as iOS has it ([PeerHost] on
 * [PosixTcpTransport], the modem's line an [FdDuplex]), over real TCP on
 * the loopback: two phones, each "modem" a socket pair's end answering
 * every sample with a byte of its own. A call rung, answered and heard
 * both ways; a wrong PIN turned away; a phone in a call busy; the hang-up.
 */
@OptIn(ExperimentalForeignApi::class)
class PosixPeerTest {
    private inner class Phone(name: String, listen: Boolean, val mark: Byte, callTo: String = "", pin: String = "") {
        private val lock = SynchronizedObject()
        private val events = ArrayList<String>()
        val heardOther = atomic(0)
        val hungUp = atomic(false)
        val host = PeerHost(0, callTo, pin, events = { e -> synchronized(lock) { events.add(e) } }, log = { }, ringMs = 300)

        init {
            assertEquals(null, host.start(listen))
        }

        fun events() = synchronized(lock) { events.toList() }

        /** the modem off hook: a socket pair, our end answering samples with [mark], the other to the line */
        fun offHook(caller: Boolean, dial: String) {
            val (modemFd, lineFd) = memScoped {
                val sv = allocArray<IntVar>(2)
                assertEquals(0, socketpair(AF_UNIX, SOCK_STREAM, 0, sv))
                sv[0] to sv[1]
            }
            val modem = FdDuplex(modemFd)
            // (the lead silence first, as the native modem writes it)
            modem.output.write(ByteArray(320) { mark })
            background("modem") {
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = modem.input.read(buf, 0, buf.size)
                        if (n < 0) break
                        for (i in 0 until n) if (buf[i] in 1..9 && buf[i] != mark) heardOther.incrementAndGet()
                        modem.output.write(ByteArray(n) { mark })
                    }
                } catch (_: Exception) {
                }
                hungUp.value = true
                modem.close()
            }
            host.lineStarted(caller, dial, lineFd)
        }
    }

    private fun waitFor(what: String, ms: Long = 15_000, cond: () -> Boolean) {
        val end = monotonicMs() + ms
        while (!cond()) {
            if (monotonicMs() > end) throw AssertionError("no $what")
            sleepMs(20)
        }
    }

    @Test
    fun aCallOverTcp() {
        val b = Phone("B", listen = true, mark = 2)
        val port = b.host.localPort
        assertTrue(port > 0)
        val a = Phone("A", listen = false, mark = 1, callTo = "127.0.0.1:$port")
        val c = Phone("C", listen = false, mark = 3, callTo = "127.0.0.1:$port")
        try {
            assertEquals("Waiting for the other phone's calls", b.host.stateText)
            a.offHook(caller = true, dial = "5551234")
            waitFor("RING at B") { "RING" in b.events() }
            assertTrue(b.host.stateText.startsWith("Ringing: 127.0.0.1"), b.host.stateText)
            b.offHook(caller = false, dial = "")
            waitFor("ANSWERED at A") { "ANSWERED" in a.events() }
            assertTrue(a.host.connected)
            waitFor("B's samples at A") { a.heardOther.value > 1000 }
            waitFor("A's samples at B") { b.heardOther.value > 1000 }
            // C calls B, in a call: busy
            c.offHook(caller = true, dial = "1")
            waitFor("C's BUSY") { "FAILED 486" in c.events() }
            // A hangs up: B's line ends, ready again
            a.host.stop()
            waitFor("B's end") { b.hungUp.value }
            waitFor("B ready") { b.host.stateText == "Waiting for the other phone's calls" }
        } finally {
            a.host.stop(); b.host.stop(); c.host.stop()
        }
    }

    @Test
    fun aPinOverTcp() {
        val b = Phone("B", listen = true, mark = 2, pin = "2468")
        val port = b.host.localPort
        val wrong = Phone("W", listen = false, mark = 3, callTo = "127.0.0.1:$port", pin = "1357")
        val right = Phone("A", listen = false, mark = 1, callTo = "127.0.0.1:$port", pin = "2468")
        try {
            wrong.offHook(caller = true, dial = "1")
            waitFor("W's denial") { "FAILED 403" in wrong.events() }
            assertTrue("RING" !in b.events())
            right.offHook(caller = true, dial = "1")
            waitFor("RING at B") { "RING" in b.events() }
        } finally {
            b.host.stop(); wrong.host.stop(); right.host.stop()
        }
    }

    @Test
    fun nobodyThere() {
        // a port nobody listens on: NO ANSWER's FAILED 480, why for the user
        val a = Phone("A", listen = false, mark = 1, callTo = "127.0.0.1:1")
        try {
            a.offHook(caller = true, dial = "1")
            waitFor("A's failure") { "FAILED 480" in a.events() }
            assertTrue(a.host.lastError!!.startsWith("can't reach 127.0.0.1:1"), a.host.lastError)
        } finally {
            a.host.stop()
        }
    }

    @Test
    fun addresses() {
        assertEquals("host" to 5070, PosixTcpTransport.parse("host", 5070))
        assertEquals("host" to 6000, PosixTcpTransport.parse("host:6000", 5070))
        assertEquals("fe80::1" to 5070, PosixTcpTransport.parse("fe80::1", 5070))
        assertEquals("fe80::1" to 6000, PosixTcpTransport.parse("[fe80::1]:6000", 5070))
    }
}
