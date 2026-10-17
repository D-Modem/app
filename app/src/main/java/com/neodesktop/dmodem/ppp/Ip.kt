// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ppp

/** IPv4 and UDP packets, as far as the link's checks need them. */
object Ip {
    fun checksum(b: ByteArray, off: Int, len: Int, start: Long = 0): Int {
        var s = start
        var i = off
        while (i + 1 < off + len) { s += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff); i += 2 }
        if (i < off + len) s += (b[i].toInt() and 0xff) shl 8
        while (s ushr 16 != 0L) s = (s and 0xffff) + (s ushr 16)
        return (s.inv() and 0xffff).toInt()
    }

    /** a UDP datagram in an IPv4 packet, its checksums set */
    fun udp(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray, id: Int = 1): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total ushr 8).toByte(); p[3] = total.toByte()
        p[4] = (id ushr 8).toByte(); p[5] = id.toByte()
        p[8] = 64
        p[9] = 17
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        val h = checksum(p, 0, 20)
        p[10] = (h ushr 8).toByte(); p[11] = h.toByte()
        val u = 20
        p[u] = (sport ushr 8).toByte(); p[u + 1] = sport.toByte()
        p[u + 2] = (dport ushr 8).toByte(); p[u + 3] = dport.toByte()
        val ul = 8 + payload.size
        p[u + 4] = (ul ushr 8).toByte(); p[u + 5] = ul.toByte()
        System.arraycopy(payload, 0, p, u + 8, payload.size)
        // (the pseudo header's sum: the addresses, the protocol, the length)
        var pseudo = 17L + ul
        for (k in 0 until 4 step 2) {
            pseudo += ((src[k].toInt() and 0xff) shl 8) or (src[k + 1].toInt() and 0xff)
            pseudo += ((dst[k].toInt() and 0xff) shl 8) or (dst[k + 1].toInt() and 0xff)
        }
        val c = checksum(p, u, ul, pseudo).let { if (it == 0) 0xffff else it }
        p[u + 6] = (c ushr 8).toByte(); p[u + 7] = c.toByte()
        return p
    }

    /** a UDP packet's payload */
    fun udpPayload(p: ByteArray): ByteArray {
        val ihl = (p[0].toInt() and 0x0f) * 4
        val ul = ((p[ihl + 4].toInt() and 0xff) shl 8) or (p[ihl + 5].toInt() and 0xff)
        return p.copyOfRange(ihl + 8, ihl + ul)
    }
}
