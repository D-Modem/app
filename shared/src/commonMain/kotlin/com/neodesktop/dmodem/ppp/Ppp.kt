// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ppp

import com.neodesktop.dmodem.io.*

import kotlin.random.Random
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Job

/**
 * A PPP client (RFC 1661) on a dial-up line, as a PC's dialer was: LCP
 * (MRU, ACCM, magic number, the authentication the server asks for),
 * PAP or CHAP-MD5 with [user] and [password], IPCP for an address and
 * the DNS servers; then IPv4 packets both ways. What it doesn't take
 * (compression, IPv6, the rest) it rejects. [send] gets the line's
 * octets (framed); [input] takes the line's.
 */
class Ppp(
    private val send: (ByteArray) -> Unit,
    private val user: String = "",
    private val password: String = "",
    private val events: Events,
    /** how long between retries, and how many before it gives up */
    private val retryMs: Long = 3000,
    private val maxRetries: Int = 10,
    /** the server's side instead (no login asked): its address, the peer's to give, the DNS server to name */
    private val server: Server? = null,
) {
    /** what a server gives the peer */
    data class Server(val local: Inet4, val peer: Inet4, val dns: Inet4)

    /** the link's address and DNS servers, once IPCP has them */
    data class IpConfig(val address: Inet4, val peer: Inet4?, val dns: List<Inet4>, val mtu: Int)

    interface Events {
        fun up(config: IpConfig)
        fun ip(packet: ByteArray)
        fun down(reason: String)
        fun log(line: String) {}
    }

    enum class Phase { DEAD, ESTABLISH, AUTHENTICATE, NETWORK, OPEN, TERMINATE }

    @kotlin.concurrent.Volatile var phase = Phase.DEAD
        private set

    private val timer = Timer()
    private val lock = SynchronizedObject()
    private val decoder = Hdlc.Decoder { f -> synchronized(lock) { frame(f) } }

    // what each side asked
    private var sendAccm = -1           // the peer's: what we escape
    private var peerMru = 1500
    private val magic = Random.nextInt().let { if (it == 0) 1 else it }
    private var authProto = 0           // what the peer asked us to authenticate with (0 none)

    // LCP
    private var lcpId = 0
    private var lcpOurAcked = false
    private var lcpTheirAcked = false
    private var lcpRetries = 0
    private var lcpMru = 1500
    private var lcpAskAccm = true
    private var lcpAskMagic = true
    private var lcpTimer: Job? = null

    // authentication
    private var authId = 0
    private var authTimer: Job? = null
    private var authRetries = 0

    // IPCP
    private var ipcpId = 0
    private var ipcpOurAcked = false
    private var ipcpTheirAcked = false
    private var ipcpRetries = 0
    private var ipcpTimer: Job? = null
    private var ourAddr = ByteArray(4)
    private var dns1 = ByteArray(4)
    private var dns2 = ByteArray(4)
    private var askDns1 = true
    private var askDns2 = true
    private var peerAddr: ByteArray? = null

    init {
        if (server != null) {
            ourAddr = server.local.octets
            askDns1 = false
            askDns2 = false
        }
    }

    // counts
    @kotlin.concurrent.Volatile var bytesIn = 0L
        private set
    @kotlin.concurrent.Volatile var bytesOut = 0L
        private set

    private fun log(s: String) = events.log(s)

    // ------------------------------------------------------------------

    /** the link brought up: LCP's Configure-Request out */
    fun start() = synchronized(lock) {
        phase = Phase.ESTABLISH
        sendLcpConfReq()
    }

    /** octets from the line */
    fun input(data: ByteArray, off: Int = 0, len: Int = data.size - off) = decoder.feed(data, off, len)

    /** an IPv4 packet out, when the link is open (false: it isn't) */
    fun sendIp(packet: ByteArray): Boolean {
        if (phase != Phase.OPEN) return false
        bytesOut += packet.size
        out(PROTO_IP, packet)
        return true
    }

    /** the link closed: LCP's Terminate-Request (its Ack waited for a moment), then down */
    fun stop(reason: String = "disconnected") {
        synchronized(lock) {
            if (phase == Phase.DEAD) return
            if (phase != Phase.ESTABLISH) {
                phase = Phase.TERMINATE
                out(PROTO_LCP, packet(TERM_REQ, ++lcpId, ByteArray(0)))
            }
        }
        // (a moment for the Terminate-Ack)
        timer.schedule(if (phase == Phase.TERMINATE) 500 else 0) { down(reason) }
    }

    private fun down(reason: String) {
        synchronized(lock) {
            if (phase == Phase.DEAD) return
            phase = Phase.DEAD
            lcpTimer?.cancel()
            authTimer?.cancel()
            ipcpTimer?.cancel()
        }
        log("down: $reason")
        events.down(reason)
        timer.shutdown()
    }

    // ------------------------------------------------------------------
    // frames

    private fun out(proto: Int, info: ByteArray) {
        val f = ByteArray(4 + info.size)
        f[0] = 0xff.toByte()
        f[1] = 0x03
        f[2] = (proto ushr 8).toByte()
        f[3] = proto.toByte()
        info.copyInto(f, 4, 0, info.size)
        // (LCP always with every control character escaped: RFC 1662 7.1)
        send(Hdlc.encode(f, if (proto == PROTO_LCP) -1 else sendAccm))
    }

    private fun packet(code: Int, id: Int, data: ByteArray): ByteArray {
        val p = ByteArray(4 + data.size)
        p[0] = code.toByte()
        p[1] = id.toByte()
        p[2] = (p.size ushr 8).toByte()
        p[3] = p.size.toByte()
        data.copyInto(p, 4, 0, data.size)
        return p
    }

    private fun frame(f: ByteArray) {
        // address and control (or not, with ACFC), the protocol (one octet with PFC)
        var i = 0
        if (f.size >= 2 && (f[0].toInt() and 0xff) == 0xff && f[1].toInt() == 0x03) i = 2
        if (i >= f.size) return
        val proto: Int
        if ((f[i].toInt() and 1) == 1) {
            proto = f[i].toInt() and 0xff
            i += 1
        } else {
            if (i + 1 >= f.size) return
            proto = ((f[i].toInt() and 0xff) shl 8) or (f[i + 1].toInt() and 0xff)
            i += 2
        }
        val info = f.copyOfRange(i, f.size)
        when (proto) {
            PROTO_LCP -> lcp(info)
            PROTO_PAP -> pap(info)
            PROTO_CHAP -> chap(info)
            PROTO_IPCP -> ipcp(info)
            PROTO_IP -> if (phase == Phase.OPEN) { bytesIn += info.size; events.ip(info) }
            else -> if (phase.ordinal >= Phase.AUTHENTICATE.ordinal && phase != Phase.TERMINATE) {
                // not ours: rejected (IPv6CP, CCP, …)
                log("protocol ${hex(proto, 4)} rejected")
                out(PROTO_LCP, packet(PROT_REJ, ++lcpId, byteArrayOf((proto ushr 8).toByte(), proto.toByte()) + info))
            }
        }
    }

    /** a packet's options: (type, data) */
    private fun options(p: ByteArray): List<Pair<Int, ByteArray>>? {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 4
        val end = minOf(p.size, len(p))
        while (i + 2 <= end) {
            val t = p[i].toInt() and 0xff
            val l = p[i + 1].toInt() and 0xff
            if (l < 2 || i + l > end) return null
            out.add(t to p.copyOfRange(i + 2, i + l))
            i += l
        }
        return out
    }

    private fun len(p: ByteArray) = if (p.size >= 4) ((p[2].toInt() and 0xff) shl 8) or (p[3].toInt() and 0xff) else 0

    private fun opts(list: List<Pair<Int, ByteArray>>): ByteArray {
        val o = Bytes()
        for ((t, d) in list) { o.write(t); o.write(d.size + 2); o.write(d) }
        return o.toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun int32(d: ByteArray) = ((d[0].toInt() and 0xff) shl 24) or ((d[1].toInt() and 0xff) shl 16) or
        ((d[2].toInt() and 0xff) shl 8) or (d[3].toInt() and 0xff)
    private fun int16(d: ByteArray) = ((d[0].toInt() and 0xff) shl 8) or (d[1].toInt() and 0xff)

    // ------------------------------------------------------------------
    // LCP

    private fun sendLcpConfReq() {
        val o = ArrayList<Pair<Int, ByteArray>>()
        if (lcpMru != 1500) o.add(1 to byteArrayOf((lcpMru ushr 8).toByte(), lcpMru.toByte()))
        // nothing of ours escaped (the modem's link is 8-bit clean)
        if (lcpAskAccm) o.add(2 to int32(0))
        if (lcpAskMagic) o.add(5 to int32(magic))
        out(PROTO_LCP, packet(CONF_REQ, ++lcpId, opts(o)))
        lcpTimer?.cancel()
        lcpTimer = timer.schedule(retryMs) {
            synchronized(lock) {
                if (phase != Phase.ESTABLISH || lcpOurAcked) return@synchronized
                if (++lcpRetries > maxRetries) { down("the other end doesn't talk PPP"); return@synchronized }
                sendLcpConfReq()
            }
        }
    }

    private fun lcp(p: ByteArray) {
        if (p.size < 4) return
        val code = p[0].toInt() and 0xff
        val id = p[1].toInt() and 0xff
        when (code) {
            CONF_REQ -> {
                // (a renegotiation once up: from the start)
                if (phase.ordinal > Phase.ESTABLISH.ordinal && phase != Phase.TERMINATE) restartLcp()
                val o = options(p) ?: return
                val reject = ArrayList<Pair<Int, ByteArray>>()
                val nak = ArrayList<Pair<Int, ByteArray>>()
                var mru = 1500
                var accm = -1
                var auth = 0
                for ((t, d) in o) {
                    when (t) {
                        1 -> if (d.size == 2) mru = int16(d) else reject.add(t to d)
                        2 -> if (d.size == 4) accm = int32(d) else reject.add(t to d)
                        3 -> {
                            val proto = if (d.size >= 2) int16(d) else 0
                            when {
                                proto == PROTO_PAP && d.size == 2 -> auth = PROTO_PAP
                                proto == PROTO_CHAP && d.size == 3 && d[2].toInt() == 5 -> auth = PROTO_CHAP
                                // (MS-CHAP and the rest: CHAP-MD5 asked for instead)
                                else -> nak.add(3 to byteArrayOf((PROTO_CHAP ushr 8).toByte(), PROTO_CHAP.toByte(), 5))
                            }
                        }
                        5 -> if (d.size == 4 && int32(d) == magic) {
                            // our own magic number: a looped-back line
                            nak.add(5 to int32(Random.nextInt()))
                        }
                        else -> reject.add(t to d)
                    }
                }
                when {
                    reject.isNotEmpty() -> out(PROTO_LCP, packet(CONF_REJ, id, opts(reject)))
                    nak.isNotEmpty() -> out(PROTO_LCP, packet(CONF_NAK, id, opts(nak)))
                    else -> {
                        peerMru = mru
                        sendAccm = accm
                        authProto = auth
                        out(PROTO_LCP, packet(CONF_ACK, id, p.copyOfRange(4, len(p).coerceAtMost(p.size))))
                        lcpTheirAcked = true
                        lcpMaybeUp()
                    }
                }
            }
            CONF_ACK -> if (id == lcpId) { lcpOurAcked = true; lcpTimer?.cancel(); lcpMaybeUp() }
            CONF_NAK -> if (id == lcpId) {
                for ((t, d) in options(p) ?: return) {
                    when (t) {
                        1 -> if (d.size == 2) lcpMru = int16(d)
                        2 -> lcpAskAccm = false
                        5 -> lcpAskMagic = d.size == 4
                    }
                }
                sendLcpConfReq()
            }
            CONF_REJ -> if (id == lcpId) {
                for ((t, _) in options(p) ?: return) {
                    when (t) { 1 -> lcpMru = 1500; 2 -> lcpAskAccm = false; 5 -> lcpAskMagic = false }
                }
                sendLcpConfReq()
            }
            TERM_REQ -> {
                out(PROTO_LCP, packet(TERM_ACK, id, ByteArray(0)))
                timer.schedule(100) { down("the other end hung up the link") }
            }
            TERM_ACK -> if (phase == Phase.TERMINATE) timer.execute { down("disconnected") }
            ECHO_REQ -> if (phase.ordinal >= Phase.AUTHENTICATE.ordinal && p.size >= 8) {
                out(PROTO_LCP, packet(ECHO_REP, id, int32(if (lcpAskMagic) magic else 0) + p.copyOfRange(8, len(p).coerceAtMost(p.size))))
            }
            PROT_REJ -> log("the other end rejected protocol ${hex(if (p.size >= 6) int16(p.copyOfRange(4, 6)) else 0, 4)}")
            CODE_REJ -> log("the other end rejected an LCP code")
            ECHO_REP, DISCARD_REQ -> {}
            else -> out(PROTO_LCP, packet(CODE_REJ, ++lcpId, p.copyOf(len(p).coerceAtMost(p.size))))
        }
    }

    private fun restartLcp() {
        log("LCP again")
        phase = Phase.ESTABLISH
        lcpOurAcked = false
        lcpTheirAcked = false
        lcpRetries = 0
        ipcpOurAcked = false
        ipcpTheirAcked = false
        sendAccm = -1
        sendLcpConfReq()
    }

    private fun lcpMaybeUp() {
        if (!(lcpOurAcked && lcpTheirAcked) || phase != Phase.ESTABLISH) return
        log("LCP up (mru $peerMru, accm ${hex(sendAccm, 8)}, auth ${hex(authProto, 4)})")
        when (authProto) {
            PROTO_PAP -> { phase = Phase.AUTHENTICATE; sendPap() }
            // (CHAP: the server's challenge comes)
            PROTO_CHAP -> phase = Phase.AUTHENTICATE
            else -> startIpcp()
        }
    }

    // ------------------------------------------------------------------
    // authentication

    private fun sendPap() {
        val u = user.encodeToByteArray()
        val pw = password.encodeToByteArray()
        out(PROTO_PAP, packet(1, ++authId, byteArrayOf(u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw))
        authTimer?.cancel()
        authTimer = timer.schedule(retryMs) {
            synchronized(lock) {
                if (phase != Phase.AUTHENTICATE) return@synchronized
                if (++authRetries > maxRetries) { stopLocked("no answer to the login"); return@synchronized }
                sendPap()
            }
        }
    }

    private fun stopLocked(why: String) {
        timer.execute { stop(why) }
    }

    private fun pap(p: ByteArray) {
        if (p.size < 4 || phase != Phase.AUTHENTICATE) return
        when (p[0].toInt()) {
            2 -> { authTimer?.cancel(); log("PAP: logged in"); startIpcp() }
            3 -> {
                authTimer?.cancel()
                val msg = if (p.size > 5) p.decodeToString(5, 5 + minOf(p[4].toInt() and 0xff, p.size - 5)) else ""
                stopLocked("the login was refused" + if (msg.isNotBlank()) ": $msg" else "")
            }
        }
    }

    private fun chap(p: ByteArray) {
        if (p.size < 4) return
        val id = p[1].toInt() and 0xff
        when (p[0].toInt()) {
            1 -> {
                // a challenge: MD5 of the id, the secret and the challenge
                val n = p[4].toInt() and 0xff
                if (p.size < 5 + n) return
                val challenge = p.copyOfRange(5, 5 + n)
                val r = Md5.digest(byteArrayOf(id.toByte()) + password.encodeToByteArray() + challenge)
                out(PROTO_CHAP, packet(2, id, byteArrayOf(r.size.toByte()) + r + user.encodeToByteArray()))
            }
            3 -> if (phase == Phase.AUTHENTICATE) { log("CHAP: logged in"); startIpcp() }
            4 -> stopLocked("the login was refused" + if (p.size > 4) ": " + p.decodeToString(4, len(p).coerceAtMost(p.size)) else "")
        }
    }

    // ------------------------------------------------------------------
    // IPCP

    private fun startIpcp() {
        phase = Phase.NETWORK
        ipcpRetries = 0
        sendIpcpConfReq()
    }

    private fun sendIpcpConfReq() {
        val o = ArrayList<Pair<Int, ByteArray>>()
        o.add(3 to ourAddr)
        if (askDns1) o.add(129 to dns1)
        if (askDns2) o.add(131 to dns2)
        out(PROTO_IPCP, packet(CONF_REQ, ++ipcpId, opts(o)))
        ipcpTimer?.cancel()
        ipcpTimer = timer.schedule(retryMs) {
            synchronized(lock) {
                if (phase != Phase.NETWORK || ipcpOurAcked) return@synchronized
                if (++ipcpRetries > maxRetries) { stopLocked("no address came"); return@synchronized }
                sendIpcpConfReq()
            }
        }
    }

    private fun ipcp(p: ByteArray) {
        if (p.size < 4) return
        if (phase.ordinal < Phase.NETWORK.ordinal || phase == Phase.TERMINATE) {
            // (before LCP is up: dropped; IPCP comes again)
            return
        }
        val code = p[0].toInt() and 0xff
        val id = p[1].toInt() and 0xff
        when (code) {
            CONF_REQ -> if (server != null) serverIpcpRequest(p, id) else {
                val o = options(p) ?: return
                val reject = o.filter { (t, d) -> t != 3 || d.size != 4 }
                if (reject.isNotEmpty()) {
                    out(PROTO_IPCP, packet(CONF_REJ, id, opts(reject)))
                } else {
                    peerAddr = o.firstOrNull { it.first == 3 }?.second
                    out(PROTO_IPCP, packet(CONF_ACK, id, p.copyOfRange(4, len(p).coerceAtMost(p.size))))
                    ipcpTheirAcked = true
                    ipcpMaybeUp()
                }
            }
            CONF_NAK -> if (id == ipcpId) {
                for ((t, d) in options(p) ?: return) {
                    if (d.size != 4) continue
                    when (t) { 3 -> ourAddr = d; 129 -> dns1 = d; 131 -> dns2 = d }
                }
                sendIpcpConfReq()
            }
            CONF_REJ -> if (id == ipcpId) {
                for ((t, _) in options(p) ?: return) {
                    when (t) { 129 -> askDns1 = false; 131 -> askDns2 = false }
                }
                sendIpcpConfReq()
            }
            CONF_ACK -> if (id == ipcpId) { ipcpOurAcked = true; ipcpTimer?.cancel(); ipcpMaybeUp() }
            TERM_REQ -> out(PROTO_IPCP, packet(TERM_ACK, id, ByteArray(0)))
        }
    }

    /**
     * a server's answer to the peer's request: other than its address and
     * the DNS servers, rejected (compression, NBNS); the address and DNS
     * not ours to give, Nak'd with ours; else Ack'd
     */
    private fun serverIpcpRequest(p: ByteArray, id: Int) {
        val s = server ?: return
        val o = options(p) ?: return
        val reject = o.filter { (t, d) -> t !in listOf(3, 129, 131) || d.size != 4 }
        if (reject.isNotEmpty()) {
            out(PROTO_IPCP, packet(CONF_REJ, id, opts(reject)))
            return
        }
        val want = mapOf(3 to s.peer.octets, 129 to s.dns.octets, 131 to s.dns.octets)
        val nak = o.filter { (t, d) -> !d.contentEquals(want.getValue(t)) }.map { (t, _) -> t to want.getValue(t) }
        if (nak.isNotEmpty()) {
            out(PROTO_IPCP, packet(CONF_NAK, id, opts(nak)))
            return
        }
        peerAddr = s.peer.octets
        dns1 = s.dns.octets
        out(PROTO_IPCP, packet(CONF_ACK, id, p.copyOfRange(4, len(p).coerceAtMost(p.size))))
        ipcpTheirAcked = true
        ipcpMaybeUp()
    }

    private fun ipcpMaybeUp() {
        if (!(ipcpOurAcked && ipcpTheirAcked) || phase != Phase.NETWORK) return
        if (ourAddr.all { it.toInt() == 0 }) { stopLocked("no address came"); return }
        phase = Phase.OPEN
        val dns = listOf(dns1, dns2).filter { d -> d.any { it.toInt() != 0 } }
            .map { inet4(it) }.distinct()
        val cfg = IpConfig(
            inet4(ourAddr),
            peerAddr?.let { inet4(it) },
            dns,
            minOf(peerMru, 1500),
        )
        log("IPCP up: ${cfg.address.dotted}, dns ${dns.joinToString { it.dotted }}")
        timer.execute { events.up(cfg) }
    }

    companion object {
        const val PROTO_IP = 0x0021
        const val PROTO_IPCP = 0x8021
        const val PROTO_LCP = 0xc021
        const val PROTO_PAP = 0xc023
        const val PROTO_CHAP = 0xc223

        const val CONF_REQ = 1
        const val CONF_ACK = 2
        const val CONF_NAK = 3
        const val CONF_REJ = 4
        const val TERM_REQ = 5
        const val TERM_ACK = 6
        const val CODE_REJ = 7
        const val PROT_REJ = 8
        const val ECHO_REQ = 9
        const val ECHO_REP = 10
        const val DISCARD_REQ = 11
    }
}

/** [v] in hex, [digits] of it at least (%0Nx) */
private fun hex(v: Number, digits: Int): String = v.toLong().let { if (it < 0) it and 0xffffffffL else it }.toString(16).padStart(digits, '0')
