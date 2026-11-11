// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.jobs

import org.dmodem.app.io.IOException
import org.dmodem.app.io.background
import org.dmodem.app.io.monotonicMs
import org.dmodem.app.io.sleepMs
import org.dmodem.app.transfer.Incoming
import org.dmodem.app.transfer.TransferCancelled
import org.dmodem.app.voice.G711
import org.dmodem.app.voice.SampleRing
import org.dmodem.app.voice.Tone
import org.dmodem.app.voice.VOICE_RATE
import org.dmodem.app.voice.Wav
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/**
 * A voice call on the modem (V.253 voice mode), for the Android app and
 * the iOS app alike: a number dialled, or the call ringing answered, by a
 * person or by the answering machine; the far end's samples to the
 * phone's speaker ([read]), its microphone's to the line ([write]).
 *
 * The modem to voice mode with μ-law (AT+FCLASS=8, AT+VSM=4), then ATD
 * or AT+VLS=1, then AT+VTR: a full-duplex stream of octets, a <DLE>
 * doubled, <DLE> and a letter for what the modem heard (l the far end
 * hung up, b busy, d dial tone, digits). Our samples go at the line's
 * pace, by the clock, a little ahead; silence when the microphone has
 * none. <DLE>^ ends the stream (the modem: <DLE><ETX>, OK), then ATH and
 * back to data mode.
 *
 * The answering machine plays the greeting, a beep, and records the
 * caller until they hang up, are quiet for [silenceS] or [maxS] has gone;
 * the message is saved as a WAV file (one under a second isn't).
 */
class VoiceJob(out: DteOut, log: LogOut, changed: Changed) : DteJob(out, log, changed) {
    enum class Phase {
        /** the modem to voice mode */
        STARTING,
        /** the number dialled, waiting for an answer */
        DIALLING,
        /** the ringing call being answered */
        ANSWERING,
        /** a call between people */
        TALKING,
        /** the answering machine's greeting (and beep) to the caller */
        GREETING,
        /** the answering machine taking the caller's message */
        RECORDING,
        /** hanging up */
        ENDING,
    }

    data class State(
        /** we dialled (else the call rang here) */
        val calling: Boolean,
        /** the number dialled, or the caller's (as caller ID had it) */
        val number: String,
        val phase: Phase,
        /** the answering machine has the call (else a person) */
        val machine: Boolean,
        /** null while it goes; "" when it ended well, else why it ended */
        val result: String? = null,
        /** the message's file name (the answering machine took one) */
        val message: String = "",
        /** its length (s) */
        val messageSeconds: Int = 0,
        /** when the call began talking (monotonicMs), 0 not yet */
        val since: Long = 0,
        /** the digits the far end pressed */
        val digits: String = "",
    ) {
        val running: Boolean get() = result == null
    }

    @Volatile var state: State? = null
        private set

    /** our microphone's samples not sent to the line (the far end hears silence) */
    @Volatile var muted = false

    private val rx = SampleRing(VOICE_RATE)
    private val tx = SampleRing(VOICE_RATE)
    private val lock = SynchronizedObject()
    private val keys = StringBuilder()
    @Volatile private var hang = false

    // the answering machine's (the call's thread's alone)
    private var greeting = ShortArray(0)
    private var gpos = 0
    private var beep: Tone? = null
    private var rec = ShortArray(0)
    private var recN = 0
    private var quiet = 0
    private var maxS = MESSAGE_MAX_S
    private var silenceS = MESSAGE_SILENCE_S
    private var into: Incoming? = null
    private var fileName = ""
    @Volatile private var machineDone = false
    private var tone: Tone? = null
    private var toneGap = 0

    /** the state changed by [change], and said so */
    private fun update(change: (State) -> State) {
        state = state?.let(change)
        changed()
    }

    /** [number] dialled for a call between people */
    fun dial(number: String) {
        val n = number.filter { it != '\r' && it != '\n' && it != ';' }.trim()
        start(State(true, n, Phase.STARTING, machine = false)) {
            update { it.copy(phase = Phase.DIALLING) }
            val r = command("D$n", CALL_MS)
            if (r != "OK") throw IOException(dialResult(r, "no answer"))
        }
    }

    /** the call ringing answered by a person; [caller] its number, if known */
    fun answer(caller: String = "") {
        start(State(false, caller, Phase.STARTING, machine = false)) { answerCall() }
    }

    /**
     * the call ringing answered by the answering machine: [greeting] (8000 Hz; none: the beep alone)
     * played, the message saved to [into] as [fileName], a WAV
     */
    fun takeMessage(
        greeting: ShortArray?,
        maxS: Int,
        silenceS: Int,
        into: Incoming,
        fileName: String,
        caller: String = "",
    ) {
        this.greeting = greeting ?: ShortArray(0)
        this.maxS = if (maxS > 0) maxS else MESSAGE_MAX_S
        this.silenceS = if (silenceS > 0) silenceS else MESSAGE_SILENCE_S
        this.into = into
        this.fileName = fileName
        start(State(false, caller, Phase.STARTING, machine = true)) { answerCall() }
    }

    /** the answering machine's call taken by a person (a message being left isn't kept): false if it can't be */
    fun pickUp(): Boolean {
        val s = state ?: return false
        if (!s.running || !s.machine || machineDone) return false
        machineDone = true
        update { it.copy(machine = false, phase = if (it.phase == Phase.GREETING || it.phase == Phase.RECORDING) Phase.TALKING else it.phase) }
        return true
    }

    /** the call hung up */
    fun hangUp() {
        hang = true
    }

    /** the call stopped at once (the modem's gone): no goodbyes */
    fun cancel() = io.cancel("cancelled")

    /** DTMF digit [d] played to the far end: false if it isn't one */
    fun dtmf(d: Char): Boolean {
        if (Tone.dtmf(d) == null || state?.running != true) return false
        synchronized(lock) { if (keys.length < 32) keys.append(d) }
        return true
    }

    /** up to [len] of the far end's samples into [pcm] at [off]: how many (none waiting: 0) */
    fun read(pcm: ShortArray, off: Int = 0, len: Int = pcm.size - off): Int = rx.get(pcm, off, len)

    /** how many of the far end's samples are waiting */
    val readable: Int get() = rx.size

    /** the far end's samples waiting cut to the newest [n] (the speaker fell behind) */
    fun keepReadable(n: Int) = rx.keep(n)

    /** [len] of the microphone's samples from [pcm] at [off], to the line */
    fun write(pcm: ShortArray, off: Int = 0, len: Int = pcm.size - off) {
        if (state?.phase != Phase.TALKING) return
        tx.put(pcm, off, len)
        // (the line takes them at its pace: a microphone ahead of it is cut back)
        if (tx.size > TX_MAX) tx.keep(TX_KEEP)
    }

    // ------------------------------------------------------------------

    /** AT+VLS=1: the ringing call answered */
    private fun answerCall() {
        update { it.copy(phase = Phase.ANSWERING) }
        val r = command("+VLS=1", 30_000)
        if (r != "OK") throw IOException(if (r == null) "the call didn't answer" else "the call didn't answer: ${r.lowercase()}")
    }

    /** AT[cmd]'s final result, or null if none came within [ms] (lines before it passed over) */
    private fun command(cmd: String, ms: Long = 5_000): String? {
        io.write(("AT$cmd\r").encodeToByteArray())
        val end = monotonicMs() + ms
        val line = StringBuilder()
        while (true) {
            if (hang) return "hung up"
            val left = end - monotonicMs()
            if (left <= 0) return null
            val c = io.read(minOf(left, 200))
            if (c < 0) continue
            if (c == '\r'.code || c == '\n'.code) {
                val l = line.toString().trim()
                line.clear()
                if (l in RESULTS) return l
            } else if (line.length < 256) {
                line.append(c.toChar())
            }
        }
    }

    /** the call in the background: voice mode, [connect] (the call made or answered), the stream until it ends, back to data */
    private fun start(first: State, connect: () -> Unit) {
        hang = false
        machineDone = !first.machine
        state = first
        changed()
        background("voice") {
            var result = ""
            var streaming = false
            try {
                if (command("E0V1") != "OK" || command("+FCLASS=8") != "OK" || command("+VSM=4") != "OK") {
                    throw IOException("the modem has no voice mode")
                }
                connect()
                if (hang) throw TransferCancelled("")
                if (command("+VTR", 10_000) != "CONNECT") throw IOException("the call's sound didn't start")
                streaming = true
                update { it.copy(phase = if (it.machine) Phase.GREETING else Phase.TALKING, since = monotonicMs()) }
                stream()
                streaming = false
            } catch (e: TransferCancelled) {
                result = ""
            } catch (e: IOException) {
                // (hung up from here while it was being set up: no fault)
                result = if (hang) "" else e.message ?: e.toString()
            } catch (e: RuntimeException) {
                result = e.toString()
            } finally {
                if (!machineDone) saveMessage(0)
                update { it.copy(phase = Phase.ENDING) }
                // the call over, the modem on hook and back to data
                if (streaming) {
                    raw("\u0010^")
                    sleepMs(300)
                }
                raw("ATH\r")
                sleepMs(500)
                raw("AT+FCLASS=0\r")
                sleepMs(300)
                io.purge()
                leftover = Latin1.string(io.leftover())
                update { it.copy(result = result) }
            }
        }
    }

    /** the stream, until either end hangs up: the far end's samples and codes read, ours sent by the clock */
    private fun stream() {
        rx.clear()
        tx.clear()
        val t0 = monotonicMs()
        var sent = 0L
        var dle = false
        var packet = false
        var ending = false
        var endAt = 0L
        val out = ByteArray(VOICE_RATE * 2)
        while (true) {
            val now = monotonicMs()
            if (!ending && hang) {
                ending = true
                endAt = now + END_MS
                if (!machineDone) saveMessage(0)
                raw("\u0010^")
            }
            if (ending && now > endAt) return
            if (!ending) {
                // our samples: as many as the time says, a little ahead (a second behind: we stalled, the line went on)
                val due = (now - t0) * VOICE_RATE / 1000 + LEAD
                if (due - sent > VOICE_RATE) sent = due - LEAD
                var k = 0
                while (sent < due && k < out.size - 1) {
                    val u = G711.ulaw(nextSample())
                    out[k++] = u.toByte()
                    if (u == DLE) out[k++] = DLE.toByte()
                    sent++
                }
                if (k > 0) io.write(out.copyOf(k))
            }
            // the modem's octets, for up to 10 ms
            val until = monotonicMs() + 10
            while (true) {
                val c = io.read(maxOf(1, until - monotonicMs()))
                if (c < 0) break
                if (dle) {
                    dle = false
                    when (c) {
                        DLE -> if (!packet) sample(c)
                        ETX -> return
                        'X'.code -> packet = true
                        '.'.code -> packet = false
                        else -> if (event(c)) {
                            ending = true
                            endAt = monotonicMs() + END_MS
                            raw("\u0010^")
                        }
                    }
                } else if (c == DLE) {
                    dle = true
                } else if (!packet) {
                    sample(c)
                }
                if (monotonicMs() >= until) break
            }
        }
    }

    /** a sample of the far end's: to the speaker, and the message being recorded */
    private fun sample(u: Int) {
        val x = G711.unulaw(u)
        rx.put(x)
        if (!machineDone && state?.phase == Phase.RECORDING) record(x)
    }

    /** a <DLE> code from the modem: true when the far end has gone (hung up, or the exchange's busy or dial tone) */
    private fun event(c: Int): Boolean {
        val ch = c.toChar()
        if (ch in '0'..'9' || ch == '*' || ch == '#' || ch in 'A'..'D') {
            update { it.copy(digits = (it.digits + ch).takeLast(32)) }
            return false
        }
        if (ch != 'l' && ch != 'b' && ch != 'd') return false
        log("voice: the far end has gone (<DLE>$ch)")
        if (!machineDone) saveMessage(if (ch == 'l') 0 else TONE_TRIM)
        return true
    }

    /** a sample of the caller's message: it's over when long enough, or the caller has been quiet long enough */
    private fun record(x: Int) {
        val max = maxS * VOICE_RATE
        if (recN == rec.size) rec = rec.copyOf(minOf(maxOf(rec.size * 2, VOICE_RATE * 8), max))
        rec[recN++] = x.toShort()
        quiet = if (x > -QUIET_LEVEL && x < QUIET_LEVEL) quiet + 1 else 0
        if (recN >= max || quiet >= silenceS * VOICE_RATE) {
            saveMessage(0)
            hang = true
        }
    }

    /** the message over: [trim] samples of its end cut (a busy tone's), its quiet end but for half a second; saved if a second or more */
    private fun saveMessage(trim: Int) {
        if (machineDone) return
        machineDone = true
        var n = recN - trim
        if (state?.phase == Phase.RECORDING && quiet > QUIET_KEEP) n = minOf(n, recN - (quiet - QUIET_KEEP))
        val dir = into
        if (state?.phase != Phase.RECORDING || n < MESSAGE_MIN || dir == null) {
            log("voice: no message left")
            return
        }
        try {
            val (out, actual) = dir.create(fileName, 44L + n * 2)
            try {
                out.use { Wav.write(it, rec, n) }
                dir.done(actual, 0)
            } catch (e: IOException) {
                dir.abandon(actual)
                throw e
            }
            log("voice: a message of ${n / VOICE_RATE} s saved as $actual")
            update { it.copy(message = actual, messageSeconds = n / VOICE_RATE) }
        } catch (e: IOException) {
            log("voice: the message couldn't be saved: ${e.message}")
        }
    }

    /** our next sample: the answering machine's greeting and beep, or the microphone's; DTMF over it */
    private fun nextSample(): Int {
        var x = 0
        val s = state
        if (s != null && s.machine && !machineDone) {
            if (s.phase == Phase.GREETING) {
                if (gpos < greeting.size) {
                    x = greeting[gpos++].toInt()
                } else {
                    val b = beep ?: Tone(BEEP_HZ, 0.0, BEEP_LEN, BEEP_AMP).also { beep = it }
                    if (b.left > 0) {
                        x = b.next()
                    } else {
                        recN = 0
                        quiet = 0
                        update { it.copy(phase = Phase.RECORDING) }
                    }
                }
            }
        } else if (s?.phase == Phase.TALKING) {
            val m = tx.take()
            if (m != null && !muted) x = m
        }
        val t = tone
        if (t != null && t.left > 0) {
            x += t.next()
        } else if (toneGap > 0) {
            toneGap--
        } else {
            val d = synchronized(lock) { if (keys.isEmpty()) null else keys[0].also { keys.deleteAt(0) } }
            if (d != null) {
                Tone.dtmf(d)?.let { (lo, hi) -> tone = Tone(lo, hi, DTMF_ON, DTMF_AMP) }
                toneGap = DTMF_OFF
            }
        }
        return x.coerceIn(-32768, 32767)
    }

    companion object {
        private const val DLE = 0x10
        private const val ETX = 0x03
        private const val CALL_MS = 120_000L
        /** our samples sent this far ahead of the clock */
        private const val LEAD = VOICE_RATE * 60 / 1000
        private const val TX_MAX = VOICE_RATE / 3
        private const val TX_KEEP = VOICE_RATE / 10
        /** the stream's end awaited this long after <DLE>^ */
        private const val END_MS = 3_000L
        private const val DTMF_ON = VOICE_RATE / 10
        private const val DTMF_OFF = VOICE_RATE * 6 / 100
        private const val DTMF_AMP = 16000.0
        private const val BEEP_HZ = 1000.0
        private const val BEEP_LEN = VOICE_RATE / 2
        private const val BEEP_AMP = 8000.0
        /** quieter than this, a sample is silence */
        private const val QUIET_LEVEL = 400
        const val MESSAGE_MAX_S = 120
        const val MESSAGE_SILENCE_S = 7
        private const val MESSAGE_MIN = VOICE_RATE
        /** a busy or dial tone's start, cut from the message's end */
        private const val TONE_TRIM = 2 * VOICE_RATE
        /** of the message's quiet end, this much kept */
        private const val QUIET_KEEP = VOICE_RATE / 2
        private val RESULTS = setOf("OK", "ERROR", "CONNECT", "BUSY", "NO ANSWER", "NO CARRIER", "NO DIALTONE", "NO DIAL TONE")

        /** a message's file name: its time (yyyyMMdd-HHmmss, given) and the caller's number, if known */
        fun messageName(time: String, caller: String): String {
            val n = caller.filter { it.isDigit() || it == '+' }
            return if (n.isEmpty()) "$time.wav" else "${time}_$n.wav"
        }

        /** a message's caller's number from its file name ("" if none) */
        fun messageCaller(name: String): String = name.removeSuffix(".wav").substringAfter('_', "")
    }
}
