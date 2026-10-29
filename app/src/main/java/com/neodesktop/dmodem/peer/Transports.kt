// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.peer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import com.neodesktop.dmodem.io.Sink
import com.neodesktop.dmodem.io.Source
import com.neodesktop.dmodem.io.asSink
import com.neodesktop.dmodem.io.asSource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** a TCP connection as a [Duplex] */
class SocketDuplex(private val socket: Socket, override val name: String = socket.inetAddress.hostAddress ?: "?") : Duplex {
    init { runCatching { socket.tcpNoDelay = true } }
    override val input: Source = socket.getInputStream().asSource()
    override val output: Sink = socket.getOutputStream().asSink()
    /** the connection closed */
    override fun close() = socket.close()
}

/**
 * Phones on the same network (Wi-Fi, or anything IP: a hotspot, a VPN):
 * calls in on TCP [port] on every address when listening, out to
 * `host[:port]`.
 */
class TcpPeerTransport(
    val port: Int = DEFAULT_PORT,
    private val bindTo: InetAddress? = null,
) : PeerTransport {
    override val label = "Wi-Fi"
    @Volatile private var server: ServerSocket? = null

    /** the port listened on (with port 0: the one given) */
    val localPort: Int get() = server?.localPort ?: port

    /** calls taken on [port] (every address, or [bindTo]): each to [accept] on a thread of its own */
    override fun listen(accept: (Duplex) -> Unit) {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindTo, port), 4)
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                Thread({ accept(SocketDuplex(c)) }, "peer-in").apply { isDaemon = true; start() }
            }
        }, "peer-listen").apply { isDaemon = true; start() }
    }

    /** a call to [address] (`host[:port]`, [port] if none), 10 s to connect */
    override fun connect(address: String): Duplex {
        val (host, p) = parse(address, port)
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, p), 10_000)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        return SocketDuplex(s, address)
    }

    /** no more calls taken (the listening socket closed) */
    override fun close() {
        runCatching { server?.close() }
        server = null
    }

    companion object {
        const val DEFAULT_PORT = 5070
        /** NSD's service type: phones on the network found by it */
        const val SERVICE_TYPE = "_dmodem._tcp."

        /** host and port of `host`, `host:port`, `[v6]:port` */
        fun parse(address: String, defaultPort: Int): Pair<String, Int> {
            val a = address.trim()
            if (a.startsWith("[")) {
                val end = a.indexOf(']')
                val host = a.substring(1, if (end < 0) a.length else end)
                val p = if (end >= 0 && a.length > end + 2 && a[end + 1] == ':') a.substring(end + 2).toIntOrNull() else null
                return host to (p ?: defaultPort)
            }
            val colon = a.lastIndexOf(':')
            // (one colon: a port; more: an IPv6 address alone)
            if (colon > 0 && a.indexOf(':') == colon) {
                return a.substring(0, colon) to (a.substring(colon + 1).toIntOrNull() ?: defaultPort)
            }
            return a to defaultPort
        }
    }
}

/**
 * Paired phones over Bluetooth (RFCOMM, a service of our own, not the
 * serial port profile's): calls in when listening, out to a paired
 * phone's address.
 */
@SuppressLint("MissingPermission") // (the caller has it: the settings ask)
class BluetoothPeerTransport(private val context: Context) : PeerTransport {
    override val label = "Bluetooth"
    @Volatile private var server: BluetoothServerSocket? = null

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private class BtDuplex(private val s: BluetoothSocket) : Duplex {
        override val input: Source = s.inputStream.asSource()
        override val output: Sink = s.outputStream.asSink()
        override val name: String = runCatching { s.remoteDevice?.let { it.name ?: it.address } }.getOrNull() ?: "Bluetooth"
        /** the RFCOMM connection closed */
        override fun close() = s.close()
    }

    /** calls taken on our RFCOMM service: each to [accept] on a thread of its own; IOException if there's no Bluetooth, or it's off */
    override fun listen(accept: (Duplex) -> Unit) {
        val a = adapter ?: throw IOException("this phone has no Bluetooth")
        if (!a.isEnabled) throw IOException("Bluetooth is off")
        val s = a.listenUsingRfcommWithServiceRecord("D-Modem line", SERVICE)
        server = s
        Thread({
            while (true) {
                val c = try { s.accept() } catch (_: IOException) { break } ?: break
                Thread({ accept(BtDuplex(c)) }, "peer-in").apply { isDaemon = true; start() }
            }
        }, "peer-listen").apply { isDaemon = true; start() }
    }

    /** a call to the paired phone at Bluetooth [address], on our RFCOMM service */
    override fun connect(address: String): Duplex {
        val a = adapter ?: throw IOException("this phone has no Bluetooth")
        if (!a.isEnabled) throw IOException("Bluetooth is off")
        runCatching { a.cancelDiscovery() }
        val s = a.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERVICE)
        try {
            s.connect()
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        return BtDuplex(s)
    }

    /** no more calls taken (the server socket closed) */
    override fun close() {
        runCatching { server?.close() }
        server = null
    }

    companion object {
        /** the line's RFCOMM service: the same on every phone with the app */
        val SERVICE: UUID = UUID.fromString("8d3c1a52-6f0e-4d2b-9a57-0d30de300001")
    }
}
