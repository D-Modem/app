// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ppp

import java.security.MessageDigest
import kotlin.random.Random

/** a PPP server, just enough: LCP asking for [auth], the login checked, an address given */
class TestIsp(val auth: Int, val user: String, val password: String, val echo: Boolean = false, val line: (ByteArray) -> Unit) {
    var loggedIn = false
    private var challenge = ByteArray(0)
    private val dec = Hdlc.Decoder { f -> frame(f) }

    /** bytes from the client, unframed */
    fun input(b: ByteArray) = dec.feed(b)

    /** our LCP Configure-Request, asking for [auth] (CHAP with MD5) */
    fun start() {
        val opt = if (auth == Ppp.PROTO_PAP) byteArrayOf(3, 4, 0xc0.toByte(), 0x23) else byteArrayOf(3, 5, 0xc2.toByte(), 0x23, 5)
        out(Ppp.PROTO_LCP, pkt(1, 1, opt + byteArrayOf(5, 6, 1, 2, 3, 4)))
    }

    /** [info] sent to the client as a [proto] frame, every control character escaped */
    private fun out(proto: Int, info: ByteArray) =
        line(Hdlc.encode(byteArrayOf(0xff.toByte(), 3, (proto ushr 8).toByte(), proto.toByte()) + info, -1))

    /** an LCP-style packet: [code], [id], its length and [d] */
    private fun pkt(code: Int, id: Int, d: ByteArray) = byteArrayOf(code.toByte(), id.toByte(), ((d.size + 4) ushr 8).toByte(), (d.size + 4).toByte()) + d

    /** a frame from the client answered: LCP acked (CHAP's challenge after), the login checked, IPCP's addresses given, IP echoed when [echo] */
    private fun frame(f: ByteArray) {
        val proto = ((f[2].toInt() and 0xff) shl 8) or (f[3].toInt() and 0xff)
        val p = f.copyOfRange(4, f.size)
        val code = p[0].toInt()
        val id = p[1].toInt() and 0xff
        when (proto) {
            Ppp.PROTO_LCP -> when (code) {
                1 -> {
                    out(Ppp.PROTO_LCP, pkt(2, id, p.copyOfRange(4, p.size)))
                    if (auth == Ppp.PROTO_CHAP) {
                        challenge = Random.nextBytes(16)
                        out(Ppp.PROTO_CHAP, pkt(1, 7, byteArrayOf(16) + challenge + "isp".toByteArray()))
                    }
                }
                9 -> {}
            }
            Ppp.PROTO_PAP -> if (code == 1) {
                val ul = p[4].toInt()
                val u = String(p, 5, ul)
                val pw = String(p, 6 + ul, p[5 + ul].toInt())
                loggedIn = u == user && pw == password
                out(Ppp.PROTO_PAP, pkt(if (loggedIn) 2 else 3, id, byteArrayOf(0)))
                if (loggedIn) ipcp()
            }
            Ppp.PROTO_CHAP -> if (code == 2) {
                val r = p.copyOfRange(5, 5 + p[4].toInt())
                val md = MessageDigest.getInstance("MD5")
                md.update(id.toByte()); md.update(password.toByteArray()); md.update(challenge)
                loggedIn = r.contentEquals(md.digest()) && String(p, 5 + 16, p.size - 21) == user
                out(Ppp.PROTO_CHAP, pkt(if (loggedIn) 3 else 4, id, (if (loggedIn) "Welcome" else "Bad password").toByteArray()))
                if (loggedIn) ipcp()
            }
            Ppp.PROTO_IP -> if (echo) echo(p)
            Ppp.PROTO_IPCP -> when (code) {
                // the client's 0.0.0.0 and DNS: our numbers instead; its numbers: taken
                1 -> {
                    val wanted = byteArrayOf(3, 6, 192.toByte(), 168.toByte(), 7, 42)
                    val dns = byteArrayOf(129.toByte(), 6, 192.toByte(), 168.toByte(), 7, 1)
                    if (p[6].toInt() == 0) out(Ppp.PROTO_IPCP, pkt(3, id, wanted + dns + byteArrayOf(131.toByte(), 6, 0, 0, 0, 0)))
                    else out(Ppp.PROTO_IPCP, pkt(2, id, p.copyOfRange(4, p.size)))
                }
            }
        }
    }

    /** a UDP packet to us (192.168.7.1) back to its sender, "pong:" before its payload */
private fun echo(p: ByteArray) {
    if (p.size < 28 || p[9].toInt() != 17 || p[16] != 192.toByte() || p[19].toInt() != 1) return
    val src = p.copyOfRange(12, 16)
    val sport = ((p[20].toInt() and 0xff) shl 8) or (p[21].toInt() and 0xff)
    val dport = ((p[22].toInt() and 0xff) shl 8) or (p[23].toInt() and 0xff)
    val reply = Ip.udp(p.copyOfRange(16, 20), src, dport, sport, "pong:".toByteArray() + Ip.udpPayload(p))
    out(Ppp.PROTO_IP, reply)
}

/** our IPCP Configure-Request, with our address 192.168.7.1 */
private fun ipcp() = out(Ppp.PROTO_IPCP, pkt(1, 1, byteArrayOf(3, 6, 192.toByte(), 168.toByte(), 7, 1)))
}

