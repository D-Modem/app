// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.modem

import com.neodesktop.dmodem.tones.CountryTones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The call progress tones: when they play (what the modem says and does,
 * the other phone ringing), and what plays (the country's, for as long).
 */
class CallTonesTest {
    private class FakePlayer : CallProgress.Player {
        val heard = CopyOnWriteArrayList<String>()
        @Volatile var now: CallTones.Kind? = null
        /** kept as heard, [kind] now playing */
        override fun play(kind: CallTones.Kind, ms: Int) { heard.add("$kind $ms"); now = kind }
        /** the tone playing stopped (only [only], if given), kept as heard */
        override fun stop(only: CallTones.Kind?) {
            if (only != null && now != only) return
            heard.add("stop" + (only?.let { " $it" } ?: ""))
            now = null
        }
    }

    private var clock = 100_000L

    /** a call's progress on a fake player, on the test's clock */
    private fun progress(): Pair<CallProgress, FakePlayer> {
        val p = FakePlayer()
        return CallProgress(p) { clock } to p
    }

    /** BUSY from the modem, split or not, plays the busy tone */
    @Test
    fun busyWhenTheModemSaysSo() {
        val (cp, p) = progress()
        cp.status(offhook = true, connecting = false)
        cp.modemText("\r\nBU")
        cp.modemText("SY\r\n")
        cp.status(offhook = false, connecting = false)
        assertEquals(listOf("BUSY 4000"), p.heard)
    }

    /** a dial ending in NO CARRIER or NO DIALTONE before a connect plays congestion; a call that connected, none */
    @Test
    fun congestionForADialThatNeverGotThrough() {
        val (cp, p) = progress()
        // dialled: off hook, no answer, NO CARRIER (SIP's 503, say)
        cp.status(offhook = true, connecting = false)
        cp.modemText("\r\nNO CARRIER\r\n")
        assertEquals(listOf("CONGESTION 4000"), p.heard)
        // answered and trained, then the line went: no tone
        p.heard.clear()
        cp.status(offhook = false, connecting = false)
        cp.status(offhook = true, connecting = false)
        cp.status(offhook = true, connecting = true)
        cp.modemText("\r\nCONNECT 33600\r\n")
        cp.modemText("\r\nNO CARRIER\r\n")
        assertTrue(p.heard.none { it.startsWith("CONGESTION") })
        // NO DIALTONE too
        p.heard.clear()
        cp.status(offhook = false, connecting = false)
        cp.status(offhook = true, connecting = false)
        cp.modemText("NO DIALTONE\r\n")
        assertEquals(listOf("CONGESTION 4000"), p.heard)
    }

    /** an answered call's NO CARRIER plays no tone; long after the ring it's a dial again */
    @Test
    fun aCallAnsweredIsntOneDialled() {
        val (cp, p) = progress()
        cp.modemText("\r\nRING\r\n")
        clock += 2000
        cp.status(offhook = true, connecting = false)
        cp.modemText("\r\nNO CARRIER\r\n")
        assertTrue(p.heard.isEmpty())
        // long after the ring: a dial again
        clock += 60_000
        cp.status(offhook = false, connecting = false)
        cp.status(offhook = true, connecting = false)
        cp.modemText("\r\nNO CARRIER\r\n")
        assertEquals(listOf("CONGESTION 4000"), p.heard)
    }

    /** the other phone ringing plays the ringing tone; BUSY there the busy tone, left playing; answered, the ringing stopped */
    @Test
    fun theOtherPhoneRinging() {
        val (cp, p) = progress()
        cp.status(offhook = true, connecting = false)
        cp.ringingThere(true)
        assertEquals(listOf("RINGING 0"), p.heard)
        // busy there: the busy tone comes, and the ringing's end doesn't stop it
        cp.modemText("\r\nBUSY\r\n")
        cp.ringingThere(false)
        assertEquals(listOf("RINGING 0", "BUSY 4000"), p.heard)
        assertEquals(CallTones.Kind.BUSY, p.now)
        // answered: the ringing stopped
        p.heard.clear()
        cp.ringingThere(true)
        cp.ringingThere(false)
        cp.status(offhook = true, connecting = true)
        assertEquals(listOf("RINGING 0", "stop RINGING", "stop"), p.heard)
    }

    /** a player's sink: the samples written, as they come */
    private class FakeSink : ModemSpeaker.Sink {
        val got = ByteArrayOutputStream()
        @Volatile var closed = false
        /** kept, as written */
        override fun write(pcm: ByteArray, n: Int) = synchronized(got) { got.write(pcm, 0, n) }
        /** any volume taken */
        override fun volume(v: Float) {}
        /** closed, for the test to see */
        override fun close() { closed = true }
        /** the samples written so far, 16-bit little-endian */
        fun samples(): ShortArray = synchronized(got) {
            val b = got.toByteArray()
            ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xff) or (b[2 * it + 1].toInt() shl 8)).toShort() }
        }
    }

    /** [f] Hz's level in [x] from [from], over [n] samples (Goertzel's) */
    private fun level(x: ShortArray, f: Double, from: Int, n: Int): Double {
        val k = 2 * cos(2 * PI * f / 8000)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until from + n) {
            val s = x[i] / 32768.0 + k * s1 - s2
            s2 = s1
            s1 = s
        }
        return 2 * sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / n
    }

    /** a second of the UK's dial tone (350 and 440 Hz, not 400), then the tail's silence; closed, nothing playing */
    @Test
    fun aSecondOfTheCountrysDialTone() {
        val sinks = CopyOnWriteArrayList<FakeSink>()
        val tones = CallTones({ FakeSink().also { sinks.add(it) } })
        tones.country = CountryTones.find("UK")!!
        val t0 = System.nanoTime()
        tones.playAndWait(CallTones.Kind.DIAL, 1000)
        val sink = sinks.single()
        assertTrue(sink.closed)
        assertNull(tones.playing)
        val x = sink.samples()
        // a second of it, then the tail's silence
        assertEquals(8000 + CallTones.TAIL_MS * 8, x.size)
        // the UK's dial tone, 350 and 440 Hz; not its 400 Hz busy tone
        assertTrue(level(x, 350.0, 800, 6400) > 0.1)
        assertTrue(level(x, 440.0, 800, 6400) > 0.1)
        assertTrue(level(x, 400.0, 800, 6400) < 0.02)
        assertTrue((8000 until x.size).all { x[it].toInt() == 0 })
        // (back no sooner than the fake sink takes it: at once)
        assertTrue(System.nanoTime() - t0 < 5_000_000_000)
    }

    /** the ringing tone played until stopped (not by another's stop); off, nothing plays; the preview plays all four anyway */
    @Test
    fun stoppedAndTurnedOff() {
        val sinks = CopyOnWriteArrayList<FakeSink>()
        val tones = CallTones({ FakeSink().also { sinks.add(it) } })
        // until stopped: the ringing tone goes on
        tones.play(CallTones.Kind.RINGING, 0)
        Thread.sleep(200)
        assertEquals(CallTones.Kind.RINGING, tones.playing)
        tones.stop(CallTones.Kind.BUSY)
        assertEquals(CallTones.Kind.RINGING, tones.playing)
        tones.stop()
        assertNull(tones.playing)
        val end = System.currentTimeMillis() + 2000
        while (!sinks[0].closed && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(sinks[0].closed)
        // off: nothing
        tones.enabled = false
        tones.playAndWait(CallTones.Kind.DIAL, 1000)
        assertEquals(1, sinks.size)
        // the settings' preview plays all four, off or not
        tones.preview(CountryTones.find("USA")!!)
        val end2 = System.currentTimeMillis() + 5000
        while (!(sinks.size == 2 && sinks[1].closed) && System.currentTimeMillis() < end2) Thread.sleep(10)
        assertEquals(8 * (1500 + 6000 + 2500 + 2500 + CallTones.TAIL_MS), sinks[1].samples().size)
    }
}
