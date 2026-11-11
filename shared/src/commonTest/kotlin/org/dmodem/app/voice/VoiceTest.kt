// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.voice

import org.dmodem.app.io.Bytes
import org.dmodem.app.io.Sink
import org.dmodem.app.io.background
import org.dmodem.app.io.monotonicMs
import org.dmodem.app.io.sleepMs
import org.dmodem.app.jobs.Latin1
import org.dmodem.app.jobs.LogOut
import org.dmodem.app.jobs.VoiceJob
import org.dmodem.app.transfer.Incoming
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Voice calls on a fake V.253 modem: dialled and hung up, busy, no voice
 * mode, the answering machine's message (the far end hanging up, or
 * quiet), picked up; G.711, tones and WAV files.
 */
class VoiceTest {
    /**
     * A modem in voice mode as dmodemd is: commands answered, AT+VTR's
     * stream a 440 Hz tone (for [toneMs], then silence, then [then]: a
     * <DLE> code, or nothing), our samples counted, <DLE>^ answered
     * with <DLE><ETX> and OK.
     */
    private class FakeModem(
        val dial: String = "OK",
        val voice: Boolean = true,
        val toneMs: Long = 100_000,
        val endMs: Long = 100_000,
        val then: Char? = null,
        val digits: String = "",
    ) {
        lateinit var job: VoiceJob
        private val lock = SynchronizedObject()
        val commands = ArrayList<String>()
        private val line = StringBuilder()
        @Volatile var streaming = false
        @Volatile var got = 0
        @Volatile var loud = 0
        @Volatile var dle = false
        @Volatile var stopped = false
        private var phase = 0.0

        /** octets from the job */
        fun write(s: String): Boolean {
            for (ch in s) {
                val c = ch.code
                if (streaming) {
                    if (dle) {
                        dle = false
                        when (c) {
                            0x10 -> sample(c)
                            '^'.code -> {
                                streaming = false
                                say("\u0010\u0003\r\nOK\r\n")
                            }
                        }
                    } else if (c == 0x10) {
                        dle = true
                    } else {
                        sample(c)
                    }
                    continue
                }
                if (c == '\r'.code) {
                    command(line.toString())
                    line.clear()
                } else {
                    line.append(ch)
                }
            }
            return true
        }

        /** a sample of ours counted (a loud one too) */
        private fun sample(u: Int) {
            got++
            if (abs(G711.unulaw(u)) > 2000) loud++
        }

        /** [s] to the job, from another thread */
        private fun say(s: String) = background("fake") { job.feed(s) }

        /** a command answered as dmodemd would */
        private fun command(cmd: String) {
            synchronized(lock) { commands.add(cmd) }
            val c = cmd.removePrefix("AT")
            when {
                !voice && (c == "+FCLASS=8" || c == "+VSM=4") -> say("\r\nERROR\r\n")
                c.startsWith("D") -> background("dial") { sleepMs(100); job.feed("\r\n$dial\r\n") }
                c == "+VTR" -> {
                    streaming = true
                    job.feed("\r\nCONNECT\r\n")
                    background("stream") { stream() }
                }
                else -> say("\r\nOK\r\n")
            }
        }

        /** the far end's sound, 20 ms at a time */
        private fun stream() {
            val t0 = monotonicMs()
            var sent = 0L
            var told = false
            var pressed = false
            while (streaming && !stopped) {
                val now = monotonicMs() - t0
                val b = StringBuilder()
                while (sent < now * 8) {
                    val x = if (now < toneMs) (sin(phase) * 8000).toInt() else 0
                    phase += 2 * PI * 440 / 8000
                    val u = G711.ulaw(x)
                    b.append(u.toChar())
                    if (u == 0x10) b.append(u.toChar())
                    sent++
                }
                if (!pressed && digits.isNotEmpty() && now > 200) {
                    pressed = true
                    for (d in digits) b.append('\u0010').append(d)
                }
                if (!told && then != null && now > endMs) {
                    told = true
                    b.append('\u0010').append(then)
                }
                job.feed(b.toString())
                sleepMs(20)
            }
        }

        /** the commands so far */
        fun said(): List<String> = synchronized(lock) { ArrayList(commands) }
    }

    private class Memory : Incoming {
        val files = LinkedHashMap<String, Bytes>()
        /** a new file [name] in memory: its stream and that name */
        override fun create(name: String, size: Long): Pair<Sink, String> = Bytes().let { b -> files[name] = b; b to name }
        /** file [name] forgotten */
        override fun abandon(name: String) { files.remove(name) }
    }

    private val log = LogOut { }

    /** waits up to [ms] for [cond], else fails for want of [what] */
    private fun waitFor(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = monotonicMs() + ms
        while (!cond()) {
            if (monotonicMs() > end) throw AssertionError("no $what")
            sleepMs(20)
        }
    }

    /** a voice job on [m] */
    private fun job(m: FakeModem): VoiceJob = VoiceJob({ s -> m.write(s) }, log, { }).also { m.job = it }

    /** a call dialled: voice mode, μ-law, ATD, AT+VTR; both ends' sound at the line's pace; hung up and back to data */
    @Test
    fun aCallBothWays() {
        val m = FakeModem()
        val j = job(m)
        try {
            j.dial("555 1234")
            waitFor("the call") { j.state?.phase == VoiceJob.Phase.TALKING }
            val mic = ShortArray(160) { (sin(2 * PI * 300 * it / 8000) * 10000).toInt().toShort() }
            val heard = ShortArray(8000)
            var n = 0
            val got0 = m.got
            val loud0 = m.loud
            val t0 = monotonicMs()
            while (monotonicMs() - t0 < 1000) {
                j.write(mic)
                n += j.read(heard, n % 4000, 160)
                sleepMs(20)
            }
            val ms = monotonicMs() - t0
            val sent = m.got - got0
            // (the line's pace, 8 a millisecond, give or take a slow machine's scheduling: a simulator's)
            assertTrue(n > ms * 4, "heard $n in $ms ms")
            assertTrue(heard.take(4000).any { abs(it.toInt()) > 4000 }, "the far end's tone")
            assertTrue(sent in ms * 5..ms * 8 + 2000, "sent $sent in $ms ms")
            assertTrue(m.loud - loud0 > sent / 4, "loud ${m.loud - loud0} of $sent")
            j.hangUp()
            waitFor("the end") { j.state?.running == false }
        } finally {
            m.stopped = true
        }
        assertEquals("", j.state?.result)
        assertEquals(listOf("ATE0V1", "AT+FCLASS=8", "AT+VSM=4", "ATD555 1234", "AT+VTR", "ATH", "AT+FCLASS=0"), m.said())
        assertEquals("", j.leftover)
    }

    /** busy: why the call didn't go, and the modem back to data */
    @Test
    fun busy() {
        val m = FakeModem(dial = "BUSY")
        val j = job(m)
        j.dial("5551234")
        waitFor("the end") { j.state?.running == false }
        assertEquals("busy", j.state?.result)
        assertTrue("AT+FCLASS=0" in m.said())
        assertTrue("AT+VTR" !in m.said())
    }

    /** a modem without voice mode */
    @Test
    fun noVoiceMode() {
        val m = FakeModem(voice = false)
        val j = job(m)
        j.answer()
        waitFor("the end") { j.state?.running == false }
        assertEquals("the modem has no voice mode", j.state?.result)
        assertTrue("AT+FCLASS=0" in m.said())
    }

    /** the answering machine: greeting, beep, the caller's message until they hang up, saved as a WAV */
    @Test
    fun aMessageTaken() {
        val m = FakeModem(toneMs = 2500, endMs = 2600, then = 'l', digits = "12#")
        val j = job(m)
        val into = Memory()
        try {
            j.takeMessage(ShortArray(1600) { 3000 }, 60, 7, into, VoiceJob.messageName("20261111-120000", "+1 555-1234"), caller = "5551234")
            waitFor("the end") { j.state?.running == false }
        } finally {
            m.stopped = true
        }
        val s = assertNotNull(j.state)
        assertEquals("", s.result)
        assertEquals("20261111-120000_+15551234.wav", s.message)
        assertEquals("+15551234", VoiceJob.messageCaller(s.message))
        assertEquals("12#", s.digits)
        assertTrue("AT+VLS=1" in m.said())
        // (0.2 s greeting, 0.5 s beep: about 1.8 s of tone left, its end's quiet but half a second)
        val pcm = assertNotNull(Wav.decode(assertNotNull(into.files[s.message]).toByteArray()))
        assertTrue(pcm.size in 12000..20000, "${pcm.size}")
        assertEquals(pcm.size / 8000, s.messageSeconds)
        assertTrue(pcm.count { abs(it.toInt()) > 4000 } > 4000)
        // (the beep reached the line: loud samples beyond the greeting's)
        assertTrue(m.loud > 2000, "loud ${m.loud}")
    }

    /** the caller quiet for silenceS: the message ends, the machine hangs up itself */
    @Test
    fun aQuietCallerEnds() {
        val m = FakeModem(toneMs = 1800)
        val j = job(m)
        val into = Memory()
        try {
            j.takeMessage(null, 60, 1, into, "m.wav")
            waitFor("the end") { j.state?.running == false }
        } finally {
            m.stopped = true
        }
        assertEquals("m.wav", j.state?.message)
        val pcm = assertNotNull(Wav.decode(assertNotNull(into.files["m.wav"]).toByteArray()))
        // (1.3 s of tone after the beep, half a second of quiet kept)
        assertTrue(pcm.size in 12000..16000, "${pcm.size}")
        assertTrue("ATH" in m.said())
    }

    /** a call picked up from the answering machine: a call between people, no message */
    @Test
    fun pickedUp() {
        val m = FakeModem()
        val j = job(m)
        val into = Memory()
        try {
            j.takeMessage(null, 60, 7, into, "m.wav")
            waitFor("the recording") { j.state?.phase == VoiceJob.Phase.RECORDING }
            assertTrue(j.pickUp())
            assertEquals(VoiceJob.Phase.TALKING, j.state?.phase)
            sleepMs(300)
            j.hangUp()
            waitFor("the end") { j.state?.running == false }
        } finally {
            m.stopped = true
        }
        assertEquals("", j.state?.message)
        assertTrue(into.files.isEmpty())
    }

    /** μ-law both ways, and A-law's smallest */
    @Test
    fun g711() {
        for (u in 0..255) {
            val x = G711.unulaw(u)
            // (0x7f and 0xff are both zero)
            if (u != 0x7f) assertEquals(u, G711.ulaw(x), "u $u")
        }
        assertEquals(0, G711.unulaw(0xff))
        assertEquals(8, G711.unalaw(0xd5))
        assertEquals(-8, G711.unalaw(0x55))
    }

    /** DTMF's frequencies, and a tone's samples */
    @Test
    fun dtmf() {
        assertEquals(697.0 to 1209.0, Tone.dtmf('1'))
        assertEquals(941.0 to 1477.0, Tone.dtmf('#'))
        assertEquals(941.0 to 1633.0, Tone.dtmf('d'))
        assertEquals(null, Tone.dtmf('x'))
        val t = Tone(1000.0, 0.0, 80, 8000.0)
        val xs = IntArray(80) { t.next() }
        assertEquals(0, t.next())
        assertTrue(xs.max() in 7900..8000)
    }

    /** a message's WAV read back; 16 kHz stereo 16 bit and 8 bit μ-law ones as 8 kHz mono */
    @Test
    fun wav() {
        val pcm = ShortArray(800) { (it * 37 % 20000 - 10000).toShort() }
        val w = Wav.encode(pcm)
        assertEquals(44 + 1600, w.size)
        assertContentEquals(pcm, Wav.decode(w))
        assertEquals(0, Wav.seconds(w.size.toLong()))
        // 16 kHz stereo: each frame (L, R) = (1000, 3000) -> 2000, half as many
        val st = Bytes()
        val h = Wav.header(0).copyOf()
        h[22] = 2
        put32(h, 24, 16000)
        put32(h, 40, 1600 * 4)
        st.write(h)
        repeat(1600) { put16(st, 1000); put16(st, 3000) }
        val d = assertNotNull(Wav.decode(st.toByteArray()))
        assertEquals(800, d.size)
        assertTrue(d.all { it.toInt() == 2000 })
        // μ-law, 8 bit
        val ul = Wav.header(0).copyOf()
        ul[20] = 7
        ul[32] = 1
        ul[34] = 8
        put32(ul, 40, 100)
        val b = Bytes()
        b.write(ul)
        repeat(100) { b.write(G711.ulaw(-5000)) }
        val du = assertNotNull(Wav.decode(b.toByteArray()))
        assertEquals(100, du.size)
        assertTrue(du.all { abs(it + 5000) < 200 })
        assertEquals(null, Wav.decode("not a wav file".encodeToByteArray()))
    }

    /** [v] to [s], little-endian, 16 bits */
    private fun put16(s: Sink, v: Int) { s.write(v and 0xff); s.write((v shr 8) and 0xff) }
    /** [v] into [h] at [at], little-endian, 32 bits */
    private fun put32(h: ByteArray, at: Int, v: Int) { for (i in 0..3) h[at + i] = (v shr (8 * i)).toByte() }

    /** a ring keeps the newest, gives the oldest first */
    @Test
    fun ring() {
        val r = SampleRing(4)
        r.put(shortArrayOf(1, 2, 3, 4, 5, 6))
        val o = ShortArray(4)
        assertEquals(4, r.get(o))
        assertContentEquals(shortArrayOf(3, 4, 5, 6), o)
        r.put(shortArrayOf(7, 8, 9))
        r.keep(1)
        assertEquals(9, r.take())
        assertEquals(null, r.take())
        assertEquals("ab", Latin1.string(byteArrayOf(97, 98)))
    }
}
