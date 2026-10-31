// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.transfer

import org.dmodem.app.io.*

/** The checks XMODEM, YMODEM and ZMODEM use: CRC-16/XMODEM (CCITT, 0x1021, from 0) and CRC-32 (IEEE). */
object Crc {
    private val table16 = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
        c and 0xffff
    }

    /** CRC-16 [crc] with the octet [b] added */
    fun crc16(crc: Int, b: Int): Int = ((crc shl 8) xor table16[((crc ushr 8) xor b) and 0xff]) and 0xffff

    /** the CRC-16 of [len] bytes at [off], from [start] */
    fun crc16(data: ByteArray, off: Int = 0, len: Int = data.size, start: Int = 0): Int {
        var c = start
        for (i in off until off + len) c = crc16(c, data[i].toInt() and 0xff)
        return c
    }

    private val table32 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    /** CRC-32 kept inverted as ZMODEM keeps it: start at 0xffffffff, invert at the end */
    fun crc32(crc: Int, b: Int): Int = table32[(crc xor b) and 0xff] xor (crc ushr 8)

    /** the CRC-32 of [len] bytes at [off], from [start], kept inverted (not inverted at the end) */
    fun crc32(data: ByteArray, off: Int = 0, len: Int = data.size, start: Int = -1): Int {
        var c = start
        for (i in off until off + len) c = crc32(c, data[i].toInt() and 0xff)
        return c
    }
}
