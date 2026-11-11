// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sound files other than 16-bit WAV read as a voice call's samples (the desktop library's tests, the same files) */
class SoundFileTest {
    /** [hex]'s octets */
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** [v] into [b] at [at], big-endian, 16 bits */
    private fun be16(b: ByteArray, at: Int, v: Int) { b[at] = (v shr 8).toByte(); b[at + 1] = v.toByte() }

    /** [v] into [b] at [at], big-endian, 32 bits */
    private fun be32(b: ByteArray, at: Int, v: Long) { be16(b, at, (v shr 16).toInt()); be16(b, at + 2, v.toInt()) }

    /** [s]'s characters into [b] at [at] */
    private fun tag(b: ByteArray, at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }

    /** an AIFF of 16-bit samples, big-endian, 8000 Hz, mono */
    @Test
    fun aiff() {
        val a = ByteArray(12 + 26 + 16 + 8)
        tag(a, 0, "FORM"); be32(a, 4, a.size - 8L); tag(a, 8, "AIFF")
        tag(a, 12, "COMM"); be32(a, 16, 18)
        be16(a, 20, 1); be32(a, 22, 4); be16(a, 26, 16)
        // (8000 as an 80-bit extended: 0x400B FA00...)
        a[28] = 0x40; a[29] = 0x0b; a[30] = 0xfa.toByte()
        tag(a, 38, "SSND"); be32(a, 42, 16)
        be16(a, 54, 0x1000); be16(a, 56, 0xf000); be16(a, 58, 0x7fff); be16(a, 60, 0x8000)
        assertContentEquals(shortArrayOf(4096, -4096, 32767, -32768), SoundFile.decode(a))
    }

    /** a Sun .au of μ-law, 8000 Hz, mono */
    @Test
    fun au() {
        val u = ByteArray(27)
        tag(u, 0, ".snd"); be32(u, 4, 24); be32(u, 8, 3); be32(u, 12, 1); be32(u, 16, 8000); be32(u, 20, 1)
        u[24] = 0xff.toByte(); u[25] = 0; u[26] = 0x80.toByte()
        assertContentEquals(shortArrayOf(0, -32124, 32124), SoundFile.decode(u))
    }

    /** WAVs of 24-bit samples and of 32-bit floats */
    @Test
    fun wideWav() {
        val w = Wav.header(3).copyOf(50)
        w[32] = 3; w[34] = 24; w[40] = 6
        bytes("000040" + "0000c0").copyInto(w, 44)
        assertContentEquals(shortArrayOf(16384, -16384), SoundFile.decode(w))
        val f = Wav.header(4).copyOf(52)
        f[20] = 3; f[32] = 4; f[34] = 32; f[40] = 8
        // 0.5, -1.0
        bytes("0000003f" + "000080bf").copyInto(f, 44)
        assertContentEquals(shortArrayOf(16383, -32767), SoundFile.decode(f))
    }

    /** ADPCM: Microsoft's as ffmpeg decodes it, IMA within a few steps (ffmpeg's arithmetic is finer than the IMA's own) */
    @Test
    fun adpcm() {
        val at = intArrayOf(1, 5, 9, 100, 300, 577)
        val ms = assertNotNull(SoundFile.decode(bytes(MS_WAV)))
        assertEquals(580, ms.size)
        assertContentEquals(intArrayOf(1567, 3890, -1630, 4191, -4014, -40), IntArray(at.size) { ms[at[it]].toInt() })
        val ima = assertNotNull(SoundFile.decode(bytes(IMA_WAV)))
        assertEquals(605, ima.size)
        val want = intArrayOf(13, 536, -1554, 4238, -3936, 8)
        for (i in at.indices) assertTrue(abs(ima[at[i]] - want[i]) <= 8, "sample ${at[i]}: ${ima[at[i]]}, not ${want[i]}")
    }

    /** a phone's decoder's 44.1 kHz stereo brought to 8000 Hz, one channel */
    @Test
    fun mixed() {
        val pcm = ShortArray(44100 * 2) { if (it % 2 == 0) 1000 else 3000 }
        val m = SoundFile.mix(pcm, 44100, 2, 44100)
        assertEquals(8000, m.size)
        assertTrue(m.all { it.toInt() == 2000 })
    }

    /** what isn't a sound file this reads */
    @Test
    fun notOnes() {
        assertNull(SoundFile.decode(bytes("464f524d00000004" + "41494646")))
        assertNull(SoundFile.decode(bytes("494433040000000000000000")))
        assertNull(SoundFile.decode(ByteArray(3)))
    }

    private companion object {
        /** ffmpeg -f lavfi -i sine=f=500:r=8000:d=0.07 -c:a adpcm_ima_wav -block_size 64 */
        val IMA_WAV =
            "524946469601000057415645666d74201400000011000100401f0000803e000040000400020079006661637404000000" +
            "300200004c4953541a000000494e464f495346540e0000004c61766636302e31362e3130300064617461400100000000" +
            "0000777777f2ad9921533312b9cdbb8a30453312b9cdbb9a31453312b9cdbb9a31453312b9cdbb9a31453312b9cdbb9a" +
            "31453312b9cdbb9a31453312b9cde1f939009b1952432380dbcbab1852432390dacbab1852432390dacbab1852432390" +
            "dacbab1852432390dacbab1852432390dacbab1852432390dacbab185243500b390002a9ccbb8a30453312b9cdbb8a30" +
            "453302b8cdbb8a30453302b8cdbb8a30453302b8cdbb8a30453302b8cdbb8a30453302b8cdbb8a30453302b8cdbb39f1" +
            "37000843442280cbbcab0853342391dbbcab0853342391dbbcab0853342391dbbcab0853342391dbbcab0853342391db" +
            "bcab1953342391dbbcab19533423ff0f3600b8bdbc8a21353402a9bdbc8a21353402a9bdbc8a21353402a9bdbc8a2135" +
            "3402a9bdbc8a213580808080808080808080808000880088008800880008"
        /** the same, -c:a adpcm_ms */
        val MS_WAV =
            "52494646b401000057415645666d74203200000002000100401f0000803e000040000400200074000700000100000002" +
            "00ff00000000c0004000f0000000cc0130ff880118ff6661637404000000300200004c4953541a000000494e464f4953" +
            "46540e0000004c61766636302e31362e3130300064617461400100000010001f0600007776ecdcdff125343200dbdcde" +
            "0035343200dbdcde0035343200dbdcdef13534321fdbdcdef13534321fdbdcdef13534321fdbdcdef1353432008701c7" +
            "0eff0feccddef13543321fdbcddef13543321fdbcddef13543321fdbcddef13543321fdbcddef13543321fdbcddef135" +
            "43321fdbcddef13543321fdb009901e1f90000ddf14444221fdccdce003444221fdccdce012444221fdccdce01244422" +
            "1fdccdce012444221fdccdce012444221fdccdce012444221fdccdce00b70139f101f02353221febdcde003534320feb" +
            "dcde0035343200dbdcde0035343200dbdcdef13534321fdbdcdef13534321fdbdcdef13534321fdbdcdef13500c4011f" +
            "060000321fdbcddef13543321fdbcddef13543321fdbcddef13543321fdbcddef13543321fdbcddef13543321fdbcdde" +
            "f135400000000000000f1f1f"
    }
}
