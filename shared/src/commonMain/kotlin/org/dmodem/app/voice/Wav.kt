// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.voice

import org.dmodem.app.io.Sink

/**
 * WAV files: a voice call's samples written (16 bit PCM, 8000 Hz, mono:
 * the answering machine's messages, a recorded greeting), and sound
 * files read as those ([SoundFile]: a greeting chosen from a file).
 */
object Wav {
    /** the 44-octet header of a WAV of [n] samples */
    fun header(n: Int): ByteArray {
        val bytes = maxOf(n, 0) * 2
        val h = ByteArray(44)
        /** [s]'s characters at [at] */
        fun tag(at: Int, s: String) = s.forEachIndexed { i, c -> h[at + i] = c.code.toByte() }
        /** [v] at [at], little-endian, 16 bits */
        fun put16(at: Int, v: Int) { h[at] = v.toByte(); h[at + 1] = (v shr 8).toByte() }
        /** [v] at [at], little-endian, 32 bits */
        fun put32(at: Int, v: Int) { put16(at, v and 0xffff); put16(at + 2, (v ushr 16) and 0xffff) }
        tag(0, "RIFF")
        put32(4, 36 + bytes)
        tag(8, "WAVEfmt ")
        put32(16, 16)
        put16(20, 1)
        put16(22, 1)
        put32(24, VOICE_RATE)
        put32(28, VOICE_RATE * 2)
        put16(32, 2)
        put16(34, 16)
        tag(36, "data")
        put32(40, bytes)
        return h
    }

    /** [n] of [pcm] as a WAV file's octets */
    fun encode(pcm: ShortArray, n: Int = pcm.size): ByteArray {
        val out = header(n).copyOf(44 + n * 2)
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            out[44 + 2 * i] = v.toByte()
            out[45 + 2 * i] = (v shr 8).toByte()
        }
        return out
    }

    /** [n] of [pcm] written to [out] as a WAV file */
    fun write(out: Sink, pcm: ShortArray, n: Int = pcm.size) {
        val b = encode(pcm, n)
        out.write(b, 0, b.size)
        out.flush()
    }

    /** a WAV file's length in seconds, from its size (one written here) */
    fun seconds(fileSize: Long): Int = (maxOf(fileSize - 44, 0) / (VOICE_RATE * 2)).toInt()

    /** a sound file's samples at 8000 Hz, one channel; null if it isn't one this reads ([SoundFile]) */
    fun decode(data: ByteArray): ShortArray? = SoundFile.decode(data)
}
