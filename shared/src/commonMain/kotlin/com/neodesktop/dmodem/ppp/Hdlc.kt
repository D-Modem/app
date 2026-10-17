// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ppp

import com.neodesktop.dmodem.io.*


/**
 * PPP in HDLC-like framing on an asynchronous line (RFC 1662): frames
 * between 0x7e flags, 0x7d escaping the flag, itself and the control
 * characters the peer's ACCM asks for, an FCS-16 after each.
 */
object Hdlc {
    const val FLAG = 0x7e
    const val ESC = 0x7d

    private val FCS_TABLE = IntArray(256) { b ->
        var v = b
        repeat(8) { v = if (v and 1 != 0) (v ushr 1) xor 0x8408 else v ushr 1 }
        v
    }

    fun fcs(data: ByteArray, off: Int = 0, len: Int = data.size - off, start: Int = 0xffff): Int {
        var f = start
        for (i in off until off + len) f = (f ushr 8) xor FCS_TABLE[(f xor data[i].toInt()) and 0xff]
        return f
    }

    /** what a good frame's FCS over its data and FCS comes to */
    const val GOOD_FCS = 0xf0b8

    /** [frame] (address, control, protocol, information) framed: escaped as [accm] has it, its FCS after */
    fun encode(frame: ByteArray, accm: Int): ByteArray {
        val f = fcs(frame) xor 0xffff
        val o = Bytes(frame.size + frame.size / 4 + 8)
        o.write(FLAG)
        fun put(b: Int) {
            if (b == FLAG || b == ESC || (b < 0x20 && (accm ushr b) and 1 != 0)) {
                o.write(ESC)
                o.write(b xor 0x20)
            } else {
                o.write(b)
            }
        }
        for (b in frame) put(b.toInt() and 0xff)
        put(f and 0xff)
        put(f ushr 8)
        o.write(FLAG)
        return o.toByteArray()
    }

    /** octets from the line into frames: each good one (its FCS checked and off) to [onFrame], bad ones counted */
    class Decoder(private val onFrame: (ByteArray) -> Unit) {
        private val buf = Bytes(1600)
        private var esc = false
        var bad = 0
            private set

        fun feed(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
            for (i in off until off + len) {
                val c = data[i].toInt() and 0xff
                when {
                    c == FLAG -> {
                        if (buf.size >= 4 && !esc) {
                            val f = buf.toByteArray()
                            if (fcs(f) == GOOD_FCS) onFrame(f.copyOf(f.size - 2)) else bad++
                        } else if (buf.size > 0) {
                            bad++
                        }
                        buf.reset()
                        esc = false
                    }
                    c == ESC -> esc = true
                    else -> {
                        buf.write(if (esc) c xor 0x20 else c)
                        esc = false
                        if (buf.size > 4000) { buf.reset(); bad++ }
                    }
                }
            }
        }
    }
}
