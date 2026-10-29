// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.modem

import com.neodesktop.dmodem.tones.CountryTones
import com.neodesktop.dmodem.tones.Tone
import com.neodesktop.dmodem.tones.ToneSynth

/**
 * The country's call progress tones on the phone's speaker (D-Modem-RE's
 * ITU table, the shared [CountryTones]): the dial tone as an entry is
 * dialled, the ringing tone while the other phone rings, the busy and
 * congestion tones when a call can't be made. What a phone line would
 * have said, where the line itself says nothing ([CallProgress] decides
 * when). [enabled] off: none.
 */
class CallTones(
    /** where the sound goes (an AudioTrack's, or the tests') */
    private val newSink: () -> ModemSpeaker.Sink = { ModemSpeaker.TrackSink() },
    private val level: Double = 0.25,
) : CallProgress.Player {
    enum class Kind { DIAL, RINGING, BUSY, CONGESTION }

    @Volatile var enabled = true
    @Volatile var country: CountryTones = CountryTones.find("")!!

    private val lock = Any()
    private var thread: Thread? = null
    @Volatile private var current: Kind? = null
    @Volatile private var generation = 0

    /** what's playing now, or null */
    val playing: Kind? get() = current

    /** [kind] played for [ms] (0: until stopped), in place of what was */
    override fun play(kind: Kind, ms: Int) {
        if (!enabled) return
        val c = country
        val tone = when (kind) {
            Kind.DIAL -> c.dial
            Kind.RINGING -> c.ringing
            Kind.BUSY -> c.busy
            Kind.CONGESTION -> c.congestion
        }
        start(kind, listOf(tone to ms))
    }

    /** [c]'s tones one after another, to hear them (the settings'): whether or not they're [enabled] */
    fun preview(c: CountryTones) {
        val ring = (c.ringing.period * 1000).toInt().coerceIn(1000, 6000)
        start(null, listOf(c.dial to 1500, c.ringing to ring, c.busy to 2500, c.congestion to 2500))
    }

    /** [parts] (each a tone and how long, 0: until stopped) played in turn, in place of what was */
    private fun start(kind: Kind?, parts: List<Pair<Tone, Int>>) {
        synchronized(lock) {
            stopLocked()
            val gen = ++generation
            current = kind
            thread = Thread({
                val sink = runCatching { newSink() }.getOrNull()
                try {
                    if (sink != null) {
                        sink.volume(1f)
                        val chunk = 50
                        val buf = ByteArray(chunk * 16)
                        for ((tone, ms) in parts) {
                            var at = 0
                            while (generation == gen && (ms <= 0 || at < ms)) {
                                val n = if (ms > 0) minOf(chunk, ms - at) else chunk
                                val pcm = ToneSynth.pcm(tone, n, level = level, fromMs = at, fadeOut = ms > 0 && at + n >= ms)
                                for (i in pcm.indices) {
                                    buf[2 * i] = pcm[i].toByte()
                                    buf[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
                                }
                                sink.write(buf, pcm.size * 2)
                                at += n
                            }
                        }
                        // (its end played out of the player's buffer before it's closed, which drops what's there)
                        if (generation == gen) sink.write(ByteArray(TAIL_MS * 16), TAIL_MS * 16)
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { sink?.close() }
                    synchronized(lock) { if (generation == gen) current = null }
                }
            }, "call-tone").apply { isDaemon = true; start() }
        }
    }

    /** stopped (only if it's [only], when given) */
    override fun stop(only: Kind?) {
        synchronized(lock) {
            if (only != null && current != only) return
            stopLocked()
        }
    }

    /** the playing thread told to end (its generation moved on), nothing current */
    private fun stopLocked() {
        generation++
        current = null
        thread = null
    }

    companion object {
        /** silence after a tone, for the player to play its end */
        const val TAIL_MS = 150
    }

    /** [kind] played for [ms]; back when it's done (the dial tone before ATD) */
    fun playAndWait(kind: Kind, ms: Int) {
        if (!enabled) return
        play(kind, ms)
        val t = synchronized(lock) { thread }
        t?.join(ms + 1000L)
    }
}

/**
 * When the call progress tones play, from what the modem says and does:
 * - BUSY: the busy tone;
 * - a call dialled that ends (NO CARRIER, NO DIALTONE) before the far end
 *   answered: the congestion tone (the network couldn't put it through);
 * - while the other phone rings (the line to another phone: no network
 *   to play a ringing tone): the ringing tone;
 * - a modem answering or connecting: none.
 * A call that rang in first isn't one dialled.
 */
class CallProgress(private val player: Player, private val clock: () -> Long = System::currentTimeMillis) {
    interface Player {
        /** [kind] played for [ms] (0: until stopped), in place of what was */
        fun play(kind: CallTones.Kind, ms: Int)
        /** stopped (only if it's [only], when given) */
        fun stop(only: CallTones.Kind? = null)
    }

    private val line = StringBuilder()
    private var offhook = false
    /** a call dialled, not answered yet */
    private var attempt = false
    private var lastRing = 0L
    private var ringback = false

    /** what the modem said */
    @Synchronized
    fun modemText(s: String) {
        for (c in s) {
            if (c == '\r' || c == '\n') {
                if (line.isNotEmpty()) result(line.toString().trim())
                line.clear()
            } else if (line.length < 80) {
                line.append(c)
            }
        }
    }

    /** the tones for result code [r]: busy, congestion for a dialled call that failed, none once connected */
    private fun result(r: String) {
        when {
            r == "RING" -> lastRing = clock()
            r == "BUSY" -> {
                attempt = false
                player.play(CallTones.Kind.BUSY, TONE_MS)
            }
            r == "NO CARRIER" || r == "NO DIALTONE" || r == "NO DIAL TONE" -> {
                if (attempt) player.play(CallTones.Kind.CONGESTION, TONE_MS)
                attempt = false
            }
            r.startsWith("CONNECT") -> {
                attempt = false
                player.stop()
            }
        }
    }

    /** the modem's state: off hook, and whether a handshake has begun (or it's online) */
    @Synchronized
    fun status(offhook: Boolean, connecting: Boolean) {
        if (offhook && !this.offhook) attempt = clock() - lastRing > RING_GAP_MS
        if (connecting) {
            attempt = false
            player.stop()
        }
        this.offhook = offhook
    }

    /** the other phone ringing (the line to another phone), or not */
    @Synchronized
    fun ringingThere(ringing: Boolean) {
        if (ringing && !ringback) player.play(CallTones.Kind.RINGING, 0)
        if (!ringing && ringback) player.stop(CallTones.Kind.RINGING)
        ringback = ringing
    }

    companion object {
        /** how long the busy and congestion tones play */
        const val TONE_MS = 4000
        /** an off hook this soon after a RING is the call answered */
        const val RING_GAP_MS = 10_000L
    }
}
