// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.peer

import com.neodesktop.dmodem.io.IOException
import com.neodesktop.dmodem.io.Sink
import com.neodesktop.dmodem.io.Source
import com.neodesktop.dmodem.io.background
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AF_UNSPEC
import platform.posix.AI_PASSIVE
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.NI_NUMERICHOST
import platform.posix.NI_NUMERICSERV
import platform.posix.O_NONBLOCK
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_REUSEADDR
import platform.posix.addrinfo
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo
import platform.posix.getpeername
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar

/** (no SIGPIPE when the other end has gone: Linux's send() flag, Apple's socket option) */
internal expect fun noSigpipe(fd: Int)
internal expect val SEND_FLAGS: Int

/**
 * A socket as a [Duplex] (Kotlin/Native: iOS, and Linux for the tests):
 * a TCP connection, or the engine's line (its socket pair's end). Closing
 * it wakes a read blocked on it.
 */
@OptIn(ExperimentalForeignApi::class)
class FdDuplex(private val fd: Int, override val name: String = "modem") : Duplex {
    private val closed = atomic(false)

    init {
        noSigpipe(fd)
    }

    override val input: Source = object : Source {
        /** up to [len] bytes received into [b]: how many, -1 at the end (or once closed) */
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            while (true) {
                val n = b.usePinned { recv(fd, it.addressOf(off), len.convert(), 0) }
                if (n < 0 && errno == EINTR) continue
                if (n < 0) {
                    if (closed.value) return -1
                    throw IOException("the connection ended (errno $errno)")
                }
                return if (n == 0L) -1 else n.toInt()
            }
        }
    }

    override val output: Sink = object : Sink {
        /** all of [len] bytes of [b] from [off] sent; an error if the connection ended */
        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            var left = len
            while (left > 0) {
                val w = b.usePinned { send(fd, it.addressOf(at), left.convert(), SEND_FLAGS) }
                if (w < 0 && errno == EINTR) continue
                if (w <= 0) throw IOException("the connection ended")
                at += w.toInt()
                left -= w.toInt()
            }
        }
    }

    /** the socket shut down and closed (once) */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        shutdown(fd, SHUT_RDWR)
        platform.posix.close(fd)
    }
}

/**
 * Phones calling each other over TCP (Wi-Fi), as the Android app's
 * TcpPeerTransport: [port] offered for calls, host[:port] called (port
 * [port] when it has none).
 */
@OptIn(ExperimentalForeignApi::class)
class PosixTcpTransport(private val port: Int) : PeerTransport {
    override val label = "Wi-Fi"
    private val running = atomic(false)
    private val listenFd = atomic(-1)

    /** the port it listens on (the one it was given, or the system's for 0) */
    val localPort: Int
        get() {
            val fd = listenFd.value
            if (fd < 0) return port
            return memScoped {
                val sa = alloc<sockaddr_storage>()
                val len = alloc<socklen_tVar>()
                len.value = sizeOf<sockaddr_storage>().convert()
                if (getsockname(fd, sa.ptr.reinterpret(), len.ptr) != 0) return@memScoped port
                numeric(sa.ptr.reinterpret(), len.value, service = true)?.toIntOrNull() ?: port
            }
        }

    /** calls taken on [port] in the background until [close], each handed to [accept] */
    override fun listen(accept: (Duplex) -> Unit) {
        val fd = bound(port) ?: throw IOException("can't take calls on port $port (errno $errno)")
        listenFd.value = fd
        running.value = true
        background("peer: listening") {
            while (running.value) {
                // (polled, so close() ends it on every system: a blocked accept() isn't always woken)
                val ready = memScoped {
                    val p = alloc<pollfd>()
                    p.fd = fd
                    p.events = POLLIN.convert()
                    poll(p.ptr, 1.convert(), 200)
                }
                if (ready <= 0 || !running.value) continue
                val c = platform.posix.accept(fd, null, null)
                if (c < 0) continue
                val who = peerName(c)
                background("peer: a call") { accept(FdDuplex(c, who)) }
            }
            platform.posix.close(fd)
        }
    }

    /** a TCP connection to [address] (host[:port]), trying each of its addresses in turn; an error saying why none answered */
    override fun connect(address: String): Duplex {
        val (host, p) = parse(address, port)
        memScoped {
            val hints = alloc<addrinfo>()
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_STREAM
            val res = alloc<CPointerVar<addrinfo>>()
            val rc = getaddrinfo(host, p.toString(), hints.ptr, res.ptr)
            if (rc != 0) throw IOException("no such host: $host")
            try {
                var ai = res.value
                var why = "nobody answers"
                while (ai != null) {
                    val a = ai.pointed
                    val fd = socket(a.ai_family, a.ai_socktype, a.ai_protocol)
                    if (fd >= 0) {
                        val err = connectWithin(fd, a.ai_addr, a.ai_addrlen, CONNECT_MS)
                        if (err == 0) return FdDuplex(fd, "$host:$p")
                        why = if (err == -1) "no answer" else "refused (errno $err)"
                        platform.posix.close(fd)
                    }
                    ai = a.ai_next
                }
                throw IOException(why)
            } finally {
                freeaddrinfo(res.value)
            }
        }
    }

    /** no more calls taken (the listening loop ends and closes its socket) */
    override fun close() {
        running.value = false
    }

    /** a listening socket on [port] (IPv4, any address), or null */
    private fun bound(port: Int): Int? = memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM
        hints.ai_flags = AI_PASSIVE
        val res = alloc<CPointerVar<addrinfo>>()
        if (getaddrinfo(null, port.toString(), hints.ptr, res.ptr) != 0) return@memScoped null
        try {
            val a = res.value?.pointed ?: return@memScoped null
            val fd = socket(a.ai_family, a.ai_socktype, a.ai_protocol)
            if (fd < 0) return@memScoped null
            val one = alloc<IntVar>()
            one.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            if (platform.posix.bind(fd, a.ai_addr, a.ai_addrlen) != 0 || platform.posix.listen(fd, 4) != 0) {
                platform.posix.close(fd)
                return@memScoped null
            }
            fd
        } finally {
            freeaddrinfo(res.value)
        }
    }

    /** connect() within [ms]: 0, an errno, or -1 for the time up */
    private fun connectWithin(fd: Int, addr: CPointer<sockaddr>?, len: UInt, ms: Int): Int = memScoped {
        val flags = fcntl(fd, F_GETFL, 0)
        fcntl(fd, F_SETFL, flags or O_NONBLOCK)
        var err = 0
        if (platform.posix.connect(fd, addr, len) != 0) {
            if (errno != EINPROGRESS) return@memScoped errno
            val p = alloc<pollfd>()
            p.fd = fd
            p.events = POLLOUT.convert()
            if (poll(p.ptr, 1.convert(), ms) <= 0) return@memScoped -1
            val e = alloc<IntVar>()
            val elen = alloc<socklen_tVar>()
            elen.value = sizeOf<IntVar>().convert()
            getsockopt(fd, SOL_SOCKET, SO_ERROR, e.ptr, elen.ptr)
            err = e.value
        }
        fcntl(fd, F_SETFL, flags)
        err
    }

    /** the numeric host of the socket's other end, "?" if unknown */
    private fun peerName(fd: Int): String = memScoped {
        val sa = alloc<sockaddr_storage>()
        val len = alloc<socklen_tVar>()
        len.value = sizeOf<sockaddr_storage>().convert()
        if (getpeername(fd, sa.ptr.reinterpret(), len.ptr) != 0) return@memScoped "?"
        numeric(sa.ptr.reinterpret(), len.value, service = false) ?: "?"
    }

    /** an address's host (or [service]: its port) as numbers */
    private fun numeric(sa: CPointer<sockaddr>, len: UInt, service: Boolean): String? = memScoped {
        val buf = allocArray<ByteVar>(64)
        val rc = if (service) {
            getnameinfo(sa, len, null, 0.convert(), buf, 64.convert(), NI_NUMERICSERV)
        } else {
            getnameinfo(sa, len, buf, 64.convert(), null, 0.convert(), NI_NUMERICHOST)
        }
        if (rc != 0) null else buf.toKString()
    }

    companion object {
        /** Android's NSD type, Bonjour's: phones taking calls on the network */
        const val SERVICE_TYPE = "_dmodem._tcp."
        private const val CONNECT_MS = 5000

        /** host[:port], [host]:port (IPv6): its host and port ([port] when it has none) */
        fun parse(address: String, port: Int): Pair<String, Int> {
            val a = address.trim()
            if (a.startsWith("[")) {
                val end = a.indexOf(']')
                if (end > 0) {
                    val p = a.substring(end + 1).removePrefix(":").toIntOrNull() ?: port
                    return a.substring(1, end) to p
                }
            }
            // (more than one colon: an IPv6 address without a port)
            if (a.count { it == ':' } == 1) {
                val p = a.substringAfter(':').toIntOrNull()
                if (p != null) return a.substringBefore(':') to p
            }
            return a to port
        }
    }
}
