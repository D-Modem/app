// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.cell

import com.neodesktop.dmodem.peer.Duplex
import com.neodesktop.dmodem.peer.PeerTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The phone's own mobile calls as the modem's line, for [PeerLine]: a
 * call placed to the number dialled, or one ringing here answered, its
 * sound the modem's (the downlink in, the uplink out: [CallAudio]).
 *
 * To [PeerLine] each call is a connection as another phone's would be:
 * its handshake made here (the call placed is "answered" once up, its
 * ringback and the far modem's answer then coming in its sound; a call
 * ringing here is a RING, answered when the modem answers), then the
 * call's samples each way. Closing it hangs up.
 */
class CellTransport(
    private val phone: CellPhone,
    private val audio: CallAudio,
    private val log: (String) -> Unit = {},
    /** how long a call placed may take to be up (ms) */
    private val dialMs: Long = 30_000,
    /** how long the call's sound may take to come once it is up (ms) */
    private val audioMs: Long = 5_000,
) : PeerTransport {
    override val label = "a mobile call"
    @Volatile private var listening: Thread? = null
    @Volatile private var call: CellDuplex? = null

    /** the call's sound, tried until it comes (it may follow the call's being up a little) */
    private fun openAudio(): CallStreams {
        val end = System.currentTimeMillis() + audioMs
        var last: IOException? = null
        while (System.currentTimeMillis() < end) {
            try {
                return audio.open()
            } catch (e: IOException) {
                last = e
                Thread.sleep(200)
            }
        }
        throw last ?: IOException("no sound from the call")
    }

    private fun waitFor(s: CellPhone.State, ms: Long): Boolean =
        runBlocking { withTimeoutOrNull(ms) { phone.state.first { it == s } } } != null

    override fun connect(address: String): Duplex {
        audio.unavailable()?.let { throw IOException(it) }
        val number = address.filter { it.isDigit() || it in "+*#" }
        if (number.isEmpty()) throw IOException("no number to call")
        if (phone.state.value != CellPhone.State.IDLE) throw IOException("the phone is in a call")
        log("calling $number")
        phone.dial(number)
        if (!waitFor(CellPhone.State.OFFHOOK, dialMs)) {
            phone.hangUp()
            throw IOException("the call wasn't placed")
        }
        val streams = try {
            openAudio()
        } catch (e: IOException) {
            phone.hangUp()
            throw e
        }
        phone.muteMic(true)
        // (up: "answered" for the line; the far end's ringing and answer come in its sound)
        return CellDuplex(number, "ANSWER\n".toByteArray(), streams).also { call = it; it.watchEnd() }
    }

    override fun listen(accept: (Duplex) -> Unit) {
        audio.unavailable()?.let { throw IOException(it) }
        listening = Thread({
            try {
                while (true) {
                    runBlocking { phone.state.first { it == CellPhone.State.RINGING } }
                    if (call == null) {
                        val d = CellDuplex("a mobile call", "DMODEM/1 RING\n".toByteArray(), null)
                        call = d
                        d.watchEnd()
                        Thread({ accept(d) }, "cell-in").apply { isDaemon = true; start() }
                    }
                    runBlocking { phone.state.first { it != CellPhone.State.RINGING } }
                }
            } catch (_: InterruptedException) {
            }
        }, "cell-listen").apply { isDaemon = true; start() }
    }

    override fun close() {
        listening?.interrupt()
        listening = null
        call?.close()
    }

    /**
     * A call: [hello] first (the handshake's word from the "far end"),
     * then the call's sound once [streams] are there (a call answered
     * here: once the modem's ANSWER has the call taken; what the modem
     * says before then waits).
     */
    private inner class CellDuplex(
        override val name: String,
        private val hello: ByteArray,
        @Volatile private var streams: CallStreams?,
    ) : Duplex {
        private val lock = Object()
        @Volatile private var closed = false
        private var helloAt = 0
        private val pending = ByteArrayOutputStream()
        private var control = StringBuilder()
        private var controlDone = false

        /** the call over (hung up there, or the network's): the line ends */
        fun watchEnd() = Thread({
            // (up, or ringing, first; then back to idle)
            runBlocking { phone.state.first { it != CellPhone.State.IDLE } }
            runBlocking { phone.state.first { it == CellPhone.State.IDLE } }
            if (!closed) log("$name: the call ended")
            close()
        }, "cell-end").apply { isDaemon = true; start() }

        override val input: InputStream = object : InputStream() {
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (helloAt < hello.size) {
                    val n = minOf(len, hello.size - helloAt)
                    System.arraycopy(hello, helloAt, b, off, n)
                    helloAt += n
                    return n
                }
                val s = synchronized(lock) {
                    while (streams == null && !closed) lock.wait()
                    if (closed) return -1
                    streams!!
                }
                return try { s.input.read(b, off, len) } catch (_: IOException) { -1 }
            }
        }

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                var at = off
                // the handshake's line (RING from a caller: nothing to do; ANSWER: the call taken)
                while (!controlDone && at < off + len) {
                    val c = b[at++].toInt().toChar()
                    if (c == '\n') {
                        controlDone = true
                        if (control.toString().trim() == "ANSWER") takeCall()
                    } else if (control.length < 300) {
                        control.append(c)
                    }
                }
                if (at >= off + len) return
                val s = synchronized(lock) {
                    if (closed) throw IOException("the call is over")
                    streams ?: run {
                        // (until the call is taken: kept, never waited for)
                        if (pending.size() < 64 * 1024) pending.write(b, at, off + len - at)
                        return
                    }
                }
                s.output.write(b, at, off + len - at)
            }
        }

        /** the ringing call answered (a thread of its own: the modem's thread mustn't wait), its sound then */
        private fun takeCall() = Thread({
            log("answering the call")
            phone.answer()
            try {
                if (!waitFor(CellPhone.State.OFFHOOK, 10_000)) throw IOException("the call wasn't answered")
                val s = openAudio()
                phone.muteMic(true)
                synchronized(lock) {
                    if (closed) { s.close(); return@Thread }
                    s.output.write(pending.toByteArray())
                    pending.reset()
                    streams = s
                    lock.notifyAll()
                }
            } catch (e: IOException) {
                log("$name: ${e.message}")
                close()
            }
        }, "cell-answer").apply { isDaemon = true; start() }

        override fun close() {
            val s = synchronized(lock) {
                if (closed) return
                closed = true
                lock.notifyAll()
                streams
            }
            runCatching { s?.close() }
            phone.muteMic(false)
            if (phone.state.value != CellPhone.State.IDLE) phone.hangUp()
            if (call === this) call = null
        }
    }
}
