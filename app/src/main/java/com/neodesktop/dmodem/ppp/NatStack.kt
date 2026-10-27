// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ppp

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * A computer's IPv4 on the phone's own network, without a VPN or root, as
 * slirp did it for dial-up shells: each TCP connection the computer opens
 * is the phone's own socket to the same place (the computer's side of it
 * kept here: the handshake, its sequence numbers, retransmission, its
 * window, the ends), each UDP flow the phone's own datagram socket, DNS to
 * the gateway's address the phone's DNS servers' answer, a ping answered
 * when the phone can reach the host. [toClient] gets the packets for the
 * computer.
 */
class NatStack(
    private val toClient: (ByteArray) -> Unit,
    /** this side's address: the computer's gateway, and its DNS server */
    private val gateway: InetAddress,
    /** the phone's DNS servers, asked for each query */
    private val dnsServers: () -> List<InetAddress>,
    /** a socket to where the computer connects (the phone's network's) */
    private val connect: (InetSocketAddress) -> Socket = { a -> Socket().apply { connect(a, 15_000) } },
    private val log: (String) -> Unit = {},
    /** the DNS servers' port (53; the tests' own) */
    private val dnsPort: Int = 53,
) {
    private val tcp = ConcurrentHashMap<String, Tcp>()
    private val udp = ConcurrentHashMap<String, Udp>()
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "nat").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nat-timer").apply { isDaemon = true } }
    @Volatile private var closed = false
    private val gw = gateway.address

    /** the MSS for segments to the computer (its link's MTU less the headers) */
    @Volatile var mss = 1460

    init {
        timer.scheduleWithFixedDelay({ tick() }, 250, 250, TimeUnit.MILLISECONDS)
    }

    /** every connection and flow ended (TCP's aborted), the timer and workers stopped */
    fun close() {
        closed = true
        tcp.values.forEach { it.abort() }
        udp.values.forEach { it.close() }
        tcp.clear(); udp.clear()
        timer.shutdownNow()
        workers.shutdownNow()
    }

    val connections: Int get() = tcp.size + udp.size

    /** packet [p] to the computer, unless closed */
    private fun send(p: ByteArray) {
        if (!closed) runCatching { toClient(p) }
    }

    /** an IPv4 packet from the computer */
    fun input(p: ByteArray) {
        if (closed || p.size < 20 || (p[0].toInt() ushr 4) != 4) return
        val ihl = (p[0].toInt() and 0x0f) * 4
        val total = minOf(p.size, ((p[2].toInt() and 0xff) shl 8) or (p[3].toInt() and 0xff))
        // (fragments: none on a link this slow; dropped)
        val frag = ((p[6].toInt() and 0x3f) shl 8) or (p[7].toInt() and 0xff)
        if (frag != 0 || total < ihl) return
        val src = p.copyOfRange(12, 16)
        val dst = p.copyOfRange(16, 20)
        val body = p.copyOfRange(ihl, total)
        when (p[9].toInt() and 0xff) {
            Ip.TCP -> tcpIn(src, dst, body)
            Ip.UDP -> udpIn(src, dst, body)
            Ip.ICMP -> icmpIn(src, dst, body)
        }
    }

    /** the big-endian 16-bit value at [i] of [b] */
    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    /** the big-endian 32-bit value at [i] of [b] */
    private fun u32(b: ByteArray, i: Int) = (u16(b, i) shl 16) or u16(b, i + 2)
    /** the address of [b]'s four octets */
    private fun addr(b: ByteArray) = InetAddress.getByAddress(b)

    // ------------------------------------------------------------------
    // UDP

    private inner class Udp(val key: String, val client: ByteArray, val cport: Int, val dst: ByteArray, val dport: Int, val dns: Boolean) {
        val socket = DatagramSocket()
        @Volatile var last = System.currentTimeMillis()

        init {
            socket.soTimeout = 1000
            workers.execute {
                val buf = ByteArray(65535)
                while (!closed && !socket.isClosed) {
                    val d = DatagramPacket(buf, buf.size)
                    try { socket.receive(d) } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { break }
                    last = System.currentTimeMillis()
                    // (an answer from where it was sent; DNS's as the gateway's)
                    val from = if (dns) gw else d.address.address
                    val fport = if (dns) 53 else d.port
                    this@NatStack.send(Ip.udp(from, client, fport, cport, buf.copyOf(d.length)))
                }
            }
        }

        /** the computer's datagram on (a DNS query: to the phone's DNS server) */
        fun forward(payload: ByteArray) {
            last = System.currentTimeMillis()
            val to = if (dns) {
                // the phone's DNS servers (one, the first that's there)
                val s = dnsServers().firstOrNull() ?: InetAddress.getByName("1.1.1.1")
                InetSocketAddress(s, dnsPort)
            } else InetSocketAddress(addr(dst), dport)
            runCatching { socket.send(DatagramPacket(payload, payload.size, to)) }
        }

        /** the flow's socket closed */
        fun close() = runCatching { socket.close() }
    }

    /** a UDP datagram from the computer: to its flow (made for a new one); to the gateway only DNS */
    private fun udpIn(src: ByteArray, dst: ByteArray, b: ByteArray) {
        if (b.size < 8) return
        val sport = u16(b, 0)
        val dport = u16(b, 2)
        val len = minOf(b.size, u16(b, 4))
        val payload = b.copyOfRange(8, len)
        val toGw = dst.contentEquals(gw)
        if (toGw && dport != 53) return
        val key = "${addr(src).hostAddress}:$sport>${addr(dst).hostAddress}:$dport"
        val u = udp[key] ?: try {
            Udp(key, src, sport, dst, dport, toGw).also { udp[key] = it }
        } catch (e: IOException) {
            return
        }
        workers.execute { u.forward(payload) }
    }

    // ------------------------------------------------------------------
    // ICMP: echo

    /** an echo request from the computer: answered for the gateway, else if the phone can reach the host */
    private fun icmpIn(src: ByteArray, dst: ByteArray, b: ByteArray) {
        if (b.size < 8 || b[0].toInt() != 8) return
        val rest = b.copyOfRange(4, b.size)
        if (dst.contentEquals(gw)) {
            send(Ip.icmp(dst, src, 0, 0, rest))
            return
        }
        workers.execute {
            val ok = runCatching { addr(dst).isReachable(3000) }.getOrDefault(false)
            if (ok) send(Ip.icmp(dst, src, 0, 0, rest))
        }
    }

    // ------------------------------------------------------------------
    // TCP

    private enum class St { CONNECTING, SYN_RCVD, ESTABLISHED, CLOSING, CLOSED }

    private inner class Tcp(val key: String, val client: ByteArray, val cport: Int, val dst: ByteArray, val dport: Int, clientIsn: Int, val peerMss: Int) {
        @Volatile var st = St.CONNECTING
        val iss = Random.nextInt()
        var sndUna = iss           // the computer's acknowledged up to
        var sndNxt = iss           // ours sent up to
        var rcvNxt = clientIsn + 1 // the computer's expected next
        @Volatile var wnd = 8192   // the computer's window
        var socket: Socket? = null
        val unacked = java.io.ByteArrayOutputStream()
        var finSent = false        // our FIN out (at sndNxt - 1)
        var finAcked = false
        var clientFin = false
        var lastSent = 0L
        var rto = 1000L
        var lastHeard = System.currentTimeMillis()
        val toSocket = LinkedBlockingQueue<ByteArray>()
        val lock = Object()

        /** a segment to the computer with [flags], [seq] and [data] (an MSS option if [mssOpt]), acknowledging [rcvNxt] */
        fun seg(flags: Int, seq: Int, data: ByteArray = ByteArray(0), mssOpt: Int = 0) =
            send(Ip.tcp(dst, client, dport, cport, seq, rcvNxt, flags, window(), data, mssOpt))

        /** the window offered the computer: what's free of 64K queued for the socket */
        fun window(): Int = (65535 - toSocket.sumOf { it.size }).coerceIn(0, 65535)

        /** the SYN-ACK to the computer, with our MSS */
        fun synAck() {
            seg(Ip.SYN or Ip.ACK, iss, mssOpt = mss)
            lastSent = System.currentTimeMillis()
        }

        /** the connection over at once: its socket closed, its writer woken to end, forgotten */
        fun abort() {
            st = St.CLOSED
            runCatching { socket?.close() }
            toSocket.add(ByteArray(0))
            synchronized(lock) { lock.notifyAll() }
            tcp.remove(key)
        }

        /** a reset to the computer, and the connection aborted */
        fun reset() {
            seg(Ip.RST or Ip.ACK, sndNxt)
            abort()
        }

        /** the socket's octets to the computer, as its window lets them */
        fun reader(s: Socket) {
            val buf = ByteArray(maxOf(536, minOf(peerMss, mss)))
            val input = s.getInputStream()
            while (st != St.CLOSED) {
                synchronized(lock) {
                    // (the computer's window full: until it acknowledges)
                    while (st != St.CLOSED && (sndNxt - sndUna) >= wnd.coerceAtLeast(1)) lock.wait(500)
                }
                if (st == St.CLOSED) return
                val room = minOf(buf.size, (wnd - (sndNxt - sndUna)).coerceAtLeast(1))
                val n = try { input.read(buf, 0, room) } catch (_: IOException) { -1 }
                synchronized(lock) {
                    if (st == St.CLOSED) return
                    if (n < 0) {
                        // the far end done: our FIN
                        finSent = true
                        seg(Ip.FIN or Ip.ACK, sndNxt)
                        sndNxt += 1
                        lastSent = System.currentTimeMillis()
                        return
                    }
                    val d = buf.copyOf(n)
                    seg(Ip.PSH or Ip.ACK, sndNxt, d)
                    unacked.write(d)
                    sndNxt += n
                    lastSent = System.currentTimeMillis()
                }
            }
        }

        /** the computer's octets to the socket, in order, by their own thread (the socket may be slow) */
        fun writer(s: Socket) {
            val out = s.getOutputStream()
            while (st != St.CLOSED) {
                val d = toSocket.take()
                if (d.isEmpty()) {
                    // its FIN (or the end): the socket's output shut
                    if (clientFin) runCatching { s.shutdownOutput() }
                    return
                }
                try { out.write(d); out.flush() } catch (_: IOException) { synchronized(lock) { reset() }; return }
            }
        }

        /** what's unacknowledged again (from sndUna, as much as the window takes); the FIN if it's due */
        fun retransmit() {
            val all = unacked.toByteArray()
            var off = 0
            val chunk = maxOf(536, minOf(peerMss, mss))
            while (off < all.size && off < wnd.coerceAtLeast(chunk)) {
                val n = minOf(chunk, all.size - off)
                seg(Ip.PSH or Ip.ACK, sndUna + off, all.copyOfRange(off, off + n))
                off += n
            }
            if (finSent && !finAcked && all.isEmpty()) seg(Ip.FIN or Ip.ACK, sndNxt - 1)
            lastSent = System.currentTimeMillis()
        }
    }

    /** a TCP segment from the computer: to its connection, a SYN opening a new one, anything else reset */
    private fun tcpIn(src: ByteArray, dst: ByteArray, b: ByteArray) {
        if (b.size < 20) return
        val sport = u16(b, 0)
        val dport = u16(b, 2)
        val seq = u32(b, 4)
        val ack = u32(b, 8)
        val off = ((b[12].toInt() and 0xff) ushr 4) * 4
        val flags = b[13].toInt() and 0x3f
        val win = u16(b, 14)
        if (off < 20 || off > b.size) return
        val data = b.copyOfRange(off, b.size)
        val key = "${addr(src).hostAddress}:$sport>${addr(dst).hostAddress}:$dport"
        var c = tcp[key]

        if (flags and Ip.RST != 0) { c?.abort(); return }
        if (c == null) {
            if (flags and Ip.SYN == 0 || flags and Ip.ACK != 0) {
                // not a connection of ours: reset
                if (flags and Ip.ACK != 0) send(Ip.tcp(dst, src, dport, sport, ack, 0, Ip.RST, 0))
                else send(Ip.tcp(dst, src, dport, sport, 0, seq + data.size + 1, Ip.RST or Ip.ACK, 0))
                return
            }
            // a SYN: its MSS, and the phone's socket to there
            var peerMss = 536
            var i = 20
            while (i < off) {
                val kind = b[i].toInt() and 0xff
                if (kind == 0) break
                if (kind == 1) { i++; continue }
                if (i + 1 >= off) break
                val l = b[i + 1].toInt() and 0xff
                if (kind == 2 && l == 4) peerMss = u16(b, i + 2)
                i += maxOf(l, 2)
            }
            if (dst.contentEquals(gw)) {
                send(Ip.tcp(dst, src, dport, sport, 0, seq + 1, Ip.RST or Ip.ACK, 0))
                return
            }
            val t = Tcp(key, src, sport, dst, dport, seq, peerMss)
            t.wnd = win
            tcp[key] = t
            c = t
            workers.execute {
                val s = try { connect(InetSocketAddress(addr(dst), dport)) } catch (e: IOException) { null }
                synchronized(t.lock) {
                    if (t.st == St.CLOSED) { runCatching { s?.close() }; return@execute }
                    if (s == null) {
                        // (refused or unreachable: the computer told, as the host would)
                        send(Ip.tcp(dst, src, dport, sport, 0, t.rcvNxt, Ip.RST or Ip.ACK, 0))
                        t.abort()
                        return@execute
                    }
                    t.socket = s
                    t.st = St.SYN_RCVD
                    t.sndNxt = t.iss + 1
                    t.sndUna = t.iss
                    t.synAck()
                }
            }
            return
        }

        synchronized(c.lock) {
            c.lastHeard = System.currentTimeMillis()
            if (flags and Ip.SYN != 0) {
                // its SYN again (our SYN-ACK lost)
                if (c.st == St.SYN_RCVD) c.synAck()
                return
            }
            if (flags and Ip.ACK != 0) {
                if (c.st == St.SYN_RCVD && ack == c.iss + 1) {
                    c.st = St.ESTABLISHED
                    c.sndUna = ack
                    val s = c.socket!!
                    workers.execute { c.writer(s) }
                    workers.execute { c.reader(s) }
                }
                // what it has now: off the unacknowledged
                val done = ack - c.sndUna
                if (done > 0 && ack - c.sndNxt <= 0) {
                    val all = c.unacked.toByteArray()
                    val dataDone = minOf(done, all.size)
                    c.unacked.reset()
                    c.unacked.write(all, dataDone, all.size - dataDone)
                    c.sndUna = ack
                    c.rto = 1000
                    if (c.finSent && ack == c.sndNxt) c.finAcked = true
                }
                c.wnd = win
                c.lock.notifyAll()
            }
            if (c.st != St.ESTABLISHED && c.st != St.CLOSING) return
            // its octets, in order only (the rest: our ACK again for it to resend)
            if (data.isNotEmpty() || flags and Ip.FIN != 0) {
                if (seq != c.rcvNxt) {
                    c.seg(Ip.ACK, c.sndNxt)
                    return
                }
                if (data.isNotEmpty()) {
                    c.toSocket.add(data)
                    c.rcvNxt += data.size
                }
                if (flags and Ip.FIN != 0) {
                    c.rcvNxt += 1
                    c.clientFin = true
                    c.st = St.CLOSING
                    c.toSocket.add(ByteArray(0))
                }
                c.seg(Ip.ACK, c.sndNxt)
            }
            // both ends done: gone
            if (c.clientFin && c.finAcked) {
                runCatching { c.socket?.close() }
                c.st = St.CLOSED
                tcp.remove(c.key)
            }
        }
    }

    /** retransmissions due, and connections and flows long idle let go */
    private fun tick() {
        val now = System.currentTimeMillis()
        for (c in tcp.values) {
            synchronized(c.lock) {
                when {
                    c.st == St.SYN_RCVD && now - c.lastSent > c.rto -> { c.synAck(); c.rto = minOf(c.rto * 2, 8000) }
                    (c.unacked.size() > 0 || (c.finSent && !c.finAcked)) && now - c.lastSent > c.rto -> {
                        c.retransmit()
                        c.rto = minOf(c.rto * 2, 8000)
                    }
                }
                // (gone quiet for long, or a close never finished)
                val idle = now - c.lastHeard
                if (idle > 2 * 60 * 60_000L || (c.st == St.CLOSING && idle > 60_000) || (c.finSent && idle > 120_000)) c.abort()
            }
        }
        for ((k, u) in udp) if (now - u.last > if (u.dns) 15_000 else 120_000) { u.close(); udp.remove(k) }
    }
}
