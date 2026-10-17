// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.io

/** MD5 (RFC 1321): PPP's CHAP logins (RFC 1994) have it */
object Md5 {
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )
    private val K = IntArray(64) { i ->
        (kotlin.math.abs(kotlin.math.sin((i + 1).toDouble())) * 4294967296.0).toLong().toInt()
    }

    fun digest(data: ByteArray): ByteArray {
        val bitLen = data.size.toLong() * 8
        val padded = ByteArray(((data.size + 8) / 64 + 1) * 64)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 8 + i] = (bitLen ushr (8 * i)).toByte()
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476
        val m = IntArray(16)
        for (chunk in padded.indices step 64) {
            for (i in 0 until 16) {
                val o = chunk + i * 4
                m[i] = (padded[o].toInt() and 0xff) or ((padded[o + 1].toInt() and 0xff) shl 8) or
                    ((padded[o + 2].toInt() and 0xff) shl 16) or ((padded[o + 3].toInt() and 0xff) shl 24)
            }
            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val t = d
                d = c
                c = b
                b += (a + f + K[i] + m[g]).rotateLeft(S[i])
                a = t
            }
            a0 += a; b0 += b; c0 += c; d0 += d
        }
        val out = ByteArray(16)
        for ((j, v) in listOf(a0, b0, c0, d0).withIndex()) for (i in 0 until 4) out[j * 4 + i] = (v ushr (8 * i)).toByte()
        return out
    }
}
