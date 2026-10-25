// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.peer

import com.neodesktop.dmodem.io.ByteQueue
import com.neodesktop.dmodem.io.Sink
import com.neodesktop.dmodem.io.Source
import com.neodesktop.dmodem.io.background
import com.neodesktop.dmodem.io.monotonicMs
import com.neodesktop.dmodem.io.sleepMs
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two phones' lines through [PeerLine] in memory, in common Kotlin (the
 * JVM, and natively as iOS has it): each phone's "modem" answers every
 * sample with one of its own (a byte that says whose). A call rung and
 * answered, each modem hearing the other's samples after the exchange's
 * tones; a phone in a call busy; the hang-up.
 */
class PeerLoopbackTest {
    /** one way of a pipe: what's written, read (blocking) on the other side; -1 once closed */
    private class Way {
        val q = ByteQueue()
        @Volatile var closed = false
        var cur = ByteArray(0)
        var at = 0
    }

    /** a pipe's end: reads [inw], writes [outw] */
    private class End(private val inw: Way, private val outw: Way, override val name: String) : Duplex {
        override val input = object : Source {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                while (inw.at >= inw.cur.size) {
                    if (inw.closed) return -1
                    inw.cur = inw.q.poll(50) ?: continue
                    inw.at = 0
                }
                val n = minOf(len, inw.cur.size - inw.at)
                inw.cur.copyInto(b, off, inw.at, inw.at + n)
                inw.at += n
                return n
            }
        }
        override val output = object : Sink {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (outw.closed || inw.closed) throw com.neodesktop.dmodem.io.IOException("closed")
                outw.q.put(b.copyOfRange(off, off + len))
            }
        }
        override fun close() {
            inw.closed = true
            outw.closed = true
        }
    }

    private fun pipe(a: String, b: String): Pair<Duplex, Duplex> {
        val ab = Way()
        val ba = Way()
        return End(ba, ab, a) to End(ab, ba, b)
    }

    /** phones by name: connect() to one listening */
    private class Exchange {
        val listeners = HashMap<String, (Duplex) -> Unit>()
        fun transport(me: String) = object : PeerTransport {
            override val label = "memory"
            override fun listen(accept: (Duplex) -> Unit) { listeners[me] = accept }
            override fun connect(address: String): Duplex {
                val accept = listeners[address] ?: throw com.neodesktop.dmodem.io.IOException("nobody at $address")
                val (mine, theirs) = (object {}).let { pipeOf(me, address) }
                background("accept") { accept(theirs) }
                return mine
            }
            override fun close() { listeners.remove(me) }
        }
        lateinit var pipeOf: (String, String) -> Pair<Duplex, Duplex>
    }

    /** a phone: its line, its "modem" answering each sample with [mark], what it heard and was told */
    private inner class Phone(val name: String, exchange: Exchange, listen: Boolean, val mark: Byte, pin: String = "") {
        val events = ArrayList<String>()
        val lock = SynchronizedObject()
        @Volatile var heardOther = 0
        @Volatile var hungUp = false
        val line = PeerLine(exchange.transport(name), events = { e -> synchronized(lock) { events.add(e) } },
            target = { it }, ringMs = 300, pin = pin)

        init { line.start(listen) }

        fun events() = synchronized(lock) { events.toList() }

        /** the modem off hook: its side of the line, answering samples with ours */
        fun offHook(caller: Boolean, dial: String) {
            val (modem, app) = pipe("modem", "app")
            // (the modem's lead silence first, as the native one writes it)
            modem.output.write(ByteArray(160 * 2) { mark })
            background("modem") {
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = modem.input.read(buf, 0, buf.size)
                        if (n < 0) break
                        for (i in 0 until n) if (buf[i] != 0.toByte() && buf[i] != mark && buf[i].toInt() in 1..9) heardOther++
                        modem.output.write(ByteArray(n) { mark })
                    }
                } catch (_: Exception) {
                }
                hungUp = true
            }
            line.lineStarted(caller, dial, app)
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
    fun aCallRungAnsweredHeardAndHungUp() {
        val ex = Exchange().apply { pipeOf = { a, b -> pipe(a, b) } }
        val b = Phone("B", ex, listen = true, mark = 2)
        val a = Phone("A", ex, listen = false, mark = 1)
        val c = Phone("C", ex, listen = false, mark = 3)
        try {
            a.offHook(caller = true, dial = "B")
            // B rings (after A's dial tone and dialling: 2.2 s of the exchange's tones)
            waitFor("RING at B") { "RING" in b.events() }
            b.offHook(caller = false, dial = "")
            waitFor("ANSWERED at A") { "ANSWERED" in a.events() }
            assertTrue(a.line.state.value is PeerLine.State.Connected)
            assertTrue(b.line.state.value is PeerLine.State.Connected)
            // each modem hears the other's samples
            waitFor("B's samples at A") { a.heardOther > 1000 }
            waitFor("A's samples at B") { b.heardOther > 1000 }

            // C calls B, in a call: busy
            c.offHook(caller = true, dial = "B")
            // (what C got, if it isn't busy: it has failed now and then under load)
            try {
                waitFor("C's BUSY") { "FAILED 486" in c.events() }
            } catch (e: AssertionError) {
                throw AssertionError(
                    "no BUSY for C: its events ${c.events()}, its line ${c.line.state.value} (${c.line.lastError.value}); " +
                        "A's ${a.events()}, B's ${b.events()}",
                )
            }

            // A hangs up (its modem's line closed): B's line ends
            a.line.stop()
            waitFor("B's end") { b.hungUp }
            assertEquals(PeerLine.State.Ready(true), b.line.state.value)
        } finally {
            a.line.stop(); b.line.stop(); c.line.stop()
        }
    }

    @Test
    fun aPinToCall() {
        val ex = Exchange().apply { pipeOf = { a, b -> pipe(a, b) } }
        val b = Phone("B", ex, listen = true, mark = 2, pin = "4321")
        val wrong = Phone("W", ex, listen = false, mark = 3, pin = "1234")
        val none = Phone("N", ex, listen = false, mark = 4)
        val a = Phone("A", ex, listen = false, mark = 1, pin = "4321")
        try {
            // the wrong PIN: turned down (NO CARRIER for the modem), B never rang
            wrong.offHook(caller = true, dial = "B")
            waitFor("W's denial") { "FAILED 403" in wrong.events() }
            assertTrue(wrong.line.lastError.value!!.contains("PIN"))
            // no PIN: the same
            none.offHook(caller = true, dial = "B")
            waitFor("N's denial") { "FAILED 403" in none.events() }
            assertTrue(none.line.lastError.value!!.contains("wants a PIN"))
            assertTrue("RING" !in b.events())
            assertTrue(b.line.lastError.value!!.contains("wrong PIN"))
            // the right one (after the wait the wrong one earned): rung, answered
            a.offHook(caller = true, dial = "B")
            waitFor("RING at B") { "RING" in b.events() }
            b.offHook(caller = false, dial = "")
            waitFor("ANSWERED at A") { "ANSWERED" in a.events() }
            waitFor("B's samples at A") { a.heardOther > 1000 }
        } finally {
            a.line.stop(); b.line.stop(); wrong.line.stop(); none.line.stop()
        }
    }

    @Test
    fun theProof() {
        // (the PIN never on the line: md5("DMODEM/1:" nonce ":" PIN))
        assertEquals("4f977da0881b962516dbccec5ebd585d", PeerLine.pinProof("ab", "1"))
        assertTrue(PeerLine.pinProof("ab", "1") != PeerLine.pinProof("ab", "2"))
        assertTrue(PeerLine.pinProof("ab", "1") != PeerLine.pinProof("ac", "1"))
    }
}
