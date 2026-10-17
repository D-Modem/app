// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.fax

import com.neodesktop.dmodem.io.*
import com.neodesktop.dmodem.ppp.Hdlc

/**
 * HDLC on the fast modulations, as error correction mode has it (T.4
 * Annex A): a class 1 modem's +FTM and +FRM carry bits, and the fax
 * software frames them itself. Flags (0x7e) between the frames, a zero
 * stuffed after five ones inside them, the FCS-16 after each; the bits
 * in the order they go on the line, the first in an octet's bottom bit
 * (as the modem takes them).
 */
object EcmHdlc {
    private class BitWriter {
        val out = Bytes()
        private var cur = 0
        private var n = 0
        var bits = 0L
            private set

        fun bit(b: Int) {
            if (b != 0) cur = cur or (1 shl n)
            bits++
            if (++n == 8) {
                out.write(cur)
                cur = 0
                n = 0
            }
        }

        fun flag() {
            for (i in 0 until 8) bit((0x7e shr i) and 1)
        }

        /** the last octet filled with ones (the line idle, after a flag) */
        fun finish(): ByteArray {
            while (n != 0) bit(1)
            return out.toByteArray()
        }
    }

    /**
     * [frames] (address, control, FCF, FIF: their FCS added here) with
     * [preambleFlags] flags before (T.30: 200 ms of them), one between
     * each, [tailFlags] after
     */
    fun encode(frames: List<ByteArray>, preambleFlags: Int, tailFlags: Int = 3): ByteArray {
        val w = BitWriter()
        repeat(maxOf(1, preambleFlags)) { w.flag() }
        for (f in frames) {
            val fcs = Hdlc.fcs(f) xor 0xffff
            var ones = 0
            fun put(octet: Int) {
                for (i in 0 until 8) {
                    val b = (octet shr i) and 1
                    w.bit(b)
                    if (b == 1) {
                        if (++ones == 5) { w.bit(0); ones = 0 }
                    } else {
                        ones = 0
                    }
                }
            }
            for (b in f) put(b.toInt() and 0xff)
            put(fcs and 0xff)
            put(fcs ushr 8)
            w.flag()
        }
        repeat(maxOf(0, tailFlags - 1)) { w.flag() }
        return w.finish()
    }

    /** the flags at [bps] for [ms] */
    fun flagsFor(bps: Int, ms: Int) = maxOf(1, bps * ms / 1000 / 8)

    /** the frames with a good FCS in [data] (their FCS off), in the order they came */
    fun decode(data: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        val cur = Bytes()
        var octet = 0
        var nbits = 0
        var ones = 0
        var inFrame = false

        // (a flag's first bit, its zero, is in the frame's bits when the flag is known: whole octets leave just it)
        fun close() {
            if (inFrame && nbits == 1 && cur.size >= 5) {
                val f = cur.toByteArray()
                if (Hdlc.fcs(f) == Hdlc.GOOD_FCS) frames.add(f.copyOf(f.size - 2))
            }
            cur.reset()
            octet = 0
            nbits = 0
            inFrame = true
        }

        fun put(b: Int) {
            if (!inFrame) return
            if (b != 0) octet = octet or (1 shl nbits)
            if (++nbits == 8) {
                cur.write(octet)
                octet = 0
                nbits = 0
                // (no frame of ECM's is this long: noise, until a flag)
                if (cur.size > 1024) { cur.reset(); inFrame = false }
            }
        }

        for (byte in data) {
            val v = byte.toInt()
            for (i in 0 until 8) {
                if ((v shr i) and 1 == 1) {
                    if (++ones >= 7) {
                        // an abort (or the line idle): hunt for a flag
                        inFrame = false
                        cur.reset()
                        nbits = 0
                        octet = 0
                    }
                    continue
                }
                when {
                    ones == 6 -> close()
                    ones == 5 -> repeat(5) { put(1) }   // (the stuffed zero dropped)
                    ones < 5 -> { repeat(ones) { put(1) }; put(0) }
                    // (7 or more: the abort above)
                }
                ones = 0
            }
        }
        return frames
    }
}
