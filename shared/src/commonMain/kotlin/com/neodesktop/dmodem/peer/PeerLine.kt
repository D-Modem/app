// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.peer

import com.neodesktop.dmodem.io.*
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** a byte stream each way, closed from any thread (a read blocked on it ends) */
interface Duplex : AutoCloseable {
    val input: Source
    val output: Sink
    /** who is at the other end, to show */
    val name: String
}

/** how phones reach each other: Wi-Fi (TCP) or Bluetooth (RFCOMM) */
interface PeerTransport : AutoCloseable {
    /** what it is, to show ("Wi-Fi", "Bluetooth") */
    val label: String
    /** this phone offered for calls: each connection to [accept], on a thread of its own; throws if it can't */
    fun listen(accept: (Duplex) -> Unit)
    /** a connection to the phone at [address] (the transport's form); throws if there's none */
    fun connect(address: String): Duplex
}

/**
 * The modem's line to another phone running the app: as a SIP call is
 * for the modem, but over a connection of our own.
 *
 * Off hook, the native modem hands us its line (a socket of 16 bit
 * samples, [lineStarted]); we tell it what the line does with
 * [events] (RING, CANCEL, ANSWERED, FAILED <SIP status>), as SIP's user
 * agent does.
 *
 * On the connection: the caller says `DMODEM/1 RING <dial string>`, the
 * phone called answers `BUSY` (and closes) or, once its modem answers,
 * `ANSWER`; from then on both carry the modems' samples as they are,
 * each way. Hanging up is closing it. The samples' pace is the modems':
 * each answers every sample with one of its own (the native side).
 */
class PeerLine(
    private val transport: PeerTransport,
    private val events: (String) -> Unit,
    /** the address to call for a dial string (null: nobody to call) */
    private val target: (String) -> String?,
    private val log: (String) -> Unit = {},
    private val ringMs: Long = 3000,
    /** where the state and the last failure go (the app's, kept across lines) */
    private val _state: MutableStateFlow<State> = MutableStateFlow(State.Off),
    private val _lastError: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    sealed interface State {
        data object Off : State
        /** waiting for calls (or, not listening, for one to make) */
        data class Ready(val listening: Boolean) : State
        data class Ringing(val from: String) : State
        data class Calling(val to: String) : State
        data class Connected(val with: String, val caller: Boolean) : State
    }

    val state: StateFlow<State> = _state.asStateFlow()

    /** what went wrong last (a call that failed), for the user */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val lock = SynchronizedObject()
    private var listening = false
    @kotlin.concurrent.Volatile private var running = false
    /** the call ringing here, not answered yet */
    private var pending: Call? = null
    /** the call on the line (ours dialled, or answered) */
    private var call: Call? = null

    fun start(listen: Boolean) {
        running = true
        listening = false
        if (listen) {
            transport.listen { conn -> incoming(conn) }
            listening = true
        }
        _state.value = State.Ready(listening)
    }

    fun stop() {
        running = false
        val calls = synchronized(lock) { listOfNotNull(pending, call) }
        calls.forEach { it.end("the modem stopped") }
        runCatching { transport.close() }
        _state.value = State.Off
    }

    /** a call's connection, the modem's line once off hook */
    private inner class Call(val caller: Boolean, @kotlin.concurrent.Volatile var conn: Duplex?) {
        @kotlin.concurrent.Volatile var line: Duplex? = null
        @kotlin.concurrent.Volatile var answered = false
        val ended = atomic(false)
        /** (the call's own lock: what goes to the connection) */
        private val callLock = SynchronizedObject()
        val who: String get() = conn?.name ?: "?"

        /** the octets the modem's line has given, and those of them before [skip] not for the other phone */
        @kotlin.concurrent.Volatile var outCount = 0L
        private var skip = 0L
        /** what the line gave before the answer (from octet [earlyAt] on, the newest kept) */
        private val early = Bytes()
        private var earlyAt = 0L

        /** the tones we play the modem before the answer (a caller's): octets so far, stopped */
        private val feedLock = SynchronizedObject()
        @kotlin.concurrent.Volatile var fed = 0L
        private var feeding = true
        /** the call placed (the dial tone over), the far phone ringing */
        val dialled = Latch()
        @kotlin.concurrent.Volatile var ringing = false

        fun end(why: String) {
            if (!ended.compareAndSet(false, true)) return
            log("call ${if (caller) "to" else "from"} $who over: $why")
            runCatching { conn?.close() }
            runCatching { line?.close() }
            dialled.countDown()
            val cancelled = synchronized(lock) {
                val ringing = pending === this
                if (ringing) pending = null
                if (call === this) call = null
                ringing
            }
            // (it rang here, not answered: the modem's RING count starts again)
            if (cancelled) events("CANCEL")
            if (running) _state.value = State.Ready(listening)
        }

        /** the line's samples to the connection (before the answer: kept) until either end goes */
        fun lineToConn() = background("peer") {
            val buf = ByteArray(4096)
            try {
                val input = line!!.input
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(callLock) {
                        val at = outCount
                        outCount += n
                        if (answered) {
                            val from = (skip - at).coerceIn(0, n.toLong()).toInt()
                            if (from < n) conn!!.output.write(buf, from, n - from)
                        } else {
                            early.write(buf, 0, n)
                            // (only the newest matter: those after the tones' answers)
                            if (early.size > EARLY_MAX) {
                                val keep = early.toByteArray().copyOfRange(early.size - EARLY_MAX / 2, early.size)
                                earlyAt += early.size - keep.size
                                early.reset()
                                early.write(keep)
                            }
                        }
                    }
                }
                end("hung up")
            } catch (e: IOException) {
                end("hung up (${e.message})")
            }
        }

        /** the connection's samples to the line until either end goes (on this thread) */
        fun connToLine() {
            val buf = ByteArray(4096)
            try {
                val input = conn!!.input
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val l = line
                    // (a caller's samples before we answered: there are none)
                    if (answered && l != null) l.output.write(buf, 0, n)
                }
                end(if (answered) "the other phone hung up" else "the caller gave up")
            } catch (e: IOException) {
                end("the connection ended (${e.message})")
            }
        }

        /**
         * Answered: the tones stopped, the modem's answers to them
         * dropped (as many octets as it had of them: what goes on is
         * its lead silence's worth, the samples on their way), the rest
         * to the other phone, then everything as it comes.
         */
        fun answer() {
            val tones = synchronized(feedLock) { feeding = false; fed }
            synchronized(callLock) {
                skip = tones
                val e = early.toByteArray()
                val from = (skip - earlyAt).coerceIn(0, e.size.toLong()).toInt()
                conn!!.output.write(e, from, e.size - from)
                early.reset()
                answered = true
            }
        }

        /**
         * The exchange's tones to the dialling modem, at the line's pace
         * (a little ahead of what it has answered): a dial tone, quiet
         * while it "dials" ([dialled] then), ringback once the far phone
         * rings; until [answer] or the end.
         */
        fun feedTones() = background("peer") {
            val chunk = ShortArray(FRAME)
            val bytes = ByteArray(FRAME * 2)
            var n = 0L     // samples played
            try {
                while (!ended.value) {
                    // (no further ahead of the modem than its lead and two frames)
                    if (fed - outCount >= 4L * FRAME) {
                        sleepMs(5)
                        continue
                    }
                    for (i in 0 until FRAME) chunk[i] = tone(n + i)
                    for (i in 0 until FRAME) {
                        bytes[2 * i] = chunk[i].toInt().toByte()
                        bytes[2 * i + 1] = (chunk[i].toInt() shr 8).toByte()
                    }
                    synchronized(feedLock) {
                        if (!feeding) return@background
                        line!!.output.write(bytes)
                        fed += bytes.size
                    }
                    n += FRAME
                    if (n >= DIALLED_AT) dialled.countDown()
                }
            } catch (_: IOException) {
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }

        /** the tone at sample [n]: dial tone (350 + 440 Hz), quiet, ringback (440 + 480 Hz, 2 s in 6) once ringing */
        private var ringFrom = -1L
        private fun tone(n: Long): Short {
            val t = n.toDouble() / RATE
            val a = when {
                n < DIAL_TONE -> sin2(350.0, 440.0, t)
                !ringing -> 0.0
                else -> {
                    if (ringFrom < 0) ringFrom = n
                    if ((n - ringFrom) % (6 * RATE) < 2 * RATE) sin2(440.0, 480.0, t) else 0.0
                }
            }
            return a.toInt().toShort()
        }

        private fun sin2(f1: Double, f2: Double, t: Double) =
            TONE * (kotlin.math.sin(2 * kotlin.math.PI * f1 * t) + kotlin.math.sin(2 * kotlin.math.PI * f2 * t))
    }

    /** a call coming in: rung until the modem answers or the caller goes; busy if a call is on */
    private fun incoming(conn: Duplex) {
        val hello = try { readLine(conn.input) } catch (_: IOException) { null }
        if (hello == null || !hello.startsWith("DMODEM/1 RING")) {
            log("${conn.name}: not a call (\"${hello ?: ""}\")")
            runCatching { conn.close() }
            return
        }
        val c = Call(caller = false, conn = conn)
        val busy = synchronized(lock) {
            if (!running || pending != null || call != null) true else { pending = c; false }
        }
        if (busy) {
            log("call from ${conn.name}: busy")
            runCatching { conn.output.write("BUSY\n".encodeToByteArray()); conn.output.flush() }
            runCatching { conn.close() }
            return
        }
        log("call from ${conn.name}: ringing")
        _state.value = State.Ringing(conn.name)
        background("peer") {
            while (!c.ended.value && !c.answered && synchronized(lock) { pending === c }) {
                events("RING")
                sleepMs(ringMs)
            }
        }
        c.connToLine()
    }

    /**
     * The modem off hook: [caller] dialling [dial] (the call made), or
     * answering the call that rings; [line] its samples (ours to close).
     */
    fun lineStarted(caller: Boolean, dial: String, line: Duplex) {
        if (!caller) {
            val c = synchronized(lock) { pending?.also { pending = null; call = it } }
            if (c == null) {
                log("the modem answered: no call rings")
                runCatching { line.close() }
                return
            }
            c.line = line
            try {
                c.conn!!.output.write("ANSWER\n".encodeToByteArray())
                c.answer()
            } catch (e: IOException) {
                c.end("the caller has gone (${e.message})")
                return
            }
            log("answered ${c.who}")
            _state.value = State.Connected(c.who, caller = false)
            c.lineToConn()
            return
        }
        val c = Call(caller = true, conn = null)
        c.line = line
        _state.value = State.Calling(dial)
        val ringing = synchronized(lock) {
            val p = pending
            pending = null
            call?.let { other -> background("peer") { other.end("another call") } }
            call = c
            p
        }
        // (a call ringing here as we dial out: it gets no answer)
        ringing?.end("we dialled out")
        c.lineToConn()
        background("peer") { dialOut(c, dial) }
    }

    private fun dialOut(c: Call, dial: String) {
        // the dial tone and the dialling first, as an exchange has them
        c.feedTones()
        c.dialled.await()
        if (c.ended.value) return
        val to = target(dial)
        if (to == null) {
            fail(c, 404, "no phone to call: choose one in the settings")
            return
        }
        _state.value = State.Calling(to)
        log("calling $to over ${transport.label}")
        val conn = try {
            transport.connect(to)
        } catch (e: Exception) {
            fail(c, 480, "can't reach $to: ${e.message ?: e}")
            return
        }
        c.conn = conn
        if (c.ended.value) {
            // (the modem gave up while we connected)
            runCatching { conn.close() }
            return
        }
        val reply = try {
            conn.output.write("DMODEM/1 RING ${dial.filter { it in ' '..'~' }.take(100)}\n".encodeToByteArray())
            conn.output.flush()
            c.ringing = true
            readLine(conn.input)
        } catch (_: IOException) {
            null
        }
        when (reply) {
            "ANSWER" -> {
                try {
                    c.answer()
                } catch (e: IOException) {
                    c.end("the connection ended (${e.message})")
                    return
                }
                events("ANSWERED")
                log("${conn.name} answered")
                _state.value = State.Connected(conn.name, caller = true)
                c.connToLine()
            }
            "BUSY" -> fail(c, 486, "${conn.name} is busy")
            else -> if (!c.ended.value) fail(c, 503, "${conn.name} didn't answer the call")
        }
    }

    /** the dial failed with [code] (as SIP's), the line closed */
    private fun fail(c: Call, code: Int, why: String) {
        _lastError.value = why
        log(why)
        events("FAILED $code")
        c.end(why)
    }

    companion object {
        private const val RATE = 8000
        /** the tones' frames: 20 ms */
        private const val FRAME = RATE / 50
        /** the dial tone (1.5 s: the modem waits for 1 s of it), then quiet; the call placed at 2.2 s
            (the modem's call progress waits 2 s before it dials, with or without a dial tone) */
        private const val DIAL_TONE = RATE * 3 / 2L
        private const val DIALLED_AT = RATE * 22 / 10L
        /** each tone's peak: -13 dBm0 or so */
        private const val TONE = 5000.0
        /** the line's octets before an answer kept at most */
        private const val EARLY_MAX = 256 * 1024

        /** a line of up to [max] octets, without its newline; null at the end */
        fun readLine(input: Source, max: Int = 300): String? {
            val sb = StringBuilder()
            while (sb.length < max) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
            return sb.toString()
        }

        /**
         * Where a dial string calls on Wi-Fi: an address in it, as the
         * digits a modem dials take it (192*168*1*20, a port after #),
         * else [fallback] (the phone chosen in the settings).
         */
        fun wifiTarget(dial: String, fallback: String): String? {
            val d = dial.uppercase().filter { it.isDigit() || it == '*' || it == '.' || it == '#' }
            Regex("""^(\d{1,3})[*.](\d{1,3})[*.](\d{1,3})[*.](\d{1,3})(?:#(\d{1,5}))?$""").find(d)?.let { m ->
                val g = m.groupValues
                val ip = (1..4).map { g[it].toInt() }
                if (ip.all { it in 0..255 }) {
                    return ip.joinToString(".") + (g[5].takeIf { it.isNotEmpty() }?.let { ":$it" } ?: "")
                }
            }
            return fallback.trim().ifEmpty { null }
        }
    }
}
