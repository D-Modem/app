// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.ppp

import com.neodesktop.dmodem.io.*

/** IPv4 and UDP packets, as far as the link's checks need them. */
object Ip {
    /** the Internet checksum (RFC 1071) of [len] bytes at [off], [start] added in */
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
        src.copyInto(p, 12, 0, 4)
        dst.copyInto(p, 16, 0, 4)
        val h = checksum(p, 0, 20)
        p[10] = (h ushr 8).toByte(); p[11] = h.toByte()
        val u = 20
        p[u] = (sport ushr 8).toByte(); p[u + 1] = sport.toByte()
        p[u + 2] = (dport ushr 8).toByte(); p[u + 3] = dport.toByte()
        val ul = 8 + payload.size
        p[u + 4] = (ul ushr 8).toByte(); p[u + 5] = ul.toByte()
        payload.copyInto(p, u + 8, 0, payload.size)
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

    const val TCP = 6
    const val UDP = 17
    const val ICMP = 1

    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    /** the sum of the pseudo header's (addresses, protocol, length) for a TCP or UDP checksum */
    private fun pseudo(src: ByteArray, dst: ByteArray, proto: Int, len: Int): Long {
        var s = proto.toLong() + len
        for (k in 0 until 4 step 2) {
            s += ((src[k].toInt() and 0xff) shl 8) or (src[k + 1].toInt() and 0xff)
            s += ((dst[k].toInt() and 0xff) shl 8) or (dst[k + 1].toInt() and 0xff)
        }
        return s
    }

    /** an IPv4 header around [body] (protocol [proto]), its checksum set */
    fun ipv4(src: ByteArray, dst: ByteArray, proto: Int, body: ByteArray, id: Int = 0, ttl: Int = 64): ByteArray {
        val p = ByteArray(20 + body.size)
        p[0] = 0x45
        p[2] = (p.size ushr 8).toByte(); p[3] = p.size.toByte()
        p[4] = (id ushr 8).toByte(); p[5] = id.toByte()
        p[6] = 0x40 // don't fragment
        p[8] = ttl.toByte()
        p[9] = proto.toByte()
        src.copyInto(p, 12, 0, 4)
        dst.copyInto(p, 16, 0, 4)
        val h = checksum(p, 0, 20)
        p[10] = (h ushr 8).toByte(); p[11] = h.toByte()
        body.copyInto(p, 20, 0, body.size)
        return p
    }

    /** a TCP segment in an IPv4 packet */
    fun tcp(
        src: ByteArray, dst: ByteArray, sport: Int, dport: Int, seq: Int, ack: Int, flags: Int, window: Int,
        payload: ByteArray = ByteArray(0), mss: Int = 0,
    ): ByteArray {
        val opt = if (mss > 0) byteArrayOf(2, 4, (mss ushr 8).toByte(), mss.toByte()) else ByteArray(0)
        val hl = 20 + opt.size
        val t = ByteArray(hl + payload.size)
        t[0] = (sport ushr 8).toByte(); t[1] = sport.toByte()
        t[2] = (dport ushr 8).toByte(); t[3] = dport.toByte()
        for (k in 0 until 4) t[4 + k] = (seq ushr (24 - 8 * k)).toByte()
        for (k in 0 until 4) t[8 + k] = (ack ushr (24 - 8 * k)).toByte()
        t[12] = ((hl / 4) shl 4).toByte()
        t[13] = flags.toByte()
        t[14] = (window ushr 8).toByte(); t[15] = window.toByte()
        opt.copyInto(t, 20, 0, opt.size)
        payload.copyInto(t, hl, 0, payload.size)
        val c = checksum(t, 0, t.size, pseudo(src, dst, TCP, t.size))
        t[16] = (c ushr 8).toByte(); t[17] = c.toByte()
        return ipv4(src, dst, TCP, t)
    }

    /** an ICMP message (its checksum set) in an IPv4 packet */
    fun icmp(src: ByteArray, dst: ByteArray, type: Int, code: Int, rest: ByteArray): ByteArray {
        val m = ByteArray(4 + rest.size)
        m[0] = type.toByte(); m[1] = code.toByte()
        rest.copyInto(m, 4, 0, rest.size)
        val c = checksum(m, 0, m.size)
        m[2] = (c ushr 8).toByte(); m[3] = c.toByte()
        return ipv4(src, dst, ICMP, m)
    }
}
