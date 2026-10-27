// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.cell

import com.neodesktop.dmodem.TestLogs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingDeque

/**
 * A mobile network for the tests: phones that call each other, the
 * call's sound each way on the phones' audio clock (20 ms frames, as
 * Android's AudioRecord has them: silence when nothing came in time)
 * and, with [codec], through a speech codec (native-test's codec_line
 * --filter: GSM, AMR-NB).
 */
class FakeNetwork(private val codec: String? = null, private val closers: MutableList<() -> Unit>) {
    /** a call's sound one way: written as it comes, read 20 ms at a time on the clock */
    inner class Wire(name: String) {
        private val frames = LinkedBlockingDeque<ByteArray>()
        private var part = ByteArray(0)
        private val filter: Process? = codec?.let { c ->
            val bin = codecLine() ?: throw AssertionError("codec_line isn't built")
            ProcessBuilder(bin.path, "--filter", c).start()
                .also { p -> closers.add { p.destroyForcibly() }; TestLogs.echo("codec $name", p.errorStream) }
        }
        @Volatile var open = true

        init {
            filter?.let { p ->
                Thread({
                    val buf = ByteArray(FRAME_BYTES)
                    try {
                        while (true) {
                            var have = 0
                            while (have < FRAME_BYTES) {
                                val n = p.inputStream.read(buf, have, FRAME_BYTES - have)
                                if (n < 0) return@Thread
                                have += n
                            }
                            frames.add(buf.copyOf())
                        }
                    } catch (_: IOException) {
                    }
                }, "codec-out-$name").apply { isDaemon = true; start() }
            }
        }

        /** the sender's octets, in whole frames (through the codec) */
        @Synchronized
        fun write(b: ByteArray, off: Int, len: Int) {
            var all = part + b.copyOfRange(off, off + len)
            while (all.size >= FRAME_BYTES) {
                val f = all.copyOfRange(0, FRAME_BYTES)
                all = all.copyOfRange(FRAME_BYTES, all.size)
                val p = filter
                if (p != null) {
                    p.outputStream.write(f)
                    p.outputStream.flush()
                } else {
                    frames.add(f)
                }
            }
            part = all
        }

        /** a jitter buffer's: three frames waiting before it plays, again after it ran dry */
        private var primed = false

        /** a frame for the receiver: what came (behind the jitter buffer), else silence */
        @Synchronized
        fun frame(): ByteArray {
            if (!primed && frames.size < 3) return ByteArray(FRAME_BYTES)
            primed = true
            return frames.poll() ?: ByteArray(FRAME_BYTES).also { primed = false }
        }

        /** the frames waiting dropped, the jitter buffer to prime again */
        @Synchronized
        fun clear() {
            frames.clear()
            primed = false
        }
    }

    /** a phone on the network */
    inner class Phone(val number: String) : CellPhone {
        override val state = MutableStateFlow(CellPhone.State.IDLE)
        var other: Phone? = null
        /** the call's sound to this phone */
        var incoming = Wire("to-$number")
        val dialled = ArrayList<String>()
        var answered = 0
        var hungUp = 0
        /** the call up (both ends off hook) */
        @Volatile var connected = false

        /** rings [number] if it's there and idle; else the call ends half a second later */
        override fun dial(number: String) {
            dialled.add(number)
            val to = phones[number]
            state.value = CellPhone.State.OFFHOOK
            if (to == null || to.state.value != CellPhone.State.IDLE) {
                // (nobody there, or busy: the network ends the call a little later)
                Thread { Thread.sleep(500); state.value = CellPhone.State.IDLE }.start()
                return
            }
            other = to
            to.other = this
            to.state.value = CellPhone.State.RINGING
        }

        /** a ringing call taken: both ends connected */
        override fun answer() {
            answered++
            val o = other ?: return
            if (state.value != CellPhone.State.RINGING) return
            state.value = CellPhone.State.OFFHOOK
            connected = true
            o.connected = true
        }

        /** both ends idle again, their call and its sound gone */
        override fun hangUp() {
            hungUp++
            val o = other
            for (p in listOfNotNull(this, o)) {
                p.state.value = CellPhone.State.IDLE
                p.connected = false
                p.other = null
                p.incoming.clear()
            }
        }

        /** the phone's call sound (AudioRecord/AudioTrack on its clock) */
        val audio = object : CallAudio {
            /** always available */
            override fun unavailable(): String? = null
            /** the call's sound, a frame each 20 ms each way; only while off hook */
            override fun open(): CallStreams {
                if (state.value != CellPhone.State.OFFHOOK) throw IOException("no call")
                val me = this@Phone
                var next = System.nanoTime()
                return object : CallStreams {
                    @Volatile var open = true
                    override val input = object : InputStream() {
                        private var frame = ByteArray(0)
                        private var at = 0
                        /** (only whole buffers read) */
                        override fun read(): Int = throw UnsupportedOperationException()
                        /** the call's sound for this phone, up to [len] bytes, a frame each 20 ms on the clock: silence while the call isn't up; -1 once closed */
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (!open) return -1
                            if (at >= frame.size) {
                                // (the clock: a frame every 20 ms)
                                next += 20_000_000L
                                val wait = next - System.nanoTime()
                                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                                if (!open) return -1
                                frame = if (me.connected) me.incoming.frame() else ByteArray(FRAME_BYTES)
                                at = 0
                            }
                            val n = minOf(len, frame.size - at)
                            System.arraycopy(frame, at, b, off, n)
                            at += n
                            return n
                        }
                    }
                    override val output = object : OutputStream() {
                        /** (only whole buffers written) */
                        override fun write(b: Int) = throw UnsupportedOperationException()
                        /** this phone's sound to the other's, while the call's up; nowhere otherwise */
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            if (!open) throw IOException("closed")
                            // (before the call's up, and after: nowhere)
                            val o = me.other
                            if (me.connected && o != null) o.incoming.write(b, off, len)
                        }
                    }
                    /** both ways ended */
                    override fun close() { open = false }
                }
            }
        }
    }

    val phones = HashMap<String, Phone>()

    /** a new phone on the network with [number] */
    fun phone(number: String) = Phone(number).also { phones[number] = it }

    companion object {
        const val FRAME_BYTES = 320

        /** native-test's codec_line, if built, or null */
        fun codecLine(): File? = listOfNotNull(
            System.getenv("DMODEM_CODEC_LINE"),
            "../build-host/codec_line",
            "build-host/codec_line",
        ).map { File(it) }.firstOrNull { it.canExecute() }
    }
}
