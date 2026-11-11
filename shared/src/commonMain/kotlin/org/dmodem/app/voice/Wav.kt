// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.voice

import org.dmodem.app.io.Sink

/**
 * WAV files: a voice call's samples written (16 bit PCM, 8000 Hz, mono:
 * the answering machine's messages, a recorded greeting), and any PCM,
 * A-law or μ-law WAV read as those (a greeting chosen from a file: mixed
 * to one channel, resampled to 8000 Hz).
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

    /** a WAV file's samples at 8000 Hz, one channel; null if it isn't a WAV this reads */
    fun decode(data: ByteArray): ShortArray? {
        /** the 16 bits at [at], little-endian */
        fun u16(at: Int) = (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8)
        /** the 32 bits at [at], little-endian */
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        /** whether [s] is at [at] */
        fun tag(at: Int, s: String) = s.indices.all { data[at + it].toInt() == s[it].code }
        if (data.size < 12 || !tag(0, "RIFF") || !tag(8, "WAVE")) return null
        var format = 0
        var channels = 0
        var bits = 0
        var rate = 0L
        var samples = -1
        var bytes = 0
        var haveFmt = false
        var p = 12
        while (data.size - p >= 8) {
            val len = u32(p + 4)
            val body = p + 8
            val left = (data.size - body).toLong()
            if (tag(p, "fmt ") && len >= 16 && left >= 16) {
                format = u16(body)
                channels = u16(body + 2)
                rate = u32(body + 4)
                bits = u16(body + 14)
                // (WAVE_FORMAT_EXTENSIBLE: its subformat's code)
                if (format == 0xfffe && len >= 26 && left >= 26) format = u16(body + 24)
                haveFmt = true
            } else if (tag(p, "data")) {
                samples = body
                bytes = minOf(len, left).toInt()
                break
            }
            if (len > left) break
            p = body + len.toInt() + (len and 1).toInt()
        }
        if (!haveFmt || samples < 0 || channels !in 1..8 || rate !in 1000..192000) return null
        val width = when {
            format == 1 && (bits == 8 || bits == 16) -> bits / 8
            (format == 6 || format == 7) && bits == 8 -> 1
            else -> return null
        }
        val frames = bytes / (width * channels)
        if (frames <= 0) return ShortArray(0)
        val outN = (frames.toDouble() * VOICE_RATE / rate).toLong().toInt()
        /** channel [c] of frame [frame] as a 16-bit sample */
        fun sample(frame: Int, c: Int): Int {
            val q = samples + (frame * channels + c) * width
            return when {
                format == 7 -> G711.unulaw(data[q].toInt() and 0xff)
                format == 6 -> G711.unalaw(data[q].toInt() and 0xff)
                width == 1 -> ((data[q].toInt() and 0xff) - 128) * 256
                else -> u16(q).toShort().toInt()
            }
        }
        return ShortArray(outN) { i ->
            // (linear between the two frames around it, each the mean of its channels)
            val at = i.toDouble() * rate / VOICE_RATE
            val f = at.toInt()
            val frac = at - f
            var y = 0.0
            for (k in 0..1) {
                val ff = minOf(f + k, frames - 1)
                var sum = 0.0
                for (c in 0 until channels) sum += sample(ff, c)
                sum /= channels
                y += if (k == 1) frac * sum else (1 - frac) * sum
            }
            y.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }
}
