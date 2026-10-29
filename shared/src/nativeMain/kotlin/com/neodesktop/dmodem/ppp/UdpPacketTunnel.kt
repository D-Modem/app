// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.ppp

import com.neodesktop.dmodem.io.background
import com.neodesktop.dmodem.jobs.DialUpJob
import com.neodesktop.dmodem.jobs.PacketTunnel
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.INADDR_LOOPBACK
import platform.posix.POLLIN
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.getsockname
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/** what starts the phone's tunnel (iOS: its packet tunnel extension, told to send its packets to [port]) */
interface TunnelStarter {
    /** the tunnel up with [address], [dns] (comma between), its packets to 127.0.0.1:[port]: whether it started */
    fun start(address: String, dns: String, mtu: Int, port: Int): Boolean

    /** the tunnel stopped */
    fun stop()
}

/**
 * The dial-up link's packets to the phone's tunnel and back as UDP on the
 * loopback (each datagram an IPv4 packet): iOS's packet tunnel runs apart
 * from the app (an extension), and the modem in the app. The extension's
 * first datagram (any, an empty one) tells where it is.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
class UdpPacketTunnel(private val job: DialUpJob, private val starter: TunnelStarter) : PacketTunnel {
    private val fd = socket(AF_INET, SOCK_DGRAM, 0)
    private val running = atomic(true)
    private val lock = SynchronizedObject()
    /** the extension's address, once it has said something */
    private var peer: ByteArray? = null

    /** the port packets come in on */
    val port: Int

    /** packets each way (for the tests) */
    val fromTunnel = atomic(0)
    val toTunnel = atomic(0)

    init {
        port = memScoped {
            val a = alloc<sockaddr_in>()
            a.sin_family = AF_INET.convert()
            a.sin_addr.s_addr = htonl(INADDR_LOOPBACK.toUInt())
            a.sin_port = 0u
            bind(fd, a.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            val len = alloc<socklen_tVar>()
            len.value = sizeOf<sockaddr_in>().convert()
            getsockname(fd, a.ptr.reinterpret(), len.ptr)
            ntohs(a.sin_port).toInt()
        }
        background("tunnel") { receive() }
    }

    /** the extension's datagrams until closed: where it is noted, each IPv4 packet passed on to the link; the socket closed at the end */
    private fun receive() {
        val buf = ByteArray(65536)
        while (running.value) {
            val ready = memScoped {
                val p = alloc<pollfd>()
                p.fd = fd
                p.events = POLLIN.convert()
                poll(p.ptr, 1.convert(), 200)
            }
            if (ready <= 0) continue
            val (n, from) = memScoped {
                val a = alloc<sockaddr_in>()
                val len = alloc<socklen_tVar>()
                len.value = sizeOf<sockaddr_in>().convert()
                val n = buf.usePinned { recvfrom(fd, it.addressOf(0), buf.size.convert(), 0, a.ptr.reinterpret(), len.ptr) }.toInt()
                val raw = ByteArray(sizeOf<sockaddr_in>().toInt())
                raw.usePinned { platform.posix.memcpy(it.addressOf(0), a.ptr, raw.size.convert()) }
                n to raw
            }
            if (n < 0) continue
            synchronized(lock) { peer = from }
            // (an IPv4 packet; anything shorter only says where the extension is)
            if (n >= 20) {
                fromTunnel.incrementAndGet()
                job.sendIp(buf.copyOf(n))
            }
        }
        platform.posix.close(fd)
    }

    /** [p] from the link to the extension, once it has said where it is (else dropped) */
    override fun packet(p: ByteArray) {
        val to = synchronized(lock) { peer } ?: return
        toTunnel.incrementAndGet()
        p.usePinned { pp ->
            to.usePinned { tp ->
                sendto(fd, pp.addressOf(0), p.size.convert(), 0, tp.addressOf(0).reinterpret(), to.size.convert())
            }
        }
    }

    /** the extension started with the link's [address], [dns] and [mtu], its packets to our port: whether it did */
    override fun up(address: String, dns: List<String>, mtu: Int): Boolean =
        starter.start(address, dns.joinToString(","), mtu, port)

    /** no more packets taken, and the extension stopped (once) */
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        starter.stop()
    }

    /** [v] in network order (big-endian) */
    private fun htonl(v: UInt): UInt = if (bigEndian) v else
        ((v and 0xffu) shl 24) or ((v and 0xff00u) shl 8) or ((v shr 8) and 0xff00u) or (v shr 24)

    /** [v] from network order */
    private fun ntohs(v: UShort): UShort = if (bigEndian) v else
        (((v.toInt() and 0xff) shl 8) or (v.toInt() shr 8 and 0xff)).toUShort()

    private val bigEndian: Boolean get() = kotlin.native.Platform.isLittleEndian.not()
}
