// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.voice

/** G.711: μ-law, the samples on the DTE in a voice call (AT+VSM=4), and A-law, for WAV files. */
object G711 {
    /** [x] as a μ-law octet */
    fun ulaw(x: Int): Int {
        var v = x
        var sign = 0
        if (v < 0) {
            v = -v
            sign = 0x80
        }
        v += 0x84
        if (v > 0x7fff) v = 0x7fff
        var exp = 7
        while (exp > 0 && v and (0x4000 shr (7 - exp)) == 0) exp--
        val mant = (v shr (exp + 3)) and 0x0f
        return (sign or (exp shl 4) or mant).inv() and 0xff
    }

    /** μ-law octet [u] as a sample */
    fun unulaw(u: Int): Int {
        val b = u.inv() and 0xff
        val exp = (b shr 4) and 7
        val mant = b and 0x0f
        val v = (((mant shl 3) + 0x84) shl exp) - 0x84
        return if (b and 0x80 != 0) -v else v
    }

    /** A-law octet [a] as a sample */
    fun unalaw(a: Int): Int {
        val b = (a and 0xff) xor 0x55
        val exp = (b shr 4) and 7
        val mant = b and 0x0f
        val v = if (exp != 0) ((mant shl 4) + 0x108) shl (exp - 1) else (mant shl 4) + 8
        return if (b and 0x80 != 0) v else -v
    }
}
