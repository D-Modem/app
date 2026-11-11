// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.voice

import kotlin.math.pow

/**
 * Sound files read as a voice call's samples (8000 Hz, one channel: the
 * channels mixed, the rate converted), for a greeting chosen from a file.
 * Read here, the same as the desktop apps' library: WAV (PCM of 8 to 32
 * bits, 32 and 64-bit floats, A-law, μ-law, IMA and Microsoft ADPCM),
 * AIFF and AIFF-C (PCM, floats, A-law, μ-law) and Sun/NeXT .au. The rest
 * (MP3, AAC, FLAC, Ogg) the phone's own decoders read, and [mix] brings
 * what they give to 8000 Hz.
 */
object SoundFile {
    private const val PCM = 0
    private const val FLOAT = 1
    private const val ALAW = 2
    private const val ULAW = 3
    private const val IMA = 4
    private const val MSADPCM = 5

    /** what a file holds, and where */
    private class Sound {
        var enc = -1
        var bits = 0
        /** 0: little-endian, 1: big-endian, 2: big-endian with 8-bit samples signed */
        var big = 0
        var channels = 0
        var block = 0
        var rate = 0.0
        var at = -1
        var bytes = 0
        /** MS ADPCM's coefficient pairs */
        var coef: Array<IntArray> = emptyArray()
    }

    /** [data]'s samples at 8000 Hz, one channel; null if it isn't a file this reads */
    fun decode(data: ByteArray): ShortArray? {
        if (data.size < 12) return null
        val f = Sound()
        val ok = when {
            tag(data, 0, "RIFF") && tag(data, 8, "WAVE") -> parseWav(data, f)
            tag(data, 0, "FORM") && (tag(data, 8, "AIFF") || tag(data, 8, "AIFC")) -> parseAiff(data, f)
            tag(data, 0, ".snd") -> parseAu(data, f)
            else -> false
        }
        if (!ok || f.channels !in 1..8 || !(f.rate >= 1000) || f.rate > 384000) return null
        val cap = mostFrames(f)
        if (cap < 0 || cap > 100_000_000L) return null
        val mono = ShortArray(cap.toInt())
        val frames = decodeMono(data, f, mono)
        if (frames < 0) return null
        return resample(mono, frames, f.rate)
    }

    /**
     * [n] interleaved frames of [pcm] ([channels] of them each, at [rate] Hz),
     * as a voice call's samples: mixed to one channel, at 8000 Hz
     */
    fun mix(pcm: ShortArray, n: Int, channels: Int, rate: Int): ShortArray {
        if (channels < 1 || rate <= 0) return ShortArray(0)
        val frames = minOf(n, pcm.size / channels)
        val mono = ShortArray(frames) { i ->
            var sum = 0
            for (c in 0 until channels) sum += pcm[i * channels + c]
            (sum / channels).toShort()
        }
        return resample(mono, frames, rate.toDouble())
    }

    /** [frames] of [mono] at [rate] Hz, at 8000 Hz (linear between the two around each) */
    private fun resample(mono: ShortArray, frames: Int, rate: Double): ShortArray {
        if (frames <= 0) return ShortArray(0)
        val outN = (frames.toDouble() * VOICE_RATE / rate).toLong().toInt()
        return ShortArray(outN) { i ->
            val at = i.toDouble() * rate / VOICE_RATE
            val k = at.toInt()
            val frac = at - k
            val k1 = if (k + 1 < frames) k + 1 else frames - 1
            val y = (1 - frac) * mono[k] + frac * mono[k1]
            y.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    /** whether [s] is at [at] in [d] */
    private fun tag(d: ByteArray, at: Int, s: String) =
        at + s.length <= d.size && s.indices.all { d[at + it].toInt() == s[it].code }

    /** the octet at [at] */
    private fun u8(d: ByteArray, at: Int) = d[at].toInt() and 0xff

    /** the 16 bits at [at], little-endian */
    private fun u16(d: ByteArray, at: Int) = u8(d, at) or (u8(d, at + 1) shl 8)

    /** the 32 bits at [at], little-endian */
    private fun u32(d: ByteArray, at: Int): Long = u16(d, at).toLong() or (u16(d, at + 2).toLong() shl 16)

    /** the 32 bits at [at], big-endian */
    private fun b32(d: ByteArray, at: Int): Long =
        (u8(d, at).toLong() shl 24) or (u8(d, at + 1).toLong() shl 16) or (u8(d, at + 2).toLong() shl 8) or u8(d, at + 3).toLong()

    /** an 80-bit IEEE extended (AIFF's rate) */
    private fun extended(d: ByteArray, at: Int): Double {
        val e = (((u8(d, at) and 0x7f) shl 8) or u8(d, at + 1)) - 16383 - 63
        val m = b32(d, at + 2).toDouble() * 4294967296.0 + b32(d, at + 6).toDouble()
        val v = m * 2.0.pow(e)
        return if (u8(d, at) and 0x80 != 0) -v else v
    }

    /** a RIFF WAVE's format and sound into [f]: false if this doesn't read it */
    private fun parseWav(d: ByteArray, f: Sound): Boolean {
        var p = 12
        var fmt = false
        var code = 0
        while (d.size - p >= 8) {
            val len = u32(d, p + 4)
            val body = p + 8
            val left = (d.size - body).toLong()
            if (tag(d, p, "fmt ") && len >= 16 && left >= 16) {
                code = u16(d, body)
                f.channels = u16(d, body + 2)
                f.rate = u32(d, body + 4).toDouble()
                f.block = u16(d, body + 12)
                f.bits = u16(d, body + 14)
                // (WAVE_FORMAT_EXTENSIBLE: its subformat's code)
                if (code == 0xfffe && len >= 26 && left >= 26) code = u16(d, body + 24)
                if (code == 2 && len >= 22 && left >= 22) {
                    val k = u16(d, body + 20)
                    if (k > 64 || 22L + 4 * k > left) return false
                    f.coef = Array(k) { i ->
                        intArrayOf(u16(d, body + 22 + 4 * i).toShort().toInt(), u16(d, body + 24 + 4 * i).toShort().toInt())
                    }
                }
                fmt = true
            } else if (tag(d, p, "data")) {
                f.at = body
                f.bytes = minOf(len, left).toInt()
                break
            }
            if (len > left) break
            p = body + len.toInt() + (len and 1).toInt()
        }
        if (!fmt || f.at < 0) return false
        f.enc = when (code) {
            1 -> PCM
            3 -> FLOAT
            6 -> ALAW
            7 -> ULAW
            0x11 -> IMA
            2 -> MSADPCM
            else -> return false
        }
        return true
    }

    /** an AIFF or AIFF-C's format and sound into [f]: false if this doesn't read it */
    private fun parseAiff(d: ByteArray, f: Sound): Boolean {
        val aifc = tag(d, 8, "AIFC")
        var p = 12
        var comm = false
        var comp = "NONE"
        while (d.size - p >= 8) {
            val len = b32(d, p + 4)
            val body = p + 8
            val left = (d.size - body).toLong()
            if (tag(d, p, "COMM") && len >= 18 && left >= 18) {
                f.channels = (u8(d, body) shl 8) or u8(d, body + 1)
                f.bits = (u8(d, body + 6) shl 8) or u8(d, body + 7)
                f.rate = extended(d, body + 8)
                if (aifc && len >= 22 && left >= 22) comp = (0 until 4).map { u8(d, body + 18 + it).toChar() }.joinToString("")
                comm = true
            } else if (tag(d, p, "SSND") && len >= 8 && left >= 8) {
                val off = b32(d, body)
                val l = minOf(len, left)
                if (off > l - 8) return false
                f.at = body + 8 + off.toInt()
                f.bytes = (l - 8 - off).toInt()
            }
            if (len > left) break
            p = body + len.toInt() + (len and 1).toInt()
        }
        if (!comm || f.at < 0) return false
        f.big = 1
        when (comp) {
            "NONE", "twos" -> f.enc = PCM
            "sowt" -> { f.enc = PCM; f.big = 0 }
            "fl32", "FL32" -> { f.enc = FLOAT; f.bits = 32 }
            "fl64", "FL64" -> { f.enc = FLOAT; f.bits = 64 }
            "ulaw", "ULAW" -> { f.enc = ULAW; f.bits = 8 }
            "alaw", "ALAW" -> { f.enc = ALAW; f.bits = 8 }
            else -> return false
        }
        // (AIFF's 8-bit samples are signed)
        if (f.enc == PCM && f.bits == 8) f.big = 2
        return true
    }

    /** a Sun/NeXT .au's format and sound into [f]: false if this doesn't read it */
    private fun parseAu(d: ByteArray, f: Sound): Boolean {
        if (d.size < 24) return false
        val off = b32(d, 4)
        val len = b32(d, 8)
        val enc = b32(d, 12)
        f.rate = b32(d, 16).toDouble()
        f.channels = b32(d, 20).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (off < 24 || off > d.size) return false
        f.at = off.toInt()
        f.bytes = d.size - f.at
        if (len != 0xffffffffL && len < f.bytes) f.bytes = len.toInt()
        f.big = 1
        when (enc) {
            1L -> { f.enc = ULAW; f.bits = 8 }
            2L -> { f.enc = PCM; f.bits = 8; f.big = 2 }
            3L -> { f.enc = PCM; f.bits = 16 }
            4L -> { f.enc = PCM; f.bits = 24 }
            5L -> { f.enc = PCM; f.bits = 32 }
            6L -> { f.enc = FLOAT; f.bits = 32 }
            7L -> { f.enc = FLOAT; f.bits = 64 }
            27L -> { f.enc = ALAW; f.bits = 8 }
            else -> return false
        }
        return true
    }

    /** a sample [w] octets wide at [q] as 16 bits ([big] as [Sound.big]) */
    private fun pcmAt(d: ByteArray, q: Int, w: Int, big: Int): Int {
        if (w == 1) return if (big == 2) d[q].toInt() * 256 else (u8(d, q) - 128) * 256
        var v = 0L
        for (i in 0 until w) v = v or (u8(d, if (big != 0) q + i else q + w - 1 - i).toLong() shl (8 * (w - 1 - i)))
        // (the top two octets: the sample's sign is the top bit)
        return (v ushr (8 * (w - 2))).toInt().toShort().toInt()
    }

    /** a float [w] octets wide at [q] as 16 bits */
    private fun floatAt(d: ByteArray, q: Int, w: Int, big: Int): Int {
        var v = 0L
        for (i in 0 until w) v = v or (u8(d, if (big != 0) q + w - 1 - i else q + i).toLong() shl (8 * i))
        val x = if (w == 4) Float.fromBits(v.toInt()).toDouble() else Double.fromBits(v)
        if (x.isNaN()) return 0
        return (x * 32767.0).coerceIn(-32768.0, 32767.0).toInt()
    }

    private val imaSteps = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34,
        37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
        157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494,
        544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552,
        1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428,
        4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
        12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086,
        29794, 32767,
    )
    private val imaIndex = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val msAdapt = intArrayOf(230, 230, 230, 230, 307, 409, 512, 614, 768, 614, 512, 409, 307, 230, 230, 230)

    /** the most frames [f] could hold */
    private fun mostFrames(f: Sound): Long {
        if (f.enc == IMA || f.enc == MSADPCM) {
            if (f.block <= 0) return 0
            return (f.bytes.toLong() / f.block + 1) * (f.block.toLong() * 2 + 2)
        }
        if (f.bits < 8) return 0
        return f.bytes.toLong() / ((f.bits / 8).toLong() * f.channels)
    }

    /** [f]'s sound into [out], its channels mixed: how many frames, -1 if it can't */
    private fun decodeMono(d: ByteArray, f: Sound, out: ShortArray): Int {
        val ch = f.channels
        val cap = out.size
        val end = f.at + f.bytes
        var fr = 0
        when (f.enc) {
            IMA -> {
                // blocks: per channel a header (sample, index), then 4-octet
                // groups of 8 nibbles per channel in turn
                val per = if (f.block > 4 * ch) (f.block - 4 * ch) * 2 / ch + 1 else 0
                if (per <= 0 || per > 65536) return -1
                var b = f.at
                val pred = IntArray(ch)
                val idx = IntArray(ch)
                while (end - b >= f.block && fr < cap) {
                    val n = minOf(per, cap - fr)
                    for (c in 0 until ch) {
                        pred[c] = u16(d, b + 4 * c).toShort().toInt()
                        idx[c] = minOf(u8(d, b + 4 * c + 2), 88)
                    }
                    for (c in 0 until ch) {
                        if (n > 0) out[fr] = (out[fr] + pred[c] / ch).toShort()
                        var k = 1
                        var q = b + 4 * ch + 4 * c
                        while (k < per && q + 4 <= b + f.block) {
                            var j = 0
                            while (j < 8 && k < per) {
                                val nib = (u8(d, q + j / 2) shr ((j and 1) * 4)) and 15
                                val step = imaSteps[idx[c]]
                                var diff = step shr 3
                                if (nib and 4 != 0) diff += step
                                if (nib and 2 != 0) diff += step shr 1
                                if (nib and 1 != 0) diff += step shr 2
                                pred[c] = (pred[c] + if (nib and 8 != 0) -diff else diff).coerceIn(-32768, 32767)
                                idx[c] = (idx[c] + imaIndex[nib]).coerceIn(0, 88)
                                if (k < n) out[fr + k] = (out[fr + k] + pred[c] / ch).toShort()
                                j++
                                k++
                            }
                            q += 4 * ch
                        }
                    }
                    fr += n
                    b += f.block
                }
                return fr
            }
            MSADPCM -> {
                if (ch > 2 || f.coef.isEmpty() || f.block < 7 * ch) return -1
                val per = (f.block - 7 * ch) * 2 / ch + 2
                var b = f.at
                val pred = IntArray(ch)
                val delta = IntArray(ch)
                val s1 = IntArray(ch)
                val s2 = IntArray(ch)
                while (end - b >= f.block && fr < cap) {
                    var q = b
                    val n = minOf(per, cap - fr)
                    for (c in 0 until ch) pred[c] = u8(d, q + c).let { if (it < f.coef.size) it else 0 }
                    q += ch
                    for (c in 0 until ch) { delta[c] = u16(d, q).toShort().toInt(); q += 2 }
                    for (c in 0 until ch) { s1[c] = u16(d, q).toShort().toInt(); q += 2 }
                    for (c in 0 until ch) { s2[c] = u16(d, q).toShort().toInt(); q += 2 }
                    // (the header's two samples: the older first)
                    if (n > 0) out[fr] = (s2.sum() / ch).toShort()
                    if (n > 1) out[fr + 1] = (s1.sum() / ch).toShort()
                    for (k in 2 until per) {
                        var v = 0L
                        for (c in 0 until ch) {
                            val ni = (k - 2) * ch + c
                            val nib = (u8(d, q + ni / 2) shr (if (ni and 1 != 0) 0 else 4)) and 15
                            val sn = if (nib and 8 != 0) nib - 16 else nib
                            val co = f.coef[pred[c]]
                            var p = (s1[c].toLong() * co[0] + s2[c].toLong() * co[1]) / 256
                            p += sn.toLong() * delta[c]
                            p = p.coerceIn(-32768L, 32767L)
                            s2[c] = s1[c]
                            s1[c] = p.toInt()
                            // (a broken file's grows without end)
                            delta[c] = (delta[c].toLong() * msAdapt[nib] / 256).toInt().coerceIn(16, 0x100000)
                            v += p
                        }
                        if (k < n) out[fr + k] = (v / ch).toInt().toShort()
                    }
                    fr += n
                    b += f.block
                }
                return fr
            }
            else -> {
                val w = f.bits / 8
                if (w < 1 || w > 8 || (f.enc == PCM && w > 4) || (f.enc == FLOAT && w != 4 && w != 8) ||
                    ((f.enc == ALAW || f.enc == ULAW) && w != 1)
                ) return -1
                val frames = minOf(f.bytes.toLong() / (w.toLong() * ch), cap.toLong()).toInt()
                for (i in 0 until frames) {
                    var sum = 0L
                    for (c in 0 until ch) {
                        val q = f.at + (i * ch + c) * w
                        sum += when (f.enc) {
                            ULAW -> G711.unulaw(u8(d, q))
                            ALAW -> G711.unalaw(u8(d, q))
                            FLOAT -> floatAt(d, q, w, f.big)
                            else -> pcmAt(d, q, w, f.big)
                        }
                    }
                    out[i] = (sum / ch).toInt().toShort()
                }
                return frames
            }
        }
    }
}
