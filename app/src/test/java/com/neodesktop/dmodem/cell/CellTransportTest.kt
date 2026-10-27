// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.cell

import com.neodesktop.dmodem.peer.Duplex
import com.neodesktop.dmodem.peer.PeerLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** The mobile call as the line's connection: the handshake made for it, the call placed, answered, hung up. */
class CellTransportTest {
    private val closers = ArrayList<() -> Unit>()

    /** the network's phones and transports closed, last first */
    @After
    fun tearDown() = closers.asReversed().forEach { runCatching { it() } }

    /** waits until [cond], up to [ms]; fails saying [what] */
    private fun waitUntil(what: String, ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError(what)
            Thread.sleep(10)
        }
    }

    /** a call placed: the number dialled, the handshake answered, samples reaching the far end once answered; closed, hung up both ends */
    @Test
    fun aCallPlacedAndHungUp() {
        val net = FakeNetwork(closers = closers)
        val a = net.phone("5550001")
        val b = net.phone("5550002")
        val t = CellTransport(a, a.audio, dialMs = 2000, audioMs = 1000)
        val d = t.connect("T555-0002")
        assertEquals(listOf("5550002"), a.dialled)
        assertEquals(CellPhone.State.RINGING, b.state.value)
        // the handshake: our RING taken, "ANSWER" back (the far end's ringing and answer come in the sound)
        d.output.write("DMODEM/1 RING T555-0002\n".toByteArray())
        assertEquals("ANSWER", PeerLine.readLine(d.input))
        // the far end answers: our samples reach it
        b.answer()
        d.output.write(ByteArray(960) { 7 })
        assertEquals(7, b.incoming.frame()[0].toInt())
        // closing it: hung up, both ends
        d.close()
        assertEquals(1, a.hungUp)
        assertEquals(CellPhone.State.IDLE, b.state.value)
    }

    /** a call the phone never places fails, and so does one with no number */
    @Test
    fun aCallNotPlaced() {
        val net = FakeNetwork(closers = closers)
        val a = net.phone("5550001")
        val stuck = object : CellPhone by a {
            /** never placed */
            override fun dial(number: String) {}
        }
        val t = CellTransport(stuck, a.audio, dialMs = 300, audioMs = 300)
        try {
            t.connect("5550002")
            throw AssertionError("connected")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("wasn't placed"))
        }
        try {
            CellTransport(a, a.audio).connect("T")
            throw AssertionError("connected")
        } catch (e: IOException) {
            assertEquals("no number to call", e.message)
        }
    }

    /** a call coming in: RING to the modem, its ANSWER answers the phone, its samples kept till then; the caller hanging up ends the line */
    @Test
    fun aCallComingIn() {
        val net = FakeNetwork(closers = closers)
        val a = net.phone("5550001")
        val b = net.phone("5550002")
        val calls = ArrayBlockingQueue<Duplex>(2)
        val t = CellTransport(b, b.audio, audioMs = 1000)
        t.listen { calls.add(it) }
        closers.add { t.close() }
        a.dial("5550002")
        val d = calls.poll(5, TimeUnit.SECONDS)!!
        assertEquals("DMODEM/1 RING", PeerLine.readLine(d.input))
        // the modem answers (never waiting: its samples kept until the call's taken)
        d.output.write("ANSWER\n".toByteArray())
        d.output.write(ByteArray(960) { 9 })
        waitUntil("answered") { b.answered == 1 && b.connected }
        waitUntil("the kept samples sent") { a.incoming.frame()[0].toInt() == 9 }
        // the caller hangs up: the line's end
        a.hangUp()
        val buf = ByteArray(4096)
        waitUntil("the end") { d.input.read(buf) < 0 }
    }

    /** a call the caller gives up on before it's answered ends the RING, unanswered */
    @Test
    fun theCallerGivesUp() {
        val net = FakeNetwork(closers = closers)
        val a = net.phone("5550001")
        val b = net.phone("5550002")
        val calls = ArrayBlockingQueue<Duplex>(2)
        val t = CellTransport(b, b.audio)
        t.listen { calls.add(it) }
        closers.add { t.close() }
        a.dial("5550002")
        val d = calls.poll(5, TimeUnit.SECONDS)!!
        assertEquals("DMODEM/1 RING", PeerLine.readLine(d.input))
        a.hangUp()
        // (unanswered: the RING ends)
        assertEquals(-1, d.input.read(ByteArray(10)))
        assertEquals(0, b.answered)
    }
}
